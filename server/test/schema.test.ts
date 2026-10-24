// schema 一致性测试：不连数据库，用文本断言校验 schema.prisma 与迁移 SQL 的关键约束。
// 目的：把"评审结论里定下的唯一键/CHECK/不唯一字段"变成可执行断言，防止后续改动手滑。
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const schema = readFileSync(new URL('../prisma/schema.prisma', import.meta.url), 'utf8');
const sql = readFileSync(new URL('../prisma/migrations/20261023000000_init/migration.sql', import.meta.url), 'utf8');

/** 提取 SQL 中所有 CREATE TABLE 的表名 */
function sqlTables(text: string): string[] {
  return [...text.matchAll(/CREATE TABLE (\w+)/g)].map((m) => m[1]!);
}

/** 提取 Prisma 模型的 @@map 表名 */
function prismaTables(text: string): string[] {
  return [...text.matchAll(/@@map\("(\w+)"\)/g)].map((m) => m[1]!);
}

describe('schema.prisma 与迁移 SQL 对齐', () => {
  test('两边的表集合一致', () => {
    const fromSql = new Set(sqlTables(sql));
    const fromPrisma = new Set(prismaTables(schema));
    assert.deepEqual([...fromPrisma].sort(), [...fromSql].sort());
  });

  test('覆盖契约要求的 12 张表', () => {
    const expected = [
      'users', 'auth_sessions', 'daily_claims', 'point_wallets', 'point_ledger',
      'products', 'orders', 'payment_events', 'entitlements',
      'sync_batches', 'sync_mutations', 'sync_cursors',
    ];
    const fromSql = new Set(sqlTables(sql));
    for (const t of expected) assert.ok(fromSql.has(t), `缺少表 ${t}`);
  });

  test('外键都指向已定义的表', () => {
    const tables = new Set(sqlTables(sql));
    const refs = [...sql.matchAll(/REFERENCES (\w+) \(/g)].map((m) => m[1]!);
    assert.ok(refs.length >= 10, `外键数量异常：${refs.length}`);
    for (const r of refs) assert.ok(tables.has(r), `外键指向不存在的表：${r}`);
  });
});

describe('评审结论里的关键约束', () => {
  test('签到唯一键 (user_id, business_date, reward_type)', () => {
    assert.match(sql, /CONSTRAINT daily_claims_user_date_type_key UNIQUE \(user_id, business_date, reward_type\)/);
    assert.match(schema, /@@unique\(\[userId, businessDate, rewardType\]\)/);
  });

  test('账本幂等键是 (user_id, event_id)', () => {
    assert.match(sql, /CONSTRAINT point_ledger_user_event_key UNIQUE \(user_id, event_id\)/);
    assert.match(schema, /@@unique\(\[userId, eventId\]\)/);
  });

  test('request_id 只建普通索引，不能唯一', () => {
    const uniqueLines = [...sql.matchAll(/UNIQUE \([^)]*\)/g)].map((m) => m[0]);
    for (const u of uniqueLines) assert.ok(!u.includes('request_id'), `request_id 不应出现在唯一约束：${u}`);
    assert.match(sql, /CREATE INDEX point_ledger_request_idx ON point_ledger \(request_id\)/);
  });

  test('钱包余额有非负 CHECK', () => {
    assert.match(sql, /point_wallets_available_nonneg CHECK \(available >= 0\)/);
    assert.match(sql, /point_wallets_reserved_nonneg\s+CHECK \(reserved >= 0\)/);
  });

  test('同步：批次回执与逐项明细分成两张表且各自唯一/索引', () => {
    assert.match(sql, /CONSTRAINT sync_batches_user_batch_key UNIQUE \(user_id, batch_id\)/);
    assert.match(sql, /CREATE INDEX sync_mutations_batch_idx/);
    assert.match(sql, /REFERENCES sync_batches \(id\) ON DELETE CASCADE/);
  });

  test('同步对象类型覆盖 task/block/checkin/availability', () => {
    const m = /sync_mutations_object_type_chk[\s\S]*?CHECK \(object_type IN \(([^)]*)\)\)/.exec(sql);
    assert.ok(m, '缺少 object_type CHECK');
    for (const t of ['task', 'block', 'checkin', 'availability']) {
      assert.ok(m![1]!.includes(`'${t}'`), `缺少对象类型 ${t}`);
    }
  });

  test('支付事件唯一键 (provider, event_id)，订单幂等键 (user_id, idempotency_key)', () => {
    assert.match(sql, /CONSTRAINT payment_events_provider_event_key UNIQUE \(provider, event_id\)/);
    assert.match(sql, /CONSTRAINT orders_user_idem_key UNIQUE \(user_id, idempotency_key\)/);
  });

  test('订单状态与环境有 CHECK（mock 不能混入 production 记录）', () => {
    assert.match(sql, /orders_status_chk CHECK \(status IN \('PENDING', 'PAID', 'CLOSED', 'REFUNDED'\)\)/);
    assert.match(sql, /orders_env_chk CHECK \(environment IN \('mock', 'production'\)\)/);
  });
});

describe('安全相关', () => {
  test('不存明文密码或明文令牌（只允许 *_hash）', () => {
    // 列名里出现裸 password / token 即失败；password_hash、access_hash、refresh_hash 允许
    const suspect = [...sql.matchAll(/\b(password|token|secret)\b(?!_)/gi)].map((m) => m[0]);
    assert.deepEqual(suspect, [], `发现疑似明文列：${suspect.join(',')}`);
    assert.match(sql, /access_hash\s+char\(64\)/);
    assert.match(sql, /refresh_hash\s+char\(64\)/);
  });

  test('账本 type 的取值与领域层枚举一致', async () => {
    const m = /point_ledger_type_chk CHECK \(type IN \(([^)]*)\)\)/.exec(sql);
    assert.ok(m, '缺少账本 type CHECK');
    const sqlTypes = [...m![1]!.matchAll(/'(\w+)'/g)].map((x) => x[1]!).sort();
    const { default: _ } = { default: null };
    const domainTypes = ['adjust', 'award', 'release', 'reserve', 'settle'];
    assert.deepEqual(sqlTypes, domainTypes);
  });

  test('时间列统一 timestamptz(3)', () => {
    const timeCols = [...sql.matchAll(/^\s+(\w*(?:_at|_date|expires_at|last_seq))\s+([a-z]+(?:\(\d\))?)/gm)];
    assert.ok(timeCols.length >= 8, `时间列数量异常：${timeCols.length}`);
    for (const [, name, type] of timeCols) {
      if (name === 'business_date') { assert.equal(type, 'date', `${name} 应为 date`); continue; }
      if (name === 'last_seq') { assert.equal(type, 'bigint', `${name} 应为 bigint`); continue; }
      assert.match(type!, /^timestamptz\(3\)$/, `${name} 应为 timestamptz(3)，实际 ${type}`);
    }
  });
});
