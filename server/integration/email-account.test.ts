// T15：邮箱账户真库集成测试（database job：PG16 + 受限 whaletodo_app）。
import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { Pool } from 'pg';
import { PrismaClient } from '@prisma/client';
import { PrismaRepository } from '../src/repo/prisma.ts';
import { EmailAccountConflict } from '../src/repo/types.ts';
import { createTicketToken } from '../src/domain/email.ts';

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

const PASSWORD = 'Synthetic-email-account-password';
const NEW_PASSWORD = 'Synthetic-email-account-new-password';

async function issueTicket(purpose: 'register' | 'reset' | 'bind', email: string) {
  const ticket = createTicketToken();
  await repo.createEmailTicket!({
    purpose,
    email,
    ticketDigest: ticket.digest,
    now: Date.now(),
    ttlMs: 60_000,
  });
  return ticket;
}

async function registerEmail(email: string, displayName?: string) {
  const ticket = await issueTicket('register', email);
  return repo.createUserWithEmail!({
    email,
    password: PASSWORD,
    displayName,
    ticketDigest: ticket.digest,
    now: Date.now(),
  });
}

before(async () => {
  await owner.query('SELECT 1');
});

beforeEach(async () => {
  await owner.query('TRUNCATE users CASCADE');
  await owner.query('TRUNCATE email_verify_tickets');
});

after(async () => {
  await owner.query('TRUNCATE users CASCADE');
  await owner.query('TRUNCATE email_verify_tickets');
  await prisma.$disconnect();
  await owner.end();
});

test('邮箱注册：生成 whale_<12hex>，email 落库，email_verified_at 保持 NULL', async () => {
  const email = 'acct@example.invalid';
  const user = await registerEmail(email, '邮箱用户');
  assert.match(user.username, /^whale_[0-9a-f]{12}$/);
  assert.equal(user.displayName, '邮箱用户');

  const rows = await owner.query(
    'SELECT email, email_verified_at FROM users WHERE id=$1',
    [user.id],
  );
  assert.equal(rows.rows[0].email, email);
  assert.equal(rows.rows[0].email_verified_at, null, 'app 不写 email_verified_at');

  assert.equal((await repo.findUserByEmail!(email))?.id, user.id);
  assert.equal((await repo.authenticateByEmail!(email, PASSWORD))?.id, user.id);
  assert.equal(await repo.authenticateByEmail!(email, 'wrong-password'), null);
});

test('邮箱唯一冲突：409 语义，ticket 不被消费', async () => {
  const email = 'dupe@example.invalid';
  await registerEmail(email);

  const ticket = await issueTicket('register', email);
  await assert.rejects(
    repo.createUserWithEmail!({
      email,
      password: PASSWORD,
      ticketDigest: ticket.digest,
      now: Date.now(),
    }),
    (err: unknown) => err instanceof EmailAccountConflict && err.kind === 'EMAIL_TAKEN',
  );

  const rows = await owner.query(
    'SELECT consumed_at FROM email_verify_tickets WHERE token_hmac=$1',
    [ticket.digest],
  );
  assert.equal(rows.rows[0].consumed_at, null, '邮箱冲突必须回滚 ticket 消费');
  const count = await owner.query('SELECT count(*)::int AS n FROM users');
  assert.equal(count.rows[0].n, 1);
});

test('重置：改密并撤销该用户全部会话，其他用户会话不受影响', async () => {
  const emailA = 'reset-a@example.invalid';
  const emailB = 'reset-b@example.invalid';
  const a = await registerEmail(emailA);
  const b = await registerEmail(emailB);
  const aFirst = await repo.createSession(a.id);
  const aSecond = await repo.createSession(a.id, aFirst.session.familyId);
  const bSession = await repo.createSession(b.id);

  const ticket = await issueTicket('reset', emailA);
  const result = await repo.resetPasswordWithTicket!({
    email: emailA,
    password: NEW_PASSWORD,
    ticketDigest: ticket.digest,
    now: Date.now(),
  });
  assert.deepEqual(result, { ok: true });

  assert.equal(await repo.authenticateByEmail!(emailA, PASSWORD), null);
  assert.equal((await repo.authenticateByEmail!(emailA, NEW_PASSWORD))?.id, a.id);

  const aActive = await owner.query(
    'SELECT count(*)::int AS n FROM auth_sessions WHERE user_id=$1 AND revoked_at IS NULL',
    [a.id],
  );
  assert.equal(aActive.rows[0].n, 0, 'A 的全部会话应被撤销');
  const bActive = await owner.query(
    'SELECT count(*)::int AS n FROM auth_sessions WHERE user_id=$1 AND revoked_at IS NULL',
    [b.id],
  );
  assert.equal(bActive.rows[0].n, 1, 'B 的会话不应受影响');
  assert.ok(aFirst.session.id && aSecond.session.id && bSession.session.id);
});

test('重置：ticket 合法但邮箱无账号 → ok:true，无写入', async () => {
  const email = 'ghost@example.invalid';
  const ticket = await issueTicket('reset', email);
  const result = await repo.resetPasswordWithTicket!({
    email,
    password: NEW_PASSWORD,
    ticketDigest: ticket.digest,
    now: Date.now(),
  });
  assert.deepEqual(result, { ok: true });
  const count = await owner.query('SELECT count(*)::int AS n FROM users');
  assert.equal(count.rows[0].n, 0);
});

test('绑定：正常绑定；已占用邮箱返回 email_taken 且 ticket 不消费', async () => {
  const emailA = 'bind-a@example.invalid';
  const emailB = 'bind-b@example.invalid';
  const a = await registerEmail(emailA);
  const b = await repo.createUser('bind_b', PASSWORD);

  const bindTicket = await issueTicket('bind', emailB);
  assert.equal(await repo.bindEmailWithTicket!({
    userId: b.id,
    email: emailB,
    ticketDigest: bindTicket.digest,
    now: Date.now(),
  }), 'ok');
  assert.equal((await repo.findUserByEmail!(emailB))?.id, b.id);

  const takenTicket = await issueTicket('bind', emailA);
  assert.equal(await repo.bindEmailWithTicket!({
    userId: b.id,
    email: emailA,
    ticketDigest: takenTicket.digest,
    now: Date.now(),
  }), 'email_taken');
  const rows = await owner.query(
    'SELECT consumed_at FROM email_verify_tickets WHERE token_hmac=$1',
    [takenTicket.digest],
  );
  assert.equal(rows.rows[0].consumed_at, null, '邮箱占用必须回滚 ticket 消费');
  assert.equal((await repo.findUserByEmail!(emailA))?.id, a.id);
});

test('consumeEmailTicket：purpose/邮箱必须同时匹配，成功只消费一次', async () => {
  const email = 'ticket@example.invalid';
  const ticket = await issueTicket('register', email);
  const now = Date.now();

  assert.equal(await repo.consumeEmailTicket!({
    ticketDigest: ticket.digest, purpose: 'reset', email, now,
  }), false);
  assert.equal(await repo.consumeEmailTicket!({
    ticketDigest: ticket.digest, purpose: 'register', email: 'other@example.invalid', now,
  }), false);
  assert.equal(await repo.consumeEmailTicket!({
    ticketDigest: ticket.digest, purpose: 'register', email, now,
  }), true);
  assert.equal(await repo.consumeEmailTicket!({
    ticketDigest: ticket.digest, purpose: 'register', email, now,
  }), false);
});
