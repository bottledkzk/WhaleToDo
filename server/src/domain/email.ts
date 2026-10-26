// 邮箱验证码的纯逻辑（不依赖数据库）：归一化、验证码生成/绑定、限流窗口、ticket。
// 依据 #14 评审冻结的安全边界：6 位数字、5 分钟有效、同邮箱 60 秒一次/每小时 5 次、
// 失败 5 次作废、HMAC 密钥用途隔离、purpose/email/有效期绑定、一次性消费。
import { createHmac, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { ApiError } from '../lib/security.ts';

export const EMAIL_PURPOSES = ['register', 'reset', 'bind'] as const;
export type EmailPurpose = (typeof EMAIL_PURPOSES)[number];

export const CODE_LENGTH = 6;
export const CODE_TTL_MS = 5 * 60 * 1000;
export const RESEND_INTERVAL_MS = 60 * 1000;
export const MAX_SENDS_PER_HOUR = 5;
export const MAX_ATTEMPTS = 5;
export const TICKET_TTL_MS = 60 * 1000;

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const MAX_EMAIL_LENGTH = 254;

export function isEmailPurpose(value: string): value is EmailPurpose {
  return (EMAIL_PURPOSES as readonly string[]).includes(value);
}

/** 归一化：去空格 + 小写。数据库里也存这个形式（与 users.email 的 lower() 唯一索引一致）。 */
export function normalizeEmail(raw: string): string {
  return raw.trim().toLowerCase();
}

export function assertEmail(raw: unknown): string {
  const email = normalizeEmail(String(raw ?? ''));
  if (!email || email.length > MAX_EMAIL_LENGTH || !EMAIL_RE.test(email)) {
    throw new ApiError('VALIDATION_ERROR', '邮箱格式不正确');
  }
  return email;
}

export function assertPurpose(raw: unknown): EmailPurpose {
  const purpose = String(raw ?? '');
  if (!isEmailPurpose(purpose)) throw new ApiError('VALIDATION_ERROR', '不支持的 purpose');
  return purpose;
}

/** 生成 6 位数字码（首位允许 0，用 randomInt 保证均匀且不可预测）。 */
export function generateCode(): string {
  return String(randomInt(0, 10 ** CODE_LENGTH)).padStart(CODE_LENGTH, '0');
}

/**
 * 验证码摘要：绑定 purpose + email + code。
 * 密钥按用途派生（`${secret}:${purpose}`），与幂等键的用途隔离做法一致；
 * 换 purpose 或换邮箱后同一份摘要无法复用。
 */
export function codeDigest(secret: string, purpose: EmailPurpose, email: string, code: string): string {
  return createHmac('sha256', `${secret}:email-code:${purpose}`)
    .update(`${purpose}\u0000${email}\u0000${code}`)
    .digest('hex');
}

/** 恒定时间比较两个十六进制摘要。 */
export function digestEquals(a: string, b: string): boolean {
  const ba = Buffer.from(a, 'hex');
  const bb = Buffer.from(b, 'hex');
  if (ba.length !== bb.length || ba.length === 0) return false;
  return timingSafeEqual(ba, bb);
}

/** 一次性 ticket 令牌与其摘要（数据库只存摘要）。 */
export function createTicketToken(): { token: string; digest: string } {
  const token = randomBytes(32).toString('base64url');
  return { token, digest: ticketDigest(token) };
}

export function ticketDigest(token: string): string {
  return createHmac('sha256', 'email-ticket').update(token).digest('hex');
}

/** 距离下次可发送的毫秒数（0 表示可以发）。 */
export function resendWaitMs(lastSentAt: number | null, now: number): number {
  if (lastSentAt === null) return 0;
  const elapsed = now - lastSentAt;
  return elapsed >= RESEND_INTERVAL_MS ? 0 : RESEND_INTERVAL_MS - elapsed;
}

/** 每小时发送次数是否超限（调用方传入本小时已发送条数）。 */
export function assertHourlyQuota(sentThisHour: number): void {
  if (sentThisHour >= MAX_SENDS_PER_HOUR) {
    throw new ApiError('RATE_LIMITED', '验证码发送过于频繁，请稍后再试');
  }
}

/** 校验码是否仍可尝试；已用满次数即作废。 */
export function assertAttemptsLeft(attempts: number): void {
  if (attempts >= MAX_ATTEMPTS) {
    throw new ApiError('VALIDATION_ERROR', '验证码已失效，请重新获取');
  }
}

/** 过期判断；过期与不匹配对外都返回同一个错误，避免泄露细节。 */
export function isExpired(expiresAt: number, now: number): boolean {
  return expiresAt <= now;
}

export function invalidCodeError(): ApiError {
  return new ApiError('VALIDATION_ERROR', '验证码不正确或已过期');
}

/**
 * 校验输入码：不匹配 → 计入一次失败（由调用方自增），对外统一错误。
 * 这里只做比较，不写库。
 */
export function verifyCode(
  secret: string,
  purpose: EmailPurpose,
  email: string,
  inputCode: string,
  storedDigest: string,
): boolean {
  if (!/^\d{6}$/.test(inputCode)) return false;
  return digestEquals(codeDigest(secret, purpose, email, inputCode), storedDigest);
}
