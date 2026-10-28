import nodemailer from 'nodemailer';
import {
  assertEmail,
  assertPurpose,
  CODE_TTL_MS,
  type EmailPurpose,
} from './email.ts';

export interface SmtpConfig {
  host: string;
  port: number;
  secure: boolean;
  user: string;
  pass: string;
  from: string;
}

export interface SmtpMail {
  from: string;
  to: string;
  subject: string;
  text: string;
}

/** Small structural boundary so tests can inject a fake transport without network. */
export interface SmtpTransport {
  sendMail(mail: SmtpMail): Promise<unknown>;
}

export interface SmtpSinkDeps {
  transport?: SmtpTransport;
  timeoutMs?: number;
}

const SMTP_KEYS = [
  'SMTP_HOST',
  'SMTP_PORT',
  'SMTP_SECURE',
  'SMTP_USER',
  'SMTP_PASS',
  'SMTP_FROM',
] as const;

const REQUIRED_KEYS = ['SMTP_HOST', 'SMTP_PORT', 'SMTP_SECURE', 'SMTP_USER', 'SMTP_PASS'] as const;
const SMTP_TIMEOUT_MS = 10_000;
const PURPOSE_LABEL: Record<EmailPurpose, string> = {
  register: '注册',
  reset: '重置',
  bind: '绑定',
};

/**
 * Resolve explicit SMTP env configuration.
 * Any SMTP_* key opts in; required keys must then be complete. FROM defaults to USER.
 * Errors are fixed codes and never include host/user/password values.
 */
export function resolveSmtpConfig(env: NodeJS.ProcessEnv = process.env): SmtpConfig | null {
  const optedIn = SMTP_KEYS.some((key) => env[key] !== undefined);
  if (!optedIn) return null;

  if (String(env.DEMO_EMAIL_OUTBOX ?? '').trim()) {
    throw new Error('SMTP_DEMO_OUTBOX_CONFLICT');
  }
  if (REQUIRED_KEYS.some((key) => String(env[key] ?? '').trim() === '')) {
    throw new Error('SMTP_CONFIG_INCOMPLETE');
  }

  const host = String(env.SMTP_HOST ?? '').trim();
  if (!host || /[\s/]/.test(host)) throw new Error('SMTP_CONFIG_INVALID');

  const portRaw = String(env.SMTP_PORT ?? '').trim();
  if (!/^\d{1,5}$/.test(portRaw)) throw new Error('SMTP_CONFIG_INVALID');
  const port = Number(portRaw);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('SMTP_CONFIG_INVALID');

  const secureRaw = String(env.SMTP_SECURE ?? '').trim().toLowerCase();
  if (secureRaw !== 'true' && secureRaw !== 'false') throw new Error('SMTP_CONFIG_INVALID');
  const secure = secureRaw === 'true';
  if (port === 465 && !secure) throw new Error('SMTP_CONFIG_INVALID');

  let user: string;
  try {
    user = assertEmail(env.SMTP_USER);
  } catch {
    throw new Error('SMTP_CONFIG_INVALID');
  }
  let from: string;
  try {
    from = assertEmail(String(env.SMTP_FROM ?? '').trim() || user);
  } catch {
    throw new Error('SMTP_CONFIG_INVALID');
  }

  const pass = String(env.SMTP_PASS ?? '');
  if (!pass.trim() || /[\r\n]/.test(pass)) throw new Error('SMTP_CONFIG_INVALID');

  return { host, port, secure, user, pass, from };
}

export function createSmtpEmailCodeSink(
  config: SmtpConfig,
  deps: SmtpSinkDeps = {},
): (input: { purpose: string; email: string; code: string }) => Promise<void> {
  const transport = deps.transport ?? nodemailer.createTransport({
    host: config.host,
    port: config.port,
    secure: config.secure,
    auth: { user: config.user, pass: config.pass },
    requireTLS: !config.secure,
    connectionTimeout: SMTP_TIMEOUT_MS,
    greetingTimeout: SMTP_TIMEOUT_MS,
    socketTimeout: SMTP_TIMEOUT_MS,
  });
  const timeoutMs = deps.timeoutMs ?? SMTP_TIMEOUT_MS;
  if (!Number.isInteger(timeoutMs) || timeoutMs <= 0) throw new Error('SMTP_TIMEOUT_CONFIG_INVALID');
  const ttlMinutes = Math.max(1, Math.ceil(CODE_TTL_MS / 60_000));

  return async (input: { purpose: string; email: string; code: string }): Promise<void> => {
    const purpose = assertPurpose(input.purpose);
    const to = assertEmail(input.email);
    if (!/^\d{6}$/.test(input.code)) throw new Error('SMTP_CODE_INVALID');

    // Fixed subject/body only: no caller-controlled subject, html, cc, bcc or attachments.
    const subject = `WhaleToDo 验证码（${PURPOSE_LABEL[purpose]}）`;
    const text = [
      `你的 WhaleToDo 验证码是：${input.code}`,
      `${ttlMinutes} 分钟内有效。请勿转发给他人。`,
    ].join('\n');

    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      await Promise.race([
        transport.sendMail({ from: config.from, to, subject, text }),
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new Error('SMTP_TIMEOUT')), timeoutMs);
        }),
      ]);
    } finally {
      if (timer !== undefined) clearTimeout(timer);
    }
  };
}
