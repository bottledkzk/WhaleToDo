// T08：邮箱验证码真库集成测试（database job：PG16 + 受限 whaletodo_app）。
// 列名以 202610250001_billing_email/migration.sql 为准：
//  - email_verifications: purpose / email_normalized / code_hmac / attempts / expires_at / consumed_at
//  - email_throttle: scope / scope_key / window_start / count
//  - email_verify_tickets: token_hmac / purpose / email_normalized / expires_at / consumed_at
import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import { PrismaClient } from '@prisma/client';
import { PrismaRepository } from '../src/repo/prisma.ts';
import { CODE_TTL_MS, codeDigest, createTicketToken, TICKET_TTL_MS } from '../src/domain/email.ts';

const ownerUrl = process.env.VERIFY_DATABASE_URL!;
const appUrl = process.env.VERIFY_APP_URL!;
if (!ownerUrl || new URL(ownerUrl).pathname !== '/whaletodo_verify') {
  throw new Error('Integration tests require isolated whaletodo_verify database');
}
if (!appUrl) throw new Error('Integration tests require VERIFY_APP_URL');

const appUrlWithSchema = /[?&]schema=/.test(appUrl)
  ? appUrl
  : `${appUrl}${appUrl.includes('?') ? '&' : '?'}schema=whaletodo`;

const owner = new Pool({ connectionString: ownerUrl, max: 2 });
const prisma = new PrismaClient({ datasourceUrl: appUrlWithSchema });
const repo = new PrismaRepository(prisma);

const SECRET = 'synthetic-email-secret-for-integration-only';
const PURPOSE = 'register' as const;
const EMAIL = 'synthetic@example.invalid';
const CODE = '123456';
// 固定中午，避免测试跨小时导致 email_throttle 窗口分裂
const FIXED_NOW = Date.UTC(2026, 5, 1, 12, 0, 0);

const digestFor = (email: string, code: string) => codeDigest(SECRET, PURPOSE, email, code);

function send(email = EMAIL, code = CODE, now = Date.now()) {
  return repo.sendEmailCode!({
    purpose: PURPOSE,
    email,
    codeDigest: digestFor(email, code),
    now,
    ttlMs: CODE_TTL_MS,
    scopeSecret: SECRET,
  });
}

function verify(email = EMAIL, code = CODE, now = Date.now()) {
  return repo.verifyEmailCode!({
    purpose: PURPOSE,
    email,
    codeDigest: digestFor(email, code),
    now,
  });
}

before(async () => {
  await owner.query('SELECT 1');
});

beforeEach(async () => {
  await owner.query('TRUNCATE email_verifications, email_throttle, email_verify_tickets');
});

after(async () => {
  await owner.query('TRUNCATE email_verifications, email_throttle, email_verify_tickets');
  await prisma.$disconnect();
  await owner.end();
});

test('发码只存 HMAC 摘要，email/global 限流行按实际列名写入', async () => {
  const res = await send();
  assert.equal(res.sent, true);
  assert.equal(res.retryAfterMs, 0);

  const rows = await owner.query(
    'SELECT purpose, email_normalized, code_hmac, attempts, consumed_at FROM email_verifications',
  );
  assert.equal(rows.rowCount, 1);
  assert.equal(rows.rows[0].purpose, PURPOSE);
  assert.equal(rows.rows[0].email_normalized, EMAIL);
  assert.equal(rows.rows[0].code_hmac, digestFor(EMAIL, CODE), '库里必须是 HMAC 摘要');
  assert.notEqual(rows.rows[0].code_hmac, CODE, '明文码不得入库');
  assert.equal(rows.rows[0].attempts, 0);
  assert.equal(rows.rows[0].consumed_at, null);

  const throttle = await owner.query('SELECT scope, count FROM email_throttle ORDER BY scope');
  assert.deepEqual(throttle.rows.map((r: { scope: string }) => r.scope), ['email', 'global']);
  assert.deepEqual(throttle.rows.map((r: { count: number }) => r.count), [1, 1]);
});

test('60 秒内二次发送被拒，且被拒请求不占配额', async () => {
  const now = FIXED_NOW;
  assert.equal((await send(EMAIL, CODE, now)).sent, true);

  const second = await send(EMAIL, CODE, now + 1_000);
  assert.equal(second.sent, false);
  assert.ok(second.retryAfterMs > 0);

  const global = await owner.query("SELECT count FROM email_throttle WHERE scope='global'");
  assert.equal(global.rows[0].count, 1, '被冷却拒绝的请求不写 global 计数');
  const email = await owner.query("SELECT count FROM email_throttle WHERE scope='email'");
  assert.equal(email.rows[0].count, 1, '被冷却拒绝的请求不占 email 配额');
});

test('并发同邮箱发码：一个 sent:true、一个 sent:false，不抛异常，只留 1 条未消费码', async () => {
  const now = FIXED_NOW;
  const results = await Promise.all([
    send(EMAIL, '111111', now),
    send(EMAIL, '222222', now),
  ]);
  assert.equal(results.filter((r) => r.sent).length, 1, '并发只能有一个真正发码');
  assert.equal(results.filter((r) => !r.sent).length, 1, '另一个应按冷却返回 false');
  const rejected = results.find((r) => !r.sent)!;
  assert.ok(rejected.retryAfterMs > 0, 'sent:false 应带 retryAfterMs');

  const unconsumed = await owner.query(
    `SELECT count(*)::int AS n FROM email_verifications
      WHERE email_normalized = $1 AND purpose = $2 AND consumed_at IS NULL`,
    [EMAIL, PURPOSE],
  );
  assert.equal(unconsumed.rows[0].n, 1, '未消费码只能剩 1 行');
});

test('61 秒间隔连发 5 次成功，第 6 次被小时配额拒绝', async () => {
  const now = FIXED_NOW;
  for (let i = 0; i < 5; i += 1) {
    const result = await send(EMAIL, CODE, now + i * 61_000);
    assert.equal(result.sent, true, `第 ${i + 1} 次应成功`);
    assert.equal(result.retryAfterMs, 0);
  }

  const sixth = await send(EMAIL, CODE, now + 5 * 61_000);
  assert.equal(sixth.sent, false);
  assert.ok(sixth.retryAfterMs > 0);

  const emailThrottle = await owner.query("SELECT count FROM email_throttle WHERE scope='email'");
  assert.equal(emailThrottle.rows[0].count, 6, '第 6 次先计数后拒绝');
  const unconsumed = await owner.query(
    'SELECT count(*)::int AS n FROM email_verifications WHERE consumed_at IS NULL',
  );
  assert.equal(unconsumed.rows[0].n, 1, '第 6 次不应插入新码');
});

test('缺 scopeSecret 直接报错，不留可预测回退', async () => {
  await assert.rejects(
    repo.sendEmailCode!({
      purpose: PURPOSE,
      email: EMAIL,
      codeDigest: digestFor(EMAIL, CODE),
      now: FIXED_NOW,
      ttlMs: CODE_TTL_MS,
    }),
    /scopeSecret/,
  );
});

test('发新码作废旧码：第一次的码校验失败，第二次的码成功', async () => {
  const now = FIXED_NOW;
  assert.equal((await send(EMAIL, '111111', now)).sent, true);
  assert.equal((await send(EMAIL, '222222', now + 61_000)).sent, true, '超过 60 秒可重发');

  const oldCode = await verify(EMAIL, '111111', now + 62_000);
  assert.equal(oldCode.ok, false, '旧码必须已作废');

  const newCode = await verify(EMAIL, '222222', now + 62_000);
  assert.equal(newCode.ok, true);
  assert.equal(newCode.attemptsLeft, 0);
});

test('连续 5 次错码 → 该码作废，第 6 次正确码也失败', async () => {
  const now = FIXED_NOW;
  assert.equal((await send(EMAIL, CODE, now)).sent, true);

  for (let i = 0; i < 5; i += 1) {
    const res = await verify(EMAIL, String(i).padStart(6, '0'), now + 1_000 + i);
    assert.equal(res.ok, false);
  }

  const afterInvalidation = await verify(EMAIL, CODE, now + 2_000);
  assert.equal(afterInvalidation.ok, false, '5 次失败后正确码也应失效');

  const rows = await owner.query('SELECT attempts, consumed_at FROM email_verifications');
  assert.equal(rows.rows[0].attempts, 5);
  assert.notEqual(rows.rows[0].consumed_at, null);
});

test('ticket 只存摘要，并发消费只成功一次', async () => {
  // ticket 用真实时间，确保 expires_at 在未来（消费 SQL 用数据库 now()）
  const now = Date.now();
  const ticket = createTicketToken();
  await repo.createEmailTicket!({
    purpose: PURPOSE,
    email: EMAIL,
    ticketDigest: ticket.digest,
    now,
    ttlMs: TICKET_TTL_MS,
  });

  const rows = await owner.query('SELECT token_hmac, purpose, email_normalized FROM email_verify_tickets');
  assert.equal(rows.rowCount, 1);
  assert.equal(rows.rows[0].token_hmac, ticket.digest, 'ticket 只存摘要');
  assert.notEqual(rows.rows[0].token_hmac, ticket.token, '不得存明文 token');
  assert.equal(rows.rows[0].purpose, PURPOSE);
  assert.equal(rows.rows[0].email_normalized, EMAIL);

  const results = await Promise.all([
    repo.consumeEmailTicket!({ ticketDigest: ticket.digest, now }),
    repo.consumeEmailTicket!({ ticketDigest: ticket.digest, now }),
  ]);
  assert.equal(results.filter(Boolean).length, 1, '并发消费只能成功一次');
  assert.equal(
    await repo.consumeEmailTicket!({ ticketDigest: ticket.digest, now: now + 1 }),
    false,
    '已消费的 ticket 不能重放',
  );
});
