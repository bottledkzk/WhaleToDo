// T13：商品/订单/模拟支付 HTTP 测试（内存路径 + buildApp + inject）。
// mock webhook 走登录态（RLS 决策 v2），不再校验 X-Mock-Token。
import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/http/app.ts';
import { createMemoryStore, MemoryRepository } from '../src/repo/memory.ts';
import { fromSync } from '../src/repo/from-sync.ts';
import { loadConfig } from '../src/config.ts';
import type { RepoProduct } from '../src/repo/types.ts';

const PASSWORD = 'S3cret-passw0rd';
const MOCK_ENV = {
  NODE_ENV: 'test',
  BUSINESS_TIMEZONE: 'Asia/Shanghai',
  PAYMENT_PROVIDER: 'mock',
  MOCK_WEBHOOK_TOKEN: 'local-test-token',
  PUBLIC_BASE_URL: 'http://127.0.0.1:8080/api/v1',
} as NodeJS.ProcessEnv;
const DISABLED_ENV = {
  NODE_ENV: 'test',
  BUSINESS_TIMEZONE: 'Asia/Shanghai',
  PAYMENT_PROVIDER: 'disabled',
  PUBLIC_BASE_URL: 'http://127.0.0.1:8080/api/v1',
} as NodeJS.ProcessEnv;

const PRODUCTS: RepoProduct[] = [
  {
    productId: 'vip_30d', title: 'VIP 30 天', priceMinor: 990, currency: 'CNY',
    vipDays: 30, points: 0, active: true,
  },
  {
    productId: 'points_300', title: '300 积分', priceMinor: 300, currency: 'CNY',
    vipDays: 0, points: 300, active: true,
  },
  {
    productId: 'combo_100_30', title: '100 积分 + VIP 30 天', priceMinor: 500, currency: 'CNY',
    vipDays: 30, points: 100, active: true,
  },
  {
    productId: 'inactive', title: '下架商品', priceMinor: 100, currency: 'CNY',
    vipDays: 0, points: 0, active: false,
  },
];

let app: FastifyInstance;
let repo: MemoryRepository;

beforeEach(async () => {
  if (app) await app.close();
  repo = new MemoryRepository(createMemoryStore(), () => Date.now());
  for (const p of PRODUCTS) repo.store.products.set(p.productId, p);
  app = buildApp({ config: loadConfig(MOCK_ENV), repo: fromSync(repo) });
});

type InjectOptions = import('light-my-request').InjectOptions;
const inject = (opts: InjectOptions) => app.inject(opts);

async function registerAndLogin(username: string, target: FastifyInstance = app): Promise<string> {
  const registered = await target.inject({
    method: 'POST', url: '/api/v1/auth/register',
    payload: { username, password: PASSWORD },
  });
  assert.equal(registered.statusCode, 201, registered.body);
  const login = await target.inject({
    method: 'POST', url: '/api/v1/auth/login',
    payload: { username, password: PASSWORD },
  });
  assert.equal(login.statusCode, 200, login.body);
  return login.json().data.access_token as string;
}

async function createOrder(token: string, productId = 'vip_30d', idemKey?: string) {
  return inject({
    method: 'POST', url: '/api/v1/orders',
    headers: { authorization: `Bearer ${token}`, ...(idemKey ? { 'idempotency-key': idemKey } : {}) },
    payload: { product_id: productId },
  });
}

async function pay(token: string, orderId: string, eventId: string, amountMinor: number, currency = 'CNY') {
  return inject({
    method: 'POST', url: '/api/v1/payments/mock/webhook',
    headers: { authorization: `Bearer ${token}` },
    payload: {
      event_id: eventId, order_id: orderId, status: 'succeeded',
      amount_minor: amountMinor, currency,
    },
  });
}

describe('商品与订单（内存路径）', () => {
  test('显式模拟确认：并发与重放只发一次权益，使用原订单快照', async () => {
    const token = await registerAndLogin('checkout_once');
    const id = (await createOrder(token, 'points_300', 'checkout-create')).json().data.order.id;
    const changed = repo.store.products.get('points_300')!;
    changed.points = 9000; changed.priceMinor = 9999;
    const confirm = () => inject({ method: 'POST', url: `/api/v1/orders/${id}/mock-pay`, headers: { authorization: `Bearer ${token}` }, payload: {} });
    const responses = await Promise.all([confirm(), confirm(), confirm()]);
    for (const r of responses) { assert.equal(r.statusCode, 200, r.body); assert.equal(r.json().data.order.status, 'PAID'); assert.equal(r.json().data.simulated, true); }
    const wallet = await inject({ method: 'GET', url: '/api/v1/wallet', headers: { authorization: `Bearer ${token}` } });
    assert.equal(wallet.json().data.available, 300);
    assert.equal(responses[0].json().data.order.amount_minor, 300);
    assert.equal((await inject({ method: 'GET', url: '/api/v1/products' })).json().data.mock_checkout, true);
  });

  test('模拟确认拒绝越权、客户端金额和非模拟订单', async () => {
    const a = await registerAndLogin('checkout_a'); const b = await registerAndLogin('checkout_b');
    const id = (await createOrder(a)).json().data.order.id;
    const confirm = (token:string, payload:object = {}) => inject({ method:'POST',url:`/api/v1/orders/${id}/mock-pay`,headers:{authorization:`Bearer ${token}`},payload });
    assert.equal((await confirm(b)).statusCode,403);
    assert.equal((await confirm(a,{amount_minor:0,points:100000})).statusCode,400);
    repo.store.orders.get(id)!.environment = 'production';
    assert.equal((await confirm(a)).statusCode,403);
    assert.equal(repo.store.orders.get(id)!.status,'PENDING');
  });

  test('关闭支付时不开放模拟确认，也不声明模拟能力', async () => {
    const disabled=buildApp({config:loadConfig(DISABLED_ENV),repo:fromSync(repo)});
    try {
      assert.equal((await disabled.inject({method:'GET',url:'/api/v1/products'})).json().data.mock_checkout,false);
      assert.equal((await disabled.inject({method:'POST',url:'/api/v1/orders/00000000-0000-0000-0000-000000000000/mock-pay',payload:{}})).statusCode,404);
      assert.throws(()=>loadConfig({...MOCK_ENV,NODE_ENV:'production'}));
    }finally{await disabled.close();}
  });

  test('商品列表只返回 active，按 product_id 升序且字段齐全', async () => {
    const res = await inject({ method: 'GET', url: '/api/v1/products' });
    assert.equal(res.statusCode, 200);
    const products = res.json().data.products as Array<Record<string, unknown>>;
    assert.deepEqual(products.map((p) => p.product_id), ['combo_100_30', 'points_300', 'vip_30d']);
    assert.deepEqual(products[0], {
      product_id: 'combo_100_30',
      title: '100 积分 + VIP 30 天',
      price_minor: 500,
      currency: 'CNY',
      vip_days: 30,
      points: 100,
    });
  });

  test('未登录下单 401；登录后 201，快照/环境/状态正确', async () => {
    const noAuth = await inject({
      method: 'POST', url: '/api/v1/orders', payload: { product_id: 'vip_30d' },
    });
    assert.equal(noAuth.statusCode, 401);

    const token = await registerAndLogin('orders_create');
    const res = await createOrder(token);
    assert.equal(res.statusCode, 201, res.body);
    const order = res.json().data.order;
    assert.equal(order.product_id, 'vip_30d');
    assert.equal(order.amount_minor, 990);
    assert.equal(order.currency, 'CNY');
    assert.equal(order.status, 'PENDING');
    assert.equal(order.environment, 'mock');
    assert.equal(order.paid_at, null);
    assert.deepEqual(order.product_snapshot, {
      title: 'VIP 30 天', price_minor: 990, currency: 'CNY', points: 0, vip_days: 30,
    });
  });

  test('Idempotency-Key：同体重放同一订单；同 key 换 product_id → 409', async () => {
    const token = await registerAndLogin('orders_idem');
    const first = await createOrder(token, 'vip_30d', 'order-key-1');
    assert.equal(first.statusCode, 201, first.body);
    const firstId = first.json().data.order.id;

    const replay = await createOrder(token, 'vip_30d', 'order-key-1');
    assert.equal(replay.statusCode, 200, replay.body);
    assert.equal(replay.json().data.order.id, firstId);
    assert.equal(repo.store.orders.size, 1);

    const changed = await createOrder(token, 'points_300', 'order-key-1');
    assert.equal(changed.statusCode, 409);
    assert.equal(changed.json().error.code, 'CONFLICT');
  });

  test('订单归属：本人 200；他人/随机 uuid/非法 uuid 一律 403', async () => {
    const tokenA = await registerAndLogin('orders_owner_a');
    const tokenB = await registerAndLogin('orders_owner_b');
    const created = await createOrder(tokenA, 'vip_30d');
    const orderId = created.json().data.order.id;

    const own = await inject({
      method: 'GET', url: `/api/v1/orders/${orderId}`,
      headers: { authorization: `Bearer ${tokenA}` },
    });
    assert.equal(own.statusCode, 200);
    assert.equal(own.json().data.order.id, orderId);

    for (const path of [
      `/api/v1/orders/${orderId}`,
      '/api/v1/orders/11111111-1111-4111-8111-111111111111',
      '/api/v1/orders/not-a-uuid',
    ]) {
      const res = await inject({
        method: 'GET', url: path, headers: { authorization: `Bearer ${tokenB}` },
      });
      assert.equal(res.statusCode, 403, path);
      assert.equal(res.json().error.code, 'FORBIDDEN');
    }
  });

  test('webhook：未登录 401、他人订单 400、金额篡改 400 且保持 PENDING', async () => {
    const tokenA = await registerAndLogin('orders_pay_a');
    const tokenB = await registerAndLogin('orders_pay_b');
    const created = await createOrder(tokenA, 'vip_30d');
    const orderId = created.json().data.order.id;

    const noAuth = await pay('', orderId, 'evt-noauth', 990);
    assert.equal(noAuth.statusCode, 401);

    const other = await pay(tokenB, orderId, 'evt-other', 990);
    assert.equal(other.statusCode, 400, other.body);
    assert.equal(other.json().error.message, '订单不存在');
    assert.equal(repo.store.orders.get(orderId)!.status, 'PENDING');

    const tampered = await pay(tokenA, orderId, 'evt-tamper', 991);
    assert.equal(tampered.statusCode, 400);
    assert.equal(tampered.json().error.message, '金额或币种与订单不一致');
    assert.equal(repo.store.orders.get(orderId)!.status, 'PENDING');
    assert.equal(repo.store.paymentEvents.size, 0);

    const currencyMismatch = await pay(tokenA, orderId, 'evt-currency', 990, 'USD');
    assert.equal(currencyMismatch.statusCode, 400);
    assert.equal(currencyMismatch.json().error.message, '金额或币种与订单不一致');
    assert.equal(repo.store.orders.get(orderId)!.status, 'PENDING');
    assert.equal(repo.store.paymentEvents.size, 0);
  });

  test('跨订单复用 event_id → 409 CONFLICT，B 订单仍 PENDING，不重复发奖', async () => {
    const tokenA = await registerAndLogin('orders_event_a');
    const tokenB = await registerAndLogin('orders_event_b');
    const orderA = (await createOrder(tokenA, 'combo_100_30')).json().data.order.id;
    const orderB = (await createOrder(tokenB, 'combo_100_30')).json().data.order.id;

    assert.equal((await pay(tokenA, orderA, 'evt-shared', 500)).statusCode, 200);
    const conflict = await pay(tokenB, orderB, 'evt-shared', 500);
    assert.equal(conflict.statusCode, 409, conflict.body);
    assert.equal(conflict.json().error.code, 'CONFLICT');
    assert.equal(conflict.json().error.message, '支付事件已被其他订单使用');

    assert.equal(repo.store.orders.get(orderB)!.status, 'PENDING');
    const userA = repo.store.orders.get(orderA)!.userId;
    const userB = repo.store.orders.get(orderB)!.userId;
    assert.equal(repo.walletOf(userA).available, 100);
    assert.equal(repo.walletOf(userB).available, 0);
    assert.equal(repo.store.entitlements.filter((e) => e.userId === userA).length, 1);
    assert.equal(repo.store.entitlements.filter((e) => e.userId === userB).length, 0);
    assert.equal(repo.store.paymentEvents.size, 1);
  });

  test('支付成功：PAID、积分入账、VIP active；重复 event_id 200 replay 不重复发奖', async () => {
    const token = await registerAndLogin('orders_pay_ok');
    const created = await createOrder(token, 'combo_100_30');
    const orderId = created.json().data.order.id;

    const paid = await pay(token, orderId, 'evt-ok-1', 500);
    assert.equal(paid.statusCode, 200, paid.body);
    assert.deepEqual(paid.json().data, { ok: true, replay: false });

    assert.equal(repo.store.orders.get(orderId)!.status, 'PAID');
    assert.equal(repo.walletOf(repo.store.orders.get(orderId)!.userId).available, 100);
    assert.equal(repo.store.entitlements.length, 1);

    const entitlements = await inject({
      method: 'GET', url: '/api/v1/me/entitlements',
      headers: { authorization: `Bearer ${token}` },
    });
    assert.equal(entitlements.statusCode, 200);
    assert.equal(entitlements.json().data.vip.active, true);
    assert.ok(entitlements.json().data.vip.expires_at);
    assert.equal(entitlements.json().data.balance.available, 100);

    const replay = await pay(token, orderId, 'evt-ok-1', 500);
    assert.equal(replay.statusCode, 200, replay.body);
    assert.deepEqual(replay.json().data, { ok: true, replay: true });
    assert.equal(repo.walletOf(repo.store.orders.get(orderId)!.userId).available, 100);
    assert.equal(repo.store.entitlements.length, 1);
  });

  test('PAYMENT_PROVIDER=disabled：webhook 404、下单 503', async () => {
    const disabledRepo = new MemoryRepository(createMemoryStore(), () => Date.now());
    const disabledApp = buildApp({ config: loadConfig(DISABLED_ENV), repo: fromSync(disabledRepo) });
    try {
      const token = await registerAndLogin('orders_disabled', disabledApp);
      const order = await disabledApp.inject({
        method: 'POST', url: '/api/v1/orders',
        headers: { authorization: `Bearer ${token}` },
        payload: { product_id: 'vip_30d' },
      });
      assert.equal(order.statusCode, 503);
      assert.equal(order.json().error.code, 'UPSTREAM_UNAVAILABLE');

      const webhook = await disabledApp.inject({
        method: 'POST', url: '/api/v1/payments/mock/webhook',
        headers: { authorization: `Bearer ${token}` },
        payload: {
          event_id: 'e', order_id: '11111111-1111-4111-8111-111111111111',
          status: 'succeeded', amount_minor: 1, currency: 'CNY',
        },
      });
      assert.equal(webhook.statusCode, 404);
    } finally {
      await disabledApp.close();
    }
  });

  test('VIP 叠加：连买两次 vip_30d，到期 = 首次 + 60 天，entitlements 两条', async () => {
    const token = await registerAndLogin('orders_vip_stack');
    const first = await createOrder(token, 'vip_30d');
    const second = await createOrder(token, 'vip_30d');
    const firstId = first.json().data.order.id;
    const secondId = second.json().data.order.id;

    assert.equal((await pay(token, firstId, 'evt-stack-1', 990)).statusCode, 200);
    assert.equal((await pay(token, secondId, 'evt-stack-2', 990)).statusCode, 200);

    assert.equal(repo.store.entitlements.length, 2);
    const expires = repo.store.entitlements
      .map((e) => e.expiresAt)
      .sort((a, b) => a - b);
    assert.equal(expires[1]! - expires[0]!, 30 * 86_400_000);
  });
});
