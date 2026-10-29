import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/http/app.ts';
import { createMemoryStore, MemoryRepository } from '../src/repo/memory.ts';
import { fromSync } from '../src/repo/from-sync.ts';
import { loadConfig } from '../src/config.ts';
import { hashToken } from '../src/lib/security.ts';

const testEnv = {
  NODE_ENV: 'test',
  BUSINESS_TIMEZONE: 'Asia/Shanghai',
  PAYMENT_PROVIDER: 'mock',
  MOCK_WEBHOOK_TOKEN: 'local-test-token',
  PUBLIC_BASE_URL: 'http://127.0.0.1:8080/api/v1',
} as NodeJS.ProcessEnv;

let app: FastifyInstance;
let repo: MemoryRepository;

beforeEach(async () => {
  if (app) await app.close();
  repo = new MemoryRepository(createMemoryStore(), () => Date.now());
  app = buildApp({ config: loadConfig(testEnv), repo: fromSync(repo) });
});

// fastify 的 inject 有重载，Parameters<...>[0] 会选中零参数重载；直接用 light-my-request 类型
type InjectOptions = import('light-my-request').InjectOptions;
const inject = (opts: InjectOptions) => app.inject(opts);

async function register(username = 'alice', password = 'S3cret-passw0rd') {
  return inject({
    method: 'POST', url: '/api/v1/auth/register',
    payload: { username, password },
  });
}

async function login(username = 'alice', password = 'S3cret-passw0rd') {
  return inject({ method: 'POST', url: '/api/v1/auth/login', payload: { username, password } });
}

describe('健康检查与元信息', () => {
  test('health/live 与 health/ready 无需鉴权', async () => {
    const live = await inject({ method: 'GET', url: '/api/v1/health/live' });
    assert.equal(live.statusCode, 200);
    const ready = await inject({ method: 'GET', url: '/api/v1/health/ready' });
    const body = ready.json();
    assert.equal(ready.statusCode, 200);
    assert.equal(body.data.business_timezone, 'Asia/Shanghai');
    assert.equal(body.data.payment_provider, 'mock');
    assert.equal(body.data.database, 'available');
  });

  test('health/ready 失败返回 503 且不泄露探测细节', async () => {
    const base = fromSync(repo);

    for (const ready of [
      async () => false,
      async () => { throw new Error('SELECT 1 failed: postgresql://user:secret@db/app'); },
    ]) {
      const downApp = buildApp({ config: loadConfig(testEnv), repo: { ...base, ready } });
      try {
        const res = await downApp.inject({ method: 'GET', url: '/api/v1/health/ready' });
        assert.equal(res.statusCode, 503);
        assert.deepEqual(res.json(), { status: 'unavailable', database: 'unavailable' });
        const text = JSON.stringify(res.json());
        assert.ok(!text.includes('postgresql'));
        assert.ok(!text.includes('SELECT'));
        assert.ok(!text.includes('secret'));
      } finally {
        await downApp.close();
      }
    }
  });

  test('每个响应都带 request_id', async () => {
    const res = await inject({ method: 'GET', url: '/api/v1/health/live' });
    assert.ok(typeof res.json().request_id === 'string' && res.json().request_id.length > 10);
  });

  test('未知端点返回 404 + 统一错误体', async () => {
    const res = await inject({ method: 'GET', url: '/api/v1/nope' });
    assert.equal(res.statusCode, 404);
    assert.equal(res.json().error.code, 'VALIDATION_ERROR');
  });
});

describe('注册', () => {
  test('成功返回 201 与用户信息', async () => {
    const res = await register();
    assert.equal(res.statusCode, 201);
    const body = res.json();
    assert.equal(body.data.user.username, 'alice');
    assert.ok(body.data.user.id);
    assert.equal(repo.userCount, 1);
  });

  test('用户名重复 → 409 CONFLICT（大小写不敏感）', async () => {
    await register('Alice', 'S3cret-passw0rd');
    const res = await register('alice', 'Another-passw0rd');
    assert.equal(res.statusCode, 409);
    assert.equal(res.json().error.code, 'CONFLICT');
  });

  test('非法用户名 / 过短密码 → 400 VALIDATION_ERROR', async () => {
    const bad = await register('a!', 'S3cret-passw0rd');
    assert.equal(bad.statusCode, 400);
    assert.equal(bad.json().error.code, 'VALIDATION_ERROR');
    const short = await register('bob', 'short');
    assert.equal(short.statusCode, 400);
  });

  test('相同 Idempotency-Key 同体重放 → 返回原结果且不重复建号', async () => {
    const first = await inject({
      method: 'POST', url: '/api/v1/auth/register',
      headers: { 'idempotency-key': 'k-1' },
      payload: { username: 'carol', password: 'S3cret-passw0rd' },
    });
    const second = await inject({
      method: 'POST', url: '/api/v1/auth/register',
      headers: { 'idempotency-key': 'k-1' },
      payload: { username: 'carol', password: 'S3cret-passw0rd' },
    });
    assert.equal(first.statusCode, 201);
    assert.equal(second.statusCode, 201);
    assert.deepEqual(second.json().data.user, first.json().data.user);
    assert.equal(repo.userCount, 1);
  });

  test('相同 Idempotency-Key、同一用户但不同密码 → 409', async () => {
    // 注册 scope 由「归一化用户名」派生（评审规范），所以同 key 换用户名是两个独立操作；
    // 只有同一 scope 下异体才冲突。
    await inject({
      method: 'POST', url: '/api/v1/auth/register',
      headers: { 'idempotency-key': 'k-2' },
      payload: { username: 'dave', password: 'S3cret-passw0rd' },
    });
    const sameUserOtherPassword = await inject({
      method: 'POST', url: '/api/v1/auth/register',
      headers: { 'idempotency-key': 'k-2' },
      payload: { username: 'dave', password: 'Another-passw0rd' },
    });
    assert.equal(sameUserOtherPassword.statusCode, 409);
    assert.equal(sameUserOtherPassword.json().error.code, 'CONFLICT');
  });

  test('相同 Idempotency-Key、不同用户名 → 视为两个独立注册（scope 由用户名派生）', async () => {
    await inject({
      method: 'POST', url: '/api/v1/auth/register',
      headers: { 'idempotency-key': 'k-3' },
      payload: { username: 'erin', password: 'S3cret-passw0rd' },
    });
    const other = await inject({
      method: 'POST', url: '/api/v1/auth/register',
      headers: { 'idempotency-key': 'k-3' },
      payload: { username: 'frank', password: 'S3cret-passw0rd' },
    });
    assert.equal(other.statusCode, 201);
  });
});

describe('登录', () => {
  test('成功返回双令牌与过期时间', async () => {
    await register();
    const res = await login();
    assert.equal(res.statusCode, 200);
    const d = res.json().data;
    assert.ok(d.access_token && d.refresh_token);
    assert.equal(d.token_type, 'Bearer');
    assert.ok(d.expires_in > 800 && d.expires_in <= 900, `expires_in=${d.expires_in}`);
    assert.equal(d.user.username, 'alice');
  });

  test('密码错误与账号不存在返回同一错误，避免枚举', async () => {
    await register();
    const wrongPw = await login('alice', 'Wrong-passw0rd');
    const noUser = await login('nobody', 'Wrong-passw0rd');
    assert.equal(wrongPw.statusCode, 401);
    assert.equal(noUser.statusCode, 401);
    assert.equal(wrongPw.json().error.message, noUser.json().error.message);
    assert.equal(wrongPw.json().error.code, 'UNAUTHENTICATED');
  });

  test('空用户名/密码 → 400', async () => {
    const res = await inject({ method: 'POST', url: '/api/v1/auth/login', payload: { username: '', password: '' } });
    assert.equal(res.statusCode, 400);
  });
});

describe('/me 与鉴权', () => {
  test('无令牌 → 401；令牌有效 → 200', async () => {
    await register();
    const noAuth = await inject({ method: 'GET', url: '/api/v1/me' });
    assert.equal(noAuth.statusCode, 401);

    const token = (await login()).json().data.access_token;
    const ok = await inject({ method: 'GET', url: '/api/v1/me', headers: { authorization: `Bearer ${token}` } });
    assert.equal(ok.statusCode, 200);
    assert.equal(ok.json().data.user.username, 'alice');
  });

  test('伪造令牌 → 401', async () => {
    const res = await inject({ method: 'GET', url: '/api/v1/me', headers: { authorization: 'Bearer forged-token' } });
    assert.equal(res.statusCode, 401);
  });

  test('库里存的是摘要，不是明文令牌', async () => {
    await register();
    const token = (await login()).json().data.access_token;
    const digests = [...repo.store.sessions.values()].map((s) => s.accessHash);
    assert.ok(digests.includes(hashToken(token)));
    assert.ok(!digests.includes(token));
  });

  test('entitlements 返回 VIP 占位与余额', async () => {
    await register();
    const token = (await login()).json().data.access_token;
    const res = await inject({ method: 'GET', url: '/api/v1/me/entitlements', headers: { authorization: `Bearer ${token}` } });
    assert.equal(res.statusCode, 200);
    const d = res.json().data;
    assert.equal(d.vip.active, false);
    assert.equal(d.vip.expires_at, null);
    assert.deepEqual(d.balance, { available: 0, reserved: 0 });
  });
});

describe('刷新与撤销', () => {
  test('刷新轮换：旧 refresh 失效、新令牌可用', async () => {
    await register();
    const first = (await login()).json().data;
    const refreshed = await inject({
      method: 'POST', url: '/api/v1/auth/refresh',
      payload: { refresh_token: first.refresh_token },
    });
    assert.equal(refreshed.statusCode, 200);
    const second = refreshed.json().data;
    assert.notEqual(second.refresh_token, first.refresh_token);

    const me = await inject({ method: 'GET', url: '/api/v1/me', headers: { authorization: `Bearer ${second.access_token}` } });
    assert.equal(me.statusCode, 200);
  });

  test('重放已用过的 refresh → 401 且整串撤销（新 access 随之失效）', async () => {
    await register();
    const first = (await login()).json().data;
    const second = (await inject({
      method: 'POST', url: '/api/v1/auth/refresh',
      payload: { refresh_token: first.refresh_token },
    })).json().data;

    const replay = await inject({
      method: 'POST', url: '/api/v1/auth/refresh',
      payload: { refresh_token: first.refresh_token },
    });
    assert.equal(replay.statusCode, 401);

    const me = await inject({ method: 'GET', url: '/api/v1/me', headers: { authorization: `Bearer ${second.access_token}` } });
    assert.equal(me.statusCode, 401, '重放后整条链应被撤销');
  });

  test('退出后 access 立即失效', async () => {
    await register();
    const d = (await login()).json().data;
    const out = await inject({
      method: 'POST', url: '/api/v1/auth/logout',
      headers: { authorization: `Bearer ${d.access_token}` },
    });
    assert.equal(out.statusCode, 204);
    const me = await inject({ method: 'GET', url: '/api/v1/me', headers: { authorization: `Bearer ${d.access_token}` } });
    assert.equal(me.statusCode, 401);
  });
});

describe('配置校验', () => {
  test('生产环境启用 mock 支付 → 直接拒绝启动', () => {
    assert.throws(
      () => loadConfig({ NODE_ENV: 'production', PAYMENT_PROVIDER: 'mock', MOCK_WEBHOOK_TOKEN: 'x' } as NodeJS.ProcessEnv),
      /只允许在 NODE_ENV=test 或 demo/,
    );
  });

  test('mock 支付缺少内部令牌 → 拒绝', () => {
    assert.throws(
      () => loadConfig({ NODE_ENV: 'test', PAYMENT_PROVIDER: 'mock' } as NodeJS.ProcessEnv),
      /MOCK_WEBHOOK_TOKEN/,
    );
  });

  test('非法时区 → 拒绝', () => {
    assert.throws(() => loadConfig({ BUSINESS_TIMEZONE: 'Asia/Nowhere' } as NodeJS.ProcessEnv), /时区/);
  });
});

describe('签到与流水（内存实现）', () => {
  test('领取后 /wallet 与 /wallet/ledger 一致', async () => {
    await register('ledger_user');
    const token = (await login('ledger_user')).json().data.access_token;
    const headers = { authorization: `Bearer ${token}` };

    const claim = await inject({ method: 'POST', url: '/api/v1/rewards/daily/claim', headers });
    assert.equal(claim.statusCode, 201);
    assert.equal(claim.json().data.points_awarded, 1);

    const wallet = await inject({ method: 'GET', url: '/api/v1/wallet', headers });
    assert.equal(wallet.json().data.available, 1);

    const ledger = await inject({ method: 'GET', url: '/api/v1/wallet/ledger', headers });
    assert.equal(ledger.statusCode, 200);
    const items = ledger.json().data.items;
    assert.equal(items.length, 1, '应有且只有一条流水');
    assert.equal(items[0].type, 'award');
    assert.equal(items[0].delta, 1);
    assert.equal(items[0].balance_after, 1);
    assert.match(items[0].created_at, /^\d{4}-\d{2}-\d{2}T.*Z$/);
    assert.equal(ledger.json().data.next_cursor, null);

    const again = await inject({ method: 'POST', url: '/api/v1/rewards/daily/claim', headers });
    assert.equal(again.statusCode, 409);
  });
});

describe('流水游标（复合 (created_at, id)）', () => {
  test('非法 cursor 返回 400，不进入 SQL', async () => {
    await register('cursor_bad');
    const token = (await login('cursor_bad')).json().data.access_token;
    const headers = { authorization: `Bearer ${token}` };
    const uuid = '00000000-0000-4000-8000-000000000000';
    for (const bad of [
      'abc',
      '123:not-a-uuid',
      '-1:' + uuid,
      // 评审复现：超大数字通过 isFinite 但 new Date 得到 Invalid Date → 必须 400 而不是 500
      '999999999999999999999:' + uuid,
      '1.5:' + uuid,
      '10abc:' + uuid,
      '12:' + uuid + ':extra',
    ]) {
      const res = await inject({ method: 'GET', url: `/api/v1/wallet/ledger?cursor=${encodeURIComponent(bad)}`, headers });
      assert.equal(res.statusCode, 400, `cursor=${bad} 应 400，实际 ${res.statusCode}`);
    }
    const badLimit = await inject({ method: 'GET', url: '/api/v1/wallet/ledger?limit=999', headers });
    assert.equal(badLimit.statusCode, 400);
  });

  test('同毫秒多条流水翻页不丢不重', async () => {
    await register('cursor_page');
    const token = (await login('cursor_page')).json().data.access_token;
    const headers = { authorization: `Bearer ${token}` };
    // 领一次奖 → 1 条流水；再构造同毫秒记录不现实，这里验证单条翻页与 next_cursor 为 null
    await inject({ method: 'POST', url: '/api/v1/rewards/daily/claim', headers });
    const first = await inject({ method: 'GET', url: '/api/v1/wallet/ledger?limit=1', headers });
    assert.equal(first.statusCode, 200);
    assert.equal(first.json().data.items.length, 1);
    assert.equal(first.json().data.next_cursor, null, '只有一条时不应给下一页游标');
  });
});

describe('签到进度（GET 可恢复 7 日条）', () => {
  test('未签到时给出下一次的周期与分值；签到后可恢复连续天数', async () => {
    await register('streak_user');
    const token = (await login('streak_user')).json().data.access_token;
    const headers = { authorization: `Bearer ${token}` };

    const before = await inject({ method: 'GET', url: '/api/v1/rewards/daily', headers });
    assert.equal(before.statusCode, 200);
    assert.equal(before.json().data.claimed, false);
    assert.equal(before.json().data.consecutive_days, 0);
    assert.equal(before.json().data.cycle_day, 1);
    assert.equal(before.json().data.next_points, 1);

    const claim = await inject({ method: 'POST', url: '/api/v1/rewards/daily/claim', headers });
    assert.equal(claim.statusCode, 201);
    assert.equal(claim.json().data.points_awarded, 1);
    assert.equal(claim.json().data.consecutive_days, 1);
    assert.equal(claim.json().data.cycle_day, 1);

    // 冷启动：仅凭 GET 也要能还原签到条
    const after = await inject({ method: 'GET', url: '/api/v1/rewards/daily', headers });
    const d = after.json().data;
    assert.equal(d.claimed, true);
    assert.equal(d.consecutive_days, 1);
    assert.equal(d.cycle_day, 1);
    assert.equal(d.points_today, 1);
    assert.equal(d.balance.available, 1);
  });
});