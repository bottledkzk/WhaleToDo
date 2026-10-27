// Fastify 应用：统一响应包、请求 ID、错误映射、幂等键与账户路由。
// 端点全部挂在 /api/v1 下（与 Nginx 约定的 api.whaletodo.top:8443/api/v1 一致）。
import Fastify, { type FastifyInstance, type FastifyRequest, type FastifyReply } from 'fastify';
import { createHmac, randomBytes, randomUUID } from 'node:crypto';
import { ApiError } from '../lib/security.ts';
import { businessDate } from '../config.ts';
import { parsePushBody, SyncBatchConflict, SyncPayloadTooLarge } from '../domain/sync.ts';
import {
  assertEmail, assertPurpose, CODE_TTL_MS, codeDigest, createTicketToken,
  generateCode, invalidCodeError, TICKET_TTL_MS, ticketDigest,
} from '../domain/email.ts';
import { FixedWindowLimiter } from '../lib/rateLimit.ts';
import { registerScope } from '../lib/idempotency.ts';
import type { AccountRepository, RepoOrder } from '../repo/types.ts';
import { EmailAccountConflict, OrderConflict, RegisterConflict } from '../repo/types.ts';
import {
  environmentForProvider, ORDER_ID_RE, paymentPayloadHash, PRODUCT_ID_RE,
} from '../domain/orders.ts';
import type { ServerConfig } from '../config.ts';

declare module 'fastify' {
  interface FastifyRequest {
    requestId: string;
    userId?: string;
    sessionId?: string;
  }
}

export interface AppDeps {
  config: ServerConfig;
  repo: AccountRepository;
  logger?: boolean;
  /**
   * 测试投递钩子：仅在真正写入验证码（sent:true）时调用。
   * 明文码只交给钩子，不得进入响应、日志或数据库。
   */
  emailCodeSink?: (input: { purpose: string; email: string; code: string }) => void | Promise<void>;
}

const USERNAME_RE = /^[A-Za-z0-9_]{3,32}$/;

function ok(requestId: string, data: unknown) {
  return { request_id: requestId, data };
}

/**
 * 幂等指纹：HMAC-SHA256（进程内随机密钥）。
 * 只存摘要，**不存原始请求体**——否则注册密码会以明文留在幂等缓存里。
 */
/**
 * 幂等指纹：HMAC-SHA256，密钥来自服务端持久配置，并做用途隔离。
 * 只存摘要，不存原始请求体——否则注册密码会以明文留在幂等回执里。
 */
function fingerprintOf(secret: string, body: unknown): string {
  return createHmac('sha256', `${secret}:idempotency-fingerprint`)
    .update(JSON.stringify(body ?? null))
    .digest('hex');
}

/** 订单 HTTP 形状（内部 camelCase → 对外 snake_case） */
function orderJson(o: RepoOrder) {
  return {
    id: o.id,
    product_id: o.productId,
    amount_minor: o.amountMinor,
    currency: o.currency,
    status: o.status,
    environment: o.environment,
    product_snapshot: o.snapshot,
    created_at: new Date(o.createdAt).toISOString(),
    paid_at: o.paidAt === null ? null : new Date(o.paidAt).toISOString(),
  };
}

export function buildApp(deps: AppDeps): FastifyInstance {
  const { config, repo } = deps;

  // 限流：注册按 IP 计次（每次尝试都算）；登录只计失败次数，成功即清零。
  // 单进程内存实现，多实例部署时需换共享存储（见 handoff 说明）。
  const registerLimiter = new FixedWindowLimiter(config.rateLimitMaxAttempts, config.rateLimitWindowMs);
  const loginLimiter = new FixedWindowLimiter(config.rateLimitMaxAttempts, config.rateLimitWindowMs);
  // 不记录请求体；对鉴权头/幂等键/模拟支付令牌脱敏；关闭默认两行日志，改由 onResponse 输出一条。
  const loggerOptions = {
    level: config.logLevel,
    redact: [
      'req.headers.authorization',
      'req.headers.cookie',
      'req.headers["idempotency-key"]',
      'req.headers["x-mock-token"]',
      'res.headers["set-cookie"]',
    ],
  };
  const app = Fastify({
    // 显式传入 logger 用传入的；否则只有在配置了 LOG_FILE 时才开启（测试默认保持静默）
    logger: (deps.logger ?? (config.logFile ? true : false))
      ? { ...loggerOptions, ...(config.logFile ? { file: config.logFile } : {}) }
      : false,
    disableRequestLogging: true,
    trustProxy: config.trustProxy,
    genReqId: () => randomUUID(),
  });

  /* ---------- 请求 ID 与统一错误 ---------- */
  app.decorateRequest('requestId', '');
  app.addHook('onRequest', async (req: FastifyRequest) => {
    req.requestId = String(req.id ?? randomUUID());
    req.raw.headers['x-request-id'] = req.requestId;
  });

  // 每个请求一条访问日志（不含 query，避免把敏感参数落盘）
  app.addHook('onResponse', async (req: FastifyRequest, reply: FastifyReply) => {
    const errCode = (reply as FastifyReply & { errorCode?: string }).errorCode ?? null;
    req.log.info({
      event: 'access',
      request_id: req.requestId,
      method: req.method,
      path: req.url.split('?')[0],
      status: reply.statusCode,
      duration_ms: Math.round(reply.elapsedTime * 10) / 10,
      user_id: req.userId ?? null,
      error_code: errCode,
    }, 'access');
  });

  app.setErrorHandler((err, req: FastifyRequest, reply: FastifyReply) => {
    if (err instanceof ApiError) {
      (reply as FastifyReply & { errorCode?: string }).errorCode = err.code;
      return reply.code(err.status).send(err.toBody(req.requestId));
    }
    const status = (err as { statusCode?: number }).statusCode ?? 500;
    if (status === 400) {
      // 畸形 JSON / 框架层 400 也要带错误码，否则访问日志的 error_code 为空（评审补充）
      (reply as FastifyReply & { errorCode?: string }).errorCode = 'VALIDATION_ERROR';
      return reply.code(400).send(new ApiError('VALIDATION_ERROR', err instanceof Error ? err.message : '请求无效').toBody(req.requestId));
    }
    // 只记受控字段：数据库异常文本会把用户参数（如非法 batch_id）原样带出，
    // 字段路径 redact 管不到自由文本，整段落盘等于绕过脱敏。
    const meta = err as { name?: string; code?: string | number };
    (reply as FastifyReply & { errorCode?: string }).errorCode = 'INTERNAL';
    req.log.error({
      event: 'unhandled_error',
      request_id: req.requestId,
      method: req.method,
      path: req.url.split('?')[0],
      error_name: typeof meta.name === 'string' ? meta.name : 'Error',
      error_code: typeof meta.code === 'string' || typeof meta.code === 'number' ? String(meta.code) : null,
      status,
    }, 'unhandled_error');
    return reply.code(500).send(new ApiError('INTERNAL', '服务器内部错误').toBody(req.requestId));
  });

  app.setNotFoundHandler((req, reply) =>
    reply.code(404).send(new ApiError('VALIDATION_ERROR', `未知端点：${req.method} ${req.url}`).toBody(req.requestId)));

  /* ---------- 鉴权 ---------- */
  async function requireAuth(req: FastifyRequest): Promise<void> {
    const header = req.headers.authorization ?? '';
    const m = /^Bearer\s+(.+)$/.exec(header);
    if (!m) throw new ApiError('UNAUTHENTICATED', '缺少访问令牌');
    const session = await repo.findActiveSessionByAccess(m[1]!.trim());
    if (!session) throw new ApiError('UNAUTHENTICATED', '访问令牌无效、已撤销或已过期');
    req.userId = session.userId;
    req.sessionId = session.id;
  }

  /* ---------- 健康检查（无鉴权） ---------- */
  app.get('/api/v1/health/live', async (req) => ok(req.requestId, { status: 'ok' }));

  app.get('/api/v1/health/ready', async (req) => ok(req.requestId, {
    status: 'ok',
    database: 'memory',                 // 接入 Prisma 后改为真实探测
    payment_provider: config.paymentProvider,
    business_timezone: config.businessTimezone,
  }));

  /* ---------- 注册 ---------- */
  app.post('/api/v1/auth/register', async (req, reply) => {
    const regKey = `ip:${req.ip}`;
    const regLimit = registerLimiter.hit(regKey);
    if (!regLimit.allowed) {
      throw new ApiError('RATE_LIMITED', '注册请求过于频繁，请稍后再试', { retry_after_ms: regLimit.retryAfterMs });
    }
    const body = (req.body ?? {}) as { username?: string; password?: string; display_name?: string };
    const username = String(body.username ?? '').trim();
    const password = String(body.password ?? '');
    if (!USERNAME_RE.test(username)) {
      throw new ApiError('VALIDATION_ERROR', '用户名需为 3~32 位字母、数字或下划线');
    }
    if (password.length < 8 || password.length > 200) {
      throw new ApiError('VALIDATION_ERROR', '密码长度需在 8~200 字符之间');
    }

    // 幂等：同 key 同体重放返回原结果；同 key 不同体返回 CONFLICT
    const idemKey = String(req.headers['idempotency-key'] ?? '').trim();
    // 注册 scope 由服务端按归一化用户名生成（不接受客户端传 scope / IP 散列）
    const scopeKey = idemKey ? registerScope(config.idempotencySecret, username) : '';
    if (scopeKey) {
      const hit = await repo.getIdempotent(scopeKey, idemKey);
      if (hit) {
        if (hit.fingerprint !== fingerprintOf(config.idempotencySecret, { username, password, display_name: body.display_name ?? null })) {
          throw new ApiError('CONFLICT', '相同 Idempotency-Key 的请求体不一致');
        }
        return reply.code(201).send(hit.response);
      }
    }

    // 指纹先算出来：下面的用户名预检查也可能需要拿它比较回执
    const requestFingerprint = fingerprintOf(config.idempotencySecret, { username, password, display_name: body.display_name ?? null });

    if (await repo.findUserByUsername(username)) {
      // 并发同体：赢家已提交时，这里会看到用户名被占用，但要先看回执——
      // 同指纹说明就是同一个注册请求，应返回首次 201，而不是 409。
      if (scopeKey) {
        const existing = await repo.getIdempotent(scopeKey, idemKey);
        if (existing) {
          if (existing.fingerprint !== requestFingerprint) {
            throw new ApiError('CONFLICT', '相同 Idempotency-Key 的请求体不一致');
          }
          return reply.code(201).send(existing.response);
        }
      }
      throw new ApiError('CONFLICT', '用户名已被占用');
    }
    // 有组合方法时用它：用户创建与回执写入在同一事务，避免"建了用户但没写回执"导致重试 409
    let user;
    if (repo.createUserWithReceipt && scopeKey) {
      try {
        user = await repo.createUserWithReceipt({
          username,
          password,
          displayName: body.display_name,
          scope: scopeKey,
          key: idemKey,
          fingerprint: requestFingerprint,
          buildResponse: (u) => ok(req.requestId, {
            user: { id: u.id, username: u.username, display_name: u.displayName },
          }),
        });
      } catch (err) {
        if (!(err instanceof RegisterConflict)) throw err;

        // 无论哪种冲突，都先尝试读取"赢家"回执：并发同体请求应返回首次 201 响应，
        // 而不是 409（评审指出只把 USERNAME_TAKEN 映射 409 仍不满足并发同体重放）。
        // 赢家可能刚提交，故短暂重试几次以消除提交窗口。
        for (let attempt = 0; attempt < 4; attempt += 1) {
          const existing = await repo.getIdempotent(scopeKey, idemKey);
          if (existing) {
            if (existing.fingerprint !== requestFingerprint) {
              throw new ApiError('CONFLICT', '相同 Idempotency-Key 的请求体不一致');
            }
            return reply.code(201).send(existing.response);
          }
          if (attempt < 3) await new Promise((resolve) => setTimeout(resolve, 50));
        }

        // 确认没有回执：这时才是真正的冲突
        if (err.kind === 'USERNAME_TAKEN') throw new ApiError('CONFLICT', '用户名已被占用');
        throw new ApiError('CONFLICT', '同一注册请求正在处理，请稍后重试');
      }
    } else {
      user = await repo.createUser(username, password, body.display_name);
    }
    const response = ok(req.requestId, {
      user: { id: user.id, username: user.username, display_name: user.displayName },
    });
    // 组合方法已在事务内写过回执；这里只给未提供组合方法的实现补写
    if (scopeKey && !repo.createUserWithReceipt) {
      await repo.saveIdempotent(
        scopeKey,
        idemKey,
        fingerprintOf(config.idempotencySecret, { username, password, display_name: body.display_name ?? null }),
        response,
      );
    }
    return reply.code(201).send(response);
  });

  /* ---------- 邮箱注册 ---------- */
  app.post('/api/v1/auth/register/email', async (req, reply) => {
    const regKey = `ip:${req.ip}`;
    const regLimit = registerLimiter.hit(regKey);
    if (!regLimit.allowed) {
      throw new ApiError('RATE_LIMITED', '注册请求过于频繁，请稍后再试', { retry_after_ms: regLimit.retryAfterMs });
    }
    const body = (req.body ?? {}) as {
      email?: string; ticket?: string; password?: string; display_name?: string;
    };
    const email = assertEmail(body.email);
    const ticket = String(body.ticket ?? '').trim();
    const password = String(body.password ?? '');
    if (!ticket) throw new ApiError('VALIDATION_ERROR', 'ticket 不能为空');
    if (password.length < 8 || password.length > 200) {
      throw new ApiError('VALIDATION_ERROR', '密码长度需在 8~200 字符之间');
    }
    if (!repo.createUserWithEmail) throw new ApiError('INTERNAL', '当前仓储未实现邮箱注册');

    try {
      const user = await repo.createUserWithEmail({
        email,
        password,
        displayName: body.display_name,
        ticketDigest: ticketDigest(ticket),
        now: Date.now(),
      });
      return reply.code(201).send(ok(req.requestId, {
        user: { id: user.id, username: user.username, display_name: user.displayName },
      }));
    } catch (err) {
      if (!(err instanceof EmailAccountConflict)) throw err;
      if (err.kind === 'EMAIL_TAKEN') throw new ApiError('CONFLICT', '邮箱已被注册');
      throw new ApiError('VALIDATION_ERROR', '验证码无效或已过期');
    }
  });

  /* ---------- 登录（username 或 email 二选一） ---------- */
  app.post('/api/v1/auth/login', async (req) => {
    const loginKey = `ip:${req.ip}`;
    const body = (req.body ?? {}) as { username?: string; email?: string; password?: string };
    const username = String(body.username ?? '').trim();
    const emailRaw = String(body.email ?? '').trim();
    const password = String(body.password ?? '');
    if (!password) throw new ApiError('VALIDATION_ERROR', '密码不能为空');
    if ((username && emailRaw) || (!username && !emailRaw)) {
      throw new ApiError('VALIDATION_ERROR', 'username 与 email 只能二选一');
    }
    const email = emailRaw ? assertEmail(emailRaw) : '';

    // 先预占额度，再做昂贵认证：超限请求**不会**进入 scrypt 校验，
    // 否则限流只改了响应，猜中密码仍能 200 并清零（评审 P1）。
    const precheck = loginLimiter.hit(loginKey);
    if (!precheck.allowed) {
      throw new ApiError('RATE_LIMITED', '登录失败次数过多，请稍后再试', { retry_after_ms: precheck.retryAfterMs });
    }

    let user;
    if (email) {
      if (!repo.authenticateByEmail) throw new ApiError('INTERNAL', '当前仓储未实现邮箱登录');
      user = await repo.authenticateByEmail(email, password);
    } else {
      user = await repo.authenticate(username, password);
    }
    // 账号不存在与密码错误返回同一个错误，避免枚举账号
    if (!user) throw new ApiError('UNAUTHENTICATED', '用户名或密码错误');

    // 认证成功：清零计数（成功的尝试不占额度）
    loginLimiter.reset(loginKey);

    const { tokens } = await repo.createSession(user.id);
    return ok(req.requestId, {
      access_token: tokens.accessToken,
      refresh_token: tokens.refreshToken,
      token_type: 'Bearer',
      expires_in: Math.floor((tokens.accessExpiresAt - Date.now()) / 1000),
      refresh_expires_in: Math.floor((tokens.refreshExpiresAt - Date.now()) / 1000),
      user: { id: user.id, username: user.username, display_name: user.displayName },
    });
  });

  /* ---------- 邮箱重置密码 ---------- */
  app.post('/api/v1/auth/password/reset', async (req, reply) => {
    const body = (req.body ?? {}) as { email?: string; ticket?: string; password?: string };
    const email = assertEmail(body.email);
    const ticket = String(body.ticket ?? '').trim();
    const password = String(body.password ?? '');
    if (!ticket) throw new ApiError('VALIDATION_ERROR', 'ticket 不能为空');
    if (password.length < 8 || password.length > 200) {
      throw new ApiError('VALIDATION_ERROR', '密码长度需在 8~200 字符之间');
    }
    if (!repo.resetPasswordWithTicket) throw new ApiError('INTERNAL', '当前仓储未实现密码重置');

    const result = await repo.resetPasswordWithTicket({
      email,
      password,
      ticketDigest: ticketDigest(ticket),
      now: Date.now(),
    });
    if (!result.ok) throw new ApiError('VALIDATION_ERROR', '验证码无效或已过期');
    // 重置不泄露账号是否存在：ticket 合法但邮箱无账号也返回 204
    return reply.code(204).send();
  });

  /* ---------- 刷新（轮换 + 重放整串撤销） ---------- */
  app.post('/api/v1/auth/refresh', async (req) => {
    const body = (req.body ?? {}) as { refresh_token?: string };
    const token = String(body.refresh_token ?? '').trim();
    if (!token) throw new ApiError('VALIDATION_ERROR', 'refresh_token 不能为空');

    const session = await repo.findSessionByRefresh(token);
    if (!session) throw new ApiError('UNAUTHENTICATED', '刷新令牌无效');

    if (session.revokedAt !== null) {
      // 已轮换过的 refresh 被再次使用 → 判定为泄漏，整串撤销
      await repo.revokeFamily(session.familyId);
      throw new ApiError('UNAUTHENTICATED', '刷新令牌已失效，请重新登录');
    }
    if (session.refreshExpiresAt <= Date.now()) {
      await repo.revokeSession(session.id);
      throw new ApiError('UNAUTHENTICATED', '刷新令牌已过期，请重新登录');
    }

    // 轮换：优先用仓储的原子实现（撤销旧 + 建新在同一事务，Prisma 提供）；
    // 内存实现没有该方法时退化为两步，仅用于本地联调。
    let tokens: { accessToken: string; refreshToken: string; accessExpiresAt: number };
    if (repo.rotateSession) {
      try {
        const rotated = await repo.rotateSession(session.id, session.userId, session.familyId);
        tokens = rotated.tokens;
      } catch (err) {
        // 并发刷新：旧会话已被另一个请求消费。按既定重放规则**整链撤销**并返回 401，
        // 不能让外层统一处理器变成 500（评审 P1）。其他数据库故障继续向上抛。
        if (err instanceof Error && err.message === 'SESSION_ALREADY_ROTATED') {
          await repo.revokeFamily(session.familyId);
          throw new ApiError('UNAUTHENTICATED', '刷新令牌已失效，请重新登录');
        }
        throw err;
      }
    } else {
      await repo.revokeSession(session.id);
      const created = await repo.createSession(session.userId, session.familyId);
      tokens = created.tokens;
    }
    return ok(req.requestId, {
      access_token: tokens.accessToken,
      refresh_token: tokens.refreshToken,
      token_type: 'Bearer',
      expires_in: Math.floor((tokens.accessExpiresAt - Date.now()) / 1000),
    });
  });

  /* ---------- 退出 ---------- */
  app.post('/api/v1/auth/logout', async (req, reply) => {
    await requireAuth(req);
    await repo.revokeSession(req.sessionId!);
    return reply.code(204).send();
  });

  /* ---------- 邮箱验证码（注册/重置/绑定前置） ---------- */
  app.post('/api/v1/auth/email/send-code', async (req, reply) => {
    const body = (req.body ?? {}) as { purpose?: string; email?: string };
    const purpose = assertPurpose(body.purpose ?? 'register');
    const email = assertEmail(body.email);
    if (!repo.sendEmailCode) throw new ApiError('INTERNAL', '当前仓储未实现邮箱验证码');

    const code = generateCode();
    const digest = codeDigest(config.idempotencySecret, purpose, email, code);
    const result = await repo.sendEmailCode({
      purpose,
      email,
      codeDigest: digest,
      now: Date.now(),
      ttlMs: CODE_TTL_MS,
      ip: req.ip,
      scopeSecret: config.idempotencySecret,
    });
    // 只有真正落库（sent:true）才投递；限流时明文码不交给任何外部钩子
    if (result.sent && deps.emailCodeSink) {
      await deps.emailCodeSink({ purpose, email, code });
    }
    // 一律 202：是否命中限流、邮箱是否已注册都不对外暴露；sent 取仓储真实结果
    return reply.code(202).send(ok(req.requestId, { sent: result.sent, retry_after_ms: result.retryAfterMs }));
  });

  app.post('/api/v1/auth/email/verify', async (req) => {
    const body = (req.body ?? {}) as { purpose?: string; email?: string; code?: string };
    const purpose = assertPurpose(body.purpose ?? 'register');
    const email = assertEmail(body.email);
    const code = String(body.code ?? '').trim();
    if (!/^\d{6}$/.test(code)) throw invalidCodeError();
    if (!repo.verifyEmailCodeAndCreateTicket) throw new ApiError('INTERNAL', '当前仓储未实现邮箱验证码原子校验');

    const digest = codeDigest(config.idempotencySecret, purpose, email, code);
    const ticket = createTicketToken();
    const now = Date.now();
    // code 消费与 ticket 写入在同一事务；失败整体回滚，不会出现“码已用、ticket 没有”。
    const result = await repo.verifyEmailCodeAndCreateTicket({
      purpose,
      email,
      codeDigest: digest,
      ticketDigest: ticket.digest,
      now,
      ticketTtlMs: TICKET_TTL_MS,
    });
    // 错误/过期/次数用尽统一同一个 400，不暴露细节
    if (!result.ok) throw invalidCodeError();

    return ok(req.requestId, { ticket: ticket.token, expires_in: Math.floor(TICKET_TTL_MS / 1000) });
  });

  /* ---------- 个人资料 ---------- */
  app.get('/api/v1/me', async (req) => {
    await requireAuth(req);
    const user = await repo.findUserById(req.userId!);
    if (!user) throw new ApiError('UNAUTHENTICATED', '账号不存在');
    return ok(req.requestId, {
      user: { id: user.id, username: user.username, display_name: user.displayName, created_at: new Date(user.createdAt).toISOString() },
    });
  });

  /* ---------- 绑定邮箱（登录态 + bind ticket） ---------- */
  app.post('/api/v1/me/email/bind', async (req, reply) => {
    await requireAuth(req);
    const body = (req.body ?? {}) as { email?: string; ticket?: string };
    const email = assertEmail(body.email);
    const ticket = String(body.ticket ?? '').trim();
    if (!ticket) throw new ApiError('VALIDATION_ERROR', 'ticket 不能为空');
    if (!repo.bindEmailWithTicket) throw new ApiError('INTERNAL', '当前仓储未实现邮箱绑定');

    const result = await repo.bindEmailWithTicket({
      userId: req.userId!,
      email,
      ticketDigest: ticketDigest(ticket),
      now: Date.now(),
    });
    if (result === 'ticket_invalid') throw new ApiError('VALIDATION_ERROR', '验证码无效或已过期');
    if (result === 'email_taken') throw new ApiError('CONFLICT', '邮箱已被注册');
    return reply.code(204).send();
  });

  /* ---------- 权益（VIP 从 entitlements 读真实到期时间） ---------- */
  app.get('/api/v1/me/entitlements', async (req) => {
    await requireAuth(req);
    const wallet = await repo.walletOf(req.userId!);
    const expiresAt = repo.vipExpiresAt ? await repo.vipExpiresAt(req.userId!) : null;
    return ok(req.requestId, {
      vip: expiresAt === null
        ? { active: false, expires_at: null }
        : { active: true, expires_at: new Date(expiresAt).toISOString() },
      balance: { available: wallet.available, reserved: wallet.reserved },
      daily_quota: { claimed_today: false, points: 0, reward_points: 0 },
    });
  });

  /* ---------- 商品与订单 ---------- */
  app.get('/api/v1/products', async (req) => {
    if (!repo.listProducts) throw new ApiError('INTERNAL', '当前仓储未实现商品列表');
    const products = await repo.listProducts();
    return ok(req.requestId, {
      products: products.map((p) => ({
        product_id: p.productId,
        title: p.title,
        price_minor: p.priceMinor,
        currency: p.currency,
        vip_days: p.vipDays,
        points: p.points,
      })),
    });
  });

  app.post('/api/v1/orders', async (req, reply) => {
    await requireAuth(req);
    const environment = environmentForProvider(config.paymentProvider);
    if (environment === null) throw new ApiError('UPSTREAM_UNAVAILABLE', '支付未启用');
    if (!repo.createOrder) throw new ApiError('INTERNAL', '当前仓储未实现下单');

    const body = (req.body ?? {}) as { product_id?: string };
    const productId = String(body.product_id ?? '').trim();
    if (!PRODUCT_ID_RE.test(productId)) {
      throw new ApiError('VALIDATION_ERROR', 'product_id 不合法');
    }

    const idemKey = String(req.headers['idempotency-key'] ?? '').trim();
    const scope = `v1:order:${req.userId!}`;
    const fingerprint = fingerprintOf(config.idempotencySecret, { product_id: productId });
    if (idemKey) {
      const hit = await repo.getIdempotent(scope, idemKey);
      if (hit) {
        if (hit.fingerprint !== fingerprint) {
          throw new ApiError('CONFLICT', '相同 Idempotency-Key 的请求体不一致');
        }
        return reply.code(200).send(hit.response);
      }
    }

    let order: RepoOrder;
    try {
      order = await repo.createOrder({
        userId: req.userId!,
        productId,
        environment,
        ...(idemKey
          ? {
              receipt: {
                scope,
                key: idemKey,
                fingerprint,
                buildResponse: (o: RepoOrder) => ok(req.requestId, { order: orderJson(o) }),
              },
            }
          : {}),
      });
    } catch (err) {
      if (!(err instanceof OrderConflict)) throw err;
      if (err.kind === 'PRODUCT_NOT_FOUND' || err.kind === 'PRODUCT_INACTIVE') {
        throw new ApiError('VALIDATION_ERROR', '商品不存在或已下架');
      }
      if (err.kind === 'IDEMPOTENT_REPLAY') {
        for (let attempt = 0; attempt < 4; attempt += 1) {
          const existing = await repo.getIdempotent(scope, idemKey);
          if (existing) {
            if (existing.fingerprint !== fingerprint) {
              throw new ApiError('CONFLICT', '相同 Idempotency-Key 的请求体不一致');
            }
            return reply.code(200).send(existing.response);
          }
          if (attempt < 3) await new Promise((resolve) => setTimeout(resolve, 50));
        }
      }
      throw new ApiError('CONFLICT', '同一请求正在处理，请稍后重试');
    }

    return reply.code(201).send(ok(req.requestId, { order: orderJson(order) }));
  });

  app.get('/api/v1/orders/:id', async (req) => {
    await requireAuth(req);
    if (!repo.getOrder) throw new ApiError('INTERNAL', '当前仓储未实现订单查询');
    const id = String((req.params as { id?: string }).id ?? '');
    // 非法 uuid、不存在、非本人统一 403，避免枚举
    if (!ORDER_ID_RE.test(id)) throw new ApiError('FORBIDDEN', '订单不存在');
    const order = await repo.getOrder(req.userId!, id);
    if (!order) throw new ApiError('FORBIDDEN', '订单不存在');
    return ok(req.requestId, { order: orderJson(order) });
  });

  if (config.paymentProvider === 'mock') {
    app.post('/api/v1/payments/mock/webhook', async (req) => {
      await requireAuth(req);
      if (!repo.applyPayment) throw new ApiError('INTERNAL', '当前仓储未实现支付回调');

      const body = (req.body ?? {}) as {
        event_id?: string; order_id?: string; status?: string;
        amount_minor?: number; currency?: string;
      };
      const eventId = String(body.event_id ?? '').trim();
      const orderId = String(body.order_id ?? '').trim();
      const status = String(body.status ?? '');
      const amountMinor = Number(body.amount_minor);
      const currency = String(body.currency ?? '').trim().toUpperCase();
      if (!eventId || eventId.length > 64 || !ORDER_ID_RE.test(orderId)
          || status !== 'succeeded' || !Number.isInteger(amountMinor) || amountMinor < 0
          || !/^[A-Z]{3}$/.test(currency)) {
        throw new ApiError('VALIDATION_ERROR', '支付回调参数不合法');
      }

      const outcome = await repo.applyPayment({
        userId: req.userId!,
        provider: 'mock',
        eventId,
        orderId,
        amountMinor,
        currency,
        payloadHash: paymentPayloadHash(body),
        now: Date.now(),
      });

      if (outcome === 'applied' || outcome === 'replay') {
        return ok(req.requestId, { ok: true, replay: outcome === 'replay' });
      }
      if (outcome === 'amount_mismatch') {
        throw new ApiError('VALIDATION_ERROR', '金额或币种与订单不一致');
      }
      if (outcome === 'not_found') {
        throw new ApiError('VALIDATION_ERROR', '订单不存在');
      }
      if (outcome === 'environment_mismatch') {
        throw new ApiError('VALIDATION_ERROR', '订单环境不匹配');
      }
      if (outcome === 'event_conflict') {
        throw new ApiError('CONFLICT', '支付事件已被其他订单使用');
      }
      throw new ApiError('CONFLICT', '订单状态不允许本次支付');
    });
  }

  /* ---------- 签到与积分（发奖走真实 daily_claims + point_ledger） ---------- */

  app.get('/api/v1/rewards/daily', async (req) => {
    await requireAuth(req);
    const date = businessDate(new Date(), config.businessTimezone);
    const wallet = await repo.walletOf(req.userId!);

    if (repo.dailyStatus) {
      const st = await repo.dailyStatus({
        userId: req.userId!, businessDate: date, rewardType: 'sign_in',
      });
      return ok(req.requestId, {
        business_date: date,
        claimed: st.claimed,
        consecutive_days: st.consecutiveDays,
        cycle_day: st.cycleDay,
        points_today: st.pointsToday,
        next_points: st.nextPoints,
        reward: { reward_type: 'sign_in', daily_points: 1, cycle_bonus_points: 5, cycle_days: 7 },
        balance: { available: wallet.available, reserved: wallet.reserved },
      });
    }

    const claimed = repo.hasClaimedDaily
      ? await repo.hasClaimedDaily(req.userId!, date, 'sign_in')
      : false;
    return ok(req.requestId, {
      business_date: date,
      claimed,
      reward: { reward_type: 'sign_in', daily_points: 1, cycle_bonus_points: 5, cycle_days: 7 },
      balance: { available: wallet.available, reserved: wallet.reserved },
    });
  });

  app.post('/api/v1/rewards/daily/claim', async (req, reply) => {
    await requireAuth(req);
    const date = businessDate(new Date(), config.businessTimezone);
    const eventId = `${req.userId}:${date}:sign_in`;

    if (repo.claimDailyReward) {
      const result = await repo.claimDailyReward({
        userId: req.userId!,
        businessDate: date,
        rewardType: 'sign_in',
        eventId,
        reason: `sign-in:${date}`,
      });
      if (!result.claimed) {
        throw new ApiError('CONFLICT', '今日已领取签到奖励', { business_date: date });
      }
      return reply.code(201).send(ok(req.requestId, {
        business_date: date,
        points_awarded: result.pointsAwarded,
        consecutive_days: result.consecutiveDays,
        cycle_day: result.cycleDay,
        wallet: { available: result.available, reserved: result.reserved },
      }));
    }

    // 没提供原子实现的仓储（理论上不该出现）：明确报错，避免静默不发奖
    throw new ApiError('INTERNAL', '当前仓储未实现签到发奖');
  });

  app.get('/api/v1/wallet', async (req) => {
    await requireAuth(req);
    const w = await repo.walletOf(req.userId!);
    return ok(req.requestId, { available: w.available, reserved: w.reserved, total: w.available + w.reserved });
  });

  app.get('/api/v1/wallet/ledger', async (req) => {
    await requireAuth(req);
    if (!repo.listLedger) throw new ApiError('INTERNAL', '当前仓储未实现流水查询');
    const q = (req.query ?? {}) as { limit?: string; cursor?: string };

    // limit：只接受纯数字文本，避免 parseInt 的部分解析（如 "10abc"）
    if (q.limit !== undefined && !/^\d{1,3}$/.test(q.limit)) {
      throw new ApiError('VALIDATION_ERROR', 'limit 需为 1~100 的整数');
    }
    const limit = q.limit === undefined ? 50 : Number(q.limit);
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
      throw new ApiError('VALIDATION_ERROR', 'limit 需为 1~100 的整数');
    }

    // 游标格式 "<毫秒时间戳>:<uuid>"：必须恰好两段、毫秒是纯数字且为安全整数、
    // 能构造出合法 Date，uuid 段合法。任何一项不满足 → 400，绝不进 SQL
    // （评审：999999999999999999999 会通过 isFinite 但产生 Invalid Date → 500）。
    const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    let cursor: { createdAt: number; id: string } | undefined;
    if (q.cursor !== undefined) {
      const parts = q.cursor.split(':');
      if (parts.length !== 2) throw new ApiError('VALIDATION_ERROR', 'cursor 格式非法');
      const [msPart, idPart] = parts as [string, string];
      if (!/^\d{1,16}$/.test(msPart)) throw new ApiError('VALIDATION_ERROR', 'cursor 格式非法');
      const ms = Number(msPart);
      if (!Number.isSafeInteger(ms) || ms < 0) throw new ApiError('VALIDATION_ERROR', 'cursor 格式非法');
      const asDate = new Date(ms);
      if (Number.isNaN(asDate.getTime())) throw new ApiError('VALIDATION_ERROR', 'cursor 时间超出范围');
      if (!UUID_RE.test(idPart)) throw new ApiError('VALIDATION_ERROR', 'cursor 格式非法');
      cursor = { createdAt: ms, id: idPart };
    }

    const page = await repo.listLedger(req.userId!, limit, cursor);
    return ok(req.requestId, {
      items: page.items.map((r) => ({
        id: r.id,
        type: r.type,
        delta: r.delta,
        balance_after: r.balanceAfter,
        reserved_after: r.reservedAfter,
        reason: r.reason,
        created_at: new Date(r.createdAt).toISOString(),
      })),
      next_cursor: page.nextCursor ? `${page.nextCursor.createdAt}:${page.nextCursor.id}` : null,
    });
  });

  /* ---------- 同步（五种对象类型，批次幂等 + 版本冲突） ---------- */
  app.post('/api/v1/sync/push', async (req) => {
    await requireAuth(req);
    if (!repo.pushSyncBatch) throw new ApiError('INTERNAL', '当前仓储未实现同步');
    const { batchId, deviceId, mutations } = parsePushBody(req.body);
    const fingerprint = fingerprintOf(config.idempotencySecret, mutations);
    let result;
    try {
      result = await repo.pushSyncBatch({
        userId: req.userId!, batchId, deviceId, fingerprint, mutations,
      });
    } catch (err) {
      if (err instanceof SyncBatchConflict) {
        throw new ApiError('CONFLICT', '相同 batch_id 的请求内容不一致');
      }
      if (err instanceof SyncPayloadTooLarge) {
        // 只给稳定的 400 文案，不回显数据库约束/异常原文
        throw new ApiError('VALIDATION_ERROR', 'payload 超过大小上限');
      }
      throw err;
    }
    return ok(req.requestId, {
      batch_id: batchId,
      status: result.status,
      replayed: result.replayed,
      items: result.items,
    });
  });

  app.get('/api/v1/sync/pull', async (req) => {
    await requireAuth(req);
    if (!repo.pullSyncChanges) throw new ApiError('INTERNAL', '当前仓储未实现同步');
    const q = (req.query ?? {}) as { cursor?: string; limit?: string };
    if (q.cursor !== undefined && !/^\d{1,16}$/.test(q.cursor)) {
      throw new ApiError('VALIDATION_ERROR', 'cursor 非法');
    }
    if (q.limit !== undefined && !/^\d{1,3}$/.test(q.limit)) {
      throw new ApiError('VALIDATION_ERROR', 'limit 非法');
    }
    const cursor = q.cursor === undefined ? 0 : Number(q.cursor);
    const limit = q.limit === undefined ? 100 : Number(q.limit);
    const page = await repo.pullSyncChanges(req.userId!, cursor, limit);
    return ok(req.requestId, {
      changes: page.changes.map((c) => ({
        object_type: c.objectType,
        object_id: c.objectId,
        version: c.version,
        deleted: c.deleted,
        payload: c.payload,
      })),
      next_cursor: page.nextCursor,
      has_more: page.hasMore,
    });
  });

  app.get('/api/v1/meta', async (req) => ok(req.requestId, {
    api_version: 'v1',
    base_url: config.publicBaseUrl,
    payment_provider: config.paymentProvider,
    note: '后端尚未部署到公网，此端点仅用于本地联调与客户端基址自检',
  }));

  return app;
}
