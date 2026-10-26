// 展示昵称的纯逻辑（不依赖数据库）。
// 规则（账号安全边界）：
//  - 默认昵称形如 `Whale-<suffix>`，suffix 固定取服务端已分配 `users.id`（UUID）的末 4 位；
//  - 客户端传入的昵称只作为展示值保存，**不能**决定默认昵称的编号：
//    即使客户端预填 `Whale-1234`，服务端仍使用自己 id 的后缀，避免用户自选编号后
//    冒用他人默认名或伪造"官方分配"的外观；
//  - 仅当客户端昵称看起来就是默认昵称占位（Whale + 可选分隔符 + 十六进制编号）时才整体替换；
//    普通自定义昵称（如「爱丽丝」）原样保留，服务端只做长度裁剪。

export const DEFAULT_NICKNAME_PREFIX = 'Whale';

/** users.display_name 是 varchar(64)；按码点截断，避免把代理对拆成半个字符 */
export const DISPLAY_NAME_MAX_CODE_POINTS = 64;

/**
 * 客户端默认昵称占位：`Whale-1234`（客户端 UI）、`Whale20261025`（旧客户端由公开编号生成）、
 * `Whale_abcd` 等。只认 1~8 位十六进制编号，避免误伤 "WhaleLover" 这类正常昵称。
 */
const DEFAULT_NICKNAME_PLACEHOLDER_RE = /^whale[-_\s]?[0-9a-f]{1,8}$/i;

/** 取服务端分配 id 的末 4 位；id 是 UUID，末 4 位恒为十六进制字符。 */
export function displayNameSuffix(userId: string): string {
  const compact = userId.replace(/-/g, '').trim().toLowerCase();
  if (compact.length < 4) {
    throw new Error('users.id 长度不足 4，无法生成默认昵称后缀');
  }
  return compact.slice(-4);
}

function clampDisplayName(value: string): string {
  return Array.from(value).slice(0, DISPLAY_NAME_MAX_CODE_POINTS).join('');
}

/**
 * 计算落库的 display_name。
 *
 * @param userId 服务端生成的 users.id（UUID），默认昵称后缀的唯一来源
 * @param requestedDisplayName 客户端传入的昵称；仅作展示值，不能决定后缀
 */
export function resolveDisplayName(userId: string, requestedDisplayName?: string | null): string {
  const fallback = `${DEFAULT_NICKNAME_PREFIX}-${displayNameSuffix(userId)}`;
  const requested = clampDisplayName(String(requestedDisplayName ?? '').trim());
  if (!requested) return fallback;
  // 客户端自带的默认昵称占位不采纳其编号：服务端后缀优先
  if (DEFAULT_NICKNAME_PLACEHOLDER_RE.test(requested)) return fallback;
  return requested;
}
