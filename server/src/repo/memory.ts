// 内存仓储：用于本地开发与集成测试（无数据库依赖）。
// Prisma 仓储在下一阶段接入，接口保持一致，便于替换。
import { cycleDay, pointsFor, streakBefore } from '../domain/rewards.ts';
import { randomUUID, randomBytes } from 'node:crypto';
import { hashPasswordAsync, verifyPasswordAsync, issueTokens, hashToken } from '../lib/security.ts';
import { digestEquals, MAX_ATTEMPTS, MAX_SENDS_PER_HOUR, RESEND_INTERVAL_MS } from '../domain/email.ts';
import { amountsMatch, snapshotOf, vipExpiryAfter } from '../domain/orders.ts';
import { resolveDisplayName } from '../domain/profile.ts';
import type { Wallet } from '../domain/ledger.ts';
import type { RepoOrder, RepoProduct, RepoUser } from './types.ts';
import { EmailAccountConflict, OrderConflict } from './types.ts';
import { decideMutation, batchStatus, SyncBatchConflict } from '../domain/sync.ts';
import type { SyncMutationInput, SyncMutationResult } from '../domain/sync.ts';

export interface User {
  id: string;
  username: string;
  email: string | null;
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

export interface EmailCodeRecord {
  purpose: string;
  email: string;
  codeDigest: string;
  attempts: number;
  expiresAt: number;
  consumedAt: number | null;
  createdAt: number;
}

export interface EmailTicketRecord {
  purpose: string;
  email: string;
  expiresAt: number;
  consumedAt: number | null;
  createdAt: number;
}

export interface EntitlementRecord {
  id: string;
  userId: string;
  kind: string;
  expiresAt: number;
  sourceOrderId: string | null;
  createdAt: number;
}

export interface MemoryStore {
  users: Map<string, User>;
  usernameIndex: Map<string, string>;
  /** 规范化邮箱（lower）-> userId；无邮箱用户不入表 */
  emailIndex: Map<string, string>;
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
  /** 邮箱验证码：key=`${purpose}:${email}`，同邮箱同用途只保留最新一条 */
  emailCodes: Map<string, EmailCodeRecord>;
  /** 邮箱限流窗口：key=`${scope}:${scopeKey}`，记录当前小时窗口与计数 */
  emailSends: Map<string, { windowStart: number; count: number }>;
  /** 邮箱一次性 ticket：key=token 摘要 */
  emailTickets: Map<string, EmailTicketRecord>;
  /** 商品（测试可直接塞） */
  products: Map<string, RepoProduct>;
  /** 订单：id -> RepoOrder */
  orders: Map<string, RepoOrder>;
  /** 支付回调：key=`${provider}:${eventId}` */
  paymentEvents: Map<string, { provider: string; eventId: string; orderId: string }>;
  /** VIP 权益行（保留每次发放，供叠加与审计） */
  entitlements: EntitlementRecord[];
}

export function createMemoryStore(): MemoryStore {
  return {
    users: new Map(),
    usernameIndex: new Map(),
    emailIndex: new Map(),
    sessions: new Map(),
    wallets: new Map(),
    idempotency: new Map(),
    claims: new Map(),
    ledger: [],
    syncObjects: new Map(),
    syncChanges: [],
    syncBatches: new Map(),
    syncSeq: 0,
    emailCodes: new Map(),
    emailSends: new Map(),
    emailTickets: new Map(),
    products: new Map(),
    orders: new Map(),
    paymentEvents: new Map(),
    entitlements: [],
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

  findUserByEmail(email: string): User | null {
    const id = this.store.emailIndex.get(email.trim().toLowerCase());
    return id ? this.store.users.get(id) ?? null : null;
  }

  async createUser(username: string, password: string, displayName?: string): Promise<User> {
    const id = randomUUID();
    const user: User = {
      id,
      username,
      email: null,
      passwordHash: await hashPasswordAsync(password),
      // 默认昵称后缀只认服务端刚分配的 id；客户端昵称仅作展示值
      displayName: resolveDisplayName(id, username, displayName),
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

  /* ---------------- 邮箱账户（内存实现） ---------------- */

  async createUserWithEmail(input: {
    email: string;
    password: string;
    displayName?: string;
    ticketDigest: string;
    now: number;
  }): Promise<User> {
    const email = input.email.trim().toLowerCase();
    // 内存实现是单线程，不需要 advisory lock；先查占用再消费，
    // 与 Prisma 的“锁 + 拿锁后重查”语义一致。
    const ticket = this.store.emailTickets.get(input.ticketDigest);
    // 先判 ticket 有效性（不消费）：同 ticket 二次注册应返回 TICKET_INVALID，
    // 而不是先看到邮箱已存在
    if (!ticket
        || ticket.consumedAt !== null
        || ticket.expiresAt <= input.now
        || ticket.purpose !== 'register'
        || ticket.email !== email) {
      throw new EmailAccountConflict('TICKET_INVALID');
    }
    // 邮箱占用时不消费 ticket（与 Prisma 事务回滚结果一致）
    if (this.findUserByEmail(email)) throw new EmailAccountConflict('EMAIL_TAKEN');
    if (!(await this.consumeEmailTicket({
      ticketDigest: input.ticketDigest, purpose: 'register', email, now: input.now,
    }))) {
      throw new EmailAccountConflict('TICKET_INVALID');
    }

    let username = '';
    do {
      username = `whale_${randomBytes(6).toString('hex')}`;
    } while (this.store.usernameIndex.has(username.toLowerCase()));

    const id = randomUUID();
    const user: User = {
      id,
      username,
      email,
      passwordHash: await hashPasswordAsync(input.password),
      displayName: resolveDisplayName(id, username, input.displayName),
      createdAt: input.now,
    };
    this.store.users.set(id, user);
    this.store.usernameIndex.set(username.toLowerCase(), id);
    this.store.emailIndex.set(email, id);
    this.store.wallets.set(id, { available: 0, reserved: 0 });
    return user;
  }

  async authenticateByEmail(email: string, password: string): Promise<User | null> {
    const user = this.findUserByEmail(email);
    if (!user) {
      await hashPasswordAsync('dummy-password-for-timing');
      return null;
    }
    return (await verifyPasswordAsync(password, user.passwordHash)) ? user : null;
  }

  async resetPasswordWithTicket(input: {
    email: string;
    password: string;
    ticketDigest: string;
    now: number;
  }): Promise<{ ok: boolean }> {
    const email = input.email.trim().toLowerCase();
    const consumed = await this.consumeEmailTicket({
      ticketDigest: input.ticketDigest, purpose: 'reset', email, now: input.now,
    });
    if (!consumed) return { ok: false };

    const user = this.findUserByEmail(email);
    if (!user) return { ok: true };

    user.passwordHash = await hashPasswordAsync(input.password);
    for (const session of this.store.sessions.values()) {
      if (session.userId === user.id && session.revokedAt === null) session.revokedAt = input.now;
    }
    return { ok: true };
  }

  async bindEmailWithTicket(input: {
    userId: string;
    email: string;
    ticketDigest: string;
    now: number;
  }): Promise<'ok' | 'ticket_invalid' | 'email_taken'> {
    const email = input.email.trim().toLowerCase();
    const owner = this.findUserByEmail(email);
    // 已被别人占用：不消费 ticket（与 Prisma 事务回滚结果一致）
    if (owner && owner.id !== input.userId) return 'email_taken';

    const consumed = await this.consumeEmailTicket({
      ticketDigest: input.ticketDigest, purpose: 'bind', email, now: input.now,
    });
    if (!consumed) return 'ticket_invalid';

    const user = this.store.users.get(input.userId);
    if (!user) return 'ticket_invalid';
    if (user.email) this.store.emailIndex.delete(user.email);
    user.email = email;
    this.store.emailIndex.set(email, user.id);
    return 'ok';
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
    const claimedDates = new Set<string>();
    let pointsToday: number | null = null;
    for (const [key, points] of this.store.claims.entries()) {
      const [uid, date, type] = key.split(':');
      if (uid !== input.userId || type !== input.rewardType || !date) continue;
      claimedDates.add(date);
      if (date === input.businessDate) pointsToday = points;
    }
    const claimed = claimedDates.has(input.businessDate);
    const prevStreak = streakBefore(claimedDates, input.businessDate);
    const consecutiveDays = claimed ? prevStreak + 1 : prevStreak;
    const shownDays = claimed ? consecutiveDays : prevStreak + 1;
    return {
      claimed,
      consecutiveDays,
      cycleDay: cycleDay(Math.max(shownDays, 1)),
      pointsToday: claimed ? pointsToday : null,
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
    const wallet = this.walletOf(input.userId);
    const claimedDates = new Set<string>();
    for (const key of this.store.claims.keys()) {
      const [uid, date, type] = key.split(':');
      if (uid === input.userId && type === input.rewardType && date) claimedDates.add(date);
    }

    const prevStreak = streakBefore(claimedDates, input.businessDate);
    const key = `${input.userId}:${input.businessDate}:${input.rewardType}`;
    if (claimedDates.has(input.businessDate)) {
      return {
        claimed: false,
        pointsAwarded: 0,
        consecutiveDays: prevStreak,
        cycleDay: cycleDay(Math.max(prevStreak, 1)),
        available: wallet.available,
        reserved: wallet.reserved,
      };
    }

    const consecutiveDays = prevStreak + 1;
    const points = pointsFor(consecutiveDays);
    this.store.claims.set(key, points);
    const next = { available: wallet.available + points, reserved: wallet.reserved };
    this.setWallet(input.userId, next);
    this.store.ledger.unshift({
      id: randomUUID(),
      userId: input.userId,
      eventId: input.eventId,
      type: 'award',
      delta: points,
      balanceAfter: next.available,
      reservedAfter: next.reserved,
      requestId: null,
      createdAt: this.now(),
    } as never);

    return {
      claimed: true,
      pointsAwarded: points,
      consecutiveDays,
      cycleDay: cycleDay(consecutiveDays),
      available: next.available,
      reserved: next.reserved,
    };
  }

  /* ---------------- 商品与订单（内存实现） ---------------- */

  async listProducts(): Promise<RepoProduct[]> {
    return [...this.store.products.values()]
      .filter((p) => p.active)
      .sort((a, b) => a.productId.localeCompare(b.productId));
  }

  async createOrder(input: {
    userId: string;
    productId: string;
    environment: 'mock' | 'production';
    receipt?: {
      scope: string;
      key: string;
      fingerprint: string;
      buildResponse: (order: RepoOrder) => unknown;
    };
  }): Promise<RepoOrder> {
    if (input.receipt) {
      const hit = this.getIdempotent(input.receipt.scope, input.receipt.key);
      if (hit) throw new OrderConflict('IDEMPOTENT_REPLAY');
    }
    const product = this.store.products.get(input.productId);
    if (!product) throw new OrderConflict('PRODUCT_NOT_FOUND');
    if (!product.active) throw new OrderConflict('PRODUCT_INACTIVE');

    const order: RepoOrder = {
      id: randomUUID(),
      userId: input.userId,
      productId: product.productId,
      amountMinor: product.priceMinor,
      currency: product.currency,
      status: 'PENDING',
      environment: input.environment,
      snapshot: snapshotOf(product),
      createdAt: this.now(),
      paidAt: null,
    };
    this.store.orders.set(order.id, order);

    if (input.receipt) {
      this.saveIdempotent(
        input.receipt.scope,
        input.receipt.key,
        input.receipt.fingerprint,
        input.receipt.buildResponse(order),
      );
    }
    return order;
  }

  async getOrder(userId: string, orderId: string): Promise<RepoOrder | null> {
    const order = this.store.orders.get(orderId);
    return order && order.userId === userId ? order : null;
  }

  async applyPayment(input: {
    userId: string;
    provider: 'mock';
    eventId: string;
    orderId: string;
    amountMinor: number;
    currency: string;
    payloadHash: string;
    now: number;
  }): Promise<'applied' | 'replay' | 'not_found' | 'amount_mismatch' | 'state_conflict' | 'environment_mismatch' | 'event_conflict'> {
    const order = this.store.orders.get(input.orderId);
    if (!order || order.userId !== input.userId) return 'not_found';
    if (order.environment !== 'mock') return 'environment_mismatch';
    if (!amountsMatch(
      { amount_minor: order.amountMinor, currency: order.currency },
      { amount_minor: input.amountMinor, currency: input.currency },
    )) return 'amount_mismatch';

    // 先查 event 归属：同单 replay，异单 event_conflict；不能覆盖已有映射
    const eventKey = `${input.provider}:${input.eventId}`;
    const existingEvent = this.store.paymentEvents.get(eventKey);
    if (existingEvent) {
      return existingEvent.orderId === order.id ? 'replay' : 'event_conflict';
    }
    if (order.status === 'PAID') return 'state_conflict';
    if (order.status !== 'PENDING') return 'state_conflict';

    order.status = 'PAID';
    order.paidAt = input.now;
    this.store.paymentEvents.set(eventKey, {
      provider: input.provider, eventId: input.eventId, orderId: order.id,
    });

    const points = order.snapshot.points;
    if (points > 0) {
      const wallet = this.walletOf(order.userId);
      const next = { available: wallet.available + points, reserved: wallet.reserved };
      this.setWallet(order.userId, next);
      this.store.ledger.unshift({
        id: randomUUID(),
        userId: order.userId,
        eventId: `order:${order.id}`,
        type: 'award',
        delta: points,
        balanceAfter: next.available,
        reservedAfter: next.reserved,
        requestId: null,
        createdAt: input.now,
      });
    }

    const vipDays = order.snapshot.vip_days;
    if (vipDays > 0) {
      const current = this.vipExpiryAt(order.userId);
      this.store.entitlements.push({
        id: randomUUID(),
        userId: order.userId,
        kind: 'vip',
        expiresAt: vipExpiryAfter(current, input.now, vipDays),
        sourceOrderId: order.id,
        createdAt: input.now,
      });
    }
    return 'applied';
  }

  private vipExpiryAt(userId: string): number | null {
    let max: number | null = null;
    for (const e of this.store.entitlements) {
      if (e.userId === userId && e.kind === 'vip' && e.expiresAt > this.now()) {
        max = Math.max(max ?? 0, e.expiresAt);
      }
    }
    return max;
  }

  async vipExpiresAt(userId: string): Promise<number | null> {
    return this.vipExpiryAt(userId);
  }

  /* ---------------- 邮箱验证码（内存实现） ---------------- */

  async sendEmailCode(input: {
    purpose: string;
    email: string;
    codeDigest: string;
    now: number;
    ttlMs: number;
    ip?: string;
    scopeSecret?: string;
  }): Promise<{ sent: boolean; retryAfterMs: number }> {
    const key = `${input.purpose}:${input.email}`;
    const existing = this.store.emailCodes.get(key) ?? null;
    const windowStart = Math.floor(input.now / 3_600_000) * 3_600_000;
    const bump = (scope: string, scopeKey: string): number => {
      const mapKey = `${scope}:${scopeKey}`;
      const row = this.store.emailSends.get(mapKey);
      if (!row || row.windowStart !== windowStart) {
        this.store.emailSends.set(mapKey, { windowStart, count: 1 });
        return 1;
      }
      row.count += 1;
      return row.count;
    };

    // 先判 60 秒冷却：被拒请求直接返回，不写 emailSends（不占小时配额）。
    // 冷却按该邮箱所有 purpose 的最新 createdAt，register/reset/bind 共享冷却。
    let latest: EmailCodeRecord | null = null;
    for (const record of this.store.emailCodes.values()) {
      if (record.email === input.email && (!latest || record.createdAt > latest.createdAt)) {
        latest = record;
      }
    }
    if (latest) {
      const elapsed = input.now - latest.createdAt;
      if (elapsed < RESEND_INTERVAL_MS) {
        return { sent: false, retryAfterMs: RESEND_INTERVAL_MS - elapsed };
      }
    }

    // 通过冷却后再计数（email / ip / global）
    const emailCount = bump('email', input.email);
    const ipCount = input.ip ? bump('ip', input.ip) : 0;
    bump('global', 'all');

    if (emailCount > MAX_SENDS_PER_HOUR || ipCount > MAX_SENDS_PER_HOUR) {
      return { sent: false, retryAfterMs: windowStart + 3_600_000 - input.now };
    }

    if (existing) existing.consumedAt = input.now;
    this.store.emailCodes.set(key, {
      purpose: input.purpose,
      email: input.email,
      codeDigest: input.codeDigest,
      attempts: 0,
      expiresAt: input.now + input.ttlMs,
      consumedAt: null,
      createdAt: input.now,
    });
    return { sent: true, retryAfterMs: 0 };
  }

  async verifyEmailCode(input: {
    purpose: string;
    email: string;
    codeDigest: string;
    now: number;
  }): Promise<{ ok: boolean; attemptsLeft: number }> {
    const key = `${input.purpose}:${input.email}`;
    const record = this.store.emailCodes.get(key);
    if (!record || record.consumedAt !== null || record.expiresAt <= input.now) {
      return { ok: false, attemptsLeft: 0 };
    }
    if (digestEquals(record.codeDigest, input.codeDigest)) {
      record.consumedAt = input.now;
      return { ok: true, attemptsLeft: 0 };
    }

    record.attempts = Math.min(record.attempts + 1, MAX_ATTEMPTS);
    if (record.attempts >= MAX_ATTEMPTS) {
      record.consumedAt = input.now;
      return { ok: false, attemptsLeft: 0 };
    }
    return { ok: false, attemptsLeft: MAX_ATTEMPTS - record.attempts };
  }

  async verifyEmailCodeAndCreateTicket(input: {
    purpose: string;
    email: string;
    codeDigest: string;
    ticketDigest: string;
    now: number;
    ticketTtlMs: number;
  }): Promise<{ ok: boolean; attemptsLeft: number }> {
    // 单线程等价事务：ticket 摘要冲突必须在消费 code 之前失败。
    if (this.store.emailTickets.has(input.ticketDigest)) throw new Error('邮箱 ticket 摘要重复');
    const result = await this.verifyEmailCode(input);
    if (result.ok) {
      this.store.emailTickets.set(input.ticketDigest, {
        purpose: input.purpose,
        email: input.email,
        expiresAt: input.now + input.ticketTtlMs,
        consumedAt: null,
        createdAt: input.now,
      });
    }
    return result;
  }

  async createEmailTicket(input: {
    purpose: string;
    email: string;
    ticketDigest: string;
    now: number;
    ttlMs: number;
  }): Promise<void> {
    if (this.store.emailTickets.has(input.ticketDigest)) throw new Error('邮箱 ticket 摘要重复');
    this.store.emailTickets.set(input.ticketDigest, {
      purpose: input.purpose,
      email: input.email,
      expiresAt: input.now + input.ttlMs,
      consumedAt: null,
      createdAt: input.now,
    });
  }

  async consumeEmailTicket(input: {
    ticketDigest: string;
    purpose: string;
    email: string;
    now: number;
  }): Promise<boolean> {
    const ticket = this.store.emailTickets.get(input.ticketDigest);
    if (!ticket
        || ticket.consumedAt !== null
        || ticket.expiresAt <= input.now
        || ticket.purpose !== input.purpose
        || ticket.email !== input.email.trim().toLowerCase()) {
      return false;
    }
    ticket.consumedAt = input.now;
    return true;
  }

  get userCount(): number { return this.store.users.size; }
  get sessionCount(): number { return this.store.sessions.size; }
}
