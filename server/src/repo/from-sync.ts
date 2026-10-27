// 把同步的内存仓储适配成异步接口，供契约测试使用。
// 说明：内存实现内部保持同步（简单、无 IO）；Prisma 实现将直接实现 AccountRepository。
// 两者跑同一套契约测试，换持久化时路由层不改。
import type { AccountRepository, RepoUser, RepoSession } from './types.ts';
import type { MemoryRepository } from './memory.ts';

function toRepoUser(u: { id: string; username: string; displayName: string; createdAt: number }): RepoUser {
  return { id: u.id, username: u.username, displayName: u.displayName, createdAt: u.createdAt };
}

function toRepoSession(s: RepoSession): RepoSession {
  return s;   // 内存仓储的会话对象已满足接口（不含明文令牌）
}

export function fromSync(repo: MemoryRepository): AccountRepository {
  return {
    findUserByUsername: async (username) => {
      const u = repo.findUserByUsername(username);
      return u ? toRepoUser(u) : null;
    },
    findUserById: async (id) => {
      const u = repo.findUserById(id);
      return u ? toRepoUser(u) : null;
    },
    createUser: async (username, password, displayName) => toRepoUser(await repo.createUser(username, password, displayName)),
    authenticate: async (username, password) => {
      const u = await repo.authenticate(username, password);
      return u ? toRepoUser(u) : null;
    },
    createSession: async (userId, familyId) => {
      const { session, tokens } = repo.createSession(userId, familyId);
      return { session: toRepoSession(session), tokens };
    },
    findActiveSessionByAccess: async (token) => {
      const s = repo.findActiveSessionByAccess(token);
      return s ? toRepoSession(s) : null;
    },
    findSessionByRefresh: async (token) => {
      const s = repo.findSessionByRefresh(token);
      return s ? toRepoSession(s) : null;
    },
    revokeSession: async (id) => { repo.revokeSession(id); },
    revokeFamily: async (familyId) => repo.revokeFamily(familyId),
    walletOf: async (userId) => repo.walletOf(userId),
    setWallet: async (userId, wallet) => { repo.setWallet(userId, wallet); },
    // 同步：默认路径（无 DATABASE_URL）返回 fromSync(new MemoryRepository())，
    // 这里漏转发会让合法 push/pull 直接 500（#12 评审 P1）
    pushSyncBatch: async (input) => repo.pushSyncBatch(input),
    pullSyncChanges: async (userId, cursor, limit) => repo.pullSyncChanges(userId, cursor, limit),
    // 签到/流水：内存实现有同名方法，直接转发（Prisma 实现则用自己的）
    dailyStatus: async (input) => repo.dailyStatus(input),
    hasClaimedDaily: async (userId, businessDate, rewardType) => repo.hasClaimedDaily(userId, businessDate, rewardType),
    claimDailyReward: async (input) => repo.claimDailyReward(input),
    listLedger: async (userId, limit, beforeCreatedAt) => repo.listLedger(userId, limit, beforeCreatedAt),
    // 邮箱验证码：默认路径也走内存实现，漏转发会让两条路由 500
    sendEmailCode: async (input) => repo.sendEmailCode(input),
    verifyEmailCode: async (input) => repo.verifyEmailCode(input),
    verifyEmailCodeAndCreateTicket: async (input) => repo.verifyEmailCodeAndCreateTicket(input),
    createEmailTicket: async (input) => repo.createEmailTicket(input),
    consumeEmailTicket: async (input) => repo.consumeEmailTicket(input),
    // 邮箱账户：默认内存路径，漏转发会让注册/登录/重置/绑定 500
    findUserByEmail: async (email) => {
      const u = repo.findUserByEmail(email);
      return u ? toRepoUser(u) : null;
    },
    createUserWithEmail: async (input) => toRepoUser(await repo.createUserWithEmail(input)),
    authenticateByEmail: async (email, password) => {
      const u = await repo.authenticateByEmail(email, password);
      return u ? toRepoUser(u) : null;
    },
    resetPasswordWithTicket: async (input) => repo.resetPasswordWithTicket(input),
    bindEmailWithTicket: async (input) => repo.bindEmailWithTicket(input),
    // 订单/支付：默认内存路径；漏转发会让 /products /orders /webhook 500
    listProducts: async () => repo.listProducts(),
    createOrder: async (input) => repo.createOrder(input),
    getOrder: async (userId, orderId) => repo.getOrder(userId, orderId),
    applyPayment: async (input) => repo.applyPayment(input),
    vipExpiresAt: async (userId) => repo.vipExpiresAt(userId),
    getIdempotent: async (scope, key) => repo.getIdempotent(scope, key),
    saveIdempotent: async (scope, key, fingerprint, response) => { repo.saveIdempotent(scope, key, fingerprint, response); },
  };
}
