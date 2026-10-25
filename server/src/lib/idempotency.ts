// 幂等 scope 生成（按 Bottledkzk 2026-10-25 的规范）
//
// 规范要点：
//  - 已登录业务：`v1:<operation>:<authenticated_user_uuid>`，operation 是内部白名单，
//    客户端**不能**传 scope 或决定 user_id
//  - 注册（未登录）：`register:v1:<HMAC-SHA256(scope_key, normalized_username)>`，
//    归一化 = trim + 小写；**不使用 IP 散列**（校园网/NAT 共享 IP 会误碰撞，换网也会破坏重试）
//  - scope 长度受数据库 `varchar(80)` 限制
import { createHmac } from 'node:crypto';

export const SCOPE_MAX_LENGTH = 80;

/** 已登录操作的白名单 */
export const OPERATIONS = ['claim', 'order', 'sync'] as const;
export type Operation = (typeof OPERATIONS)[number];

function assertScope(scope: string): string {
  if (scope.length > SCOPE_MAX_LENGTH) {
    throw new Error(`幂等 scope 超长（${scope.length} > ${SCOPE_MAX_LENGTH}）：${scope}`);
  }
  return scope;
}

/** `v1:claim:<uuid>` 形式；operation 不在白名单时抛错，避免客户端注入任意 scope */
export function operationScope(operation: string, userId: string): string {
  if (!(OPERATIONS as readonly string[]).includes(operation)) {
    throw new Error(`不允许的幂等操作：${operation}`);
  }
  if (!userId) throw new Error('operationScope 需要已认证的 user_id');
  return assertScope(`v1:${operation}:${userId}`);
}

/** 用户名归一化：trim + 小写（只用已校验的 ASCII 用户名） */
export function normalizeUsername(username: string): string {
  return username.trim().toLowerCase();
}

/** 注册 scope：对归一化用户名做 HMAC，输出定长十六进制，避免用户名出现在 scope 里 */
export function registerScope(secret: string, username: string): string {
  const digest = createHmac('sha256', `${secret}:idempotency-scope`)
    .update(normalizeUsername(username))
    .digest('hex')
    .slice(0, 40);
  return assertScope(`register:v1:${digest}`);
}
