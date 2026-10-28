// 演示环境启动参数：仅用于内存模式，不进入 config.ts 的正式配置面。
// 生产环境出现任何 DEMO_* 都由 main.ts 拒绝启动。
import { existsSync, statSync } from 'node:fs';
import { appendFile } from 'node:fs/promises';
import { dirname, isAbsolute } from 'node:path';
import { assertEmail } from './email.ts';
import { DISPLAY_NAME_MAX } from './profile.ts';

export interface DemoSeed {
  email: string;
  password: string;
  displayName?: string;
}

const DEMO_ENV_KEYS = ['DEMO_EMAIL', 'DEMO_PASSWORD', 'DEMO_DISPLAY_NAME', 'DEMO_EMAIL_OUTBOX'] as const;

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

/** DEMO_* 只能配合内存仓储；与 DATABASE_URL 同时出现时拒绝启动。 */
export function assertDemoSeedUsesMemory(demoSeedConfigured: boolean, memoryMode: boolean): void {
  if (demoSeedConfigured && !memoryMode) {
    throw new Error('DEMO_* 仅用于内存模式：请移除 DATABASE_URL，或不要设置 DEMO_*');
  }
}

/**
 * demo 收码文件路径：显式、绝对、父目录已存在；不自动创建目录。
 * 已存在的文件必须是普通文件，POSIX 下不得对组/其他用户开放（group/other 位为 0）。
 * 错误只返回固定标识，不包含路径。
 */
export function resolveDemoOutboxPath(env: NodeJS.ProcessEnv = process.env): string | null {
  const raw = String(env.DEMO_EMAIL_OUTBOX ?? '').trim();
  if (!raw) return null;
  if (!isAbsolute(raw)) throw new Error('DEMO_EMAIL_OUTBOX_PATH_INVALID');
  let parentStat;
  try {
    parentStat = statSync(dirname(raw));
  } catch {
    throw new Error('DEMO_EMAIL_OUTBOX_PARENT_MISSING');
  }
  if (!parentStat.isDirectory()) throw new Error('DEMO_EMAIL_OUTBOX_PARENT_MISSING');
  if (existsSync(raw)) {
    const target = statSync(raw);
    if (!target.isFile()) throw new Error('DEMO_EMAIL_OUTBOX_PATH_INVALID');
    // Windows 的 stat.mode 不能可靠表达 ACL；权限回归测试在 POSIX 上执行。
    if (process.platform !== 'win32' && (target.mode & 0o077) !== 0) {
      throw new Error('DEMO_EMAIL_OUTBOX_PERMISSIONS');
    }
  }
  return raw;
}

/** 只有 NODE_ENV=demo 才允许使用开发收码文件。 */
export function assertDemoOutboxNodeEnv(outboxConfigured: boolean, nodeEnv: string): void {
  if (outboxConfigured && nodeEnv !== 'demo') {
    throw new Error('DEMO_EMAIL_OUTBOX_ONLY_DEMO');
  }
}

/** 只有内存仓储才允许使用开发收码文件（不能在生产 DB 模式落明文验证码）。 */
export function assertDemoOutboxUsesMemory(outboxConfigured: boolean, memoryMode: boolean): void {
  if (outboxConfigured && !memoryMode) {
    throw new Error('DEMO_EMAIL_OUTBOX_ONLY_MEMORY');
  }
}

/** 每条验证码一行 JSON；appendFile 的 mode 只保证新建文件为 0600。 */
export function createDemoEmailCodeSink(path: string) {
  return async (input: { purpose: string; email: string; code: string }): Promise<void> => {
    await appendFile(
      path,
      JSON.stringify({
        time: new Date().toISOString(),
        purpose: input.purpose,
        email: input.email,
        code: input.code,
      }) + '\n',
      { mode: 0o600 },
    );
  };
}

/** 日志只允许出现掩码邮箱，例如 d***@example.com。 */
export function maskEmail(email: string): string {
  const clean = String(email ?? '').trim().toLowerCase();
  const at = clean.indexOf('@');
  if (at <= 0) return '***';
  return `${Array.from(clean.slice(0, at))[0] ?? '*'}***${clean.slice(at)}`;
}
