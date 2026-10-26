// 订单与支付的纯逻辑（不依赖数据库）。
// 依据 T13 v2 / 《订单与商品字段建议》第 3、4、5 节：
//  - 商品快照不可变；发积分/VIP 只读订单快照；
//  - environment 只由服务端配置映射，不接受客户端输入；
//  - VIP 叠加 = max(now, 现有到期) + vip_days。
import { createHash } from 'node:crypto';
import type { RepoProduct } from '../repo/types.ts';

export const ORDER_STATUSES = ['PENDING', 'PAID', 'CLOSED', 'REFUNDED'] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];

export const PRODUCT_ID_RE = /^[a-z0-9_]{1,32}$/;
export const ORDER_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export type PaymentOutcome =
  | 'applied'
  | 'replay'
  | 'not_found'
  | 'amount_mismatch'
  | 'state_conflict'
  | 'environment_mismatch';

/** 支付配置 → 订单 environment；disabled 返回 null（下单 503） */
export function environmentForProvider(provider: 'disabled' | 'mock' | 'real'): 'mock' | 'production' | null {
  if (provider === 'disabled') return null;
  if (provider === 'mock') return 'mock';
  return 'production';
}

/** 下单时的不可变商品快照（只取这五个字段，避免把可变商品数据带入订单） */
export function snapshotOf(p: RepoProduct): {
  title: string;
  price_minor: number;
  currency: string;
  points: number;
  vip_days: number;
} {
  return {
    title: p.title,
    price_minor: p.priceMinor,
    currency: p.currency,
    points: p.points,
    vip_days: p.vipDays,
  };
}

/** 回调金额/币种必须与订单一致；币种大小写不敏感，但值必须相等 */
export function amountsMatch(
  order: { amount_minor: number; currency: string },
  input: { amount_minor: number; currency: string },
): boolean {
  return order.amount_minor === input.amount_minor
    && order.currency.trim().toUpperCase() === input.currency.trim().toUpperCase();
}

/** VIP 叠加：从 max(now, 当前有效到期) 起加 vip_days 天 */
export function vipExpiryAfter(currentExpiryMs: number | null, nowMs: number, vipDays: number): number {
  const base = Math.max(nowMs, currentExpiryMs ?? nowMs);
  return base + vipDays * 86_400_000;
}

/** 回调原文摘要（只存摘要，不存原文） */
export function paymentPayloadHash(payload: unknown): string {
  return createHash('sha256').update(JSON.stringify(payload ?? null)).digest('hex');
}
