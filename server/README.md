# WhaleToDo Server（Weiding 主责）

后端骨架：账户、签到积分、订单/支付、同步。技术栈按评审结论：**TypeScript + Fastify + Prisma + PostgreSQL**
（数据库 schema 与迁移由 Bottledkzk 统一维护，本目录只提交**迁移建议**与业务实现）。

## 当前状态（2026-10-23）

| 部分 | 状态 |
| --- | --- |
| 统一错误码（9 个） | ✅ 已实现 + 测试 |
| 密码哈希（scrypt，Argon2id 待替换） | ✅ 已实现 + 测试 |
| 不透明令牌（只存 SHA-256 摘要、15min/30d） | ✅ 已实现 + 测试 |
| 积分账本（award/reserve/settle/release/adjust + 幂等） | ✅ 已实现 + 测试（14 项） |
| **账户接口**：`register / login / refresh / logout` | ✅ 已实现 + 测试（含刷新重放整串撤销） |
| **个人资料**：`/me`、`/me/entitlements` | ✅ 已实现 + 测试 |
| 健康检查 `/health/live`、`/health/ready`、`/meta` | ✅ 已实现 + 测试 |
| 内存仓储（本地开发/测试）；Prisma 仓储 | ✅ 内存仓储已完成 / ⏳ Prisma 下一步 |
| Prisma schema 与迁移建议 | ⏳ 下一步（schema 归属 Bottledkzk） |
| PostgreSQL 集成测试 | ⏳ 本机无 Postgres/Docker |
| 签到 `/rewards`、`/wallet`、`/orders`、`/sync` | ⏳ 下一阶段 |

**测试现状：47 项全通过**（security 12 + ledger 14 + http 21）。

## 本地启动（3 条命令）

```bash
cd server
cp .env.example .env       # 无真实秘密，可直接用默认值
npm install                # 当前仅需 fastify（约 85 秒）
node src/main.ts           # 默认 http://127.0.0.1:8080/api/v1
```

启动日志会打印实际的 `api / business_timezone / payment_provider / storage`。
内存仓储用于本地联调，**重启即清空**；接 Prisma 后换成数据库。

## 接口样例（本地实跑，2026-10-23）

| # | 请求 | 状态 | 响应要点 |
| --- | --- | --- | --- |
| 1 | `GET /health/live` | 200 | `{"request_id":"…","data":{"status":"ok"}}` |
| 2 | `POST /auth/register` `{"username":"demo_43046","password":"…"}` | 201 | `data.user{id,username,display_name}` |
| 3 | `POST /auth/login` 同上 | 200 | `access_token`、`refresh_token`、`token_type:"Bearer"`、`expires_in:899`、`refresh_expires_in:2591999` |
| 4 | `GET /me` + `Authorization: Bearer` | 200 | `data.user{id,username,display_name,created_at}` |
| 5 | `GET /me/entitlements` | 200 | `vip{active:false,expires_at:null}`、`balance{available,reserved}` |
| 6 | `POST /auth/refresh` | 200 | 新 `access_token` + 轮换后的 `refresh_token` |
| 7 | 用**旧** refresh 再请求 | 401 | `UNAUTHENTICATED / 刷新令牌已失效，请重新登录`（整串撤销） |
| 8 | 撤销后用新 access 访问 `/me` | 401 | `UNAUTHENTICATED / 访问令牌无效、已撤销或已过期` |
| 9 | 无令牌访问 `/me` | 401 | `UNAUTHENTICATED / 缺少访问令牌` |

约定：所有响应含 `request_id`；错误体为 `{request_id, error:{code,message,details?}}`；
注册支持 `Idempotency-Key`（同键同体返回原结果、同键异体返回 `CONFLICT`）。

## 客户端基址（可配置）

公网 `api.whaletodo.top:8443` 目前由 Nginx 返回 503（后端未部署），因此本阶段客户端基址**必须可配置**：

| 场景 | 基址 |
| --- | --- |
| 本机联调（模拟器访问宿主机） | `http://10.0.2.2:8080/api/v1` |
| 本机联调（真机同局域网） | `http://<本机内网IP>:8080/api/v1` |
| 部署后（待公网 TLS 验证） | `https://api.whaletodo.top:8443/api/v1` |

发布构建**不允许**跳过证书校验，也不允许把访问令牌发往 `http://`（见部署说明第 3 条）。


## 为什么现在就能跑测试

Node 24 原生支持**直接运行 TypeScript**（类型剥离），且 `node --test` 支持 `.ts`。
所以纯逻辑层不依赖任何 npm 包：

```bash
cd server
node --test test/*.test.ts      # 26 项，全过
```

需要第三方依赖（fastify / prisma / argon2）的部分，要等网络可用后：

```bash
npm install        # 本机当前到 registry 只有 8~21 KB/s，建议换网或挂代理
npm run typecheck
```

## 设计要点（与《接口与数据契约草案》和评审结论对齐）

1. **错误码**：`UNAUTHENTICATED / FORBIDDEN / VALIDATION_ERROR / INSUFFICIENT_POINTS / RATE_LIMITED /
   CONFLICT / UPSTREAM_UNAVAILABLE / REQUEST_PENDING / INTERNAL`，响应体 `{request_id, error:{code,message,details?}}`。
2. **密码**：当前用 `scrypt(N=2^15,r=8,p=1)` + 16B 随机盐，编码里带参数便于升级；
   契约要求 Argon2id，等 `argon2` 包可用后替换 `hashPassword/verifyPassword` 实现，接口不变。
3. **令牌**：256bit 随机不透明串，**库里只存 SHA-256 摘要**；access 15 分钟、refresh 30 天；
   刷新时旧 refresh 立即作废、重放触发整串撤销（由会话表实现，下一步接 Prisma）。
4. **账本**：`event_id` 才是幂等键（唯一），`request_id` 只做关联（普通索引）；
   AI 流程 = 鉴权+限额 → **事务预占** → 模型调用 → 成功结算 / 明确失败释放；结果未知保留状态可查询。
   `available` 与 `reserved` 分开且均不得为负；批量记账失败整体抛出，调用方须包在数据库事务里。
5. **时区**：签到业务日由服务端按 `BUSINESS_TIMEZONE`（默认 `Asia/Shanghai`）生成，配置化、不硬编码；
   客户端展示时区跟随手机系统，两者互不影响。
6. **模拟支付**：`PAYMENT_PROVIDER=mock` 仅在 `NODE_ENV=test|demo` 允许；生产启动时检测到 mock 直接拒绝启动。

## 目录

```text
server/
├── src/
│   ├── lib/security.ts      # 错误码 / 密码 / 令牌
│   └── domain/ledger.ts     # 积分账本（纯函数）
├── test/
│   ├── security.test.ts     # 12 项
│   └── ledger.test.ts       # 14 项
├── package.json
└── tsconfig.json
```

## 下一步

1. `src/http/app.ts`：Fastify 实例 + 统一错误处理 + 请求 ID + `Idempotency-Key` 校验。
2. `src/routes/auth.ts`：`register / login / refresh / logout`；`src/routes/me.ts`：`/me`、`/me/entitlements`。
3. `prisma/schema.prisma`（建议稿）：`users`、`auth_sessions`、`daily_claims`、`point_wallets`、`point_ledger`，
   其中账本唯一键为 `(user_id, event_id)`。
4. 集成测试：需要 PostgreSQL（本机待装或用服务器），覆盖错误密码、令牌过期、刷新重放、撤销、A/B 越权。
