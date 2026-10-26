// T05 问题 1：默认路径（无 DATABASE_URL）必须能走通同步。
// 评审现场：默认 createRepository() → fromSync(new MemoryRepository())，
// from-sync.ts 漏了 pushSyncBatch / pullSyncChanges 转发 → 合法 push/pull 500。
// 这里不依赖数据库，直接构造等价默认路径（fromSync + MemoryRepository）+ buildApp + inject。
import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/http/app.ts';
import { createMemoryStore, MemoryRepository } from '../src/repo/memory.ts';
import { fromSync } from '../src/repo/from-sync.ts';
import { loadConfig } from '../src/config.ts';
import { SyncPayloadTooLarge } from '../src/domain/sync.ts';
import type { AccountRepository } from '../src/repo/types.ts';

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
  // 等价默认 createRepository()：未设置 DATABASE_URL 时的 fromSync(new MemoryRepository())
  const repo = new MemoryRepository(createMemoryStore(), () => Date.now());
  app = buildApp({ config: loadConfig(testEnv), repo: fromSync(repo) });
});

// fastify 的 inject 有重载，Parameters<...>[0] 会选中零参数重载；直接用 light-my-request 类型
type InjectOptions = import('light-my-request').InjectOptions;
const inject = (opts: InjectOptions) => app.inject(opts);

async function registerAndLogin(username: string): Promise<string> {
  const registered = await inject({
    method: 'POST', url: '/api/v1/auth/register',
    payload: { username, password: PASSWORD },
  });
  assert.equal(registered.statusCode, 201, registered.body);
  const login = await inject({
    method: 'POST', url: '/api/v1/auth/login',
    payload: { username, password: PASSWORD },
  });
  assert.equal(login.statusCode, 200, login.body);
  return login.json().data.access_token as string;
}

describe('同步默认路径（无 DATABASE_URL → fromSync 内存仓储）', () => {
  test('合法 push 200 / pull 200，且能拉到刚推的变更', async () => {
    const token = await registerAndLogin('sync_default');
    const objectId = randomUUID();

    const push = await inject({
      method: 'POST', url: '/api/v1/sync/push',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        batch_id: randomUUID(),
        device_id: 'test-device',
        mutations: [{ object_type: 'task', object_id: objectId, op: 'create', payload: { title: '默认路径' } }],
      },
    });
    assert.equal(push.statusCode, 200, push.body);
    assert.equal(push.json().data.status, 'applied');
    assert.ok(!push.body.includes('当前仓储未实现同步'), 'push 不得再报"仓储未实现同步"');

    const pull = await inject({
      method: 'GET', url: '/api/v1/sync/pull',
      headers: { authorization: `Bearer ${token}` },
    });
    assert.equal(pull.statusCode, 200, pull.body);
    assert.ok(!pull.body.includes('当前仓储未实现同步'), 'pull 不得再报"仓储未实现同步"');

    const changes = pull.json().data.changes as Array<{ object_id: string; payload: { title: string } }>;
    assert.equal(changes.length, 1);
    assert.equal(changes[0]!.object_id, objectId);
    assert.equal(changes[0]!.payload.title, '默认路径');
  });

  test('payload 超过 32KB 前置守卫 → 400 VALIDATION_ERROR（不是 500）', async () => {
    const token = await registerAndLogin('sync_default_big');
    const push = await inject({
      method: 'POST', url: '/api/v1/sync/push',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        batch_id: randomUUID(),
        device_id: 'test-device',
        mutations: [{
          object_type: 'task', object_id: randomUUID(), op: 'create',
          payload: { note: 'x'.repeat(40_000) },
        }],
      },
    });
    assert.equal(push.statusCode, 400, push.body);
    assert.equal(push.json().error.code, 'VALIDATION_ERROR');
    assert.ok(push.json().error.message.includes('payload 超过大小上限'), push.json().error.message);
  });

  test('SyncPayloadTooLarge 路由映射：400 + 稳定文案，不回显数据库原文', async () => {
    const base = fromSync(new MemoryRepository(createMemoryStore(), () => Date.now()));
    const failingRepo: AccountRepository = {
      ...base,
      pushSyncBatch: async () => { throw new SyncPayloadTooLarge(); },
    };
    const appWithFailingRepo = buildApp({ config: loadConfig(testEnv), repo: failingRepo });
    try {
      const registered = await appWithFailingRepo.inject({
        method: 'POST', url: '/api/v1/auth/register',
        payload: { username: 'sync_payload_map', password: PASSWORD },
      });
      assert.equal(registered.statusCode, 201, registered.body);
      const login = await appWithFailingRepo.inject({
        method: 'POST', url: '/api/v1/auth/login',
        payload: { username: 'sync_payload_map', password: PASSWORD },
      });
      const token = login.json().data.access_token as string;

      const res = await appWithFailingRepo.inject({
        method: 'POST', url: '/api/v1/sync/push',
        headers: { authorization: `Bearer ${token}` },
        payload: {
          batch_id: randomUUID(),
          device_id: 'test-device',
          mutations: [{ object_type: 'task', object_id: randomUUID(), op: 'create', payload: { title: 'x' } }],
        },
      });
      assert.equal(res.statusCode, 400, res.body);
      assert.equal(res.json().error.code, 'VALIDATION_ERROR');
      assert.equal(res.json().error.message, 'payload 超过大小上限');
      assert.ok(!res.body.includes('sync_changes_payload_check'), '不得回显约束名');
      assert.ok(!res.body.includes('violates check constraint'), '不得回显数据库异常原文');
    } finally {
      await appWithFailingRepo.close();
    }
  });
});
