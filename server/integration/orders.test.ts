// T13：订单/模拟支付真库集成测试（database job：PG16 + 受限 whaletodo_app）。
// v2 RLS 决策：createOrder / getOrder / applyPayment 都在事务内 set_config('app.user_id')。
import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import { PrismaClient } from '@prisma/client';
import { PrismaRepository } from '../src/repo/prisma.ts';
import { buildApp } from '../src/http/app.ts';
import { loadConfig } from '../src/config.ts';
import { readFileSync } from 'node:fs';

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
const prisma = new PrismaClient({ datasourceUrl: appUrlWithSchema });
const repo = new PrismaRepository(prisma);

const PASSWORD = 'Synthetic-order-password';
const PAYLOAD_HASH = 'a'.repeat(64);

const createUser = (name: string) => repo.createUser(name, PASSWORD);
const createOrder = (userId: string, productId = 'combo_100_30') =>
  repo.createOrder!({ userId, productId, environment: 'mock' });
const pay = (
  userId: string,
  orderId: string,
  eventId: string,
  amountMinor = 500,
  currency = 'CNY',
) => repo.applyPayment!({
  userId,
  provider: 'mock',
  eventId,
  orderId,
  amountMinor,
  currency,
  payloadHash: PAYLOAD_HASH,
  now: Date.now(),
});

before(async () => {
  await owner.query('SELECT 1');
});

beforeEach(async () => {
  await owner.query('TRUNCATE users CASCADE');
  await owner.query('DELETE FROM products');
  await owner.query(`
    INSERT INTO products (product_id, title, price_minor, currency, vip_days, points, active)
    VALUES
      ('combo_100_30', '100 积分 + VIP 30 天', 500, 'CNY', 30, 100, true),
      ('vip_30d', 'VIP 30 天', 990, 'CNY', 30, 0, true),
      ('points_300', '300 积分', 300, 'CNY', 0, 300, true)`);
});

after(async () => {
  await owner.query('TRUNCATE users CASCADE');
  await owner.query('DELETE FROM products');
  await prisma.$disconnect();
  await owner.end();
});

test('下单快照 = 商品行，amount/currency/environment/status 正确', async () => {
  const user = await createUser('ord_snapshot');
  const order = await createOrder(user.id);
  assert.equal(order.status, 'PENDING');
  assert.equal(order.environment, 'mock');
  assert.equal(order.amountMinor, 500);
  assert.equal(order.currency, 'CNY');
  assert.deepEqual(order.snapshot, {
    title: '100 积分 + VIP 30 天',
    price_minor: 500,
    currency: 'CNY',
    points: 100,
    vip_days: 30,
  });

  const rows = await owner.query(
    'SELECT amount_minor, currency, status, environment, product_snapshot, paid_at FROM orders WHERE id=$1',
    [order.id],
  );
  assert.equal(rows.rowCount, 1);
  assert.equal(rows.rows[0].amount_minor, 500);
  assert.equal(rows.rows[0].currency, 'CNY');
  assert.equal(rows.rows[0].status, 'PENDING');
  assert.equal(rows.rows[0].environment, 'mock');
  assert.deepEqual(rows.rows[0].product_snapshot, order.snapshot);
  assert.equal(rows.rows[0].paid_at, null);

  const got = await repo.getOrder!(user.id, order.id);
  assert.equal(got?.id, order.id);
});

test('金额篡改 → amount_mismatch，订单 PENDING，无事件/流水/权益', async () => {
  const user = await createUser('ord_mismatch');
  const order = await createOrder(user.id);
  assert.equal(await pay(user.id, order.id, 'evt-mismatch', 501), 'amount_mismatch');
  assert.equal(await pay(user.id, order.id, 'evt-currency', 500, 'USD'), 'amount_mismatch');

  const rows = await owner.query('SELECT status FROM orders WHERE id=$1', [order.id]);
  assert.equal(rows.rows[0].status, 'PENDING');
  for (const table of ['payment_events', 'point_ledger', 'entitlements']) {
    const count = await owner.query(`SELECT count(*)::int AS n FROM ${table}`);
    assert.equal(count.rows[0].n, 0, `${table} 必须为 0`);
  }
  const wallet = await owner.query('SELECT available FROM point_wallets WHERE user_id=$1', [user.id]);
  assert.equal(wallet.rows[0].available, 0);
});

test('跨订单复用 event_id → event_conflict，B 订单 PENDING，不重复发奖', async () => {
  const a = await createUser('ord_event_a');
  const b = await createUser('ord_event_b');
  const orderA = await createOrder(a.id);
  const orderB = await createOrder(b.id);

  assert.equal(await pay(a.id, orderA.id, 'evt-shared'), 'applied');
  assert.equal(await pay(b.id, orderB.id, 'evt-shared'), 'event_conflict');

  const bOrder = await owner.query('SELECT status FROM orders WHERE id=$1', [orderB.id]);
  assert.equal(bOrder.rows[0].status, 'PENDING', 'B 订单必须保持 PENDING');

  const events = await owner.query('SELECT count(*)::int AS n FROM payment_events');
  assert.equal(events.rows[0].n, 1);
  const ledger = await owner.query('SELECT count(*)::int AS n FROM point_ledger');
  assert.equal(ledger.rows[0].n, 1);
  const entitlements = await owner.query('SELECT count(*)::int AS n FROM entitlements');
  assert.equal(entitlements.rows[0].n, 1);
  const walletB = await owner.query('SELECT available FROM point_wallets WHERE user_id=$1', [b.id]);
  assert.equal(walletB.rows[0].available, 0);
});

test('支付成功：PAID/paid_at/流水/钱包/entitlement 单事务落库', async () => {
  const user = await createUser('ord_success');
  const order = await createOrder(user.id);
  assert.equal(await pay(user.id, order.id, 'evt-success'), 'applied');

  const rows = await owner.query('SELECT status, paid_at FROM orders WHERE id=$1', [order.id]);
  assert.equal(rows.rows[0].status, 'PAID');
  assert.notEqual(rows.rows[0].paid_at, null);

  const events = await owner.query(
    'SELECT provider, event_id, order_id, status, amount_minor, currency FROM payment_events WHERE order_id=$1',
    [order.id],
  );
  assert.equal(events.rowCount, 1);
  assert.equal(events.rows[0].provider, 'mock');
  assert.equal(events.rows[0].event_id, 'evt-success');
  assert.equal(events.rows[0].status, 'succeeded');
  assert.equal(events.rows[0].amount_minor, 500);

  const ledger = await owner.query(
    'SELECT event_id, type, available_delta, balance_after FROM point_ledger WHERE user_id=$1',
    [user.id],
  );
  assert.equal(ledger.rowCount, 1);
  assert.equal(ledger.rows[0].event_id, `order:${order.id}`);
  assert.equal(ledger.rows[0].type, 'award');
  assert.equal(ledger.rows[0].available_delta, 100);
  assert.equal(ledger.rows[0].balance_after, 100);

  const wallet = await owner.query('SELECT available, reserved FROM point_wallets WHERE user_id=$1', [user.id]);
  assert.equal(wallet.rows[0].available, 100);
  assert.equal(wallet.rows[0].reserved, 0);

  const entitlements = await owner.query(
    "SELECT kind, source_order_id, expires_at FROM entitlements WHERE user_id=$1 AND kind='vip'",
    [user.id],
  );
  assert.equal(entitlements.rowCount, 1);
  assert.equal(entitlements.rows[0].source_order_id, order.id);
  assert.ok(entitlements.rows[0].expires_at.getTime() > Date.now());
  assert.ok((await repo.vipExpiresAt!(user.id))! > Date.now());
});

test('重复回调 → replay，流水/权益/钱包不重复', async () => {
  const user = await createUser('ord_replay');
  const order = await createOrder(user.id);
  assert.equal(await pay(user.id, order.id, 'evt-replay'), 'applied');
  assert.equal(await pay(user.id, order.id, 'evt-replay'), 'replay');

  for (const [table, where] of [
    ['payment_events', 'order_id'],
    ['point_ledger', 'user_id'],
    ['entitlements', 'user_id'],
  ] as const) {
    const count = await owner.query(`SELECT count(*)::int AS n FROM ${table} WHERE ${where}=$1`, [
      where === 'order_id' ? order.id : user.id,
    ]);
    assert.equal(count.rows[0].n, 1, `${table} 不能重复发奖`);
  }
  const wallet = await owner.query('SELECT available FROM point_wallets WHERE user_id=$1', [user.id]);
  assert.equal(wallet.rows[0].available, 100);
});

test('并发两次同 event_id：一个 applied、一个 replay，流水 1 行', async () => {
  const user = await createUser('ord_concurrent');
  const order = await createOrder(user.id);
  const results = await Promise.all([
    pay(user.id, order.id, 'evt-concurrent'),
    pay(user.id, order.id, 'evt-concurrent'),
  ]);
  assert.deepEqual([...results].sort(), ['applied', 'replay']);

  const ledger = await owner.query('SELECT count(*)::int AS n FROM point_ledger WHERE user_id=$1', [user.id]);
  assert.equal(ledger.rows[0].n, 1);
  const wallet = await owner.query('SELECT available FROM point_wallets WHERE user_id=$1', [user.id]);
  assert.equal(wallet.rows[0].available, 100);
});

test('归属：B 查/付 A 的订单 → null/not_found，订单仍 PENDING', async () => {
  const a = await createUser('ord_owner_a');
  const b = await createUser('ord_owner_b');
  const order = await createOrder(a.id);

  assert.equal(await repo.getOrder!(b.id, order.id), null);
  assert.equal(await pay(b.id, order.id, 'evt-other'), 'not_found');

  const rows = await owner.query('SELECT status FROM orders WHERE id=$1', [order.id]);
  assert.equal(rows.rows[0].status, 'PENDING');
  const events = await owner.query('SELECT count(*)::int AS n FROM payment_events');
  assert.equal(events.rows[0].n, 0);
});

test('VIP 叠加：两单到期 = 首单 + 30 天，entitlement 2 行', async () => {
  const user = await createUser('ord_vip_stack');
  const first = await createOrder(user.id, 'vip_30d');
  const second = await createOrder(user.id, 'vip_30d');
  assert.equal(await pay(user.id, first.id, 'evt-vip-1', 990), 'applied');
  assert.equal(await pay(user.id, second.id, 'evt-vip-2', 990), 'applied');

  const rows = await owner.query(
    "SELECT expires_at FROM entitlements WHERE user_id=$1 AND kind='vip' ORDER BY expires_at",
    [user.id],
  );
  assert.equal(rows.rowCount, 2);
  const diff = rows.rows[1].expires_at.getTime() - rows.rows[0].expires_at.getTime();
  assert.equal(diff, 30 * 86_400_000);
});

test('并发支付两笔零积分 VIP 订单：按用户串行，VIP 叠加 14 天', async () => {
  const user = await createUser('ord_vip_concurrent');
  await owner.query(`
    INSERT INTO products (product_id, title, price_minor, currency, vip_days, points, active)
    VALUES ('vip_7d', 'VIP 7 天', 100, 'CNY', 7, 0, true)`);

  const first = await createOrder(user.id, 'vip_7d');
  const second = await createOrder(user.id, 'vip_7d');
  assert.notEqual(first.id, second.id);

  const results = await Promise.all([
    pay(user.id, first.id, 'evt-vip-concurrent-a', 100),
    pay(user.id, second.id, 'evt-vip-concurrent-b', 100),
  ]);
  assert.deepEqual(results, ['applied', 'applied']);

  const orders = await owner.query(
    'SELECT status FROM orders WHERE id IN ($1::uuid, $2::uuid)',
    [first.id, second.id],
  );
  assert.equal(orders.rowCount, 2);
  assert.ok(orders.rows.every((row) => row.status === 'PAID'), '两单都必须 PAID');

  const events = await owner.query(
    'SELECT count(*)::int AS n FROM payment_events WHERE order_id IN ($1::uuid, $2::uuid)',
    [first.id, second.id],
  );
  assert.equal(events.rows[0].n, 2);

  const entitlements = await owner.query(
    "SELECT expires_at FROM entitlements WHERE user_id=$1 AND kind='vip' ORDER BY expires_at",
    [user.id],
  );
  assert.equal(entitlements.rowCount, 2);
  const earliest = entitlements.rows[0].expires_at.getTime();
  const latest = entitlements.rows[1].expires_at.getTime();
  assert.equal(latest - earliest, 7 * 86_400_000);
  assert.ok(latest > Date.now() + 13 * 86_400_000, '两笔 7 天必须叠到约 14 天');

  const wallet = await owner.query('SELECT available FROM point_wallets WHERE user_id=$1', [user.id]);
  assert.equal(wallet.rows[0].available, 0);
});

test('原子性：钱包溢出 → 整事务回滚，订单仍 PENDING 且无事件/流水/权益', async () => {
  const user = await createUser('ord_atomic');
  await owner.query('UPDATE point_wallets SET available=999999 WHERE user_id=$1', [user.id]);
  const order = await createOrder(user.id, 'points_300');

  await assert.rejects(pay(user.id, order.id, 'evt-overflow', 300));

  const orderRows = await owner.query('SELECT status FROM orders WHERE id=$1', [order.id]);
  assert.equal(orderRows.rows[0].status, 'PENDING');
  const events = await owner.query('SELECT count(*)::int AS n FROM payment_events');
  assert.equal(events.rows[0].n, 0);
  const ledger = await owner.query('SELECT count(*)::int AS n FROM point_ledger');
  assert.equal(ledger.rows[0].n, 0);
  const entitlements = await owner.query('SELECT count(*)::int AS n FROM entitlements');
  assert.equal(entitlements.rows[0].n, 0);
  const wallet = await owner.query('SELECT available FROM point_wallets WHERE user_id=$1', [user.id]);
  assert.equal(wallet.rows[0].available, 999999);
});


test('production mock HTTP uses restricted PG, order snapshot and exactly-once entitlement', async () => {
 const app=buildApp({config:loadConfig({NODE_ENV:'production',PAYMENT_PROVIDER:'mock',IDEMPOTENCY_SECRET:'synthetic-integration-persistent-secret'}),repo});
 try {
  async function token(name:string) {
   const r=await app.inject({method:'POST',url:'/api/v1/auth/register',payload:{username:name,password:'Synthetic-long-password-2026'}});assert.equal(r.statusCode,201,r.body);
   const login=await app.inject({method:'POST',url:'/api/v1/auth/login',payload:{username:name,password:'Synthetic-long-password-2026'}});assert.equal(login.statusCode,200,login.body);return login.json().data.access_token as string;
  }
  const a=await token('mock_http_a'),b=await token('mock_http_b');
  const created=await app.inject({method:'POST',url:'/api/v1/orders',headers:{authorization:`Bearer ${a}`,'idempotency-key':randomUUID()},payload:{product_id:'points_300'}});assert.equal(created.statusCode,201,created.body);
  const id=created.json().data.order.id;
  await owner.query('UPDATE products SET points=$1, price_minor=$2 WHERE product_id=$3',[9000,9999,'points_300']);
  const confirm=(t:string)=>app.inject({method:'POST',url:`/api/v1/orders/${id}/mock-pay`,headers:{authorization:`Bearer ${t}`},payload:{}});
  assert.equal((await confirm(b)).statusCode,403);
  for(const r of await Promise.all([confirm(a),confirm(a),confirm(a)])) {assert.equal(r.statusCode,200,r.body);assert.equal(r.json().data.order.amount_minor,300);}
  const wallet=await app.inject({method:'GET',url:'/api/v1/wallet',headers:{authorization:`Bearer ${a}`}});assert.equal(wallet.json().data.available,300);
  assert.equal((await owner.query('SELECT count(*)::int AS n FROM payment_events WHERE order_id=$1',[id])).rows[0].n,1);
  assert.equal((await app.inject({method:'POST',url:'/api/v1/payments/mock/webhook',headers:{authorization:`Bearer ${a}`},payload:{}})).statusCode,404);
 } finally {await app.close();}
});

test('mock catalog migration adds defaults idempotently without replacing configured prices',async()=>{
 const sql=readFileSync(new URL('../prisma/migrations/202610300002_mock_catalog/migration.sql',import.meta.url),'utf8');
 await owner.query(sql);await owner.query(sql);
 const rows=(await owner.query('SELECT product_id,price_minor FROM products WHERE product_id=ANY($1::text[]) ORDER BY product_id',[['vip_7d','vip_30d','points_100']])).rows;
 assert.equal(rows.length,3);assert.equal(rows.find(r=>r.product_id==='vip_30d').price_minor,990);
});
