// Prisma 仓储实现：对齐 Bottledkzk 的 schema（PR #3）
// 约定：
//  - 对外用户对象不含 password_hash
//  - 注册：users + point_wallets + user_settings 同事务（他的交接文档第 6 节第 3 点）
//  - 刷新轮换：撤销旧 + 插新同事务，撤销必须命中 1 行（并发消费检测）
//  - demo_slot 由数据库函数 next_demo_slot() 生成，插入时不传
//  - RLS 表（tasks/plan_blocks/...）不在此接口内，后续由内容仓储在事务里 set_config 处理
import { randomUUID } from 'node:crypto';
import { PrismaClient } from '@prisma/client';
import type { AccountRepository, CreatedSession, IdempotencyHit, RepoSession, RepoUser } from './types.ts';
import { hashPasswordAsync, verifyPasswordAsync, issueTokens, hashToken } from '../lib/security.ts';
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
    // 用户 + 钱包 + 默认设置 同事务（交接文档要求）；demo_slot 由 next_demo_slot() 生成
    const created = await this.prisma.$transaction(async (tx) => {
      const user = await tx.user.create({
        data: {
          id,
          username,
          password_hash: passwordHash,
          display_name: (displayName ?? username).slice(0, 64),
        },
        select: { id: true, username: true, display_name: true, created_at: true },
      });
      await tx.point_wallets.create({ data: { user_id: user.id } });
      await tx.user_settings.create({ data: { user_id: user.id } });
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

  /* ---------------- 幂等回执 ---------------- */

  async getIdempotent(scope: string, key: string): Promise<IdempotencyHit | undefined> {
    const row = await this.prisma.idempotency_receipts.findUnique({ where: { scope_key: { scope, key } } });
    if (!row) return undefined;
    if (row.expires_at.getTime() <= this.now()) return undefined;
    return { fingerprint: row.fingerprint, response: row.response };
  }

  async saveIdempotent(scope: string, key: string, fingerprint: string, response: unknown): Promise<void> {
    const expiresAt = new Date(this.now() + IDEMPOTENCY_TTL_MS);
    try {
      await this.prisma.idempotency_receipts.upsert({
        where: { scope_key: { scope, key } },
        create: { scope, key, fingerprint, response: response as object, expires_at: expiresAt },
        update: { fingerprint, response: response as object, expires_at: expiresAt },
      });
    } catch {
      // 并发插入同一键：唯一约束触发，按"已存在"处理，不覆盖首次结果
    }
  }
}

/** "register:abc" -> { scope: "register", key: "abc" }；无冒号时 scope 取 "default" */
function splitScopeKey(raw: string): { scope: string; k: string } {
  const idx = raw.indexOf(':');
  if (idx <= 0) return { scope: 'default', k: raw.slice(0, 128) };
  return { scope: raw.slice(0, idx).slice(0, 80), k: raw.slice(idx + 1).slice(0, 128) };
}
