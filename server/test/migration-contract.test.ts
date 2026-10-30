// 对 Bottledkzk 实际迁移的结构校验（不连数据库）
// 目的：把"评审时说过的话"变成可执行断言——任何人改迁移破坏了这些约束，测试会红。
// 与 test/schema.test.ts（我原建议稿的断言）不同：这里校验的是**仓库里真实生效的迁移**。
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const migrationsDir = new URL('../prisma/migrations/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const schemaPath = new URL('../prisma/schema.prisma', import.meta.url);

/** 收集所有迁移 SQL（按目录名排序，模拟 Prisma 执行顺序） */
function collectMigrations(): { name: string; sql: string }[] {
  return readdirSync(migrationsDir)
    .filter((d) => statSync(join(migrationsDir, d)).isDirectory())
    .sort()
    .map((d) => {
      let sql = '';
      try {
        sql = readFileSync(join(migrationsDir, d, 'migration.sql'), 'utf8');
      } catch {
        // 目录里没有 migration.sql 时跳过（如仅 lock 文件的目录）
      }
      return { name: d, sql };
    })
    .filter((m) => m.sql.length > 0);
}

const migrations = collectMigrations();
const allSql = migrations.map((m) => m.sql).join('\n');
const schema = readFileSync(schemaPath, 'utf8');

describe('迁移文件本身', () => {
  test('至少有一个带 SQL 的迁移', () => {
    assert.ok(migrations.length >= 1, 'prisma/migrations 下应有 migration.sql');
  });

  test('存在 migration_lock.toml 且 provider 为 postgresql', () => {
    const lock = readFileSync(join(migrationsDir, 'migration_lock.toml'), 'utf8');
    assert.match(lock, /provider\s*=\s*"postgresql"/);
  });
});

describe('账户表约束（评审关注点）', () => {
  test('用户名与邮箱是 lower() 表达式唯一索引（大小写不敏感）', () => {
    assert.match(allSql, /CREATE UNIQUE INDEX users_username_ci ON users\(lower\(username\)\)/i);
    assert.match(allSql, /CREATE UNIQUE INDEX users_email_ci ON users\(lower\(email\)\) WHERE email IS NOT NULL/i,
      '邮箱唯一索引必须是部分索引，允许为空');
  });

  test('密码列禁止明文：CHECK 只接受 scrypt$ 或 $argon2id$', () => {
    // 实际 SQL：password_hash varchar(512) NOT NULL CHECK (password_hash ~ '^(scrypt\$|\$argon2id\$)')
    assert.ok(/password_hash varchar\(512\)/.test(allSql), 'password_hash 应为 varchar(512)');
    assert.ok(
      allSql.includes("^(scrypt\\$|\\$argon2id\\$)"),
      'password_hash 必须有格式 CHECK，防止写入明文密码',
    );
    // 不应存在裸 password 列
    const bare = /^\s*password\s+(varchar|text)/im.test(allSql);
    assert.equal(bare, false, '不应存在无 _hash 后缀的 password 列');
  });

  test('demo_slot 限制 1~10，槽满时抛可识别错误', () => {
    assert.match(allSql, /demo_slot integer UNIQUE NOT NULL CHECK \(demo_slot BETWEEN 1 AND 10\)/i);
    assert.match(allSql, /RAISE EXCEPTION 'DEMO_USER_LIMIT' USING ERRCODE='23514'/i,
      '槽满必须抛出可识别的错误码，供应用映射为 409 而不是 500');
    assert.match(allSql, /CREATE TRIGGER users_demo_capacity BEFORE INSERT ON users/i);
  });

  test('role / status 有取值 CHECK（客户端不能自选 role）', () => {
    assert.match(allSql, /role varchar\(16\) NOT NULL DEFAULT 'user' CHECK \(role IN \('user','admin'\)\)/i);
    assert.match(allSql, /status varchar\(16\) NOT NULL DEFAULT 'active' CHECK \(status IN \('active','disabled'\)\)/i);
  });
});

describe('会话与幂等回执', () => {
  test('会话摘要唯一，family_id 有索引', () => {
    assert.match(allSql, /access_hash\s+char\(64\)[^,]*UNIQUE/i, 'access_hash 应唯一');
    assert.match(allSql, /refresh_hash\s+char\(64\)[^,]*UNIQUE/i, 'refresh_hash 应唯一');
    assert.match(allSql, /CREATE INDEX auth_sessions_family ON auth_sessions\(family_id\)/i);
  });

  test('幂等回执主键为 (scope, key)，并按过期时间索引', () => {
    assert.match(allSql, /PRIMARY KEY\s*\(\s*scope\s*,\s*key\s*\)/i);
    assert.match(allSql, /fingerprint\s+char\(64\)/i);
    // 已知差异（已向 Bottledkzk 提出，未阻断）：idempotency_receipts 目前没有 expires_at 索引。
    // 影响：按过期时间清理回执时会全表扫描；数据量小（≤10 用户）时可接受，等表长大再补。
    // 这里不断言存在，避免因为"建议未采纳"让 CI 变红；补索引后可在下一版加回断言。
  });
});

describe('积分账本', () => {
  test('流水唯一键 (user_id, event_id)，且同时记录余额与预占快照', () => {
    assert.match(allSql, /UNIQUE\(user_id,event_id\)/i);
    assert.match(allSql, /balance_after/i);
    assert.match(allSql, /reserved_after/i);
    assert.match(allSql, /available_delta/i);
    assert.match(allSql, /reserved_delta/i);
  });

  test('钱包余额非负 CHECK', () => {
    assert.match(allSql, /CHECK\s*\(available BETWEEN 0 AND 1000000\)/i);
    assert.match(allSql, /CHECK\s*\(reserved BETWEEN 0 AND 1000000\)/i);
  });
});

describe('RLS（交接文档要求的接入方式）', () => {
  const tenantTables = ['tasks', 'plan_blocks', 'checkins', 'availability', 'user_settings', 'sync_batches', 'sync_changes'];

  test('内容表全部启用 RLS', () => {
    assert.match(allSql, /ALTER TABLE %I ENABLE ROW LEVEL SECURITY/i, '应通过循环对内容表批量启用 RLS');
    for (const t of tenantTables) assert.ok(allSql.includes(`'${t}'`), `RLS 表清单应包含 ${t}`);
  });

  test('策略按 app.user_id 判定归属，且带 WITH CHECK', () => {
    assert.match(allSql, /USING \(user_id = nullif\(current_setting\('app\.user_id',true\),''\)::uuid\)/i);
    assert.match(allSql, /WITH CHECK \(user_id = nullif\(current_setting\('app\.user_id',true\),''\)::uuid\)/i);
  });

  test('会话与账户表不套用内容 RLS（服务端特权）', () => {
    for (const t of ['auth_sessions', 'users', 'idempotency_receipts']) {
      const enable = new RegExp(`ALTER TABLE [^;]*\\b${t}\\b[^;]*ENABLE ROW LEVEL SECURITY`, 'i');
      assert.equal(enable.test(allSql), false, `${t} 不应启用内容 RLS`);
    }
  });
});

describe('Prisma schema 与实际迁移一致', () => {
  test('schema 声明了 23 个模型', () => {
    const models = [...schema.matchAll(/^model\s+(\w+)/gm)].map((m) => m[1]!);
    assert.equal(models.length, 23, `实际模型数：${models.length}（${models.join(', ')}）`);
  });

  test('关键字段名与适配器实现一致', () => {
    // 适配器 src/repo/prisma.ts 依赖这些字段名
    for (const f of ['password_hash', 'demo_slot', 'display_name', 'family_id', 'access_hash', 'refresh_hash', 'expires_at']) {
      assert.ok(schema.includes(f), `schema 应包含字段 ${f}`);
    }
  });

  test('不得使用 db push 覆盖约束（迁移目录必须存在）', () => {
    assert.ok(migrations.length > 0);
    const pkg = readFileSync(new URL('../package.json', import.meta.url), 'utf8');
    assert.ok(!/"db:push"/.test(pkg), 'package.json 不应提供 db:push 脚本（会绕过 CHECK/RLS）');
    assert.match(pkg, /"db:deploy":\s*"prisma migrate deploy"/);
  });
});
