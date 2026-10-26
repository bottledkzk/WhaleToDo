// 内存仓储：用于本地开发与集成测试（无数据库依赖）。
// Prisma 仓储在下一阶段接入，接口保持一致，便于替换。
import { randomUUID } from 'node:crypto';
import { hashPasswordAsync, verifyPasswordAsync, issueTokens, hashToken } from '../lib/security.ts';
import { resolveDisplayName } from '../domain/profile.ts';
import type { Wallet } from '../domain/ledger.ts';
import type { RepoUser } from './types.ts';
import { decideMutation, batchStatus, SyncBatchConflict } from '../domain/sync.ts';
import type { SyncMutationInput, SyncMutationResult } from '../domain/sync.ts';

export interface User {
  id: string;
  username: string;
  passwordHash: string;
  displayName: string;
  createdAt: number;
}

export interface Session {
  id: string;
  userId: string;
  accessHash: string;
  refreshHash: string;
  accessExpiresAt: number;
  refreshExpiresAt: number;
  /** 同一登录链（refresh 轮换后共享），用于重放时整串撤销 */
  familyId: string;
  revokedAt: number | null;
  createdAt: number;
}

export interface MemoryStore {
  users: Map<string, User>;
  usernameIndex: Map<string, string>;
  sessions: Map<string, Session>;
  wallets: Map<string, Wallet>;
  /** 幂等键 -> { 请求指纹, 响应体 } */
  idempotency: Map<string, { fingerprint: string; response: unknown }>;
  /** 签到记录：`${userId}:${businessDate}:${rewardType}` -> 发放积分 */
  claims: Map<string, number>;
  /** 同步：对象当前状态 key=`${userId}:${type}:${id}` */
  syncObjects: Map<string, { version: number; deleted: boolean; payload: unknown; seq: number }>;
  /** 同步：变更序列（全局单调递增，按用户过滤） */
  syncChanges: Array<{ seq: number; userId: string; objectType: string; objectId: string; version: number; deleted: boolean; payload: unknown }>;
  /** 同步：批次回执 key=`${userId}:${batchId}` */
  syncBatches: Map<string, { status: 'applied' | 'partial' | 'rejected'; items: SyncMutationResult[]; fingerprint: string }>;
  /** 同步：序列计数器 */
  syncSeq: number;
  /** 账本流水（内存实现，接 Prisma 后由 point_ledger 承担） */
  ledger: Array<{
    id: string; userId: string; eventId: string; type: string; delta: number;
    balanceAfter: number; reservedAfter: number; requestId: string | null; createdAt: number;
  }>;
}

export function createMemoryStore(): MemoryStore {
  return {
    users: new Map(),
    usernameIndex: new Map(),
    sessions: new Map(),
    wallets: new Map(),
    idempotency: new Map(),
    claims: new Map(),
    ledger: [],
    syncObjects: new Map(),
    syncChanges: [],
    syncBatches: new Map(),
    syncSeq: 0,
  };
}

export class MemoryRepository {
  readonly store: MemoryStore;
  private readonly now: () => number;

  // 注意：Node 的类型剥离不支持构造函数参数属性（constructor(readonly x)），故显式赋值
  constructor(store: MemoryStore = createMemoryStore(), now: () => number = Date.now) {
    this.store = store;
    this.now = now;
  }

  findUserByUsername(username: string): User | null {
    const id = this.store.usernameIndex.get(username.toLowerCase());
    return id ? this.store.users.get(id) ?? null : null;
  }

  findUserById(id: string): User | null {
    return this.store.users.get(id) ?? null;
  }

  async createUser(username: string, password: string, displayName?: string): Promise<User> {
    const id = randomUUID();
    const user: User = {
      id,
      username,
      passwordHash: await hashPasswordAsync(password),
      // 默认昵称的后缀只认服务端刚分配的 id；客户端昵称仅作展示值
      displayName: resolveDisplayName(id, displayName),
      createdAt: this.now(),
    };
    this.store.users.set(user.id, user);
    this.store.usernameIndex.set(username.toLowerCase(), user.id);
    this.store.wallets.set(user.id, { available: 0, reserved: 0 });
    return user;
  }

  async authenticate(username: string, password: string): Promise<User | null> {
    const user = this.findUserByUsername(username);
    if (!user) {
      // 即使账号不存在也跑一次哈希，避免用响应时间探测用户是否存在
      await hashPasswordAsync('dummy-password-for-timing');
      return null;
    }
    return (await verifyPasswordAsync(password, user.passwordHash)) ? user : null;
  }

  createSession(userId: string, familyId?: string): { session: Session; tokens: ReturnType<typeof issueTokens> } {
    const tokens = issueTokens(this.now());
    const session: Session = {
      id: randomUUID(),
      userId,
      accessHash: tokens.accessHash,
      refreshHash: tokens.refreshHash,
      accessExpiresAt: tokens.accessExpiresAt,
      refreshExpiresAt: tokens.refreshExpiresAt,
      familyId: familyId ?? randomUUID(),
      revokedAt: null,
      createdAt: this.now(),
    };
    this.store.sessions.set(session.id, session);
    return { session, tokens };
  }

  /** 按访问令牌摘要找会话（未撤销且未过期） */
  findActiveSessionByAccess(accessToken: string): Session | null {
    const digest = hashToken(accessToken);
    for (const s of this.store.sessions.values()) {
      if (s.accessHash === digest && s.revokedAt === null && s.accessExpiresAt > this.now()) return s;
    }
    return null;
  }

  findSessionByRefresh(refreshToken: string): Session | null {
    const digest = hashToken(refreshToken);
    for (const s of this.store.sessions.values()) {
      if (s.refreshHash === digest) return s;
    }
    return null;
  }

  revokeSession(id: string): void {
    const s = this.store.sessions.get(id);
    if (s) s.revokedAt = this.now();
  }

  revokeFamily(familyId: string): number {
    let n = 0;
    for (const s of this.store.sessions.values()) {
      if (s.familyId === familyId && s.revokedAt === null) { s.revokedAt = this.now(); n++; }
    }
    return n;
  }

  walletOf(userId: string): Wallet {
    return this.store.wallets.get(userId) ?? { available: 0, reserved: 0 };
  }

  setWallet(userId: string, wallet: Wallet): void {
    this.store.wallets.set(userId, wallet);
  }

  getIdempotent(scope: string, key: string): { fingerprint: string; response: unknown } | undefined {
    return this.store.idempotency.get(`${scope}\u0000${key}`);
  }

  saveIdempotent(scope: string, key: string, fingerprint: string, response: unknown): void {
    this.store.idempotency.set(`${scope}\u0000${key}`, { fingerprint, response });
  }

  /** 注册 + 回执：内存实现在同一同步段内完成，等价于事务 */
  async createUserWithReceipt(input: {
    username: string;
    password: string;
    displayName?: string;
    scope: string;
    key: string;
    fingerprint: string;
    buildResponse: (user: RepoUser) => unknown;
  }): Promise<User> {
    const user = await this.createUser(input.username, input.password, input.displayName);
    const repoUser = { id: user.id, username: user.username, displayName: user.displayName, createdAt: user.createdAt };
    this.saveIdempotent(input.scope, input.key, input.fingerprint, input.buildResponse(repoUser));
    return user;
  }

  /** 签到发奖：内存实现（唯一键语义用 Map 模拟，供本地联调与单元测试） */

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
    const sorted = this.store.ledger
      .filter((r) => r.userId === userId)
      // 与 Prisma 相同的排序：(created_at DESC, id DESC)
      .sort((a, b) => (b.createdAt - a.createdAt) || (a.id < b.id ? 1 : a.id > b.id ? -1 : 0))
      .filter((r) => {
        if (!cursor) return true;
        // 复合游标：元组严格小于 (createdAt, id)
        if (r.createdAt !== cursor.createdAt) return r.createdAt < cursor.createdAt;
        return r.id < cursor.id;
      });

    const page = sorted.slice(0, size);
    const last = page[page.length - 1];
    return {
      items: page.map((r) => ({
        id: r.id,
        type: r.type,
        delta: r.delta,
        balanceAfter: r.balanceAfter,
        reservedAfter: r.reservedAfter,
        reason: '',
        createdAt: r.createdAt,
      })),
      nextCursor: sorted.length > size && last ? { createdAt: last.createdAt, id: last.id } : null,
    };
  }

  async pushSyncBatch(input: {
    userId: string;
    batchId: string;
    deviceId: string;
    fingerprint: string;
    mutations: SyncMutationInput[];
  }): Promise<{ replayed: boolean; status: 'applied' | 'partial' | 'rejected'; items: SyncMutationResult[] }> {
    const batchKey = `${input.userId}:${input.batchId}`;
    const cached = this.store.syncBatches.get(batchKey);
    if (cached) {
      if (cached.fingerprint !== input.fingerprint) throw new SyncBatchConflict();
      return { replayed: true, status: cached.status, items: cached.items };
    }

    const items: SyncMutationResult[] = [];
    for (const m of input.mutations) {
      const key = `${input.userId}:${m.objectType}:${m.objectId}`;
      const current = this.store.syncObjects.get(key) ?? null;
      const decided = decideMutation(current ? { version: current.version, deleted: current.deleted } : null, m);
      let serverVersion = decided.serverVersion;
      if (decided.result === 'applied') {
        serverVersion = decided.serverVersion ?? 1;
        this.store.syncSeq += 1;
        this.store.syncObjects.set(key, {
          version: serverVersion,
          deleted: m.op === 'delete',
          payload: m.op === 'delete' ? null : m.payload,
          seq: this.store.syncSeq,
        });
        this.store.syncChanges.push({
          seq: this.store.syncSeq,
          userId: input.userId,
          objectType: m.objectType,
          objectId: m.objectId,
          version: serverVersion,
          deleted: m.op === 'delete',
          payload: m.op === 'delete' ? null : m.payload,
        });
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
    this.store.syncBatches.set(batchKey, { status, items, fingerprint: input.fingerprint });
    return { replayed: false, status, items };
  }

  async pullSyncChanges(userId: string, cursor: number, limit: number): Promise<{
    changes: Array<{ seq: number; objectType: string; objectId: string; version: number; deleted: boolean; payload: unknown }>;
    nextCursor: number;
    hasMore: boolean;
  }> {
    const size = Math.min(Math.max(limit, 1), 200);
    const mine = this.store.syncChanges
      .filter((c) => c.userId === userId && c.seq > cursor)
      .sort((a, b) => a.seq - b.seq);
    const page = mine.slice(0, size);
    return {
      changes: page.map((c) => ({
        seq: c.seq, objectType: c.objectType, objectId: c.objectId,
        version: c.version, deleted: c.deleted, payload: c.payload,
      })),
      nextCursor: page.length ? page[page.length - 1]!.seq : cursor,
      hasMore: mine.length > page.length,
    };
  }

  async hasClaimedDaily(userId: string, businessDate: string, rewardType: string): Promise<boolean> {
    return this.store.claims.has(`${userId}:${businessDate}:${rewardType}`);
  }

  async claimDailyReward(input: {
    userId: string;
    businessDate: string;
    rewardType: string;
    points: number;
    eventId: string;
    reason: string;
  }): Promise<{ claimed: boolean; available: number; reserved: number }> {
    const key = `${input.userId}:${input.businessDate}:${input.rewardType}`;
    const wallet = this.walletOf(input.userId);
    if (this.store.claims.has(key)) {
      return { claimed: false, available: wallet.available, reserved: wallet.reserved };
    }
    this.store.claims.set(key, input.points);
    const next = { available: wallet.available + input.points, reserved: wallet.reserved };
    this.setWallet(input.userId, next);
    this.store.ledger.unshift({
      id: randomUUID(),
      userId: input.userId,
      eventId: input.eventId,
      type: 'award',
      delta: input.points,
      balanceAfter: next.available,
      reservedAfter: next.reserved,
      requestId: null,
      createdAt: this.now(),
    } as never);
    return { claimed: true, available: next.available, reserved: next.reserved };
  }

  get userCount(): number { return this.store.users.size; }
  get sessionCount(): number { return this.store.sessions.size; }
}
