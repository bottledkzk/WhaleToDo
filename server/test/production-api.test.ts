import { test } from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { productionApiEnvironment, productionApiSummary } from '../src/ops/api-config.ts';

function fixture(run: (dir: string) => void) {
  const dir = mkdtempSync(join(tmpdir(), 'whaletodo-api-'));
  writeFileSync(join(dir, 'database.url'), 'postgresql://whaletodo_app:synthetic@127.0.0.1:5432/whaletodo?schema=whaletodo&connection_limit=2');
  writeFileSync(join(dir, 'idempotency.secret'), 'synthetic-persistent-secret-'.repeat(2));
  try { run(dir); } finally { rmSync(dir, { recursive: true, force: true }); }
}
test('production account credentials pin the restricted local pool and safe runtime', () => fixture(dir => {
  const env = productionApiEnvironment({ NODE_ENV: 'production', CREDENTIALS_DIRECTORY: dir, HOST: '0.0.0.0', PORT: '99', TRUST_PROXY: 'true' });
  assert.deepEqual(productionApiSummary(env), { nodeEnv: 'production', host: '127.0.0.1', port: 8080,
    paymentProvider: 'disabled', credentials: 'systemd', database_pool_max: 2, email_delivery: 'not_integrated' });
  assert.ok(!JSON.stringify(productionApiSummary(env)).includes('synthetic'));
}));
test('production account loader rejects demo, inherited secrets and enabled payments', () => fixture(dir => {
  for (const bad of [{ NODE_ENV: 'test' }, { DEMO_EMAIL: 'x' }, { DEMO_PASSWORD: '' }, { DATABASE_URL: 'postgres://x' },
    { IDEMPOTENCY_SECRET: 'x' }, { PAYMENT_PROVIDER: 'real' }]) {
    assert.throws(() => productionApiEnvironment({ NODE_ENV: 'production', CREDENTIALS_DIRECTORY: dir, ...bad }));
  }
}));
test('production account loader rejects remote, privileged, wrong schema and unbounded pools', () => fixture(dir => {
  const good = 'postgresql://whaletodo_app:synthetic@127.0.0.1:5432/whaletodo?schema=whaletodo&connection_limit=2';
  for (const bad of [good.replace('127.0.0.1', 'remote.invalid'), good.replace('whaletodo_app:', 'whaletodo_migrator:'),
    good.replace('schema=whaletodo', 'schema=public'), good.replace('connection_limit=2', 'connection_limit=8'), good + '&options=-csearch_path=public']) {
    writeFileSync(join(dir, 'database.url'), bad);
    assert.throws(() => productionApiEnvironment({ NODE_ENV: 'production', CREDENTIALS_DIRECTORY: dir }), /API_DATABASE_TARGET_REFUSED/);
  }
}));
test('production account loader fails closed for absent and invalid secrets without echoing them', () => fixture(dir => {
  writeFileSync(join(dir, 'idempotency.secret'), 'synthetic-short');
  assert.throws(() => productionApiEnvironment({ NODE_ENV: 'production', CREDENTIALS_DIRECTORY: dir }), /API_IDEMPOTENCY_SECRET_INVALID/);
  rmSync(join(dir, 'database.url'));
  assert.throws(() => productionApiEnvironment({ NODE_ENV: 'production', CREDENTIALS_DIRECTORY: dir }), /API_CREDENTIAL_FILES_UNAVAILABLE/);
}));
test('production entry --check imports without a database connection and redacts startup failures', () => fixture(dir => {
  const entry = fileURLToPath(new URL('../src/ops/api-main.ts', import.meta.url));
  const env = { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, NODE_ENV: 'production', CREDENTIALS_DIRECTORY: dir };
  const good = spawnSync(process.execPath, [entry, '--check'], { env, encoding: 'utf8' });
  assert.equal(good.status, 0, good.stderr);
  assert.equal(JSON.parse(good.stdout).database_pool_max, 2);
  assert.ok(!good.stdout.includes('synthetic'));
  writeFileSync(join(dir, 'database.url'), 'postgresql://secret-user:synthetic-private-password@remote.invalid/other');
  const bad = spawnSync(process.execPath, [entry, '--check'], { env, encoding: 'utf8' });
  assert.equal(bad.status, 1);
  assert.equal(bad.stdout, '');
  assert.equal(bad.stderr.trim(), 'API_STARTUP_REFUSED_CHECK_PRIVATE_CONFIGURATION');
}));

test('production entry refuses a source .env even for --check without logging its contents', () => fixture(dir => {
  const source = join(dir, 'isolated-source');
  mkdirSync(join(source, 'src/ops'), { recursive: true });
  writeFileSync(join(source, 'package.json'), '{"type":"module"}');
  for (const path of ['ops/api-main.ts', 'ops/api-config.ts', 'config.ts']) {
    copyFileSync(new URL('../src/' + path, import.meta.url), join(source, 'src', path));
  }
  writeFileSync(join(source, '.env'), 'PRIVATE_SYNTHETIC_VALUE=must-never-appear');
  const result = spawnSync(process.execPath, [join(source, 'src/ops/api-main.ts'), '--check'], {
    env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, NODE_ENV: 'production', CREDENTIALS_DIRECTORY: dir }, encoding: 'utf8',
  });
  assert.equal(result.status, 1); assert.equal(result.stdout, '');
  assert.equal(result.stderr.trim(), 'API_STARTUP_REFUSED_CHECK_PRIVATE_CONFIGURATION');
}));

test('production enables explicitly selected mock with credential-bound PostgreSQL and no demo identity', () => fixture(dir => {
  const env=productionApiEnvironment({NODE_ENV:'production',CREDENTIALS_DIRECTORY:dir,PAYMENT_PROVIDER:'mock'});
  assert.equal(productionApiSummary(env).paymentProvider,'mock');
  assert.equal(env.NODE_ENV,'production');
  assert.ok(env.DATABASE_URL?.includes('whaletodo_app:'));
  assert.equal(env.DEMO_EMAIL,undefined);
}));
