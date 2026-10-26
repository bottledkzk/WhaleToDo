// T08：邮箱验证码路由（内存路径 + buildApp + inject）。
// 重点：一律 202 防探测、60 秒间隔、错误信息不区分原因、一次性消费、5 次失败作废。
// 明文码只通过 emailCodeSink 投递给测试，不进入响应/日志/数据库。
import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/http/app.ts';
import { createMemoryStore, MemoryRepository } from '../src/repo/memory.ts';
import { fromSync } from '../src/repo/from-sync.ts';
import { loadConfig } from '../src/config.ts';
import { createTicketToken } from '../src/domain/email.ts';

const testEnv = {
  NODE_ENV: 'test',
  BUSINESS_TIMEZONE: 'Asia/Shanghai',
  PAYMENT_PROVIDER: 'mock',
  MOCK_WEBHOOK_TOKEN: 'local-test-token',
  PUBLIC_BASE_URL: 'http://127.0.0.1:8080/api/v1',
} as NodeJS.ProcessEnv;

const sentCodes: string[] = [];
let app: FastifyInstance;

beforeEach(async () => {
  if (app) await app.close();
  sentCodes.length = 0;
  const repo = new MemoryRepository(createMemoryStore(), () => Date.now());
  app = buildApp({
    config: loadConfig(testEnv),
    repo: fromSync(repo),
    emailCodeSink: ({ code }) => { sentCodes.push(code); },
  });
});

// fastify 的 inject 有重载，Parameters<...>[0] 会选中零参数重载；直接用 light-my-request 类型
type InjectOptions = import('light-my-request').InjectOptions;
const inject = (opts: InjectOptions) => app.inject(opts);

const EMAIL = 'synthetic@example.invalid';

function sendCode(email = EMAIL, purpose = 'register') {
  return inject({ method: 'POST', url: '/api/v1/auth/email/send-code', payload: { purpose, email } });
}

function verifyCode(code: string, email = EMAIL, purpose = 'register') {
  return inject({ method: 'POST', url: '/api/v1/auth/email/verify', payload: { purpose, email, code } });
}

function latestCode(): string {
  const code = sentCodes[sentCodes.length - 1];
  assert.ok(code, 'emailCodeSink 未收到验证码');
  return code;
}

function wrongCode(code: string): string {
  return code === '000000' ? '111111' : '000000';
}

function fiveWrongCodes(code: string): string[] {
  const out: string[] = [];
  for (let i = 0; out.length < 5; i += 1) {
    const candidate = String(i).padStart(6, '0');
    if (candidate !== code && !out.includes(candidate)) out.push(candidate);
  }
  return out;
}

describe('邮箱验证码（内存路径）', () => {
  test('发码一律 202；60 秒内再发仍 202 且 retry_after_ms>0（被限流不投递）', async () => {
    const first = await sendCode();
    assert.equal(first.statusCode, 202, first.body);
    assert.equal(first.json().data.sent, true);
    assert.equal(first.json().data.retry_after_ms, 0);
    assert.match(latestCode(), /^\d{6}$/);
    assert.equal(sentCodes.length, 1);

    const second = await sendCode();
    assert.equal(second.statusCode, 202, second.body);
    assert.equal(second.json().data.sent, true);
    assert.ok(second.json().data.retry_after_ms > 0, '60 秒内重发应给出等待时间');
    assert.equal(sentCodes.length, 1, 'sent:false 时不得调用 emailCodeSink');
  });

  test('错误码 → 400，且错误信息不区分错误/过期/格式', async () => {
    assert.equal((await sendCode()).statusCode, 202);
    const code = latestCode();

    for (const bad of [wrongCode(code), 'abc', '']) {
      const res = await verifyCode(bad);
      assert.equal(res.statusCode, 400, `${bad}: ${res.body}`);
      assert.equal(res.json().error.code, 'VALIDATION_ERROR');
      assert.equal(res.json().error.message, '验证码不正确或已过期');
    }
  });

  test('正确码 → 200 拿 ticket；同一码二次校验 → 400（一次性消费）', async () => {
    assert.equal((await sendCode()).statusCode, 202);
    const code = latestCode();

    const ok = await verifyCode(code);
    assert.equal(ok.statusCode, 200, ok.body);
    assert.ok(typeof ok.json().data.ticket === 'string' && ok.json().data.ticket.length > 20);
    assert.equal(ok.json().data.expires_in, 60);
    assert.ok(!ok.body.includes(code), '响应不得回显明文验证码');

    const replay = await verifyCode(code);
    assert.equal(replay.statusCode, 400);
    assert.equal(replay.json().error.message, '验证码不正确或已过期');
  });

  test('连续 5 次错码 → 该码作废，第 6 次正确码也 400', async () => {
    assert.equal((await sendCode()).statusCode, 202);
    const code = latestCode();

    for (const bad of fiveWrongCodes(code)) {
      const res = await verifyCode(bad);
      assert.equal(res.statusCode, 400);
    }

    const correctAfterInvalidation = await verifyCode(code);
    assert.equal(correctAfterInvalidation.statusCode, 400, '5 次失败后正确码也应失效');
    assert.equal(correctAfterInvalidation.json().error.message, '验证码不正确或已过期');
  });

  test('非法邮箱 / 非法 purpose → 400（不投递）', async () => {
    const badEmail = await sendCode('not-an-email');
    assert.equal(badEmail.statusCode, 400);
    assert.equal(badEmail.json().error.code, 'VALIDATION_ERROR');

    const badPurpose = await sendCode(EMAIL, 'login');
    assert.equal(badPurpose.statusCode, 400);
    assert.equal(badPurpose.json().error.code, 'VALIDATION_ERROR');
    assert.equal(sentCodes.length, 0);
  });

  test('consumeEmailTicket：from-sync 转发到内存实现，并发只成功一次', async () => {
    const repo = fromSync(new MemoryRepository(createMemoryStore(), () => Date.now()));
    const ticket = createTicketToken();
    const now = Date.now();
    await repo.createEmailTicket!({
      purpose: 'register', email: EMAIL, ticketDigest: ticket.digest, now, ttlMs: 60_000,
    });

    const results = await Promise.all([
      repo.consumeEmailTicket!({ ticketDigest: ticket.digest, now }),
      repo.consumeEmailTicket!({ ticketDigest: ticket.digest, now }),
    ]);
    assert.equal(results.filter(Boolean).length, 1, '并发消费只能成功一次');
    assert.equal(await repo.consumeEmailTicket!({ ticketDigest: ticket.digest, now }), false);
  });
});
