// 本地启动入口：node src/main.ts（Node 24 直接跑 TypeScript）
import { loadConfig } from './config.ts';
import { MemoryRepository } from './repo/memory.ts';
import { buildApp } from './http/app.ts';

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
  api: `http://${config.host}:${config.port}/api/v1`,
  business_timezone: config.businessTimezone,
  payment_provider: config.paymentProvider,
  storage: 'memory（本地开发用，重启即清空）',
}, 'whaletodo-api ready');
