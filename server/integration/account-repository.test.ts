// 账户仓储的真实 PG 集成测试（运行在 database job：PG16 + 受限 whaletodo_app）
// 目的：证明 PrismaRepository 在**真实受限角色**下可用，而不是只有内存契约测试。
// 覆盖评审提出的三处：注册 42501 / user_settings RLS / 并发刷新与回执不覆盖。
import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import { PrismaClient } from '@prisma/client';
import { PrismaRepository } from '../src/repo/prisma.ts';

const ownerUrl = process.env.VERIFY_DATABASE_URL!;
const appUrl = process.env.VERIFY_APP_URL!;
if (!ownerUrl || new URL(ownerUrl).pathname !== '/whaletodo_verify') {
  throw new Error('Integration tests require isolated whaletodo_verify database');
}
if (!appUrl) throw new Error('Integration tests require VERIFY_APP_URL (restricted whaletodo_app role)');

// Prisma 默认用 public schema，而表在 whaletodo schema；VERIFY_APP_URL 不带 ?schema 时必须补上
const appUrlWithSchema = /[?&]schema=/.test(appUrl)
  ? appUrl
  : `${appUrl}${appUrl.includes('?') ? '&' : '?'}schema=whaletodo`;

const owner = new Pool({ connectionString: ownerUrl, max: 2 });
const prisma = new PrismaClient({ datasourceUrl: appUrlWithSchema });
const repo = new PrismaRepository(prisma);

const PASSWORD = 'Synthetic-integration-password';

before(async () => {
  await owner.query('SELECT 1');
});

beforeEach(async () => {
  // 每个用例从干净库开始；users 级联清掉 sessions / wallets / settings / receipts 之外的表
  await owner.query('TRUNCATE users CASCADE');
  await owner.query('TRUNCATE idempotency_receipts');
  // demo_slot 容量只有 10，先确认没有残留
  const { rows } = await owner.query('SELECT count(*)::int AS n FROM users');
  assert.equal(rows[0].n, 0);
});

after(async () => {
  await owner.query('TRUNCATE users CASCADE');
  await owner.query('TRUNCATE idempotency_receipts');
  await prisma.$disconnect();
  await owner.end();
});

/* ---------------- 注册：受限角色 + RLS ---------------- */

test('受限角色可完成注册：users + point_wallets + user_settings 同事务建立', async () => {
  const user = await repo.createUser('acct_alpha', PASSWORD, 'Alpha');
  assert.equal(user.username, 'acct_alpha');

  const w = await owner.query('SELECT available, reserved FROM point_wallets WHERE user_id=$1', [user.id]);
  assert.equal(w.rowCount, 1, '钱包应在注册事务内创建');
  assert.equal(w.rows[0].available, 0);
  assert.equal(w.rows[0].reserved, 0);

  const s = await owner.query('SELECT settings FROM user_settings WHERE user_id=$1', [user.id]);
  assert.equal(s.rowCount, 1, 'user_settings 应在 RLS 上下文下成功插入');

  const slot = await owner.query('SELECT demo_slot FROM users WHERE id=$1', [user.id]);
  assert.ok(slot.rows[0].demo_slot >= 1 && slot.rows[0].demo_slot <= 10);
});

test('注册不会越权写 role/status，且默认值来自数据库', async () => {
  const user = await repo.createUser('acct_role', PASSWORD);
  const r = await owner.query('SELECT role, status FROM users WHERE id=$1', [user.id]);
  assert.equal(r.rows[0].role, 'user');
  assert.equal(r.rows[0].status, 'active');

  // 运行角色仍然不能改 role（授权未被放宽）。
  // 注意：必须用受限的 whaletodo_app 连接（prisma），不能用迁移账号 owner——后者本来就能改。
  await assert.rejects(
    prisma.$executeRaw`UPDATE users SET role = 'admin' WHERE id = ${user.id}::uuid`,
    /permission denied|denied for table/i,
  );
});

test('认证走异步哈希：正确密码通过、错误密码失败', async () => {
  const user = await repo.createUser('acct_auth', PASSWORD);
  assert.equal((await repo.authenticate('acct_auth', PASSWORD))?.id, user.id);
  assert.equal(await repo.authenticate('acct_auth', 'wrong-password'), null);
});

/* ---------------- 幂等回执：首次结果不被覆盖 ---------------- */

test('同 scope/key 先写 A 再写 B：读回仍是 A；过期后才允许替换', async () => {
  const scope = 'register:v1:integration';
  const key = 'idem-key-1';
  await repo.saveIdempotent(scope, key, 'a'.repeat(64), { first: true });

  await repo.saveIdempotent(scope, key, 'b'.repeat(64), { first: false });
  const hit = await repo.getIdempotent(scope, key);
  assert.equal(hit?.fingerprint, 'a'.repeat(64), '未过期回执不能被覆盖');
  assert.deepEqual(hit?.response, { first: true });

  // 手动把回执改为已过期，再写 B
  await owner.query("UPDATE idempotency_receipts SET expires_at = now() - interval '1 hour' WHERE scope=$1 AND key=$2", [scope, key]);
  await repo.saveIdempotent(scope, key, 'b'.repeat(64), { first: false });
  const replaced = await repo.getIdempotent(scope, key);
  assert.equal(replaced?.fingerprint, 'b'.repeat(64), '过期回执允许被替换');
});

test('不同 scope 下的同 key 互不影响', async () => {
  await repo.saveIdempotent('v1:claim:user-a', 'k', 'a'.repeat(64), { owner: 'a' });
  assert.equal(await repo.getIdempotent('v1:claim:user-b', 'k'), undefined);
});

/* ---------------- 并发刷新：一个成功、一个 401 语义 ---------------- */

test('并发刷新同一 refresh：一个成功，另一个报 SESSION_ALREADY_ROTATED，整链可撤销', async () => {
  const user = await repo.createUser('acct_rotate', PASSWORD);
  const { session } = await repo.createSession(user.id);

  const results = await Promise.allSettled([
    repo.rotateSession(session.id, user.id, session.familyId),
    repo.rotateSession(session.id, user.id, session.familyId),
  ]);
  const ok = results.filter((r) => r.status === 'fulfilled');
  const failed = results.filter((r) => r.status === 'rejected');
  assert.equal(ok.length, 1, '并发轮换只能有一个成功');
  assert.equal(failed.length, 1);
  const reason = (failed[0] as PromiseRejectedResult).reason as Error;
  assert.equal(reason.message, 'SESSION_ALREADY_ROTATED');

  // 路由层收到该错误后会 revokeFamily；这里直接验证整链撤销确实生效
  const revoked = await repo.revokeFamily(session.familyId);
  assert.ok(revoked >= 1, '整链撤销应至少撤销新会话');

  const active = await owner.query(
    'SELECT count(*)::int AS n FROM auth_sessions WHERE family_id=$1 AND revoked_at IS NULL',
    [session.familyId],
  );
  assert.equal(active.rows[0].n, 0, '撤销后 family 内不应有有效会话');
});

test('旧会话被撤销后，其 access 令牌查不到', async () => {
  const user = await repo.createUser('acct_access', PASSWORD);
  const { session, tokens } = await repo.createSession(user.id);
  assert.ok(await repo.findActiveSessionByAccess(tokens.accessToken));

  await repo.revokeSession(session.id);
  assert.equal(await repo.findActiveSessionByAccess(tokens.accessToken), null);
});
