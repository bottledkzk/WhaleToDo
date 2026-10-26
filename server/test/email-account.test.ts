// T15：邮箱账户 HTTP 测试（内存路径 + buildApp + inject + emailCodeSink）。
import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/http/app.ts';
import { createMemoryStore, MemoryRepository } from '../src/repo/memory.ts';
import { fromSync } from '../src/repo/from-sync.ts';
import { loadConfig } from '../src/config.ts';
import { createTicketToken, ticketDigest } from '../src/domain/email.ts';

const PASSWORD = 'S3cret-passw0rd';
const NEW_PASSWORD = 'N3w-secret-passw0rd';
const testEnv = {
  NODE_ENV: 'test',
  BUSINESS_TIMEZONE: 'Asia/Shanghai',
  PAYMENT_PROVIDER: 'mock',
  MOCK_WEBHOOK_TOKEN: 'local-test-token',
  PUBLIC_BASE_URL: 'http://127.0.0.1:8080/api/v1',
} as NodeJS.ProcessEnv;

let app: FastifyInstance;
let repo: MemoryRepository;
const sentCodes: string[] = [];

beforeEach(async () => {
  if (app) await app.close();
  sentCodes.length = 0;
  repo = new MemoryRepository(createMemoryStore(), () => Date.now());
  app = buildApp({
    config: loadConfig(testEnv),
    repo: fromSync(repo),
    emailCodeSink: ({ code }) => { sentCodes.push(code); },
  });
});

type InjectOptions = import('light-my-request').InjectOptions;
const inject = (opts: InjectOptions) => app.inject(opts);

function latestCode(): string {
  const code = sentCodes[sentCodes.length - 1];
  assert.ok(code, 'emailCodeSink 未收到验证码');
  return code;
}

async function ticketViaSink(email: string, purpose: 'register' | 'reset' | 'bind'): Promise<string> {
  const sent = await inject({
    method: 'POST', url: '/api/v1/auth/email/send-code', payload: { email, purpose },
  });
  assert.equal(sent.statusCode, 202, sent.body);
  assert.equal(sent.json().data.sent, true);
  const verified = await inject({
    method: 'POST', url: '/api/v1/auth/email/verify',
    payload: { email, purpose, code: latestCode() },
  });
  assert.equal(verified.statusCode, 200, verified.body);
  return verified.json().data.ticket as string;
}

/** 直接造 ticket，避免同邮箱 60 秒限流；只用于测试 */
function seedTicket(email: string, purpose: 'register' | 'reset' | 'bind'): string {
  const ticket = createTicketToken();
  repo.store.emailTickets.set(ticket.digest, {
    purpose,
    email: email.trim().toLowerCase(),
    expiresAt: Date.now() + 60_000,
    consumedAt: null,
    createdAt: Date.now(),
  });
  return ticket.token;
}

function registerEmail(email: string, ticket: string, password = PASSWORD, displayName?: string) {
  return inject({
    method: 'POST', url: '/api/v1/auth/register/email',
    payload: { email, ticket, password, ...(displayName === undefined ? {} : { display_name: displayName }) },
  });
}

async function login(body: { username?: string; email?: string; password: string }) {
  return inject({ method: 'POST', url: '/api/v1/auth/login', payload: body });
}

async function registerUsername(username: string, password = PASSWORD): Promise<string> {
  const registered = await inject({
    method: 'POST', url: '/api/v1/auth/register',
    payload: { username, password },
  });
  assert.equal(registered.statusCode, 201, registered.body);
  const logged = await login({ username, password });
  assert.equal(logged.statusCode, 200, logged.body);
  return logged.json().data.access_token as string;
}

async function bindEmail(token: string, email: string, ticket: string) {
  return inject({
    method: 'POST', url: '/api/v1/me/email/bind',
    headers: { authorization: `Bearer ${token}` },
    payload: { email, ticket },
  });
}

describe('邮箱账户（内存路径）', () => {
  test('邮箱注册 201：whale_<12hex> username，昵称走 resolveDisplayName；邮箱/用户名登录均可用', async () => {
    const email = 'account@example.invalid';
    const ticket = await ticketViaSink(email, 'register');
    const res = await registerEmail(email, ticket, PASSWORD, '我的鲸鱼');
    assert.equal(res.statusCode, 201, res.body);
    const user = res.json().data.user;
    assert.match(user.username, /^whale_[0-9a-f]{12}$/);
    assert.equal(user.display_name, '我的鲸鱼');
    assert.ok(user.id);

    assert.equal((await login({ email, password: PASSWORD })).statusCode, 200);
    assert.equal((await login({ username: user.username, password: PASSWORD })).statusCode, 200);
  });

  test('同邮箱再注册 409 且不消费新 ticket；同 ticket 二次注册 400；purpose 错配 400', async () => {
    const email = 'dupe@example.invalid';
    const firstTicket = await ticketViaSink(email, 'register');
    assert.equal((await registerEmail(email, firstTicket)).statusCode, 201);

    const secondTicket = seedTicket(email, 'register');
    const dupe = await registerEmail(email, secondTicket);
    assert.equal(dupe.statusCode, 409, dupe.body);
    assert.equal(dupe.json().error.code, 'CONFLICT');
    assert.equal(repo.store.emailTickets.get(ticketDigest(secondTicket))?.consumedAt, null);

    const sameTicket = await registerEmail(email, firstTicket);
    assert.equal(sameTicket.statusCode, 400, sameTicket.body);

    const resetTicket = seedTicket('purpose@example.invalid', 'reset');
    const wrongPurpose = await registerEmail('purpose@example.invalid', resetTicket);
    assert.equal(wrongPurpose.statusCode, 400);
  });

  test('登录二选一：同时传 username+email → 400；只传 email 成功；密码错 401', async () => {
    const email = 'login@example.invalid';
    const ticket = await ticketViaSink(email, 'register');
    const created = await registerEmail(email, ticket);
    const username = created.json().data.user.username;

    const both = await login({ username, email, password: PASSWORD });
    assert.equal(both.statusCode, 400);

    assert.equal((await login({ email, password: PASSWORD })).statusCode, 200);
    const wrong = await login({ email, password: 'Wrong-passw0rd' });
    assert.equal(wrong.statusCode, 401);
    assert.equal(wrong.json().error.code, 'UNAUTHENTICATED');
  });

  test('重置 204：旧密码 401、新密码 200、旧 access token 401', async () => {
    const email = 'reset@example.invalid';
    const ticket = await ticketViaSink(email, 'register');
    assert.equal((await registerEmail(email, ticket)).statusCode, 201);
    const logged = await login({ email, password: PASSWORD });
    const oldToken = logged.json().data.access_token as string;

    const resetTicket = seedTicket(email, 'reset');
    const reset = await inject({
      method: 'POST', url: '/api/v1/auth/password/reset',
      payload: { email, ticket: resetTicket, password: NEW_PASSWORD },
    });
    assert.equal(reset.statusCode, 204, reset.body);

    assert.equal((await login({ email, password: PASSWORD })).statusCode, 401);
    assert.equal((await login({ email, password: NEW_PASSWORD })).statusCode, 200);
    const me = await inject({
      method: 'GET', url: '/api/v1/me', headers: { authorization: `Bearer ${oldToken}` },
    });
    assert.equal(me.statusCode, 401, '重置后旧会话应全部撤销');
  });

  test('重置不泄露账号是否存在：合法 reset ticket + 无账号邮箱 → 204', async () => {
    const email = 'ghost@example.invalid';
    const resetTicket = seedTicket(email, 'reset');
    const res = await inject({
      method: 'POST', url: '/api/v1/auth/password/reset',
      payload: { email, ticket: resetTicket, password: NEW_PASSWORD },
    });
    assert.equal(res.statusCode, 204);
    assert.equal(repo.userCount, 0);
  });

  test('绑定：登录后 bind 204；已占用邮箱 409 且 ticket 不消费；未登录 401', async () => {
    const token = await registerUsername('bind_user');
    const bindEmailAddress = 'bind@example.invalid';
    const ticket = seedTicket(bindEmailAddress, 'bind');
    assert.equal((await bindEmail(token, bindEmailAddress, ticket)).statusCode, 204);
    assert.equal((await login({ email: bindEmailAddress, password: PASSWORD })).statusCode, 200);

    // 另一个用户占用邮箱
    const otherEmail = 'taken@example.invalid';
    const otherTicket = await ticketViaSink(otherEmail, 'register');
    assert.equal((await registerEmail(otherEmail, otherTicket)).statusCode, 201);

    const takenTicket = seedTicket(otherEmail, 'bind');
    const taken = await bindEmail(token, otherEmail, takenTicket);
    assert.equal(taken.statusCode, 409, taken.body);
    assert.equal(taken.json().error.code, 'CONFLICT');
    assert.equal(repo.store.emailTickets.get(ticketDigest(takenTicket))?.consumedAt, null);

    const noAuth = await inject({
      method: 'POST', url: '/api/v1/me/email/bind',
      payload: { email: 'x@example.invalid', ticket: seedTicket('x@example.invalid', 'bind') },
    });
    assert.equal(noAuth.statusCode, 401);
  });

  test('非法邮箱 / 弱密码 / 空 ticket → 400', async () => {
    const badEmail = await registerEmail('not-an-email', seedTicket('bad@example.invalid', 'register'));
    assert.equal(badEmail.statusCode, 400);

    const weak = await registerEmail('weak@example.invalid', seedTicket('weak@example.invalid', 'register'), 'short');
    assert.equal(weak.statusCode, 400);

    const noTicket = await registerEmail('no-ticket@example.invalid', '');
    assert.equal(noTicket.statusCode, 400);

    const resetWeak = await inject({
      method: 'POST', url: '/api/v1/auth/password/reset',
      payload: { email: 'weak@example.invalid', ticket: seedTicket('weak@example.invalid', 'reset'), password: 'short' },
    });
    assert.equal(resetWeak.statusCode, 400);
  });
});
