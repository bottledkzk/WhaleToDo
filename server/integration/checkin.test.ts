// 签到发奖的真实 PG 集成测试（database job：PG16 + 受限 whaletodo_app）
// 验证：只发一次奖、重复领取 409（路由层）、并发只成功一次、流水与余额一致
import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { Pool } from 'pg';
import { PrismaClient } from '@prisma/client';
import type { FastifyInstance } from 'fastify';
import { PrismaRepository } from '../src/repo/prisma.ts';
import { buildApp } from '../src/http/app.ts';
import { loadConfig } from '../src/config.ts';

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
let prisma: PrismaClient;
let repo: PrismaRepository;
let app: FastifyInstance;

const PASSWORD = 'Synthetic-integration-password';
const DATE = '2026-10-25';

before(async () => {
  await owner.query('SELECT 1');
  prisma = new PrismaClient({ datasourceUrl: appUrlWithSchema });
  repo = new PrismaRepository(prisma);
  const config = loadConfig({
    NODE_ENV: 'demo',
    BUSINESS_TIMEZONE: 'Asia/Shanghai',
    IDEMPOTENCY_SECRET: 'integration-secret-'.padEnd(40, 'x'),
    RATE_LIMIT_MAX_ATTEMPTS: '1000',
    PAYMENT_PROVIDER: 'disabled',
  } as NodeJS.ProcessEnv);
  app = buildApp({ config, repo });
  await app.ready();
});

beforeEach(async () => {
  await owner.query('TRUNCATE users CASCADE');
  await owner.query('TRUNCATE idempotency_receipts');
});

after(async () => {
  await owner.query('TRUNCATE users CASCADE');
  await app.close();
  await prisma.$disconnect();
  await owner.end();
});

const claim = (userId: string) => repo.claimDailyReward!({
  userId, businessDate: DATE, rewardType: 'sign_in', points: 30,
  eventId: `${userId}:${DATE}:sign_in`, reason: `sign-in:${DATE}`,
});

test('首次领取发奖并写流水；重复领取不发奖也不写流水', async () => {
  const user = await repo.createUser('ck_one', PASSWORD);

  const first = await claim(user.id);
  assert.equal(first.claimed, true);
  assert.equal(first.available, 30);

  const second = await claim(user.id);
  assert.equal(second.claimed, false, '重复领取必须不发奖');
  assert.equal(second.available, 30, '余额不能增加');

  const ledger = await owner.query('SELECT type, available_delta, balance_after FROM point_ledger WHERE user_id=$1', [user.id]);
  assert.equal(ledger.rowCount, 1, '重复领取不能写第二条流水');
  assert.equal(ledger.rows[0].type, 'award');
  assert.equal(ledger.rows[0].available_delta, 30);
  assert.equal(ledger.rows[0].balance_after, 30);

  const wallet = await owner.query('SELECT available, reserved FROM point_wallets WHERE user_id=$1', [user.id]);
  assert.deepEqual(wallet.rows[0], { available: 30, reserved: 0 });
});

test('并发 5 次领取只有一个成功，余额与流水都只有一份', async () => {
  const user = await repo.createUser('ck_race', PASSWORD);
  const results = await Promise.all([claim(user.id), claim(user.id), claim(user.id), claim(user.id), claim(user.id)]);
  const claimed = results.filter((r) => r.claimed).length;
  assert.equal(claimed, 1, `并发只允许成功一次，实际 ${claimed}`);

  const ledger = await owner.query('SELECT count(*)::int AS n FROM point_ledger WHERE user_id=$1', [user.id]);
  assert.equal(ledger.rows[0].n, 1, '并发下只能有一条流水');
  const wallet = await owner.query('SELECT available FROM point_wallets WHERE user_id=$1', [user.id]);
  assert.equal(wallet.rows[0].available, 30, '余额只加一次');
});

test('HTTP 层：首次 201，重复领取 409', async () => {
  const user = await repo.createUser('ck_http', PASSWORD);
  const { tokens } = await repo.createSession(user.id);
  const headers = { authorization: `Bearer ${tokens.accessToken}` };

  const first = await app.inject({ method: 'POST', url: '/api/v1/rewards/daily/claim', headers });
  assert.equal(first.statusCode, 201, `首次应 201，实际 ${first.statusCode}: ${first.body}`);
  assert.equal(first.json().data.points_awarded, 30);

  const second = await app.inject({ method: 'POST', url: '/api/v1/rewards/daily/claim', headers });
  assert.equal(second.statusCode, 409, `重复应 409，实际 ${second.statusCode}: ${second.body}`);
  assert.equal(second.json().error.code, 'CONFLICT');

  const status = await app.inject({ method: 'GET', url: '/api/v1/rewards/daily', headers });
  assert.equal(status.json().data.claimed, true);
  assert.equal(status.json().data.balance.available, 30);
});

test('同毫秒 3 条流水，limit=2 翻页不丢不重（真实 PG）', async () => {
  const user = await repo.createUser('ck_page', PASSWORD);
  const { tokens } = await repo.createSession(user.id);
  const headers = { authorization: `Bearer ${tokens.accessToken}` };

  // 用迁移账号插入 3 条 created_at 完全相同的流水，制造同毫秒场景
  const sameMs = new Date('2026-10-25T10:00:00.000Z');
  for (let i = 0; i < 3; i += 1) {
    await owner.query(
      `INSERT INTO point_ledger
         (id, user_id, event_id, type, available_delta, reserved_delta, balance_after, reserved_after, reason, created_at)
       VALUES ($1,$2,$3,'adjust',$4,0,$5,0,$6,$7)`,
      [randomUUID(), user.id, `page-${i}`, i + 1, i + 1, `page ${i}`, sameMs],
    );
  }

  const first = await app.inject({ method: 'GET', url: '/api/v1/wallet/ledger?limit=2', headers });
  assert.equal(first.statusCode, 200, first.body);
  const p1 = first.json().data;
  assert.equal(p1.items.length, 2, '第一页应有 2 条');
  assert.ok(p1.next_cursor, '还有一条，应给出 next_cursor');

  const second = await app.inject({
    method: 'GET',
    url: `/api/v1/wallet/ledger?limit=2&cursor=${encodeURIComponent(p1.next_cursor)}`,
    headers,
  });
  assert.equal(second.statusCode, 200, `第二页不应 500：${second.body}`);
  const p2 = second.json().data;
  assert.equal(p2.items.length, 1, '第二页应有剩余 1 条');
  assert.equal(p2.next_cursor, null);

  const ids = [...p1.items, ...p2.items].map((x: { id: string }) => x.id);
  assert.equal(new Set(ids).size, 3, '三页合计应无重复');
});

test('流水按账户隔离：另一个账户看不到', async () => {
  const a = await repo.createUser('ck_iso_a', PASSWORD);
  const b = await repo.createUser('ck_iso_b', PASSWORD);
  await claim(a.id);
  const bPage = await repo.listLedger!(b.id, 10);
  assert.equal(bPage.items.length, 0, 'B 不应看到 A 的流水');
});