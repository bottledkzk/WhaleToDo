import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { cycleDay, pointsFor, shiftDate, streakBefore } from '../src/domain/rewards.ts';

describe('签到连续规则（方案 a）', () => {
  test('周期第几天：1..7 循环，第 8 天回到 1', () => {
    assert.deepEqual([1, 2, 6, 7, 8, 14, 15].map(cycleDay), [1, 2, 6, 7, 1, 7, 1]);
  });

  test('分值：第 1~6 天各 1 分，第 7 天 5 分', () => {
    assert.deepEqual([1, 2, 3, 4, 5, 6].map(pointsFor), [1, 1, 1, 1, 1, 1]);
    assert.equal(pointsFor(7), 5);
    assert.equal(pointsFor(14), 5);
    assert.equal(pointsFor(8), 1);
  });

  test('shiftDate 跨月/跨年正确', () => {
    assert.equal(shiftDate('2026-03-01', -1), '2026-02-28');
    assert.equal(shiftDate('2026-01-01', -1), '2025-12-31');
    assert.equal(shiftDate('2024-03-01', -1), '2024-02-29');
  });

  test('streakBefore：连续、断签、无记录', () => {
    const today = '2026-10-26';
    assert.equal(streakBefore(new Set(['2026-10-25', '2026-10-24']), today), 2);
    assert.equal(streakBefore(new Set(['2026-10-24']), today), 0, '昨天没签即重置');
    assert.equal(streakBefore(new Set(), today), 0);
    assert.equal(streakBefore(new Set(['2026-10-25']), today), 1);
  });
});