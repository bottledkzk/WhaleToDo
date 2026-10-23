// 评审修复的回归测试（PR #2 Request changes）
// 对应 reviewer 的四条意见：
//  P1  注册幂等缓存不得保留明文密码，且需纳入 display_name
//  P2  tsc --noEmit 必须通过（由 CI 执行，本文件不重复）
//  P2  .env 必须真的被加载
//  P2  /me 的 created_at 必须是 UTC ISO8601，而不是毫秒数字
import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/http/app.ts';
import { createMemoryStore, MemoryRepository } from '../src/repo/memory.ts';
import { loadConfig, loadDotEnv } from '../src/config.ts';

const testEnv = { NODE_ENV: 'test', BUSINESS_TIMEZONE: 'Asia/Shanghai' } as NodeJS.ProcessEnv;

let app: FastifyInstance;
let repo: MemoryRepository;

beforeEach(async () => {
  if (app) await app.close();
  repo = new MemoryRepository(createMemoryStore(), () => Date.now());
  app = buildApp({ config: loadConfig(testEnv), repo });
});

const inject = (opts: import('light-my-request').InjectOptions) => app.inject(opts);
const PASSWORD = 'S3cret-passw0rd';

async function registerWithKey(username: string, headers: Record<string, string> = {}, displayName?: string) {
  return inject({
    method: 'POST', url: '/api/v1/auth/register',
    headers: { 'content-type': 'application/json', ...headers },
    payload: { username, password: PASSWORD, ...(displayName ? { display_name: displayName } : {}) },
  });
}

describe('[P1] 注册幂等缓存不得含明文密码', () => {
  test('缓存里只有 HMAC 指纹，没有原始密码', async () => {
    const res = await registerWithKey('idem1', { 'idempotency-key': 'k-1' });
    assert.equal(res.statusCode, 201);

    const entries = [...repo.store.idempotency.entries()];
    assert.equal(entries.length, 1);

    const serialized = JSON.stringify(entries);
    assert.ok(!serialized.includes(PASSWORD), '幂等缓存中不得出现明文密码');
    assert.ok(!serialized.includes('password'), '幂等缓存中不得出现 password 字段');

    const fingerprint = entries[0]![1].fingerprint;
    assert.match(fingerprint, /^[0-9a-f]{64}$/, '指纹应是 HMAC-SHA256 的十六进制摘要');
  });

  test('同键同体重放 → 返回原结果且不重复建号', async () => {
    const first = await registerWithKey('idem2', { 'idempotency-key': 'k-2' });
    const second = await registerWithKey('idem2', { 'idempotency-key': 'k-2' });
    assert.equal(first.statusCode, 201);
    assert.equal(second.statusCode, 201);
    assert.deepEqual(second.json().data.user, first.json().data.user);
    assert.equal(repo.userCount, 1);
  });

  test('同键但 display_name 不同 → 409（同键异体必须冲突）', async () => {
    await registerWithKey('idem3', { 'idempotency-key': 'k-3' }, '张三');
    const changed = await registerWithKey('idem3', { 'idempotency-key': 'k-3' }, '李四');
    assert.equal(changed.statusCode, 409);
    assert.equal(changed.json().error.code, 'CONFLICT');
  });

  test('同键但密码不同 → 409', async () => {
    await inject({
      method: 'POST', url: '/api/v1/auth/register',
      headers: { 'content-type': 'application/json', 'idempotency-key': 'k-4' },
      payload: { username: 'idem4', password: PASSWORD },
    });
    const changed = await inject({
      method: 'POST', url: '/api/v1/auth/register',
      headers: { 'content-type': 'application/json', 'idempotency-key': 'k-4' },
      payload: { username: 'idem4', password: 'Another-passw0rd' },
    });
    assert.equal(changed.statusCode, 409);
  });
});

describe('[P2] /me 的时间字段是 UTC ISO8601', () => {
  test('created_at 形如 2026-10-24T00:00:00.000Z，且可被 Date 解析', async () => {
    await registerWithKey('iso1');
    const login = await inject({
      method: 'POST', url: '/api/v1/auth/login',
      headers: { 'content-type': 'application/json' },
      payload: { username: 'iso1', password: PASSWORD },
    });
    const token = login.json().data.access_token;
    const me = await inject({ method: 'GET', url: '/api/v1/me', headers: { authorization: `Bearer ${token}` } });
    assert.equal(me.statusCode, 200);

    const createdAt = me.json().data.user.created_at;
    assert.equal(typeof createdAt, 'string', 'created_at 必须是字符串，不能是毫秒数字');
    assert.match(createdAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    assert.ok(!Number.isNaN(Date.parse(createdAt)), '必须能被 Date.parse 解析');
  });
});

describe('[P2] .env 真的会被加载', () => {
  let dir: string;

  beforeEach(() => { dir = mkdtempSync(join(tmpdir(), 'whaletodo-env-')); });
  afterEach(() => { rmSync(dir, { recursive: true, force: true }); });

  test('文件存在时把变量写入 process.env', () => {
    const file = join(dir, '.env');
    writeFileSync(file, 'WHALETODO_TEST_PORT=18081\nWHALETODO_TEST_TZ=UTC\n', 'utf8');
    assert.equal(loadDotEnv(file), true);
    assert.equal(process.env.WHALETODO_TEST_PORT, '18081');
    assert.equal(process.env.WHALETODO_TEST_TZ, 'UTC');
    delete process.env.WHALETODO_TEST_PORT;
    delete process.env.WHALETODO_TEST_TZ;
  });

  test('文件不存在时返回 false 且不抛错', () => {
    assert.equal(loadDotEnv(join(dir, 'no-such.env')), false);
  });

  test('加载后的变量能被 loadConfig 读到（.env → 配置 → 生产校验）', () => {
    const file = join(dir, '.env');
    writeFileSync(file, 'WHALETODO_CFG_TZ=UTC\n', 'utf8');
    assert.equal(loadDotEnv(file), true);
    const cfg = loadConfig({ BUSINESS_TIMEZONE: process.env.WHALETODO_CFG_TZ } as NodeJS.ProcessEnv);
    assert.equal(cfg.businessTimezone, 'UTC');
    delete process.env.WHALETODO_CFG_TZ;
  });

  test('production + mock 支付必须拒绝启动', () => {
    assert.throws(
      () => loadConfig({ NODE_ENV: 'production', PAYMENT_PROVIDER: 'mock', MOCK_WEBHOOK_TOKEN: 'x' } as NodeJS.ProcessEnv),
      /只允许在 NODE_ENV=test 或 demo/,
    );
  });
});
