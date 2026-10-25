// 本地启动入口：node src/main.ts（Node 24 直接跑 TypeScript）
// 配置来源：先加载 server/.env（存在则加载），再由环境变量覆盖。
import { loadConfig, loadDotEnv } from './config.ts';
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
/**
 * 仓储选择：
 *  - 配置了 DATABASE_URL → Prisma（生产唯一允许的方式）
 *  - 生产环境未配置 → 拒绝启动（内存仓储重启即清空，不能当账户存储）
 *  - 开发/测试 → 内存仓储（包一层异步适配器）
 */
function createRepository(): { repo: AccountRepository; mode: string } {
  const databaseUrl = (process.env.DATABASE_URL ?? '').trim();
  if (databaseUrl) {
    const prisma = new PrismaClient();
    return { repo: new PrismaRepository(prisma), mode: 'postgres(prisma)' };
  }
  if (config.nodeEnv === 'production') {
    throw new Error('生产环境必须配置 DATABASE_URL：内存仓储只用于本地联调，不能作为账户存储');
  }
  return { repo: fromSync(new MemoryRepository()), mode: 'memory(dev only)' };
}

const { repo, mode: storageMode } = createRepository();
const app = buildApp({ config, repo, logger: true });

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
}, 'whaletodo-api ready');
