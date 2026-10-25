// 幂等 scope 规范（按 Bottledkzk 2026-10-25 第 7 节确认）
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
  operationScope, registerScope, normalizeUsername, SCOPE_MAX_LENGTH,
} from '../src/lib/idempotency.ts';

const SECRET = 'x'.repeat(40);

describe('已登录业务的 scope', () => {
  test('固定为 v1:<operation>:<user_uuid>', () => {
    assert.equal(operationScope('claim', 'a1b2c3d4-0000-4000-8000-000000000000'),
      'v1:claim:a1b2c3d4-0000-4000-8000-000000000000');
  });

  test('operation 必须在白名单内（防止客户端注入任意 scope）', () => {
    assert.throws(() => operationScope('admin', 'u1'), /不允许的幂等操作/);
    assert.throws(() => operationScope('', 'u1'), /不允许的幂等操作/);
  });

  test('没有已认证用户时拒绝生成', () => {
    assert.throws(() => operationScope('claim', ''), /需要已认证的 user_id/);
  });
});

describe('注册 scope', () => {
  test('形如 register:v1:<40 位十六进制>，不含明文用户名', () => {
    const scope = registerScope(SECRET, 'Alice_01');
    assert.match(scope, /^register:v1:[0-9a-f]{40}$/);
    assert.ok(!scope.toLowerCase().includes('alice'), 'scope 里不应出现用户名');
  });

  test('用户名归一化：trim + 小写，同一用户得到同一 scope', () => {
    assert.equal(normalizeUsername('  Alice_01 '), 'alice_01');
    assert.equal(registerScope(SECRET, ' Alice_01 '), registerScope(SECRET, 'alice_01'));
  });

  test('不同用户名得到不同 scope；不同密钥得到不同 scope', () => {
    assert.notEqual(registerScope(SECRET, 'alice'), registerScope(SECRET, 'bob'));
    assert.notEqual(registerScope(SECRET, 'alice'), registerScope('y'.repeat(40), 'alice'));
  });

  test('长度不超过数据库 varchar(80)', () => {
    const scope = registerScope(SECRET, 'a'.repeat(32));
    assert.ok(scope.length <= SCOPE_MAX_LENGTH, `实际长度 ${scope.length}`);
  });
});
