// 本地启动入口：node src/main.ts（Node 24 直接跑 TypeScript）
// 配置来源：先加载 server/.env（存在则加载），再由环境变量覆盖。
import { loadConfig, loadDotEnv } from './config.ts';
import {
  assertDemoOutboxNodeEnv,
  assertDemoOutboxUsesMemory,
  assertDemoSeedUsesMemory,
  createDemoEmailCodeSink,
  demoSeedFromEnv,
  hasDemoEnv,
  maskEmail,
  resolveDemoOutboxPath,
} from './domain/demo.ts';
import { createSmtpEmailCodeSink, resolveSmtpConfig } from './domain/email-smtp.ts';
import { MemoryRepository } from './repo/memory.ts';
import { fromSync } from './repo/from-sync.ts';
import { PrismaRepository } from './repo/prisma.ts';
import type { AccountRepository } from './repo/types.ts';
import { PrismaClient } from '@prisma/client';
import { buildApp } from './http/app.ts';

// 显式加载 .env：避免"文档让你复制 .env，但程序根本不读"的问题。
// 已存在的环境变量优先（loadEnvFile 不覆盖），所以 `PORT=9000 node src/main.ts` 仍然生效。
const dotenvLoaded = loadDotEnv(new URL('../.env', import.meta.url));

const config = loadConfig();
const smtpConfig = resolveSmtpConfig(process.env);
const demoOutbox = resolveDemoOutboxPath(process.env);
if (smtpConfig !== null && demoOutbox !== null) {
  throw new Error('SMTP_DEMO_OUTBOX_CONFLICT');
}
assertDemoOutboxNodeEnv(demoOutbox !== null, config.nodeEnv);
if (config.nodeEnv === 'production' && hasDemoEnv(process.env)) {
  throw new Error('生产环境禁止设置 DEMO_* 演示变量');
}
const demoSeed = demoSeedFromEnv(process.env);

/**
 * 仓储选择：
 *  - 配置了 DATABASE_URL → Prisma（生产唯一允许的方式）
 *  - 生产环境未配置 → 拒绝启动（内存仓储重启即清空，不能当账户存储）
 *  - 开发/测试 → 内存仓储（包一层异步适配器）
 */
function createRepository(): {
  repo: AccountRepository;
  mode: string;
  memory: MemoryRepository | null;
} {
  const databaseUrl = (process.env.DATABASE_URL ?? '').trim();
  if (databaseUrl) {
    const prisma = new PrismaClient();
    return { repo: new PrismaRepository(prisma), mode: 'postgres(prisma)', memory: null };
  }
  if (config.nodeEnv === 'production') {
    throw new Error('生产环境必须配置 DATABASE_URL：内存仓储只用于本地联调，不能作为账户存储');
  }
  const memory = new MemoryRepository();
  return { repo: fromSync(memory), mode: 'memory(dev only)', memory };
}

const { repo, mode: storageMode, memory } = createRepository();
assertDemoSeedUsesMemory(demoSeed !== null, memory !== null);
assertDemoOutboxUsesMemory(demoOutbox !== null, memory !== null);
let seededDemoEmail: string | null = null;
if (demoSeed && memory) {
  const seeded = await memory.seedEmailAccount(
    demoSeed.email,
    demoSeed.password,
    demoSeed.displayName,
  );
  seededDemoEmail = seeded.email ?? demoSeed.email;
}
let seededDemoProducts = 0;
if (config.nodeEnv === 'demo' && memory) {
  seededDemoProducts = memory.seedDemoProducts();
}
const smtpSink = smtpConfig === null ? null : createSmtpEmailCodeSink(smtpConfig);
const outboxSink = demoOutbox === null ? null : createDemoEmailCodeSink(demoOutbox);
const emailCodeSink = smtpSink ?? outboxSink ?? undefined;
const app = buildApp({ config, repo, logger: true, emailCodeSink });

const shutdown = async (signal: string) => {
  app.log.info({ signal }, 'shutting down');
  await app.close();
  process.exit(0);
};
process.on('SIGINT', () => void shutdown('SIGINT'));
process.on('SIGTERM', () => void shutdown('SIGTERM'));

await app.listen({ host: config.host, port: config.port });
app.log.info({
  env: config.nodeEnv,
  dotenv_loaded: dotenvLoaded,
  api: `http://${config.host}:${config.port}/api/v1`,
  business_timezone: config.businessTimezone,
  payment_provider: config.paymentProvider,
  storage: storageMode,
  demo_seed: seededDemoEmail !== null,
  demo_products: seededDemoProducts,
  email_delivery: smtpSink !== null ? 'smtp' : outboxSink !== null ? 'outbox' : 'none',
  ...(seededDemoEmail === null ? {} : { email: maskEmail(seededDemoEmail) }),
}, 'whaletodo-api ready');
