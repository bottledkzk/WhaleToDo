import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
  assertEmail, assertPurpose, normalizeEmail, generateCode, codeDigest, digestEquals,
  createTicketToken, ticketDigest, resendWaitMs, assertHourlyQuota, assertAttemptsLeft,
  isExpired, verifyCode, CODE_TTL_MS, RESEND_INTERVAL_MS, MAX_SENDS_PER_HOUR, MAX_ATTEMPTS,
} from '../src/domain/email.ts';
import { ApiError } from '../src/lib/security.ts';

const isApiError = (code: string) => (e: unknown) => e instanceof ApiError && e.code === code;

describe('邮箱归一化与校验', () => {
  test('去空格并小写', () => {
    assert.equal(normalizeEmail('  Alice@Example.COM '), 'alice@example.com');
  });

  test('非法邮箱 → VALIDATION_ERROR', () => {
    for (const bad of ['', 'no-at', 'a@b', 'a@b.c', 'sp ace@x.com', 'x'.repeat(250) + '@a.com']) {
      assert.throws(() => assertEmail(bad), isApiError('VALIDATION_ERROR'), `应拒绝 ${bad}`);
    }
  });

  test('合法邮箱通过', () => {
    assert.equal(assertEmail(' User@Whale.ToDo '), 'user@whale.todo');
  });

  test('purpose 白名单', () => {
    for (const p of ['register', 'reset', 'bind']) assert.equal(assertPurpose(p), p);
    assert.throws(() => assertPurpose('unbind'), isApiError('VALIDATION_ERROR'));
  });
});

describe('验证码生成与绑定', () => {
  test('6 位数字且不重复（连续 200 次）', () => {
    const seen = new Set<string>();
    for (let i = 0; i < 200; i += 1) {
      const code = generateCode();
      assert.match(code, /^\d{6}$/);
      seen.add(code);
    }
    assert.ok(seen.size > 150, '不应大量重复');
  });

  test('purpose 或邮箱变化后摘要不同（绑定生效）', () => {
    const secret = 'secret-key-for-test-only-32chars!!';
    const a = codeDigest(secret, 'register', 'a@x.com', '123456');
    const b = codeDigest(secret, 'reset', 'a@x.com', '123456');
    const c = codeDigest(secret, 'register', 'b@x.com', '123456');
    const d = codeDigest(secret, 'register', 'a@x.com', '123457');
    assert.notEqual(a, b);
    assert.notEqual(a, c);
    assert.notEqual(a, d);
    assert.equal(a, codeDigest(secret, 'register', 'a@x.com', '123456'), '同输入必须稳定');
  });

  test('摘要为 64 位十六进制；digestEquals 恒定时间比较', () => {
    const secret = 'secret-key-for-test-only-32chars!!';
    const d = codeDigest(secret, 'register', 'a@x.com', '123456');
    assert.match(d, /^[0-9a-f]{64}$/);
    assert.equal(digestEquals(d, d), true);
    const other = d.slice(0, -1) + (d.endsWith('0') ? '1' : '0');
    assert.equal(digestEquals(d, other), false);
    assert.equal(digestEquals(d, ''), false);
    assert.equal(digestEquals('', ''), false);
  });

  test('verifyCode：仅接受 6 位数字且摘要匹配', () => {
    const secret = 'secret-key-for-test-only-32chars!!';
    const digest = codeDigest(secret, 'register', 'a@x.com', '012345');
    assert.equal(verifyCode(secret, 'register', 'a@x.com', '012345', digest), true);
    assert.equal(verifyCode(secret, 'register', 'a@x.com', '012346', digest), false);
    assert.equal(verifyCode(secret, 'reset', 'a@x.com', '012345', digest), false, 'purpose 不同必须失败');
    assert.equal(verifyCode(secret, 'register', 'a@x.com', '12345', digest), false, '位数不足');
    assert.equal(verifyCode(secret, 'register', 'a@x.com', 'abcdef', digest), false);
  });
});

describe('ticket', () => {
  test('token 为随机值，摘要稳定且与原文不同', () => {
    const t1 = createTicketToken();
    const t2 = createTicketToken();
    assert.notEqual(t1.token, t2.token);
    assert.match(t1.digest, /^[0-9a-f]{64}$/);
    assert.equal(ticketDigest(t1.token), t1.digest);
    assert.notEqual(t1.digest, t1.token);
  });
});

describe('限流与有效期', () => {
  test('60 秒重发间隔', () => {
    const now = 1_000_000;
    assert.equal(resendWaitMs(null, now), 0);
    assert.equal(resendWaitMs(now - RESEND_INTERVAL_MS, now), 0);
    assert.equal(resendWaitMs(now - 10_000, now), RESEND_INTERVAL_MS - 10_000);
  });

  test('每小时 5 次上限 → RATE_LIMITED', () => {
    for (let i = 0; i < MAX_SENDS_PER_HOUR; i += 1) assert.doesNotThrow(() => assertHourlyQuota(i));
    assert.throws(() => assertHourlyQuota(MAX_SENDS_PER_HOUR), isApiError('RATE_LIMITED'));
  });

  test('失败 5 次作废', () => {
    for (let i = 0; i < MAX_ATTEMPTS; i += 1) assert.doesNotThrow(() => assertAttemptsLeft(i));
    assert.throws(() => assertAttemptsLeft(MAX_ATTEMPTS), isApiError('VALIDATION_ERROR'));
  });

  test('5 分钟过期', () => {
    const created = 1_000_000;
    assert.equal(isExpired(created + CODE_TTL_MS - 1, created + 1000), false);
    assert.equal(isExpired(created + 1000, created + CODE_TTL_MS), true);
  });
});
