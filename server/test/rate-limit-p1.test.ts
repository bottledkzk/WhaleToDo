// 评审 P1 回归：登录限流必须在认证前阻断（不能只是改失败响应）
import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/http/app.ts';
import { createMemoryStore, MemoryRepository } from '../src/repo/memory.ts';
import { loadConfig } from '../src/config.ts';
import { FixedWindowLimiter } from '../src/lib/rateLimit.ts';

const PASSWORD = 'S3cret-passw0rd';

/** 每个用例：小额度（2 次）便于触发限流，同时记录 authenticate 调用次数 */
function makeApp(maxAttempts = 2) {
  const memory = new MemoryRepository(createMemoryStore(), () => Date.now());
  const counter = { authCalls: 0 };
  const original = memory.authenticate.bind(memory);
  // 这个分支的仓储是同步实现，包装时保持同步返回
  memory.authenticate = (username: string, password: string) => {
    counter.authCalls += 1;
    return original(username, password);
  };
  const env = {
    NODE_ENV: 'test',
    BUSINESS_TIMEZONE: 'Asia/Shanghai',
    RATE_LIMIT_MAX_ATTEMPTS: String(maxAttempts),
    RATE_LIMIT_WINDOW_MS: '60000',
  } as NodeJS.ProcessEnv;
  const app = buildApp({ config: loadConfig(env), repo: memory });
  return { app, memory, counter };
}

let ctx: ReturnType<typeof makeApp>;
let app: FastifyInstance;

beforeEach(async () => {
  if (app) await app.close();
  ctx = makeApp(2);
  app = ctx.app;
});

const login = (password: string) => app.inject({
  method: 'POST', url: '/api/v1/auth/login',
  headers: { 'content-type': 'application/json' },
  payload: { username: 'user1', password },
});

const register = (username: string) => app.inject({
  method: 'POST', url: '/api/v1/auth/register',
  headers: { 'content-type': 'application/json' },
  payload: { username, password: PASSWORD },
});

describe('[P1] 超限请求不得进入密码校验', () => {
  test('额度 2：连续 5 次错误口令，只有前 2 次真正调用 authenticate', async () => {
    await register('user1');
    const statuses: number[] = [];
    for (let i = 0; i < 5; i++) {
      const res = await login('Wrong-passw0rd');
      statuses.push(res.statusCode);
    }
    assert.deepEqual(statuses, [401, 401, 429, 429, 429]);
    assert.equal(ctx.counter.authCalls, 2, `authenticate 应只被调用 2 次，实际 ${ctx.counter.authCalls} 次`);
  });

  test('超限后即使口令正确也不会 200，仍被 429 拦下', async () => {
    await register('user1');
    await login('Wrong-passw0rd');
    await login('Wrong-passw0rd');
    const correct = await login(PASSWORD);
    assert.equal(correct.statusCode, 429, '超限后正确口令也不能放行（否则会清零计数绕过限流）');
    assert.equal(ctx.counter.authCalls, 2, '正确口令不应进入 authenticate');
  });

  test('成功登录会清零计数，之后重新获得完整额度', async () => {
    await register('user1');
    await login('Wrong-passw0rd');          // 1/2
    const ok = await login(PASSWORD);       // 2/2 成功 → reset
    assert.equal(ok.statusCode, 200);
    // 清零后应还能错 2 次
    assert.equal((await login('Wrong-passw0rd')).statusCode, 401);
    assert.equal((await login('Wrong-passw0rd')).statusCode, 401);
    assert.equal((await login('Wrong-passw0rd')).statusCode, 429);
    assert.equal(ctx.counter.authCalls, 4, '2 次失败 + 1 次成功 + 清零后 2 次失败 = 5 次尝试中的 4 次进入认证');
  });

  test('并发超限：认证调用次数不超过额度', async () => {
    await register('user1');
    const results = await Promise.all(Array.from({ length: 6 }, () => login('Wrong-passw0rd')));
    const codes = results.map((r) => r.statusCode);
    assert.ok(codes.filter((c) => c === 401).length <= 2, `401 数量应 ≤ 2，实际 ${codes.join(',')}`);
    assert.ok(codes.filter((c) => c === 429).length >= 4, `429 数量应 ≥ 4，实际 ${codes.join(',')}`);
    assert.ok(ctx.counter.authCalls <= 2, `authenticate 调用应 ≤ 2，实际 ${ctx.counter.authCalls}`);
  });
});

describe('限流桶有上界（不依赖定时任务）', () => {
  test('超过 maxBuckets 时先清过期、再淘汰最早到期的桶', () => {
    let now = 1000;
    const limiter = new FixedWindowLimiter(5, 60_000, () => now, 2);
    limiter.hit('a');
    limiter.hit('b');
    assert.equal(limiter.size, 2);
    limiter.hit('c');                       // 第 3 个键 → 淘汰一个
    assert.ok(limiter.size <= 2, `桶数量应有上界，实际 ${limiter.size}`);
  });

  test('sweep 清理过期桶', () => {
    let now = 1000;
    const limiter = new FixedWindowLimiter(5, 1000, () => now, 5);
    limiter.hit('old');
    assert.equal(limiter.size, 1);
    now += 1001;
    assert.equal(limiter.sweep(), 1, '过期桶应被清理');
    assert.equal(limiter.size, 0);
  });

  test('达到上界时优先清理过期桶而不是淘汰有效桶', () => {
    let now = 1000;
    const limiter = new FixedWindowLimiter(5, 1000, () => now, 1);
    limiter.hit('expired');
    now += 1001;
    limiter.hit('live');                    // 达到上界 → 先 sweep 掉 expired
    assert.equal(limiter.size, 1, '过期桶应被清掉，只剩 live');
  });
});
