// 仓储接口：内存实现与 Prisma 实现都遵守同一份契约。
// 目的：把"换持久化"变成纯替换，路由层不改；契约测试（test/repository-contract.test.ts）
// 对任何实现跑同一套断言。
import type { Wallet } from '../domain/ledger.ts';
import type { SyncMutationInput, SyncMutationResult } from '../domain/sync.ts';

export interface RepoUser {
  id: string;
  username: string;
  displayName: string;
  createdAt: number;
}

export interface RepoSession {
  id: string;
  userId: string;
  accessHash: string;
  refreshHash: string;
  accessExpiresAt: number;
  refreshExpiresAt: number;
  familyId: string;
  revokedAt: number | null;
  createdAt: number;
}

export interface IdempotencyHit {
  fingerprint: string;
  response: unknown;
}

export interface CreatedSession {
  session: RepoSession;
  tokens: {
    accessToken: string;
    refreshToken: string;
    accessHash: string;
    refreshHash: string;
    accessExpiresAt: number;
    refreshExpiresAt: number;
  };
}

/**
 * 账户相关的最小契约。
 * 事务边界（Prisma 实现必须遵守）：
 *  - `createUser`：插入 users + 初始化 point_wallets，单事务
 *  - `createSession` / `revokeSession` / `revokeFamily`：会话表写操作，单事务
 *  - 刷新轮换 = revokeSession(旧) + createSession(新, 同一 family) 必须在**同一事务**内，
 *    否则中途失败会出现"旧令牌已废、新令牌没发"的锁死状态
 */
export interface AccountRepository {
  findUserByUsername(username: string): Promise<RepoUser | null>;
  findUserById(id: string): Promise<RepoUser | null>;
  createUser(username: string, password: string, displayName?: string): Promise<RepoUser>;
  /**
   * 注册 + 回执**同一事务**：避免"用户已建、回执没写"时客户端重试拿到 409。
   * 提供该方法的仓储由路由优先调用；未提供的实现退回 createUser + saveIdempotent。
   */
  createUserWithReceipt?(input: {
    username: string;
    password: string;
    displayName?: string;
    scope: string;
    key: string;
    fingerprint: string;
    /** 用新建用户构造首次响应体；在事务内保存，保证回执存的是真实响应而非占位 */
    buildResponse: (user: RepoUser) => unknown;
  }): Promise<RepoUser>;
  authenticate(username: string, password: string): Promise<RepoUser | null>;

  createSession(userId: string, familyId?: string): Promise<CreatedSession>;
  /**
   * 原子轮换：撤销旧会话 + 创建同 family 新会话，必须在同一事务内完成。
   * Prisma 实现提供该方法；内存实现不提供时，路由退化为"先撤销后创建"。
   */
  rotateSession?(oldSessionId: string, userId: string, familyId: string): Promise<CreatedSession>;
  findActiveSessionByAccess(accessToken: string): Promise<RepoSession | null>;
  findSessionByRefresh(refreshToken: string): Promise<RepoSession | null>;
  revokeSession(id: string): Promise<void>;
  revokeFamily(familyId: string): Promise<number>;

  walletOf(userId: string): Promise<Wallet>;
  /**
   * 签到发奖（单事务）：插 daily_claims（唯一键兜并发）→ 加钱包余额 → 写 point_ledger。
   * 返回 claimed=false 表示今天已领过（不重复发奖、不写流水）。
   */
  /** 查询某业务日是否已领取（供 GET /rewards/daily 展示用） */
  hasClaimedDaily?(userId: string, businessDate: string, rewardType: string): Promise<boolean>;
  /** 积分流水：按 (created_at, id) 倒序的稳定复合游标 */
  listLedger?(userId: string, limit: number, cursor?: { createdAt: number; id: string }): Promise<{
    items: Array<{
      id: string;
      type: string;
      delta: number;
      balanceAfter: number;
      reservedAfter: number;
      reason: string;
      createdAt: number;
    }>;
    nextCursor: { createdAt: number; id: string } | null;
  }>;
  /** 同步：推送一批变更（事务内 set_config + 批次幂等 + 逐项明细 + 变更序列） */
  pushSyncBatch?(input: {
    userId: string;
    batchId: string;
    deviceId: string;
    fingerprint: string;
    mutations: SyncMutationInput[];
  }): Promise<{
    replayed: boolean;
    status: 'applied' | 'partial' | 'rejected';
    items: SyncMutationResult[];
  }>;
  /** 同步：按变更序列拉取（游标为 seq） */
  pullSyncChanges?(userId: string, cursor: number, limit: number): Promise<{
    changes: Array<{
      seq: number;
      objectType: string;
      objectId: string;
      version: number;
      deleted: boolean;
      payload: unknown;
    }>;
    nextCursor: number;
    hasMore: boolean;
  }>;
  claimDailyReward?(input: {
    userId: string;
    businessDate: string;
    rewardType: string;
    points: number;
    eventId: string;
    reason: string;
  }): Promise<{ claimed: boolean; available: number; reserved: number }>;
  setWallet(userId: string, wallet: Wallet): Promise<void>;

  getIdempotent(scope: string, key: string): Promise<IdempotencyHit | undefined>;
  saveIdempotent(scope: string, key: string, fingerprint: string, response: unknown): Promise<void>;
}

/** 注册事务中的可识别冲突：由路由映射成 201(重放) / 409，不变成 500 */
export class RegisterConflict extends Error {
  readonly kind: 'IDEMPOTENT_REPLAY' | 'IDEMPOTENCY_RACE' | 'USERNAME_TAKEN';
  constructor(kind: 'IDEMPOTENT_REPLAY' | 'IDEMPOTENCY_RACE' | 'USERNAME_TAKEN') {
    super(kind);
    this.name = 'RegisterConflict';
    this.kind = kind;
  }
}
