import { randomUUID } from 'node:crypto';

// 配置：全部来自环境变量，默认值可直接本地起服务；不含任何真实秘密。
export interface ServerConfig {
  nodeEnv: 'development' | 'test' | 'demo' | 'production';
  host: string;
  port: number;
  /** 业务日时区（签到等），必须可配置；默认 Asia/Shanghai */
  businessTimezone: string;
  /** mock 为全账号模拟充值；production 保留真实账户与持久账本。 */
  paymentProvider: 'disabled' | 'mock' | 'real';
  /** mock webhook 所需的内部令牌（仅测试环境使用，无默认值） */
  mockWebhookToken: string | null;
  /** 客户端 API 基址（仅用于返回给客户端的自描述信息，不参与鉴权） */
  publicBaseUrl: string;
  /**
   * 信任的反向代理：false 表示不信任；字符串为 IP/网段列表（如 127.0.0.1）。
   * 部署时**只应信任回环或实际代理地址**，不能对任意来源盲信 X-Forwarded-*。
   */
  trustProxy: boolean | string;
  /** 日志级别：debug | info | warn | error */
  logLevel: string;
  /** 日志文件路径（可选；不设则只输出 stdout） */
  logFile: string | null;
  /** 注册/登录限流：窗口内最大尝试次数 */
  rateLimitMaxAttempts: number;
  /** 注册/登录限流：窗口长度（毫秒） */
  rateLimitWindowMs: number;
  /**
   * 幂等 scope/fingerprint 的 HMAC 密钥。必须服务端持久配置：
   * 用仅进程内随机密钥时，重启后同一请求会被判成"异体"而返回 409。
   * 生产环境必须显式提供；test/development 缺省时用一次性随机值。
   */
  idempotencySecret: string;
}

const ALLOWED_ENVS = ['development', 'test', 'demo', 'production'] as const;

function requireTimezone(tz: string): string {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: tz });
    return tz;
  } catch {
    throw new Error(`BUSINESS_TIMEZONE 不是合法的 IANA 时区：${tz}`);
  }
}

/**
 * 解析 TRUST_PROXY：
 *   false / 未设置 → 不信任任何转发头
 *   true          → 信任（仅限本机全部代理，谨慎使用）
 *   其他字符串    → 作为 IP/网段列表传给 Fastify（例如 127.0.0.1 或 127.0.0.1,10.0.0.0/8）
 */
export function parseTrustProxy(raw: string | undefined): boolean | string {
  const value = (raw ?? '').trim();
  if (!value || value === 'false') return false;
  if (value === 'true') return true;
  return value;   // Fastify 接受逗号分隔的 IP/CIDR 列表
}

function parseIntEnv(raw: string | undefined, fallback: number, name: string): number {
  if (raw === undefined || raw === '') return fallback;
  const n = Number.parseInt(raw, 10);
  if (!Number.isInteger(n) || n < 0 || n > 65535) throw new Error(`${name} 必须是 0~65535 的整数`);
  return n;
}

/**
 * 读取并校验配置。
 * 2026-10-30 产品决定：全账号使用 mock，须显式启用；production 不开放测试 webhook。
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  const rawEnv = (env.NODE_ENV ?? 'development').trim();
  if (!ALLOWED_ENVS.includes(rawEnv as (typeof ALLOWED_ENVS)[number])) {
    throw new Error(`NODE_ENV 只能是 ${ALLOWED_ENVS.join(' / ')}，当前：${rawEnv}`);
  }
  const nodeEnv = rawEnv as ServerConfig['nodeEnv'];

  const paymentProvider = (env.PAYMENT_PROVIDER ?? 'disabled').trim() as ServerConfig['paymentProvider'];
  if (!['disabled', 'mock', 'real'].includes(paymentProvider)) {
    throw new Error(`PAYMENT_PROVIDER 只能是 disabled / mock / real，当前：${paymentProvider}`);
  }
  if (paymentProvider === 'mock' && !['test', 'demo', 'production'].includes(nodeEnv)) {
    throw new Error('PAYMENT_PROVIDER=mock 只允许在 NODE_ENV=test / demo / production 时显式启用');
  }

  const mockWebhookToken = (env.MOCK_WEBHOOK_TOKEN ?? '').trim() || null;
  if (paymentProvider === 'mock' && ['test', 'demo'].includes(nodeEnv) && !mockWebhookToken) {
    throw new Error('启用 mock 支付时必须提供 MOCK_WEBHOOK_TOKEN（仅测试环境，不入库不进仓库）');
  }

  return {
    nodeEnv,
    host: (env.HOST ?? '127.0.0.1').trim(),
    port: parseIntEnv(env.PORT, 8080, 'PORT'),
    businessTimezone: requireTimezone((env.BUSINESS_TIMEZONE ?? 'Asia/Shanghai').trim()),
    paymentProvider,
    mockWebhookToken,
    publicBaseUrl: (env.PUBLIC_BASE_URL ?? 'http://127.0.0.1:8080/api/v1').replace(/\/$/, ''),
    trustProxy: parseTrustProxy(env.TRUST_PROXY),
    logLevel: (env.LOG_LEVEL ?? 'info').trim(),
    logFile: (env.LOG_FILE ?? '').trim() || null,
    rateLimitMaxAttempts: parseIntEnv(env.RATE_LIMIT_MAX_ATTEMPTS, 10, 'RATE_LIMIT_MAX_ATTEMPTS'),
    rateLimitWindowMs: parseIntEnv(env.RATE_LIMIT_WINDOW_MS, 5 * 60 * 1000, 'RATE_LIMIT_WINDOW_MS'),
    idempotencySecret: resolveIdempotencySecret(env, nodeEnv),
  };
}

/** 业务日字符串（YYYY-MM-DD），按配置时区计算，不依赖手机时间 */
export function businessDate(now: Date, timeZone: string): string {
  const fmt = new Intl.DateTimeFormat('en-CA', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
  });
  return fmt.format(now);   // en-CA 输出即为 YYYY-MM-DD
}

/**
 * 加载 .env 文件（Node 20.12+ 的 process.loadEnvFile）。
 * 找不到文件返回 false，不抛错；已存在的环境变量优先（loadEnvFile 不覆盖）。
 */
export function loadDotEnv(fileUrl: URL | string): boolean {
  try {
    process.loadEnvFile(fileUrl as string);
    return true;
  } catch {
    return false;
  }
}
/**
 * 幂等密钥：生产必须显式提供；开发/测试允许缺失（用一次性随机值，重启即失效）。
 * 长度下限 32 字符，避免弱密钥。
 */
function resolveIdempotencySecret(env: NodeJS.ProcessEnv, nodeEnv: ServerConfig['nodeEnv']): string {
  const raw = (env.IDEMPOTENCY_SECRET ?? '').trim();
  if (raw) {
    if (raw.length < 32) throw new Error('IDEMPOTENCY_SECRET 至少 32 字符');
    return raw;
  }
  if (nodeEnv === 'production') {
    throw new Error('生产环境必须配置 IDEMPOTENCY_SECRET（幂等指纹密钥不可用进程内随机值）');
  }
  return randomUUID() + randomUUID();
}
