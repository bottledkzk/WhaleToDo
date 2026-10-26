// 签到连续规则（2026-10-26 评审确认，方案 a）
// - 连续天数由 daily_claims 推导：漏一天即重置，首版不支持补签
// - 周期奖励：第 1~6 天各 1 分，第 7 天 5 分；第 8 天周期回到第 1 天，但连续天数继续累加
// - 业务日一律由服务端 BUSINESS_TIMEZONE 决定，客户端时区不能制造额外业务日
export const CYCLE_DAYS = 7;
export const DAILY_POINTS = 1;
export const CYCLE_BONUS_POINTS = 5;

/** 连续天数 -> 周期内第几天（1..7） */
export function cycleDay(consecutiveDays: number): number {
  if (consecutiveDays <= 0) return 1;
  return ((consecutiveDays - 1) % CYCLE_DAYS) + 1;
}

/** 连续天数 -> 当日应发积分 */
export function pointsFor(consecutiveDays: number): number {
  return cycleDay(consecutiveDays) === CYCLE_DAYS ? CYCLE_BONUS_POINTS : DAILY_POINTS;
}

/** 'YYYY-MM-DD' 加减天数（纯字符串运算，避免时区漂移） */
export function shiftDate(date: string, days: number): string {
  const [y, m, d] = date.split('-').map((x) => Number.parseInt(x, 10));
  const t = Date.UTC(y!, (m ?? 1) - 1, d ?? 1) + days * 86_400_000;
  return new Date(t).toISOString().slice(0, 10);
}

/**
 * 由"已签到的业务日集合"推导今天之前已连续多少天。
 * 传入的集合包含今天（若今天已签过）；这里只往回数到昨天为止。
 */
export function streakBefore(claimedDates: ReadonlySet<string>, today: string): number {
  let streak = 0;
  let cursor = shiftDate(today, -1);
  while (claimedDates.has(cursor)) {
    streak += 1;
    cursor = shiftDate(cursor, -1);
  }
  return streak;
}
