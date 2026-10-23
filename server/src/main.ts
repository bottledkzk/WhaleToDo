// 本地启动入口：node src/main.ts（Node 24 直接跑 TypeScript）
// 配置来源：先加载 server/.env（存在则加载），再由环境变量覆盖。
import { loadConfig, loadDotEnv } from './config.ts';
import { MemoryRepository } from './repo/memory.ts';
import { buildApp } from './http/app.ts';

// 显式加载 .env：避免"文档让你复制 .env，但程序根本不读"的问题。
// 已存在的环境变量优先（loadEnvFile 不覆盖），所以 `PORT=9000 node src/main.ts` 仍然生效。
const dotenvLoaded = loadDotEnv(new URL('../.env', import.meta.url));

const config = loadConfig();
const repo = new MemoryRepository();
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
  storage: 'memory（本地开发用，重启即清空）',
}, 'whaletodo-api ready');
