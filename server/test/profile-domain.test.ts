// 默认昵称的纯逻辑测试：后缀只能来自服务端分配的 users.id。
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { DISPLAY_NAME_MAX_CODE_POINTS, displayNameSuffix, resolveDisplayName } from '../src/domain/profile.ts';

// 末尾 4 位固定为 6abc，方便断言
const USER_ID = '0f0e0d0c-1111-2222-3333-444455556abc';

describe('默认昵称 resolveDisplayName', () => {
  test('未传昵称 → Whale-<users.id 末 4 位>', () => {
    assert.equal(resolveDisplayName(USER_ID), 'Whale-6abc');
    assert.equal(resolveDisplayName(USER_ID, ''), 'Whale-6abc');
    assert.equal(resolveDisplayName(USER_ID, '   '), 'Whale-6abc');
    assert.equal(resolveDisplayName(USER_ID, null), 'Whale-6abc');
  });

  test('客户端传 Whale-1234 时仍用服务端 id 后缀（不能自选编号）', () => {
    const name = resolveDisplayName(USER_ID, 'Whale-1234');
    assert.equal(name, 'Whale-6abc');
    assert.notEqual(name, 'Whale-1234');
    assert.ok(name.endsWith(displayNameSuffix(USER_ID)));
  });

  test('旧客户端用公开编号拼的 Whale20261025 / Whale_abcd 同样被服务端后缀替换', () => {
    assert.equal(resolveDisplayName(USER_ID, 'Whale20261025'), 'Whale-6abc');
    assert.equal(resolveDisplayName(USER_ID, 'whale_abcd'), 'Whale-6abc');
  });

  test('普通自定义昵称原样保留（仅作展示值）', () => {
    assert.equal(resolveDisplayName(USER_ID, '爱丽丝'), '爱丽丝');
    assert.equal(resolveDisplayName(USER_ID, 'WhaleLover'), 'WhaleLover');
    assert.equal(resolveDisplayName(USER_ID, 'Whale'), 'Whale');
  });

  test('不同 id 得到不同后缀，且大小写不敏感', () => {
    assert.equal(resolveDisplayName('11111111-1111-1111-1111-111111111234'), 'Whale-1234');
    assert.equal(resolveDisplayName('11111111-1111-1111-1111-11111111ABCD'), 'Whale-abcd');
  });

  test('超长昵称按码点截到 64，不拆代理对', () => {
    const long = '🐳'.repeat(DISPLAY_NAME_MAX_CODE_POINTS + 5);
    const name = resolveDisplayName(USER_ID, long);
    assert.equal(Array.from(name).length, DISPLAY_NAME_MAX_CODE_POINTS);
    assert.equal(name, '🐳'.repeat(DISPLAY_NAME_MAX_CODE_POINTS));
  });

  test('id 太短时抛错，而不是悄悄产出错误后缀', () => {
    assert.throws(() => displayNameSuffix('abc'), /长度不足 4/);
  });
});
