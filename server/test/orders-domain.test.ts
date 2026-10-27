// T13：订单/支付领域纯函数测试。
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
  amountsMatch, environmentForProvider, ORDER_ID_RE, paymentPayloadHash,
  PRODUCT_ID_RE, snapshotOf, vipExpiryAfter,
} from '../src/domain/orders.ts';
import type { RepoProduct } from '../src/repo/types.ts';

const product: RepoProduct = {
  productId: 'vip_30d',
  title: 'VIP 30 天',
  priceMinor: 990,
  currency: 'CNY',
  vipDays: 30,
  points: 0,
  active: true,
};

describe('订单领域纯函数', () => {
  test('environment 映射：disabled → null，mock → mock，real → production', () => {
    assert.equal(environmentForProvider('disabled'), null);
    assert.equal(environmentForProvider('mock'), 'mock');
    assert.equal(environmentForProvider('real'), 'production');
  });

  test('snapshotOf 只取不可变的五个字段', () => {
    assert.deepEqual(snapshotOf(product), {
      title: 'VIP 30 天',
      price_minor: 990,
      currency: 'CNY',
      points: 0,
      vip_days: 30,
    });
  });

  test('金额/币种比较：金额必须相等，币种大小写不敏感但值必须相同', () => {
    assert.equal(amountsMatch(
      { amount_minor: 990, currency: 'CNY' },
      { amount_minor: 990, currency: 'cny' },
    ), true);
    assert.equal(amountsMatch(
      { amount_minor: 990, currency: 'CNY' },
      { amount_minor: 991, currency: 'CNY' },
    ), false);
    assert.equal(amountsMatch(
      { amount_minor: 990, currency: 'CNY' },
      { amount_minor: 990, currency: 'USD' },
    ), false);
  });

  test('VIP 叠加：max(now, 现有到期) + vip_days', () => {
    const day = 86_400_000;
    const now = Date.UTC(2026, 9, 26, 12, 0, 0);
    assert.equal(vipExpiryAfter(null, now, 30), now + 30 * day);
    assert.equal(vipExpiryAfter(now + 10 * day, now, 30), now + 40 * day);
    assert.equal(vipExpiryAfter(now - 10 * day, now, 30), now + 30 * day);
  });

  test('回调摘要固定 64 位十六进制且随 payload 变化', () => {
    const a = paymentPayloadHash({ event_id: 'e1', amount_minor: 990 });
    const b = paymentPayloadHash({ event_id: 'e1', amount_minor: 990 });
    const c = paymentPayloadHash({ event_id: 'e1', amount_minor: 991 });
    assert.match(a, /^[0-9a-f]{64}$/);
    assert.equal(a, b);
    assert.notEqual(a, c);
  });

  test('product/order id 正则', () => {
    assert.equal(PRODUCT_ID_RE.test('vip_30d'), true);
    assert.equal(PRODUCT_ID_RE.test('VIP-30d'), false);
    assert.equal(ORDER_ID_RE.test('11111111-1111-4111-8111-111111111111'), true);
    assert.equal(ORDER_ID_RE.test('not-a-uuid'), false);
  });
});
