// Prisma 仓储实现：对齐 Bottledkzk 的 schema（PR #3）
// 约定：
//  - 对外用户对象不含 password_hash
//  - 注册：users + point_wallets + user_settings 同事务（他的交接文档第 6 节第 3 点）
//  - 刷新轮换：撤销旧 + 插新同事务，撤销必须命中 1 行（并发消费检测）
//  - demo_slot 由数据库函数 next_demo_slot() 生成，插入时不传
//  - RLS 表（tasks/plan_blocks/...）不在此接口内，后续由内容仓储在事务里 set_config 处理
import { cycleDay, pointsFor, streakBefore } from '../domain/rewards.ts';
import { randomUUID } from 'node:crypto';
import { PrismaClient } from '@prisma/client';
import type { AccountRepository, CreatedSession, IdempotencyHit, RepoSession, RepoUser } from './types.ts';
import { RegisterConflict } from './types.ts';
import { decideMutation, batchStatus, isObjectType, isSyncOp, SyncBatchConflict, SyncPayloadTooLarge } from '../domain/sync.ts';
import type { SyncMutationInput, SyncMutationResult } from '../domain/sync.ts';
import { hashPasswordAsync, verifyPasswordAsync, issueTokens, hashToken } from '../lib/security.ts';
import { resolveDisplayName } from '../domain/profile.ts';
import type { Wallet } from '../domain/ledger.ts';

const IDEMPOTENCY_TTL_MS = 24 * 60 * 60 * 1000;

interface UserRow {
  id: string;
  username: string;
  display_name: string;
  created_at: Date;
}

function toRepoUser(u: UserRow): RepoUser {
  return {
    id: u.id,
    username: u.username,
    displayName: u.display_name,
    createdAt: u.created_at.getTime(),
  };
}

interface SessionRow {
  id: string;
  user_id: string;
  access_hash: string;
  refresh_hash: string;
  family_id: string;
  access_expires_at: Date;
  refresh_expires_at: Date;
  revoked_at: Date | null;
  created_at: Date;
}

function toRepoSession(s: SessionRow): RepoSession {
  return {
    id: s.id,
    userId: s.user_id,
    accessHash: s.access_hash,
    refreshHash: s.refresh_hash,
    familyId: s.family_id,
    accessExpiresAt: s.access_expires_at.getTime(),
    refreshExpiresAt: s.refresh_expires_at.getTime(),
    revokedAt: s.revoked_at ? s.revoked_at.getTime() : null,
    createdAt: s.created_at.getTime(),
  };
}

export class PrismaRepository implements AccountRepository {
  private readonly prisma: PrismaClient;
  private readonly now: () => number;

  constructor(prisma: PrismaClient, now: () => number = Date.now) {
    this.prisma = prisma;
    this.now = now;
  }

  /* ---------------- 用户 ---------------- */

  async findUserByUsername(username: string): Promise<RepoUser | null> {
    // 数据库唯一索引是 lower(username)：必须用 lower() 精确匹配。
    // 不能用 Prisma 的 mode:'insensitive'（生成 ILIKE），因为用户名允许下划线，_ 在 LIKE 里是通配符。
    const rows = await this.prisma.$queryRaw<UserRow[]>`
      SELECT id, username, display_name, created_at
      FROM users WHERE lower(username) = lower(${username})
      LIMIT 1`;
    const u = rows[0];
    return u ? toRepoUser(u) : null;
  }

  async findUserById(id: string): Promise<RepoUser | null> {
    const u = await this.prisma.user.findUnique({
      where: { id },
      select: { id: true, username: true, display_name: true, created_at: true },
    });
    return u ? toRepoUser(u) : null;
  }

  async createUser(username: string, password: string, displayName?: string): Promise<RepoUser> {
    const passwordHash = await hashPasswordAsync(password);
    const id = randomUUID();
    // 默认昵称后缀只认服务端 id；客户端昵称（含 Whale-1234 形态）仅作展示值
    const resolvedDisplayName = resolveDisplayName(id, username, displayName);
    // 用户 + 钱包 + 默认设置 同事务（交接文档要求）；demo_slot 由 next_demo_slot() 生成
    // 运行角色 whaletodo_app 没有 users.role / users.status 的写权限（grants 有意如此），
    // 而 Prisma 的 create 会把 schema 里的字面默认值一起写进 INSERT，导致 42501。
    // 因此用参数化 INSERT 只列允许字段，让数据库默认值（role=user / status=active）生效。
    const created = await this.prisma.$transaction(async (tx) => {
      await tx.$executeRaw`
        INSERT INTO users (id, username, password_hash, display_name)
        VALUES (${id}::uuid, ${username}, ${passwordHash}, ${resolvedDisplayName})`;
      const rows = await tx.$queryRaw<UserRow[]>`
        SELECT id, username, display_name, created_at FROM users WHERE id = ${id}::uuid`;
      const user = rows[0];
      if (!user) throw new Error('创建用户后回读失败');

      // user_settings 启用了 owner_scope RLS：WITH CHECK 要求 user_id = current_setting('app.user_id')。
      // 必须在同一事务里设置事务级上下文（true = 仅本事务），不能设全局会话值。
      await tx.$executeRaw`SELECT set_config('app.user_id', ${user.id}, true)`;
      await tx.user_settings.create({ data: { user_id: user.id } });
      await tx.point_wallets.create({ data: { user_id: user.id } });
      return user;
    });
    return toRepoUser(created);
  }

  async authenticate(username: string, password: string): Promise<RepoUser | null> {
    const rows = await this.prisma.$queryRaw<(UserRow & { password_hash: string; status: string })[]>`
      SELECT id, username, display_name, created_at, password_hash, status
      FROM users WHERE lower(username) = lower(${username})
      LIMIT 1`;
    const u = rows[0];
    if (!u || u.status !== 'active') {
      // 账号不存在也跑一次哈希，避免用响应时间探测用户是否存在
      await hashPasswordAsync('dummy-password-for-timing');
      return null;
    }
    const ok = await verifyPasswordAsync(password, u.password_hash);
    return ok ? toRepoUser(u) : null;
  }

  /* ---------------- 会话 ---------------- */

  async createSession(userId: string, familyId?: string): Promise<CreatedSession> {
    const tokens = issueTokens(this.now());
    const row = await this.prisma.auth_sessions.create({
      data: {
        id: randomUUID(),
        user_id: userId,
        access_hash: tokens.accessHash,
        refresh_hash: tokens.refreshHash,
        family_id: familyId ?? randomUUID(),
        access_expires_at: new Date(tokens.accessExpiresAt),
        refresh_expires_at: new Date(tokens.refreshExpiresAt),
      },
      select: {
        id: true, user_id: true, access_hash: true, refresh_hash: true, family_id: true,
        access_expires_at: true, refresh_expires_at: true, revoked_at: true, created_at: true,
      },
    });
    return { session: toRepoSession(row), tokens };
  }

  async findActiveSessionByAccess(accessToken: string): Promise<RepoSession | null> {
    const row = await this.prisma.auth_sessions.findUnique({
      where: { access_hash: hashToken(accessToken) },
    });
    if (!row || row.revoked_at !== null || row.access_expires_at.getTime() <= this.now()) return null;
    return toRepoSession(row);
  }

  async findSessionByRefresh(refreshToken: string): Promise<RepoSession | null> {
    const row = await this.prisma.auth_sessions.findUnique({
      where: { refresh_hash: hashToken(refreshToken) },
    });
    return row ? toRepoSession(row) : null;
  }

  async revokeSession(id: string): Promise<void> {
    await this.prisma.auth_sessions.updateMany({
      where: { id, revoked_at: null },
      data: { revoked_at: new Date(this.now()) },
    });
  }

  async revokeFamily(familyId: string): Promise<number> {
    const res = await this.prisma.auth_sessions.updateMany({
      where: { family_id: familyId, revoked_at: null },
      data: { revoked_at: new Date(this.now()) },
    });
    return res.count;
  }

  /**
   * 刷新轮换（单事务）：撤销旧会话 + 创建新会话，沿用同一 family_id。
   * 撤销必须命中 1 行，否则说明被并发消费 → 抛错由调用方按"重放"处理（整串撤销）。
   */
  async rotateSession(oldSessionId: string, userId: string, familyId: string): Promise<CreatedSession> {
    const tokens = issueTokens(this.now());
    return this.prisma.$transaction(async (tx) => {
      const revoked = await tx.auth_sessions.updateMany({
        where: { id: oldSessionId, revoked_at: null },
        data: { revoked_at: new Date(this.now()) },
      });
      if (revoked.count !== 1) throw new Error('SESSION_ALREADY_ROTATED');
      const row = await tx.auth_sessions.create({
        data: {
          id: randomUUID(),
          user_id: userId,
          access_hash: tokens.accessHash,
          refresh_hash: tokens.refreshHash,
          family_id: familyId,
          access_expires_at: new Date(tokens.accessExpiresAt),
          refresh_expires_at: new Date(tokens.refreshExpiresAt),
        },
        select: {
          id: true, user_id: true, access_hash: true, refresh_hash: true, family_id: true,
          access_expires_at: true, refresh_expires_at: true, revoked_at: true, created_at: true,
        },
      });
      return { session: toRepoSession(row), tokens };
    });
  }

  /**
   * 注册 + 回执同一事务：任一步失败则整体回滚，不会留下"有用户没回执"的中间态。
   * 回执唯一冲突（并发同 key）会抛 P2002，由路由重新读取回执处理。
   */
  /**
   * 注册 + 回执同一事务（评审 P2）：
   *  - 未过期的既有回执 → 抛 RegisterConflict('IDEMPOTENT_REPLAY')，路由读回执比较指纹
   *  - 已过期的既有回执 → 条件 UPDATE 抢占（影响 0 行说明别人抢先 → IDEMPOTENCY_RACE）
   *  - users 唯一冲突 → USERNAME_TAKEN（普通用户名占用）而不是 500
   *  - 回执插入唯一冲突 → IDEMPOTENCY_RACE
   */
  async createUserWithReceipt(input: {
    username: string;
    password: string;
    displayName?: string;
    scope: string;
    key: string;
    fingerprint: string;
    buildResponse: (user: RepoUser) => unknown;
  }): Promise<RepoUser> {
    const passwordHash = await hashPasswordAsync(input.password);
    const id = randomUUID();
    // 幂等注册与普通注册共用同一昵称规则：后缀只认服务端 id，客户端昵称仅作展示值
    const resolvedDisplayName = resolveDisplayName(id, input.username, input.displayName);
    const expiresAt = new Date(this.now() + IDEMPOTENCY_TTL_MS);

    return this.prisma.$transaction(async (tx) => {
      const existing = await tx.idempotency_receipts.findUnique({
        where: { scope_key: { scope: input.scope, key: input.key } },
      });
      let claimedExpired = false;

      if (existing) {
        if (existing.expires_at.getTime() > this.now()) throw new RegisterConflict('IDEMPOTENT_REPLAY');
        // 过期行仍占着主键：本次注册事务内原子替换，避免 create 撞唯一约束
        const updated = await tx.$executeRaw`
          UPDATE idempotency_receipts
             SET fingerprint = ${input.fingerprint}, expires_at = ${expiresAt}
           WHERE scope = ${input.scope} AND key = ${input.key} AND expires_at <= now()`;
        if (updated !== 1) throw new RegisterConflict('IDEMPOTENCY_RACE');
        claimedExpired = true;
      }

      try {
        await tx.$executeRaw`
          INSERT INTO users (id, username, password_hash, display_name)
          VALUES (${id}::uuid, ${input.username}, ${passwordHash}, ${resolvedDisplayName})`;
      } catch (err) {
        if (isUniqueViolation(err)) throw new RegisterConflict('USERNAME_TAKEN');
        throw err;
      }

      const rows = await tx.$queryRaw<UserRow[]>`
        SELECT id, username, display_name, created_at FROM users WHERE id = ${id}::uuid`;
      const user = rows[0];
      if (!user) throw new Error('创建用户后回读失败');

      await tx.$executeRaw`SELECT set_config('app.user_id', ${user.id}, true)`;
      await tx.user_settings.create({ data: { user_id: user.id } });
      await tx.point_wallets.create({ data: { user_id: user.id } });

      const responseJson = JSON.stringify(input.buildResponse(toRepoUser(user)));
      if (claimedExpired) {
        await tx.$executeRaw`
          UPDATE idempotency_receipts
             SET fingerprint = ${input.fingerprint}, response = ${responseJson}::jsonb, expires_at = ${expiresAt}
           WHERE scope = ${input.scope} AND key = ${input.key}`;
      } else {
        try {
          await tx.$executeRaw`
            INSERT INTO idempotency_receipts (scope, key, fingerprint, response, expires_at)
            VALUES (${input.scope}, ${input.key}, ${input.fingerprint}, ${responseJson}::jsonb, ${expiresAt})`;
        } catch (err) {
          if (isUniqueViolation(err)) throw new RegisterConflict('IDEMPOTENCY_RACE');
          throw err;
        }
      }
      return toRepoUser(user);
    });
  }

  /* ---------------- 钱包 ---------------- */

  async walletOf(userId: string): Promise<Wallet> {
    const w = await this.prisma.point_wallets.findUnique({ where: { user_id: userId } });
    return { available: w?.available ?? 0, reserved: w?.reserved ?? 0 };
  }

  async setWallet(userId: string, wallet: Wallet): Promise<void> {
    await this.prisma.point_wallets.update({
      where: { user_id: userId },
      data: { available: wallet.available, reserved: wallet.reserved, updated_at: new Date(this.now()) },
    });
  }

  /**
   * 签到发奖：同一事务内
   *  1) INSERT daily_claims ... ON CONFLICT DO NOTHING —— 唯一键 (user_id, business_date, reward_type) 兜住并发
   *  2) 钱包 available += points（UPDATE ... RETURNING 拿到余额快照）
   *  3) 写 point_ledger（award，含 available_delta 与 balance_after）
   * 已领过则返回 claimed=false，不发奖也不写流水。
   */

  async listLedger(
    userId: string,
    limit: number,
    cursor?: { createdAt: number; id: string },
  ): Promise<{
    items: Array<{
      id: string; type: string; delta: number; balanceAfter: number; reservedAfter: number;
      reason: string; createdAt: number;
    }>;
    nextCursor: { createdAt: number; id: string } | null;
  }> {
    const size = Math.min(Math.max(limit, 1), 100);
    type Row = {
      id: string; type: string; available_delta: number; balance_after: number;
      reserved_after: number; reason: string; created_at: Date;
    };

    // 游标必须是合法的 Date + uuid，且用 (created_at, id) 复合比较，
    // 否则同毫秒的多条流水会被漏掉（评审 P2）；毫秒 number 不能直接强转 timestamptz。
    const rows: Row[] = cursor
      ? await this.prisma.$queryRaw<Row[]>`
          SELECT id, type, available_delta, balance_after, reserved_after, reason, created_at
            FROM point_ledger
           WHERE user_id = ${userId}::uuid
             AND (created_at, id) < (${new Date(cursor.createdAt)}, ${cursor.id}::uuid)
           ORDER BY created_at DESC, id DESC
           LIMIT ${size + 1}`
      : await this.prisma.$queryRaw<Row[]>`
          SELECT id, type, available_delta, balance_after, reserved_after, reason, created_at
            FROM point_ledger
           WHERE user_id = ${userId}::uuid
           ORDER BY created_at DESC, id DESC
           LIMIT ${size + 1}`;

    const page = rows.slice(0, size);
    const last = page[page.length - 1];
    return {
      items: page.map((r) => ({
        id: r.id,
        type: r.type,
        delta: r.available_delta,
        balanceAfter: r.balance_after,
        reservedAfter: r.reserved_after,
        reason: r.reason,
        createdAt: r.created_at.getTime(),
      })),
      nextCursor: rows.length > size && last ? { createdAt: last.created_at.getTime(), id: last.id } : null,
    };
  }

  async pushSyncBatch(input: {
    userId: string;
    batchId: string;
    deviceId: string;
    fingerprint: string;
    mutations: SyncMutationInput[];
  }): Promise<{ replayed: boolean; status: 'applied' | 'partial' | 'rejected'; items: SyncMutationResult[] }> {
    return this.prisma.$transaction(async (tx) => {
      // 内容表与批次表都受 RLS 保护：本事务内设置认证用户
      await tx.$executeRaw`SELECT set_config('app.user_id', ${input.userId}, true)`;

      // P1：同账号所有同步写入串行化（账号级事务锁，先于任何读版本/写 seq）。
      // 这样 seq 的分配顺序与提交顺序一致：不会出现"小 seq 的事务后提交"，
      // pull 推进到较大 seq 后也不会永久漏掉那条小 seq 记录；
      // 同对象的两个 expected_version 相同更新也只会有一个成功。
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${input.userId}))`;

      // 批次幂等：UNIQUE(user_id, batch_id)
      const batchRowId = randomUUID();
      const inserted = await tx.$executeRaw`
        INSERT INTO sync_batches (id, user_id, batch_id, device_id, fingerprint, status)
        VALUES (${batchRowId}::uuid, ${input.userId}::uuid, ${input.batchId}::uuid, ${input.deviceId}, ${input.fingerprint}, 'processing')
        ON CONFLICT (user_id, batch_id) DO NOTHING`;

      if (inserted === 0) {
        const existing = await tx.$queryRaw<{ status: string; response: unknown; fingerprint: string }[]>`
          SELECT status, response, fingerprint FROM sync_batches
           WHERE user_id = ${input.userId}::uuid AND batch_id = ${input.batchId}::uuid`;
        const row = existing[0];
        // 同 batch_id 不同内容必须拒绝，否则新请求会被静默丢弃（评审 P2）
        if (row && row.fingerprint !== input.fingerprint) throw new SyncBatchConflict();
        if (row?.response) {
          const saved = row.response as { status: 'applied' | 'partial' | 'rejected'; items: SyncMutationResult[] };
          return { replayed: true, status: saved.status, items: saved.items };
        }
        throw new Error('SYNC_BATCH_PENDING');
      }

      const items: SyncMutationResult[] = [];
      for (const m of input.mutations) {
        // 未知类型/操作：先判定，避免对非法 object_id 做 UUID 查询（评审 P2）
        if (!isObjectType(m.objectType) || !isSyncOp(m.op)) {
          const decided = decideMutation(null, m);
          items.push({
            object_type: m.objectType,
            object_id: m.objectId,
            op: m.op,
            result: decided.result,
            server_version: null,
            ...(decided.reason ? { reason: decided.reason } : {}),
          });
          continue;
        }
        // 当前对象版本：取该对象最新一条变更
        const currentRows = await tx.$queryRaw<{ version: number; deleted: boolean }[]>`
          SELECT version, deleted FROM sync_changes
           WHERE user_id = ${input.userId}::uuid
             AND object_type = ${m.objectType}
             AND object_id = ${m.objectId}::uuid
           ORDER BY seq DESC LIMIT 1`;
        const current = currentRows[0] ? { version: currentRows[0].version, deleted: currentRows[0].deleted } : null;

        const decided = decideMutation(current, m);
        let serverVersion = decided.serverVersion;

        if (decided.result === 'applied') {
          serverVersion = decided.serverVersion ?? 1;
          try {
            await tx.$executeRaw`
              INSERT INTO sync_changes (user_id, object_type, object_id, version, deleted, payload)
              VALUES (${input.userId}::uuid, ${m.objectType}, ${m.objectId}::uuid, ${serverVersion}, ${m.op === 'delete'}, ${JSON.stringify(m.payload ?? null)}::jsonb)`;
          } catch (err) {
            // 只把 sync_changes_payload_check 的 23514 翻译成 400；其他 P2010/23514 继续传播
            if (isPayloadTooLargeViolation(err)) throw new SyncPayloadTooLarge();
            throw err;
          }
        }

        // sync_mutations 上有 CHECK(object_type IN 五种) 与 CHECK(op IN 三种)，
        // 未知类型/操作的 rejected 写不进去；这类拒绝只记录在批次 response 里。
        if (isObjectType(m.objectType) && isSyncOp(m.op)) {
          await tx.$executeRaw`
            INSERT INTO sync_mutations
              (id, batch_id, mutation_id, object_type, object_id, op, result, server_version)
            VALUES (${randomUUID()}::uuid, ${batchRowId}::uuid, ${randomUUID()}::uuid,
                    ${m.objectType}, ${m.objectId}::uuid, ${m.op}, ${decided.result}, ${serverVersion})`;
        }

        items.push({
          object_type: m.objectType,
          object_id: m.objectId,
          op: m.op,
          result: decided.result,
          server_version: serverVersion,
          ...(decided.reason ? { reason: decided.reason } : {}),
        });
      }

      const status = batchStatus(items);
      await tx.$executeRaw`
        UPDATE sync_batches SET status = ${status}, response = ${JSON.stringify({ status, items })}::jsonb
         WHERE id = ${batchRowId}::uuid`;

      return { replayed: false, status, items };
    });
  }

  async pullSyncChanges(userId: string, cursor: number, limit: number): Promise<{
    changes: Array<{ seq: number; objectType: string; objectId: string; version: number; deleted: boolean; payload: unknown }>;
    nextCursor: number;
    hasMore: boolean;
  }> {
    const size = Math.min(Math.max(limit, 1), 200);
    return this.prisma.$transaction(async (tx) => {
      await tx.$executeRaw`SELECT set_config('app.user_id', ${userId}, true)`;
      const rows = await tx.$queryRaw<Array<{
        seq: bigint; object_type: string; object_id: string; version: number; deleted: boolean; payload: unknown;
      }>>`
        SELECT seq, object_type, object_id, version, deleted, payload
          FROM sync_changes
         WHERE user_id = ${userId}::uuid AND seq > ${cursor}
         ORDER BY seq ASC
         LIMIT ${size + 1}`;
      const page = rows.slice(0, size);
      const last = page[page.length - 1];
      return {
        changes: page.map((r) => ({
          seq: Number(r.seq),
          objectType: r.object_type,
          objectId: r.object_id,
          version: r.version,
          deleted: r.deleted,
          payload: r.payload,
        })),
        nextCursor: last ? Number(last.seq) : cursor,
        hasMore: rows.length > size,
      };
    });
  }

  async hasClaimedDaily(userId: string, businessDate: string, rewardType: string): Promise<boolean> {
    const rows = await this.prisma.$queryRaw<{ one: number }[]>`
      SELECT 1 AS one FROM daily_claims
       WHERE user_id = ${userId}::uuid AND business_date = ${businessDate}::date AND reward_type = ${rewardType}
       LIMIT 1`;
    return rows.length > 0;
  }


  async dailyStatus(input: {
    userId: string;
    businessDate: string;
    rewardType: string;
  }): Promise<{
    claimed: boolean;
    consecutiveDays: number;
    cycleDay: number;
    pointsToday: number | null;
    nextPoints: number;
  }> {
    const rows = await this.prisma.$queryRaw<{ business_date: string; points: number }[]>`
      SELECT to_char(business_date, 'YYYY-MM-DD') AS business_date, points
        FROM daily_claims
       WHERE user_id = ${input.userId}::uuid AND reward_type = ${input.rewardType}
       ORDER BY business_date DESC`;
    const claimedDates = new Set(rows.map((r) => r.business_date));
    const claimed = claimedDates.has(input.businessDate);
    const prevStreak = streakBefore(claimedDates, input.businessDate);
    // 今日已签：进度含今天；未签：显示"下一次"会是第几天
    const consecutiveDays = claimed ? prevStreak + 1 : prevStreak;
    const shownDays = claimed ? consecutiveDays : prevStreak + 1;
    const pointsToday = claimed
      ? rows.find((r) => r.business_date === input.businessDate)?.points ?? pointsFor(consecutiveDays)
      : null;
    return {
      claimed,
      consecutiveDays,
      cycleDay: cycleDay(Math.max(shownDays, 1)),
      pointsToday,
      nextPoints: pointsFor(shownDays),
    };
  }

  async claimDailyReward(input: {
    userId: string;
    businessDate: string;
    rewardType: string;
    eventId: string;
    reason: string;
  }): Promise<{
    claimed: boolean;
    pointsAwarded: number;
    consecutiveDays: number;
    cycleDay: number;
    available: number;
    reserved: number;
  }> {
    return this.prisma.$transaction(async (tx) => {
      // 0) 同账号发奖串行化：在读取历史之前取事务级咨询锁，
      //    这样"昨天未提交、今天先读历史"的跨业务日交错不会发生（评审 P2）
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${input.userId}))`;

      // 1) 先算连续天数：取该用户全部签到记录（含今天，若已签）。
      //    不能加 LIMIT：截断会让长连续记录的周期错位（评审 P2，405 天场景）
      const rows = await tx.$queryRaw<{ business_date: string }[]>`
        SELECT to_char(business_date, 'YYYY-MM-DD') AS business_date
          FROM daily_claims
         WHERE user_id = ${input.userId}::uuid AND reward_type = ${input.rewardType}
         ORDER BY business_date DESC`;
      const claimedDates = new Set(rows.map((r) => r.business_date));

      const prevStreak = streakBefore(claimedDates, input.businessDate);
      const consecutiveDays = prevStreak + 1;              // 今天将计入
      const day = cycleDay(consecutiveDays);
      const points = pointsFor(consecutiveDays);

      // 2) 唯一键兜并发：插不进去说明今天已领
      const inserted = await tx.$executeRaw`
        INSERT INTO daily_claims (id, user_id, business_date, reward_type, points)
        VALUES (${randomUUID()}::uuid, ${input.userId}::uuid, ${input.businessDate}::date, ${input.rewardType}, ${points})
        ON CONFLICT (user_id, business_date, reward_type) DO NOTHING`;

      if (inserted === 0) {
        const w = await tx.point_wallets.findUnique({ where: { user_id: input.userId } });
        return {
          claimed: false,
          pointsAwarded: 0,
          consecutiveDays: prevStreak,   // 今天没领成，连续天数还是截至昨天
          cycleDay: cycleDay(Math.max(prevStreak, 1)),
          available: w?.available ?? 0,
          reserved: w?.reserved ?? 0,
        };
      }

      // 3) 钱包 + 流水，同一事务
      const updated = await tx.$queryRaw<{ available: number; reserved: number }[]>`
        UPDATE point_wallets
           SET available = available + ${points}, updated_at = now()
         WHERE user_id = ${input.userId}::uuid
        RETURNING available, reserved`;
      const wallet = updated[0];
      if (!wallet) throw new Error('签到发奖时找不到钱包');

      await tx.$executeRaw`
        INSERT INTO point_ledger
          (id, user_id, event_id, type, available_delta, reserved_delta, balance_after, reserved_after, reason)
        VALUES
          (${randomUUID()}::uuid, ${input.userId}::uuid, ${input.eventId}, 'award',
           ${points}, 0, ${wallet.available}, ${wallet.reserved}, ${input.reason})`;

      return {
        claimed: true,
        pointsAwarded: points,
        consecutiveDays,
        cycleDay: day,
        available: wallet.available,
        reserved: wallet.reserved,
      };
    });
  }

  /* ---------------- 幂等回执 ---------------- */

  async getIdempotent(scope: string, key: string): Promise<IdempotencyHit | undefined> {
    const row = await this.prisma.idempotency_receipts.findUnique({ where: { scope_key: { scope, key } } });
    if (!row) return undefined;
    if (row.expires_at.getTime() <= this.now()) return undefined;
    return { fingerprint: row.fingerprint, response: row.response };
  }

  /**
   * 保存回执：
   *  - 未过期的既有回执**不覆盖**（首次结果优先，异体由调用方比较指纹后返回 409）
   *  - 只有已过期回执允许替换
   *  - 并发插入的唯一冲突（P2002）按"已存在"处理；其他错误（连接、权限、超长等）必须抛出
   */
  /**
   * 保存回执（TTL 感知，不覆盖未过期结果）。
   * 过期替换用**条件 UPDATE**（WHERE expires_at <= now()）抢占：
   * 两个请求同时读到过期行时，只有一个的 UPDATE 影响 1 行，另一个放弃并尊重赢家。
   */
  async saveIdempotent(scope: string, key: string, fingerprint: string, response: unknown): Promise<void> {
    const expiresAt = new Date(this.now() + IDEMPOTENCY_TTL_MS);
    const existing = await this.prisma.idempotency_receipts.findUnique({ where: { scope_key: { scope, key } } });

    if (existing) {
      if (existing.expires_at.getTime() > this.now()) return;   // 未过期：保留首次结果
      // 原子条件替换：只有"当前仍然过期"的行才会被改
      await this.prisma.$executeRaw`
        UPDATE idempotency_receipts
           SET fingerprint = ${fingerprint}, response = ${JSON.stringify(response)}::jsonb, expires_at = ${expiresAt}
         WHERE scope = ${scope} AND key = ${key} AND expires_at <= now()`;
      return;   // 影响 0 行说明别人刚替换成未过期回执，放弃覆盖
    }

    try {
      await this.prisma.idempotency_receipts.create({
        data: { scope, key, fingerprint, response: response as object, expires_at: expiresAt },
      });
    } catch (err) {
      if (!isUniqueViolation(err)) throw err;   // 只吞并发唯一冲突
    }
  }
}

/** "register:abc" -> { scope: "register", key: "abc" }；无冒号时 scope 取 "default" */
function splitScopeKey(raw: string): { scope: string; k: string } {
  const idx = raw.indexOf(':');
  if (idx <= 0) return { scope: 'default', k: raw.slice(0, 128) };
  return { scope: raw.slice(0, idx).slice(0, 80), k: raw.slice(idx + 1).slice(0, 128) };
}

/**
 * 唯一约束冲突识别：
 *  - Prisma 模型层：P2002
 *  - 原生 $executeRaw：P2010，且 meta.code === '23505'（PostgreSQL unique_violation）
 * 只认这两类；其他 P2010（权限、语法、连接）必须继续传播，不能被吞。
 */
function isUniqueViolation(err: unknown): boolean {
  if (typeof err !== 'object' || err === null) return false;
  const e = err as { code?: string; meta?: { code?: string } };
  if (e.code === 'P2002') return true;
  return e.code === 'P2010' && e.meta?.code === '23505';
}

/**
 * jsonb 大小约束冲突识别：
 *  - 原生 $executeRaw：P2010，meta.code === '23514'（PostgreSQL check_violation）
 *  - 且约束名必须是 sync_changes_payload_check
 * 其他 23514（别的 CHECK）与 P2010 必须继续传播，不能被笼统吞。
 */
function isPayloadTooLargeViolation(err: unknown): boolean {
  if (typeof err !== 'object' || err === null) return false;
  const e = err as { code?: string; meta?: { code?: string; message?: string }; message?: string };
  if (e.code !== 'P2010' || e.meta?.code !== '23514') return false;
  const message = e.meta?.message ?? e.message ?? '';
  return message.includes('sync_changes_payload_check');
}

