import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildApp } from '../src/http/app.ts';
import { MemoryRepository, createMemoryStore } from '../src/repo/memory.ts';
import { fromSync } from '../src/repo/from-sync.ts';
import { loadConfig } from '../src/config.ts';
import {
  createSmtpEmailCodeSink,
  resolveSmtpConfig,
  type SmtpConfig,
  type SmtpMail,
  type SmtpTransport,
} from '../src/domain/email-smtp.ts';
import { CODE_TTL_MS } from '../src/domain/email.ts';

const baseEnv = {
  SMTP_HOST: 'smtp.example.invalid',
  SMTP_PORT: '465',
  SMTP_SECURE: 'true',
  SMTP_USER: 'Sender@Example.invalid',
  SMTP_PASS: 'synthetic-auth-code-never-real',
  SMTP_FROM: 'Sender@Example.invalid',
} as NodeJS.ProcessEnv;

function config(env: NodeJS.ProcessEnv = baseEnv): SmtpConfig {
  const resolved = resolveSmtpConfig(env);
  assert.ok(resolved);
  return resolved;
}

function fakeTransport(handler: (mail: SmtpMail) => Promise<unknown>): SmtpTransport & { mails: SmtpMail[] } {
  const mails: SmtpMail[] = [];
  return {
    mails,
    sendMail: (mail: SmtpMail) => {
      mails.push(mail);
      return handler(mail);
    },
  };
}

test('SMTP config is null when absent, complete when present, FROM defaults to USER', () => {
  assert.equal(resolveSmtpConfig({}), null);

  const resolved = config({
    SMTP_HOST: 'smtp.example.invalid',
    SMTP_PORT: '465',
    SMTP_SECURE: 'true',
    SMTP_USER: 'sender@example.invalid',
    SMTP_PASS: 'synthetic-pass',
  });
  assert.equal(resolved.from, 'sender@example.invalid');
  assert.equal(resolved.host, 'smtp.example.invalid');
  assert.equal(resolved.port, 465);
  assert.equal(resolved.secure, true);
  assert.equal(resolved.user, 'sender@example.invalid');
});

test('SMTP partial config fails with a fixed code and no secret values', () => {
  for (const env of [
    { SMTP_HOST: 'smtp.example.invalid' },
    { ...baseEnv, SMTP_PASS: '' },
    { SMTP_FROM: 'sender@example.invalid' },
  ]) {
    assert.throws(
      () => resolveSmtpConfig(env as NodeJS.ProcessEnv),
      /SMTP_CONFIG_INCOMPLETE/,
    );
  }

  const secret = 'never-log-this-synthetic-secret';
  let message = '';
  try {
    resolveSmtpConfig({ SMTP_HOST: 'smtp.example.invalid', SMTP_PASS: secret });
  } catch (error) {
    message = String((error as Error).message);
  }
  assert.match(message, /SMTP_CONFIG_INCOMPLETE/);
  assert.ok(!message.includes(secret));
});

test('SMTP invalid values and outbox conflict fail closed', () => {
  const badEnvs: NodeJS.ProcessEnv[] = [
    { ...baseEnv, SMTP_PORT: 'abc' },
    { ...baseEnv, SMTP_PORT: '465', SMTP_SECURE: 'false' },
    { ...baseEnv, SMTP_SECURE: 'yes' },
    { ...baseEnv, SMTP_USER: 'not-an-email' },
    { ...baseEnv, SMTP_FROM: 'not-an-email' },
    { ...baseEnv, SMTP_PASS: 'line1\nline2' },
  ];
  for (const env of badEnvs) {
    assert.throws(() => resolveSmtpConfig(env), /SMTP_CONFIG_INVALID/);
  }
  assert.throws(
    () => resolveSmtpConfig({ ...baseEnv, DEMO_EMAIL_OUTBOX: '/tmp/demo-outbox.jsonl' }),
    /SMTP_DEMO_OUTBOX_CONFLICT/,
  );
});

test('SMTP sink sends exactly one fixed-template mail to the validated recipient', async () => {
  const transport = fakeTransport(async () => ({ messageId: 'synthetic' }));
  const sink = createSmtpEmailCodeSink(config(), { transport });

  await sink({ purpose: 'reset', email: ' User@Example.invalid ', code: '123456' });

  assert.equal(transport.mails.length, 1);
  const mail = transport.mails[0]!;
  assert.deepEqual(Object.keys(mail).sort(), ['from', 'subject', 'text', 'to']);
  assert.equal(mail.from, 'sender@example.invalid');
  assert.equal(mail.to, 'user@example.invalid');
  assert.match(mail.subject, /^WhaleToDo 验证码（重置）$/);
  assert.match(mail.text, /你的 WhaleToDo 验证码是：123456/);
  assert.match(mail.text, new RegExp(`${Math.ceil(CODE_TTL_MS / 60_000)} 分钟内有效`));
  assert.ok(!('html' in mail));
  assert.ok(!('cc' in mail));
  assert.ok(!('bcc' in mail));
  assert.ok(!('attachments' in mail));
});

test('SMTP transport envelope uses the same canonical mailbox as storage and cooldown', async () => {
  const transport = fakeTransport(async () => ({ messageId: 'synthetic' }));
  const sink = createSmtpEmailCodeSink(config(), { transport });
  const repo = new MemoryRepository(createMemoryStore());
  const app = buildApp({
    config: loadConfig({
      NODE_ENV: 'test',
      BUSINESS_TIMEZONE: 'Asia/Shanghai',
      PAYMENT_PROVIDER: 'disabled',
      PUBLIC_BASE_URL: 'http://127.0.0.1:8080/api/v1',
    }),
    repo: fromSync(repo),
    emailCodeSink: sink,
  });
  const canonical = 'alice@example.test';
  try {
    const first = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/email/send-code',
      payload: { purpose: 'register', email: ' Alice@Example.Test ' },
    });
    assert.equal(first.statusCode, 202, first.body);
    assert.equal(first.json().data.sent, true);
    assert.equal(transport.mails.length, 1);
    assert.equal(transport.mails[0]!.to, canonical, 'SMTP envelope 必须使用 canonical');
    assert.equal(repo.store.emailCodes.get(`register:${canonical}`)?.email, canonical);

    // 同 canonical 在 60 秒内再次请求：不得再投递，SMTP 不能看到第二个 envelope。
    const second = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/email/send-code',
      payload: { purpose: 'register', email: 'ALICE@EXAMPLE.TEST' },
    });
    assert.equal(second.statusCode, 202, second.body);
    assert.equal(second.json().data.sent, false);
    assert.ok(second.json().data.retry_after_ms > 0);
    assert.equal(transport.mails.length, 1);

    const variant = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/email/send-code',
      payload: { purpose: 'register', email: 'alice@example.test(a)' },
    });
    assert.equal(variant.statusCode, 400, variant.body);
    assert.equal(variant.json().error.code, 'VALIDATION_ERROR');
    assert.equal(transport.mails.length, 1);
  } finally {
    await app.close();
  }
});

test('SMTP sink rejects display-name/comment/list variants before transport', async () => {
  const transport = fakeTransport(async () => ({ messageId: 'synthetic' }));
  const sink = createSmtpEmailCodeSink(config(), { transport });
  for (const email of [
    'not-an-email',
    'alice@example.test(a)',
    'alice@example.test(b)',
    'x<alice@example.test>',
    '"a b"@example.test',
    'a@@b.test',
    'a@b',
    'a@-b.test',
    'a..b@test',
  ]) {
    await assert.rejects(
      () => sink({ purpose: 'register', email, code: '123456' }),
      /邮箱格式不正确/,
      `应拒绝 ${email}`,
    );
  }
  await assert.rejects(() => sink({ purpose: 'register', email: 'user@example.invalid', code: '12' }));
  await assert.rejects(() => sink({ purpose: 'unbind', email: 'user@example.invalid', code: '123456' }));
  assert.equal(transport.mails.length, 0);
});

test('SMTP sink times out and propagates transport failure', async () => {
  const hanging = createSmtpEmailCodeSink(config(), {
    transport: { sendMail: () => new Promise(() => {}) },
    timeoutMs: 20,
  });
  await assert.rejects(
    () => hanging({ purpose: 'register', email: 'user@example.invalid', code: '123456' }),
    /SMTP_TIMEOUT/,
  );

  const failed = createSmtpEmailCodeSink(config(), {
    transport: { sendMail: async () => { throw new Error('synthetic smtp failure'); } },
  });
  await assert.rejects(
    () => failed({ purpose: 'register', email: 'user@example.invalid', code: '123456' }),
    /synthetic smtp failure/,
  );
});

test('SMTP sink failure makes send-code return 500 without sent:true or code', async () => {
  const mails: SmtpMail[] = [];
  const transport: SmtpTransport = {
    sendMail: async (mail) => {
      mails.push(mail);
      throw new Error('synthetic smtp failure');
    },
  };
  const sink = createSmtpEmailCodeSink(config(), { transport });
  const repo = new MemoryRepository(createMemoryStore());
  const app = buildApp({
    config: loadConfig({
      NODE_ENV: 'test',
      BUSINESS_TIMEZONE: 'Asia/Shanghai',
      PAYMENT_PROVIDER: 'disabled',
      PUBLIC_BASE_URL: 'http://127.0.0.1:8080/api/v1',
    }),
    repo: fromSync(repo),
    emailCodeSink: sink,
  });
  try {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/email/send-code',
      payload: { purpose: 'register', email: 'user@example.invalid' },
    });
    assert.equal(res.statusCode, 500, res.body);
    assert.equal(res.json().error.code, 'INTERNAL');
    assert.ok(!res.body.includes('"sent":true'));
    assert.ok(!res.body.includes('synthetic-auth-code-never-real'));
    assert.equal(mails.length, 1);
    const code = /：(\d{6})/.exec(mails[0]!.text)?.[1];
    assert.ok(code);
    assert.ok(!res.body.includes(code!));
  } finally {
    await app.close();
  }
});
