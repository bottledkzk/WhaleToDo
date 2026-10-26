// T10：默认昵称的接口级测试（内存仓储 + buildApp + inject）。
// 覆盖：未传昵称的 HTTP 响应、去空格、64 截断、Whale-1234 安全断言、id 异常回退、
// 昵称不参与登录（display_name 不能当 username 用）。
import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/http/app.ts';
import { createMemoryStore, MemoryRepository } from '../src/repo/memory.ts';
import { fromSync } from '../src/repo/from-sync.ts';
import { loadConfig } from '../src/config.ts';
import { DISPLAY_NAME_MAX, resolveDisplayName } from '../src/domain/profile.ts';

const PASSWORD = 'S3cret-passw0rd';
const testEnv = {
  NODE_ENV: 'test',
  BUSINESS_TIMEZONE: 'Asia/Shanghai',
  PAYMENT_PROVIDER: 'mock',
  MOCK_WEBHOOK_TOKEN: 'local-test-token',
  PUBLIC_BASE_URL: 'http://127.0.0.1:8080/api/v1',
} as NodeJS.ProcessEnv;

let app: FastifyInstance;

beforeEach(async () => {
  if (app) await app.close();
  const repo = new MemoryRepository(createMemoryStore(), () => Date.now());
  app = buildApp({ config: loadConfig(testEnv), repo: fromSync(repo) });
});

// fastify 的 inject 有重载，Parameters<...>[0] 会选中零参数重载；直接用 light-my-request 类型
type InjectOptions = import('light-my-request').InjectOptions;
const inject = (opts: InjectOptions) => app.inject(opts);

async function register(username: string, displayName?: string) {
  return inject({
    method: 'POST',
    url: '/api/v1/auth/register',
    payload: { username, password: PASSWORD, ...(displayName === undefined ? {} : { display_name: displayName }) },
  });
}

async function login(username: string, password = PASSWORD) {
  return inject({
    method: 'POST',
    url: '/api/v1/auth/login',
    payload: { username, password },
  });
}

/** 服务端规则：id 去掉连字符后的末 4 位 */
function expectedDefault(id: string): string {
  return `Whale-${id.replace(/-/g, '').slice(-4)}`;
}

describe('Whale 默认昵称（HTTP）', () => {
  test('注册不传 display_name → Whale-xxxx，后缀精确等于服务端 id 末 4 位', async () => {
    // 固定 id 精确断言（安全断言 c：默认值只来自服务端 id）
    assert.equal(resolveDisplayName('11111111-1111-4111-8111-111111111aaa', 'nick_fixed'), 'Whale-1aaa');

    const res = await register('nick_default');
    assert.equal(res.statusCode, 201, res.body);
    const user = res.json().data.user;
    assert.match(user.display_name, /^Whale-[0-9a-f]{4}$/);
    assert.equal(user.display_name, expectedDefault(user.id));

    const token = (await login('nick_default')).json().data.access_token as string;
    const me = await inject({ method: 'GET', url: '/api/v1/me', headers: { authorization: `Bearer ${token}` } });
    assert.equal(me.statusCode, 200);
    assert.equal(me.json().data.user.display_name, user.display_name);
  });

  test('自定义昵称去空格后原样保存', async () => {
    const res = await register('nick_trim', '  我的鲸鱼  ');
    assert.equal(res.statusCode, 201, res.body);
    assert.equal(res.json().data.user.display_name, '我的鲸鱼');
  });

  test('100 字符昵称截断到 64', async () => {
    const res = await register('nick_long', '鲸'.repeat(100));
    assert.equal(res.statusCode, 201, res.body);
    const displayName = res.json().data.user.display_name as string;
    assert.equal(Array.from(displayName).length, DISPLAY_NAME_MAX);
    assert.equal(displayName, '鲸'.repeat(DISPLAY_NAME_MAX));
  });

  test('安全断言：display_name 原样保留，且不影响鉴权身份 / username / id', async () => {
    // requested 非空原样使用：即使形如 Whale-1234 也不覆盖
    const registered = await register('nick_spoof', 'Whale-1234');
    assert.equal(registered.statusCode, 201, registered.body);
    const user = registered.json().data.user;
    assert.equal(user.display_name, 'Whale-1234');
    assert.equal(user.username, 'nick_spoof', '昵称不得写进 username');
    assert.notEqual(user.username, user.display_name);
    assert.ok(!user.display_name.includes(user.id), 'display_name 不得包含 users.id');
    assert.ok(!user.id.includes(user.display_name), 'users.id 不得包含 display_name');

    // 鉴权只认 username：用昵称文本当 username 登录必须失败
    const byNickname = await login('Whale-1234');
    assert.equal(byNickname.statusCode, 401);

    // 用 username 登录成功，响应里的 user.id 与注册时分配的一致
    const byUsername = await login('nick_spoof');
    assert.equal(byUsername.statusCode, 200);
    assert.equal(byUsername.json().data.user.id, user.id);
    assert.equal(byUsername.json().data.user.display_name, 'Whale-1234');

    const token = byUsername.json().data.access_token as string;
    const me = await inject({ method: 'GET', url: '/api/v1/me', headers: { authorization: `Bearer ${token}` } });
    assert.equal(me.statusCode, 200);
    assert.equal(me.json().data.user.id, user.id, '昵称文本变化不得改变身份 id');
  });

  test('id 异常时回退 username，不产出 Whale- 空后缀', () => {
    assert.equal(resolveDisplayName('abc', 'fallback_user'), 'fallback_user');
    assert.equal(resolveDisplayName('abc', 'fallback_user', '   '), 'fallback_user');
    assert.notEqual(resolveDisplayName('abc', 'fallback_user'), 'Whale-');
  });
});
