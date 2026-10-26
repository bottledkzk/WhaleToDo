import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildApp } from '../src/http/app.ts';
import { loadConfig } from '../src/config.ts';
import { createMemoryStore, MemoryRepository } from '../src/repo/memory.ts';
import { fromSync } from '../src/repo/from-sync.ts';

const PASSWORD = 'S3cret-passw0rd';

test('畸形 JSON → 400 且访问日志 error_code=VALIDATION_ERROR', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'whaletodo-log-'));
  const logFile = join(dir, 'access.log');
  try {
    const config = loadConfig({
      NODE_ENV: 'demo',
      BUSINESS_TIMEZONE: 'Asia/Shanghai',
      IDEMPOTENCY_SECRET: 'observability-test-secret-'.padEnd(40, 'x'),
      RATE_LIMIT_MAX_ATTEMPTS: '100',
      PAYMENT_PROVIDER: 'disabled',
      LOG_LEVEL: 'info',
      LOG_FILE: logFile,
    } as NodeJS.ProcessEnv);

    const repo = fromSync(new MemoryRepository(createMemoryStore()));
    const app = buildApp({ config, repo });
    await app.ready();

    // 先发一条合法请求，确认日志落盘正常
    await app.inject({
      method: 'POST',
      url: '/api/v1/auth/register',
      headers: { 'content-type': 'application/json' },
      payload: JSON.stringify({ username: 'log_user', password: PASSWORD }),
    });

    // 畸形 JSON：框架层 400
    const bad = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/register',
      headers: { 'content-type': 'application/json' },
      payload: '{"username": ',
    });
    assert.equal(bad.statusCode, 400, `畸形 JSON 应为 400，实际 ${bad.statusCode}: ${bad.body}`);

    // 等异步日志 flush
    await new Promise((r) => setTimeout(r, 200));
    assert.ok(existsSync(logFile), '日志文件应已生成');
    const raw = readFileSync(logFile, 'utf8');
    assert.match(raw, /"event":"access"/);
    assert.match(raw, /"status":400/, '应记录 400 访问日志');
    assert.match(raw, /"error_code":"VALIDATION_ERROR"/, '400 也必须有 error_code');
    assert.doesNotMatch(raw, /access_token/, '日志不得包含令牌字段');

    await app.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
