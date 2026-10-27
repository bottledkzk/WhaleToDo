// T18：内存模式演示账号 seed 测试（不依赖数据库/邮件服务）。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/http/app.ts';
import { createMemoryStore, MemoryRepository } from '../src/repo/memory.ts';
import { fromSync } from '../src/repo/from-sync.ts';
import { loadConfig } from '../src/config.ts';
import { demoSeedFromEnv, maskEmail, assertDemoSeedUsesMemory } from '../src/domain/demo.ts';

const PASSWORD = 'Demo-passw0rd';
const testEnv = {
  NODE_ENV: 'test',
  BUSINESS_TIMEZONE: 'Asia/Shanghai',
  PAYMENT_PROVIDER: 'disabled',
  PUBLIC_BASE_URL: 'http://127.0.0.1:8080/api/v1',
} as NodeJS.ProcessEnv;

function appFor(repo: MemoryRepository, overrides: NodeJS.ProcessEnv = {}): FastifyInstance {
  return buildApp({
    config: loadConfig({ ...testEnv, ...overrides }),
    repo: fromSync(repo),
  });
}

test('seed 后邮箱登录成功，/me 的 username 是 demo_<8hex>', async () => {
  const repo = new MemoryRepository(createMemoryStore());
  const seeded = await repo.seedEmailAccount('  Demo@Example.Invalid ', PASSWORD, '演示鲸鱼');
  assert.equal(seeded.email, 'demo@example.invalid');
  assert.match(seeded.username, /^demo_[0-9a-f]{8}$/);
  assert.equal(seeded.displayName, '演示鲸鱼');
  assert.equal(repo.store.wallets.has(seeded.id), true);

  const app = appFor(repo);
  try {
    const login = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { email: 'demo@example.invalid', password: PASSWORD },
    });
    assert.equal(login.statusCode, 200, login.body);
    const token = login.json().data.access_token as string;
    assert.ok(token);

    const me = await app.inject({
      method: 'GET',
      url: '/api/v1/me',
      headers: { authorization: `Bearer ${token}` },
    });
    assert.equal(me.statusCode, 200, me.body);
    assert.match(me.json().data.user.username, /^demo_[0-9a-f]{8}$/);
    assert.equal(me.json().data.user.display_name, '演示鲸鱼');
  } finally {
    await app.close();
  }
});

test('同一邮箱重复 seed 幂等，不重复建号', async () => {
  const repo = new MemoryRepository(createMemoryStore());
  const first = await repo.seedEmailAccount('demo@example.invalid', PASSWORD);
  const second = await repo.seedEmailAccount('DEMO@example.invalid', PASSWORD);
  assert.equal(second.id, first.id);
  assert.equal(repo.userCount, 1);
  assert.equal(repo.store.emailIndex.size, 1);
});

test('非法 email / 弱密码 / 过长昵称抛错且不建号', async () => {
  const repo = new MemoryRepository(createMemoryStore());
  await assert.rejects(
    () => repo.seedEmailAccount('not-an-email', PASSWORD),
    /邮箱格式/,
  );
  await assert.rejects(
    () => repo.seedEmailAccount('demo@example.invalid', 'short'),
    /8~200/,
  );
  await assert.rejects(
    () => repo.seedEmailAccount('demo@example.invalid', PASSWORD, '鲸'.repeat(65)),
    /64/,
  );
  assert.equal(repo.userCount, 0);
});

test('DEMO_* 参数成对校验，日志邮箱掩码正确', () => {
  assert.equal(demoSeedFromEnv({}), null);
  assert.throws(
    () => demoSeedFromEnv({ DEMO_EMAIL: 'demo@example.invalid' }),
    /必须同时设置/,
  );
  assert.throws(
    () => demoSeedFromEnv({ DEMO_EMAIL: 'bad-email', DEMO_PASSWORD: PASSWORD }),
    /邮箱格式/,
  );
  assert.throws(
    () => demoSeedFromEnv({ DEMO_EMAIL: 'demo@example.invalid', DEMO_PASSWORD: 'short' }),
    /8~200/,
  );
  assert.equal(
    demoSeedFromEnv({ DEMO_EMAIL: 'Demo@Example.Invalid', DEMO_PASSWORD: PASSWORD })?.email,
    'demo@example.invalid',
  );
  assert.equal(maskEmail('demo@example.invalid'), 'd***@example.invalid');
});

test('DEMO_* 与 DATABASE_URL 同时存在时 fail-fast', () => {
  assert.throws(
    () => assertDemoSeedUsesMemory(true, false),
    /DEMO_\* 仅用于内存模式：请移除 DATABASE_URL，或不要设置 DEMO_\*/,
  );
  assert.doesNotThrow(() => assertDemoSeedUsesMemory(true, true));
  assert.doesNotThrow(() => assertDemoSeedUsesMemory(false, false));
});

test('demo 商品 seed 后列表返回 3 项且字段正确', async () => {
  const repo = new MemoryRepository(createMemoryStore());
  assert.equal(repo.seedDemoProducts(), 3);
  const app = appFor(repo);
  try {
    const res = await app.inject({ method: 'GET', url: '/api/v1/products' });
    assert.equal(res.statusCode, 200, res.body);
    const products = res.json().data.products as Array<Record<string, unknown>>;
    assert.equal(products.length, 3);
    const vip = products.find((p) => p.product_id === 'vip_7d');
    assert.deepEqual(vip, {
      product_id: 'vip_7d',
      title: 'VIP 7 天',
      price_minor: 700,
      currency: 'CNY',
      vip_days: 7,
      points: 0,
    });
  } finally {
    await app.close();
  }
});

test('商品 seed 幂等：重复调用仍是 3 项', async () => {
  const repo = new MemoryRepository(createMemoryStore());
  repo.seedDemoProducts();
  repo.seedDemoProducts();
  const products = await repo.listProducts();
  assert.equal(products.length, 3);
  assert.deepEqual(
    products.map((p) => p.productId).sort(),
    ['points_100', 'vip_30d', 'vip_7d'],
  );
});

test('vip_7d 下单金额 700、状态 PENDING', async () => {
  const repo = new MemoryRepository(createMemoryStore());
  await repo.seedEmailAccount('demo@example.invalid', PASSWORD, '演示鲸鱼');
  repo.seedDemoProducts();
  const app = appFor(repo, {
    PAYMENT_PROVIDER: 'mock',
    MOCK_WEBHOOK_TOKEN: 'local-test-token',
  });
  try {
    const login = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { email: 'demo@example.invalid', password: PASSWORD },
    });
    assert.equal(login.statusCode, 200, login.body);
    const token = login.json().data.access_token as string;

    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/orders',
      headers: { authorization: `Bearer ${token}` },
      payload: { product_id: 'vip_7d' },
    });
    assert.equal(res.statusCode, 201, res.body);
    const order = res.json().data.order as Record<string, unknown>;
    assert.equal(order.product_id, 'vip_7d');
    assert.equal(order.amount_minor, 700);
    assert.equal(order.currency, 'CNY');
    assert.equal(order.status, 'PENDING');
    assert.deepEqual(order.product_snapshot, {
      title: 'VIP 7 天',
      price_minor: 700,
      currency: 'CNY',
      points: 0,
      vip_days: 7,
    });
  } finally {
    await app.close();
  }
});
