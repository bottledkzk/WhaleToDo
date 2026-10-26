// T10：默认昵称的纯规则测试（不依赖 HTTP/数据库）。
// 重点覆盖三条安全断言：默认值只来自服务端 id、客户端昵称原样保留且不参与标识、id 异常回退。
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { DISPLAY_NAME_MAX, resolveDisplayName } from '../src/domain/profile.ts';

const SERVER_ID = '11111111-1111-4111-8111-111111111aaa';

describe('resolveDisplayName 纯规则', () => {
  test('未传昵称 → Whale-<服务端 id 去掉连字符后的末 4 位>', () => {
    assert.equal(resolveDisplayName(SERVER_ID, 'alice'), 'Whale-1aaa');
    assert.equal(resolveDisplayName('00000000-0000-0000-0000-000000006abc', 'alice'), 'Whale-6abc');
    assert.equal(resolveDisplayName(SERVER_ID, 'alice', '   '), 'Whale-1aaa');
    assert.equal(resolveDisplayName(SERVER_ID, 'alice', null), 'Whale-1aaa');
  });

  test('requested 非空原样保留，即使形如 Whale-1234 / Whale20261025', () => {
    assert.equal(resolveDisplayName(SERVER_ID, 'alice', 'Whale-1234'), 'Whale-1234');
    assert.equal(resolveDisplayName(SERVER_ID, 'alice', 'Whale20261025'), 'Whale20261025');
    assert.equal(resolveDisplayName(SERVER_ID, 'alice', 'whale_abcd'), 'whale_abcd');
  });

  test('自定义昵称去空格后原样保留（只作展示值）', () => {
    assert.equal(resolveDisplayName(SERVER_ID, 'alice', '  我的鲸鱼  '), '我的鲸鱼');
    assert.equal(resolveDisplayName(SERVER_ID, 'alice', 'WhaleLover'), 'WhaleLover');
    assert.equal(resolveDisplayName(SERVER_ID, 'alice', 'Whale'), 'Whale');
  });

  test('超长昵称按码点截断到 64，不拆代理对', () => {
    const long = '🐳'.repeat(DISPLAY_NAME_MAX + 5);
    const name = resolveDisplayName(SERVER_ID, 'alice', long);
    assert.equal(Array.from(name).length, DISPLAY_NAME_MAX);
    assert.equal(name, '🐳'.repeat(DISPLAY_NAME_MAX));
  });

  test('id 异常回退 username.slice(0,64)，不产出 Whale- 空后缀', () => {
    assert.equal(resolveDisplayName('abc', 'fallback_user'), 'fallback_user');
    assert.equal(resolveDisplayName('', 'fallback_user'), 'fallback_user');
    assert.equal(resolveDisplayName('zzzz', 'fallback_user'), 'fallback_user');
    assert.equal(resolveDisplayName('abc', 'x'.repeat(100)).length, DISPLAY_NAME_MAX);
    assert.notEqual(resolveDisplayName('abc', 'fallback_user'), 'Whale-');
  });

  test('UUID 大小写不影响后缀', () => {
    assert.equal(resolveDisplayName('11111111-1111-4111-8111-111111111AAA', 'alice'), 'Whale-1aaa');
  });
});
