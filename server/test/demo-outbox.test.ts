// DEMO_EMAIL_OUTBOX：仅 demo 内存模式的本地开发收码。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/http/app.ts';
import { MemoryRepository, createMemoryStore } from '../src/repo/memory.ts';
import { fromSync } from '../src/repo/from-sync.ts';
import { loadConfig } from '../src/config.ts';
import {
  assertDemoOutboxNodeEnv,
  assertDemoOutboxUsesMemory,
  createDemoEmailCodeSink,
  hasDemoEnv,
  resolveDemoOutboxPath,
} from '../src/domain/demo.ts';

type Sink = (input: { purpose: string; email: string; code: string }) => Promise<void>;

const demoEnv = {
  NODE_ENV: 'demo',
  BUSINESS_TIMEZONE: 'Asia/Shanghai',
  PAYMENT_PROVIDER: 'disabled',
  PUBLIC_BASE_URL: 'http://127.0.0.1:8080/api/v1',
} as NodeJS.ProcessEnv;

function appFor(repo: MemoryRepository, sink?: Sink): FastifyInstance {
  return buildApp({
    config: loadConfig({ ...demoEnv }),
    repo: fromSync(repo),
    ...(sink ? { emailCodeSink: sink } : {}),
  });
}

function tempDir(): string {
  return mkdtempSync(join(tmpdir(), 'whaletodo-outbox-'));
}

test('demo send-code 写入一行 JSON，verify 用同一验证码成功', async () => {
  const dir = tempDir();
  const file = join(dir, 'outbox.jsonl');
  const repo = new MemoryRepository(createMemoryStore());
  const app = appFor(repo, createDemoEmailCodeSink(file));
  try {
    const send = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/email/send-code',
      payload: { email: 'demo@example.invalid', purpose: 'register' },
    });
    assert.equal(send.statusCode, 202, send.body);
    assert.equal(send.json().data.sent, true);

    const lines = readFileSync(file, 'utf8').trim().split('\n');
    assert.equal(lines.length, 1);
    const entry = JSON.parse(lines[0]!);
    assert.deepEqual(Object.keys(entry).sort(), ['code', 'email', 'purpose', 'time']);
    assert.match(entry.code, /^\d{6}$/);
    assert.equal(entry.purpose, 'register');
    assert.equal(entry.email, 'demo@example.invalid');
    assert.match(entry.time, /^\d{4}-\d{2}-\d{2}T/);
    // 响应只回 sent/retry_after，不包含明文验证码。
    assert.ok(!JSON.stringify(send.json().data).includes(entry.code));

    const verify = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/email/verify',
      payload: { email: 'demo@example.invalid', purpose: 'register', code: entry.code },
    });
    assert.equal(verify.statusCode, 200, verify.body);
    assert.ok(verify.json().data.ticket);

    if (process.platform !== 'win32') {
      assert.equal(statSync(file).mode & 0o777, 0o600);
    }
  } finally {
    await app.close();
    rmSync(dir, { recursive: true, force: true });
  }
});

test('outbox 路径必须是绝对路径且父目录必须存在', () => {
  assert.throws(
    () => resolveDemoOutboxPath({ DEMO_EMAIL_OUTBOX: 'relative/outbox.jsonl' }),
    /DEMO_EMAIL_OUTBOX_PATH_INVALID/,
  );
  const dir = tempDir();
  try {
    assert.throws(
      () => resolveDemoOutboxPath({ DEMO_EMAIL_OUTBOX: join(dir, 'missing', 'outbox.jsonl') }),
      /DEMO_EMAIL_OUTBOX_PARENT_MISSING/,
    );
    const valid = join(dir, 'outbox.jsonl');
    assert.equal(resolveDemoOutboxPath({ DEMO_EMAIL_OUTBOX: valid }), valid);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('已存在文件对组/其他用户开放时拒绝（POSIX）', (t) => {
  if (process.platform === 'win32') {
    t.skip('Windows 的 stat.mode 不能可靠表达 ACL；权限测试仅在 POSIX 执行');
    return;
  }
  const dir = tempDir();
  const file = join(dir, 'outbox.jsonl');
  writeFileSync(file, '', { mode: 0o644 });
  try {
    assert.throws(
      () => resolveDemoOutboxPath({ DEMO_EMAIL_OUTBOX: file }),
      /DEMO_EMAIL_OUTBOX_PERMISSIONS/,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('outbox 只允许 demo 且内存仓储', () => {
  assert.equal(hasDemoEnv({ DEMO_EMAIL_OUTBOX: '/tmp/outbox' }), true);
  assert.throws(() => assertDemoOutboxNodeEnv(true, 'production'), /ONLY_DEMO/);
  assert.throws(() => assertDemoOutboxNodeEnv(true, 'test'), /ONLY_DEMO/);
  assert.doesNotThrow(() => assertDemoOutboxNodeEnv(false, 'production'));
  assert.throws(() => assertDemoOutboxUsesMemory(true, false), /ONLY_MEMORY/);
  assert.doesNotThrow(() => assertDemoOutboxUsesMemory(true, true));
  assert.doesNotThrow(() => assertDemoOutboxUsesMemory(false, false));
});

test('sink 写失败返回 500，不谎报 sent:true', async () => {
  const dir = tempDir();
  const repo = new MemoryRepository(createMemoryStore());
  const app = appFor(repo, async () => {
    throw new Error('synthetic outbox write failure');
  });
  try {
    const send = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/email/send-code',
      payload: { email: 'demo@example.invalid', purpose: 'register' },
    });
    assert.equal(send.statusCode, 500, send.body);
    assert.ok(!send.body.includes('"sent":true'));
    assert.equal(send.json().error.code, 'INTERNAL');
  } finally {
    await app.close();
    rmSync(dir, { recursive: true, force: true });
  }
});
