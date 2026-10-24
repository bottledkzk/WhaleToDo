# 数据库 Schema 建议稿（Weiding）

日期：2026-10-23　状态：**建议稿，待 Bottledkzk 评审后合入正式迁移**
对应文件：`server/prisma/schema.prisma`、`server/prisma/migrations/20261023000000_init/migration.sql`

> 归属约定（评审已确认）：**schema 与迁移由 Bottledkzk 统一维护**，本目录只提交建议；
> 合入前请按你们的迁移目录规范调整时间戳前缀与命名。

## 1. 表清单与用途

| 表 | 用途 | 关键约束 |
| --- | --- | --- |
| `users` | 账户主体（最少资料） | `username` 唯一；`password_hash` 只存哈希 |
| `auth_sessions` | 会话与令牌摘要 | 令牌只存 `char(64)` 的 SHA-256；`family_id` 支持整串撤销 |
| `daily_claims` | 签到记录 | **唯一 `(user_id, business_date, reward_type)`**，防并发重复领取 |
| `point_wallets` | 余额（可用／预占） | `CHECK (available >= 0)`、`CHECK (reserved >= 0)` |
| `point_ledger` | 积分流水 | **唯一 `(user_id, event_id)`** 做幂等；`request_id` 仅普通索引 |
| `products` | 套餐（服务端定价） | 金额为最小货币单位整数 |
| `orders` | 订单 | **唯一 `(user_id, idempotency_key)`**；`status`/`environment` 有 CHECK |
| `payment_events` | 支付回调 | **唯一 `(provider, event_id)`**，重复回调不重复发放 |
| `entitlements` | VIP 权益 | 目前只有 `kind=vip` + 到期时间，权限码待定 |
| `sync_batches` | 同步批次回执 | **唯一 `(user_id, batch_id)`** |
| `sync_mutations` | 批次内逐项结果 | 外键指向 `sync_batches`；结果限 `applied/conflict/rejected` |
| `sync_cursors` | 每设备拉取游标 | 主键 `(user_id, device_id)`，`last_seq` 单调 |

## 2. 按评审结论落实的四个点

1. **`request_id` 不唯一**：一个 AI 请求可能产生「预占 + 结算」或多条调整事件，唯一键应为每次账本事件的 `event_id`。SQL 中 `request_id` 只有普通索引。
2. **批次回执与逐项明细分开**：`sync_batches` 只答"这批处理结果"，`sync_mutations` 记录每项 `object_type/object_id/op/server_version/result`，便于回放与排查。
3. **业务日时区配置化**：`daily_claims.business_date` 为 `date`，由服务端按 `BUSINESS_TIMEZONE`（默认 `Asia/Shanghai`）生成，**不存客户端时区**。
4. **`locked` 需要同步保留**：手动安排的锁定状态属于客户端领域对象（`app` 侧 `BlockRow.locked`），同步时由 `payload` 原样带回，服务端不推断。

## 3. 与客户端领域对象的对应（同步范围首版）

| 服务端 `object_type` | 客户端来源 | 说明 |
| --- | --- | --- |
| `task` | `TaskRow` | 含 `done/importance/deadline/minutes/chunkMinutes/splittable` |
| `block` | `BlockRow` | **必须保留 `locked`**；`start/end` 为 epoch 毫秒 |
| `checkin` | `CheckInRow` | `completedAt` + `rating(1..5)` |
| `availability` | 今日 WorkTime | 本地按当地钟表分钟记录，同步需要带时区标识 |

`expected_version` 由服务端维护（每个对象单调递增），客户端提交时带上自己看到的值；不一致返回 `CONFLICT` 与当前版本，不做静默覆盖。

## 4. 需要你确认

1. **PG 版本**（我按 16 写，`timestamptz(3)`、`bigint`、`CHECK` 在 13+ 都可用）
2. **迁移目录与命名**：是否沿用 `prisma/migrations/<timestamp>_init/`，还是你们另有规范
3. **`sync_mutations.object_type` 的 CHECK 清单**：现在锁死 `task/block/checkin/availability`，`goal/stage` 上来时需要改约束（或改为字典表）
4. **`orders.environment`**：`mock` 与 `production` 是否需要分库/分 schema（现在同表不同值）
5. **删除策略**：`users` 级联删除会带走账本与订单。审计要求下是否需要软删除（`status=disabled` + 保留记录）

## 5. 验证方式

不连数据库也能校验结构一致性：

```bash
cd server && node --test test/schema.test.ts   # 14 项
```

断言内容：两边表集合一致、12 张表齐全、外键目标存在、四个关键唯一键、`request_id` 不在任何 UNIQUE 中、
钱包 CHECK、同步表拆分的索引、支付唯一键、无明文密码/令牌列、时间列统一 `timestamptz(3)`。

接 PostgreSQL 后再补集成测试（迁移可重复执行、约束实际生效、并发签到不超发）。
