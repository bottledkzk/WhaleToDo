// 注册并发的真实 HTTP 测试（评审要求：database job 内起并发探针）
// 覆盖：同体并发两个 201（同一用户/同一首次响应）· 异体并发 409 · 不同 key 同用户名 409
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
let app: FastifyInstance;

const PASSWORD = 'Synthetic-integration-password';
const HEADERS = (key: string) => ({ 'content-type': 'application/json', 'idempotency-key': key });

before(async () => {
  await owner.query('SELECT 1');
  prisma = new PrismaClient({ datasourceUrl: appUrlWithSchema });
  const repo = new PrismaRepository(prisma);
  const config = loadConfig({
    NODE_ENV: 'demo',
    BUSINESS_TIMEZONE: 'Asia/Shanghai',
    IDEMPOTENCY_SECRET: 'integration-test-secret-'.padEnd(40, 'x'),
    RATE_LIMIT_MAX_ATTEMPTS: '1000',
    RATE_LIMIT_WINDOW_MS: '60000',
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
  await owner.query('TRUNCATE idempotency_receipts');
  await app.close();
  await prisma.$disconnect();
  await owner.end();
});

const register = (key: string, payload: Record<string, unknown>) =>
  app.inject({ method: 'POST', url: '/api/v1/auth/register', headers: HEADERS(key), payload });

test('同体并发注册：两个 201，同一用户、同一首次响应（不再是 500）', async () => {
  const body = { username: 'race_same', password: PASSWORD, display_name: 'Race' };
  const [a, b] = await Promise.all([register('race-key-1', body), register('race-key-1', body)]);

  assert.equal(a.statusCode, 201, `第一个应为 201，实际 ${a.statusCode}: ${a.body}`);
  assert.equal(b.statusCode, 201, `第二个应为 201（重放），实际 ${b.statusCode}: ${b.body}`);

  const ua = a.json().data.user;
  const ub = b.json().data.user;
  assert.equal(ua.id, ub.id, '两个响应必须指向同一用户');
  assert.deepEqual(ua, ub, '重放应返回首次的响应内容');

  // 数据库里只应有一个该用户名的用户
  const rows = await owner.query("SELECT count(*)::int AS n FROM users WHERE lower(username) = 'race_same'");
  assert.equal(rows.rows[0].n, 1, '并发同体只允许建一个用户');
});

test('同 key 异体并发：不得出现 500，最终一个 201 一个 409', async () => {
  const a = register('race-key-2', { username: 'race_diff', password: PASSWORD });
  const b = register('race-key-2', { username: 'race_diff', password: `${PASSWORD}-changed` });
  const results = await Promise.all([a, b]);
  const codes = results.map((r) => r.statusCode).sort();

  assert.ok(!codes.includes(500), `不应出现 500，实际 ${codes.join(',')}`);
  assert.ok(codes.includes(201), `应至少有一个 201，实际 ${codes.join(',')}`);
  assert.ok(codes.includes(409), `异体应被判 409，实际 ${codes.join(',')}`);
});

test('不同 key、同一用户名并发：一个 201 一个 409（普通用户名冲突）', async () => {
  const body = { username: 'race_user_only', password: PASSWORD };
  const [a, b] = await Promise.all([register('race-key-3a', body), register('race-key-3b', body)]);
  const codes = [a.statusCode, b.statusCode].sort();

  assert.ok(!codes.includes(500), `不应出现 500，实际 ${codes.join(',')}`);
  assert.deepEqual(codes, [201, 409], `应为一个 201 一个 409，实际 ${codes.join(',')}`);

  const rows = await owner.query("SELECT count(*)::int AS n FROM users WHERE lower(username) = 'race_user_only'");
  assert.equal(rows.rows[0].n, 1);
});
