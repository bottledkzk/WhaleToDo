// 演示环境启动参数：仅用于内存模式，不进入 config.ts 的正式配置面。
// 生产环境出现任何 DEMO_* 都由 main.ts 拒绝启动。
import { assertEmail } from './email.ts';
import { DISPLAY_NAME_MAX } from './profile.ts';

export interface DemoSeed {
  email: string;
  password: string;
  displayName?: string;
}

const DEMO_ENV_KEYS = ['DEMO_EMAIL', 'DEMO_PASSWORD', 'DEMO_DISPLAY_NAME'] as const;

export function hasDemoEnv(env: NodeJS.ProcessEnv = process.env): boolean {
  return DEMO_ENV_KEYS.some((key) => env[key] !== undefined);
}

export function normalizeDemoEmail(raw: unknown): string {
  return assertEmail(raw);
}

export function validateDemoPassword(raw: unknown): string {
  const password = String(raw ?? '');
  if (password.length < 8 || password.length > 200) {
    throw new Error('DEMO_PASSWORD 长度需为 8~200 字符');
  }
  return password;
}

export function normalizeDemoDisplayName(raw: unknown): string | undefined {
  const displayName = String(raw ?? '').trim();
  if (Array.from(displayName).length > DISPLAY_NAME_MAX) {
    throw new Error(`DEMO_DISPLAY_NAME 不能超过 ${DISPLAY_NAME_MAX} 字符`);
  }
  return displayName || undefined;
}

/** 三个变量都没有时返回 null；只给部分字段则报错。 */
export function demoSeedFromEnv(env: NodeJS.ProcessEnv = process.env): DemoSeed | null {
  const emailRaw = env.DEMO_EMAIL;
  const passwordRaw = env.DEMO_PASSWORD;
  const displayRaw = env.DEMO_DISPLAY_NAME;
  if (emailRaw === undefined && passwordRaw === undefined && displayRaw === undefined) return null;

  const email = String(emailRaw ?? '').trim();
  const password = String(passwordRaw ?? '');
  if (!email || !password) {
    throw new Error('DEMO_EMAIL 与 DEMO_PASSWORD 必须同时设置');
  }
  return {
    email: normalizeDemoEmail(email),
    password: validateDemoPassword(password),
    displayName: normalizeDemoDisplayName(displayRaw),
  };
}

/** 日志只允许出现掩码邮箱，例如 d***@example.com。 */
export function maskEmail(email: string): string {
  const clean = String(email ?? '').trim().toLowerCase();
  const at = clean.indexOf('@');
  if (at <= 0) return '***';
  return `${Array.from(clean.slice(0, at))[0] ?? '*'}***${clean.slice(at)}`;
}
