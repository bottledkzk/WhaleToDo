import { existsSync } from 'node:fs';
import { productionApiEnvironment, productionApiSummary } from './api-config.ts';

try {
  if (process.argv.slice(2).some(arg => arg !== '--check') || process.argv.slice(2).length > 1) throw new Error('API_ARGUMENTS_REFUSED');
  if (existsSync(new URL('../../.env', import.meta.url))) throw new Error('API_DOTENV_REFUSED');
  const env = productionApiEnvironment(process.env);
  if (process.argv.includes('--check')) {
    console.log(JSON.stringify(productionApiSummary(env)));
  } else {
    Object.assign(process.env, env);
    await import('../main.ts');
  }
} catch {
  // Never echo config/parser/driver errors: they may include a credential URL.
  console.error('API_STARTUP_REFUSED_CHECK_PRIVATE_CONFIGURATION');
  process.exitCode = 1;
}
