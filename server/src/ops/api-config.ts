import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadConfig, type ServerConfig } from '../config.ts';

/** LoadCredential is the only secret source for the production account process. */
export function productionApiEnvironment(env: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  if (!env.CREDENTIALS_DIRECTORY || env.NODE_ENV !== 'production') throw new Error('API_PRODUCTION_CREDENTIALS_REQUIRED');
  if (Object.keys(env).some(key => key.startsWith('DEMO_'))) throw new Error('API_DEMO_ENV_REFUSED');
  if (env.DATABASE_URL || env.IDEMPOTENCY_SECRET) {
    throw new Error('API_SECRET_OR_PAYMENT_OVERRIDE_REFUSED');
  }
  const paymentProvider = env.PAYMENT_PROVIDER ?? 'disabled';
  if (!['disabled', 'mock'].includes(paymentProvider)) throw new Error('API_PAYMENT_MODE_REFUSED');
  let database: string, secret: string;
  try {
    database = readFileSync(join(env.CREDENTIALS_DIRECTORY, 'database.url'), 'utf8').trim();
    secret = readFileSync(join(env.CREDENTIALS_DIRECTORY, 'idempotency.secret'), 'utf8').trim();
  } catch { throw new Error('API_CREDENTIAL_FILES_UNAVAILABLE'); }
  let target: URL;
  try { target = new URL(database); } catch { throw new Error('API_DATABASE_TARGET_REFUSED'); }
  if (!['postgres:', 'postgresql:'].includes(target.protocol) || target.hostname !== '127.0.0.1' ||
      target.port !== '5432' || target.pathname !== '/whaletodo' || target.username !== 'whaletodo_app' ||
      !target.password || target.searchParams.get('schema') !== 'whaletodo' ||
      target.searchParams.get('connection_limit') !== '2' || target.hash ||
      [...target.searchParams.keys()].some(key => !['schema', 'connection_limit', 'pool_timeout'].includes(key))) {
    throw new Error('API_DATABASE_TARGET_REFUSED');
  }
  if (secret.length < 32 || /[\r\n]/.test(secret)) throw new Error('API_IDEMPOTENCY_SECRET_INVALID');
  const result: NodeJS.ProcessEnv = {
    ...env, NODE_ENV: 'production', HOST: '127.0.0.1', PORT: '8080', TRUST_PROXY: '127.0.0.1',
    PAYMENT_PROVIDER: paymentProvider, DATABASE_URL: database, IDEMPOTENCY_SECRET: secret,
    PUBLIC_BASE_URL: 'https://api.whaletodo.top:8443/api/v1',
  };
  // Detect .env drift rather than letting main.ts load additional production settings.
  loadConfig(result);
  return result;
}

export function productionApiSummary(env: NodeJS.ProcessEnv): Pick<ServerConfig, 'nodeEnv' | 'host' | 'port' | 'paymentProvider'> & { credentials: string; database_pool_max: number; email_delivery: string } {
  const config = loadConfig(env);
  return { nodeEnv: config.nodeEnv, host: config.host, port: config.port, paymentProvider: config.paymentProvider,
    credentials: 'systemd', database_pool_max: 2, email_delivery: 'not_integrated' };
}
