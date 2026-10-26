# WhaleToDo API 参考（v1）

本地基址 `http://127.0.0.1:8080/api/v1`；部署后 `https://api.whaletodo.top:8443/api/v1`（当前仅 `/ai/*` 已上线，账户路径返回 503）。
客户端基址**必须可配置**，发布构建不允许明文 HTTP、不允许跳过证书校验。

## 统一约定

| 项 | 规则 |
| --- | --- |
| 响应包 | 成功 `{ request_id, data }`；失败 `{ request_id, error: { code, message, details? } }` |
| 鉴权 | `Authorization: Bearer <access_token>`，access 15 分钟、refresh 30 天 |
| 幂等 | 写操作带 `Idempotency-Key`；同键同体返回首次结果，同键异体 `CONFLICT` |
| 时间 | UTC ISO8601（`2026-10-25T02:19:59.000Z`）；业务日单独用 `YYYY-MM-DD` |
| 错误码 | `UNAUTHENTICATED` 401 / `FORBIDDEN` 403 / `VALIDATION_ERROR` 400 / `INSUFFICIENT_POINTS` 402 / `RATE_LIMITED` 429 / `CONFLICT` 409 / `UPSTREAM_UNAVAILABLE` 503 / `REQUEST_PENDING` 202 / `INTERNAL` 500 |

## 1. 健康与元信息（无需鉴权）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health/live` | 存活：`{status:"ok"}` |
| GET | `/health/ready` | 就绪：含 `database`、`payment_provider`、`business_timezone` |
| GET | `/meta` | 自描述：`api_version`、`base_url`、`payment_provider` |

## 2. 账户

| 方法 | 路径 | 请求 | 成功 |
| --- | --- | --- | --- |
| POST | `/auth/register` | `{username, password, display_name?}` + 可选 `Idempotency-Key` | **201** `{user:{id,username,display_name}}` |
| POST | `/auth/login` | `{username, password}` | **200** `{access_token, refresh_token, token_type:"Bearer", expires_in, refresh_expires_in, user}` |
| POST | `/auth/refresh` | `{refresh_token}` | **200** 新令牌对（旧 refresh 立即失效） |
| POST | `/auth/logout` | 空（需 Bearer） | **204** |
| GET | `/me` | 需 Bearer | **200** `{user:{id,username,display_name,created_at}}` |
| GET | `/me/entitlements` | 需 Bearer | **200** `{vip:{active,expires_at}, balance, daily_quota}` |

**默认昵称**：不传 `display_name`（或传 `Whale-1234` 这类占位名）时，服务端生成
`Whale-<users.id 末 4 位>`；客户端自带的编号会被服务端后缀覆盖，普通自定义昵称只作展示值保存。
**登录失败**：账号不存在与密码错误统一 `401 UNAUTHENTICATED`（不区分，避免枚举）。
**刷新重放**：已用过的 refresh 再次使用 → `401`，且同 `family_id` 全部会话作废（整串撤销）。
**限流**：注册按 IP、登录按失败次数；超限 `429 RATE_LIMITED`，`details.retry_after_ms` 给出等待时间；登录成功清零。

## 3. 签到与积分（需 Bearer）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/rewards/daily` | 业务日（服务端时区）、是否已领、奖励值、当前余额 |
| POST | `/rewards/daily/claim` | 领取；首次 **201** `{points_awarded, wallet}`；当日重复 **409 CONFLICT** |
| GET | `/wallet` | `{available, reserved, total}` |
| GET | `/wallet/ledger?limit=&cursor=` | 流水：`{items:[{type,delta,balance_after,reserved_after,reason,created_at}], next_cursor}` |

业务日由服务端按 `BUSINESS_TIMEZONE`（默认 `Asia/Shanghai`）生成，**不信任客户端时区**。

## 4. 商品与订单（需 Bearer）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/products` | 服务端定价：`{product_id,title,price_minor,currency,vip_days,points}` |
| POST | `/orders` | `{product_id}` + `Idempotency-Key`；**201** 建单，同键重放 200 返回同一订单 |
| GET | `/orders/{id}` | 归属校验；越权与不存在统一 403（防枚举） |

**金额与币种只取服务端商品快照**，客户端传的 `amount_minor` 会被忽略。

## 5. 模拟支付回调（仅 demo/test）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/payments/mock/webhook` | 头 `X-Mock-Token`；体 `{event_id, order_id, status, amount_minor?, currency?}` |

`(provider, event_id)` 幂等，重复回调返回 `duplicated:true` 且不重复发权益；金额不符 → `400` 且订单保持 `PENDING`。
`PAYMENT_PROVIDER=mock` 只允许 `NODE_ENV=test|demo`，生产配置 mock 会**拒绝启动**。

## 6. 同步

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/sync/push` | `{batch_id, device_id, mutations:[{object_type,object_id,op,expected_version?,payload?}]}` |
| GET | `/sync/pull?cursor=&limit=` | `{changes:[{object_type,object_id,version,deleted,payload}], next_cursor, has_more}` |

- 对象类型首版：`task` / `block` / `checkin` / `availability`（服务端 schema 另有 `settings`）
- `expected_version` 是**服务端对象版本**，落后即 `conflict` 并回传 `server_version`，不静默覆盖
- `locked`（手动安排锁定）在 payload 中原样往返，服务端不推断
- 批次幂等：同 `batch_id` 重放返回首次回执，不重复应用

## 7. 错误示例

```json
{
  "request_id": "9e66231f-e4ba-48d6-b565-b56b0de930d6",
  "error": { "code": "INSUFFICIENT_POINTS", "message": "可用积分不足", "details": { "available": 3, "need": 10 } }
}
```

## 8. 客户端接入要点

1. 基址来自 `BuildConfig`，debug 可覆盖、release 锁死 https
2. 401 时用 refresh 换一次新令牌并**重试一次**；刷新失败立即清本地令牌（不拿废令牌反复请求）
3. 令牌存 Android Keystore 加密，不写明文 SharedPreferences
4. 写请求必须带 `Idempotency-Key`（注册、领奖、下单、同步批次）
5. 本地联调：模拟器 `http://10.0.2.2:8080/api/v1`，真机 `http://<内网IP>:8080/api/v1`

## 9. 尚未实现

`/ai/*` 由 Bottledkzk 的独立网关（8081）提供，不在本服务内；商品/价格/签到奖励数值仍为占位，待产品定稿。
