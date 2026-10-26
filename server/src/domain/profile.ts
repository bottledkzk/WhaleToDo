// 展示昵称的纯逻辑（不依赖数据库）。
// 规则（T10 修订版，2026-10-26 15:23 PR #18 语义）：
//  - 默认昵称只在"用户未填写昵称"时使用：suffix 只取服务端已分配 users.id
//    去掉连字符后的末 4 位；
//  - requested 去空格后非空 → 截断 64 原样返回（即使形如 `Whale-1234` 也保留）；
//    客户端文本只作展示值，不参与 username / id / 鉴权；
//  - id 无法取出 4 位十六进制后缀时退回 `username.slice(0, 64)`，
//    不得产出 `Whale-` 空后缀。

/** 与 users.display_name 的 varchar(64) 对齐；按码点截断，避免拆开代理对 */
export const DISPLAY_NAME_MAX = 64;

const SERVER_SUFFIX_RE = /^[0-9a-f]{4}$/;

function clampDisplayName(text: string): string {
  return Array.from(text).slice(0, DISPLAY_NAME_MAX).join('');
}

/** 取 UUID 去掉连字符后的末 4 位；取不出 4 位十六进制时返回 null */
function serverSuffix(id: string): string | null {
  const compact = String(id ?? '').replace(/-/g, '').trim().toLowerCase();
  if (compact.length < 4) return null;
  const suffix = compact.slice(-4);
  return SERVER_SUFFIX_RE.test(suffix) ? suffix : null;
}

export function resolveDisplayName(id: string, username: string, requested?: string | null): string {
  const wanted = clampDisplayName(String(requested ?? '').trim());
  // 用户填了昵称就原样使用；只有未填写时才生成服务端默认值
  if (wanted) return wanted;
  const suffix = serverSuffix(id);
  if (suffix === null) return clampDisplayName(String(username ?? ''));
  return `Whale-${suffix}`;
}
