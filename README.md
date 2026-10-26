# WhaleToDo

把待办、时间和行动放在一起。

WhaleToDo 是软件工程团队项目：以 Android 应用为主要客户端，配套下载介绍页与后续账户服务。优先把快速记录、直观计划、自动协调、提醒和打卡做成顺手的日常流程，再逐步加入 AI 辅助与自主分享。

## 当前阶段

本分支交付 0.5.2-preview.1 公开签名预览：单事项记录、保护固定安排的排程、日程与预览日历、打卡、本地日记、资料／主题／Working 与时区设置，Room v4。独立包名 `io.whaletodo.app.preview` 与原调试体验包并存；APK、SHA256、签名／源码信息和官网部署 ZIP 已在 [Release](https://github.com/bottledkzk/WhaleToDo/releases/tag/v0.5.2-preview.1) 公开并读回核验，发布流程见 [release](release/README.md)。生产网站仍待连接恢复后切换，详见[本轮交付与限制](doc/Bottledkzk/2026-10-26-签名预览与官网交付.md)。

AI 模式、会话、读取授权、Accept／Refuse／意见重生成、Token 计费和权限策略已有代码与隔离测试。当前 APP 默认网络桥接仍未连接，不能在 APP 内登录、签到或调用在线 AI；生产网关版本升级与客户端闭环分别验收。通知、媒体导出、系统分享、邮件和支付按团队分工接入，不把页面和测试夹具视为在线服务。

2026-10-26 范围调整：保留现有预览日历，取消独立日／周／月／学期切换和长期／多阶段模型。

团队先读 [项目方案书](doc/All/项目方案书.md)、[目标功能文档](doc/All/目标功能文档.md)、[分工与协作方案](doc/All/分工与协作方案.md) 和 [账户、AI积分与安全方案](doc/All/账户-AI积分与安全方案.md)。

## 目标能力

- 快速记录：最少字段创建待办，支持稍后补全、搜索和标签。
- 直观 Todo 分布：清单、日程与现有预览日历，呈现安排和截止。
- 自动协调：在空闲、忙碌、截止和工作量约束下安排，临时插入后保留已完成与固定安排。
- 提醒与打卡：通知、完成、自评和复盘，不把计划当完成。
- AI 辅助：登录并使用服务端积分额度，会话、受控提案、日记与分享短句，确认后才修改记录。
- 自主分享：Android 分享面板、可保存的计划／打卡图片，默认隐藏私人字段。
- 账户和网站：登录注册、账户隔离、同步、签到积分、VIP与简化支付；下载页提供实际版本信息。

完整范围、优先级和验收见 [目标功能文档](doc/All/目标功能文档.md)。

## 仓库结构

```text
WhaleToDo/
├── app/          Android 客户端
├── core/         可独立测试的业务规则
├── web/          下载介绍页
├── doc/
│   ├── All/      团队共用基线，一般通过评审才修改
│   ├── Bottledkzk/  成员面向团队的汇报
│   └── Weiding/     成员面向团队的汇报
├── build/        构建产物说明，生成文件不直接入库
├── env/          可分享的环境配置和示例
└── script/       配置、构建和检查脚本
```

本地 SDK、IDE、设备镜像及个人日志放在仓库外。真实账户凭据、签名库和手机数据不进入公开 Git。

## 协作与答辩

[迭代与验收](doc/All/迭代与验收.md) · [架构与技术栈](doc/All/架构与技术栈.md) · [协作约定](doc/All/协作约定.md) · [答辩准备](doc/All/答辩准备.md)

分工已按项目发起人要求写入共享方案：Bottledkzk侧重核心前端、数据库、AI与网站；Weiding侧重提醒、分享、身份、网络与同步，交叉接口共同确认。按真实完成的阶段提交，不以空提交或重复拆分凑数。部分初始材料与实现可由 AI 协助，提交者仍需理解、验证并能讲述代码。

## 开发与验证

环境与运行方法见 [script/README.md](script/README.md)，固定版本见 [env/toolchain.json](env/toolchain.json)。真实SDK、IDE、镜像和个人日志位于仓库外，不直接提交生成APK或缓存。下载网站源码在 [web](web/README.md)，账户API尚未部署；源码可在团队自己的环境继续运行和部署。

最新外观调整见 [0.4.1图标与列表](doc/Bottledkzk/2026-10-25-图标与列表细节调整.md)，AI接口见 [0.4.0 AI助手与权限接入](doc/Bottledkzk/2026-10-24-AI助手设计与接入.md)，前一轮见 [0.3.0待办整合与手动安排](doc/Bottledkzk/2026-10-23-待办整合与手动安排.md)，前一轮记录见 [今日页面交互优化](doc/Bottledkzk/2026-10-23-今日页面交互优化.md)。现有星期 Working 与时区设置已实现；独立日周月／学期视图和长期／多阶段模型已取消。

## 后端快速开始（账户 / 签到 / 同步）

后端在 `server/`，Node 24 可直接运行 TypeScript，无需编译。

```bash
cd server
npm ci --ignore-scripts                                      # 跳过 prisma postinstall
npx prisma generate --schema prisma/schema.prisma            # 必须执行，CI 同款步骤
npm run typecheck                                            # 期望 0 诊断
npm test                                                     # 单元/接口测试
npm run test:database                                        # 真库集成测试（需 PG）
```

### 启动方式

```bash
PORT=8080 npm start     # 主服务（main 已可用）：账户 / 签到 / 钱包 / 流水
```

> **待合并的功能不要当作 main 现有能力**：
> - `/sync/push`、`/sync/pull` 在 PR #12，尚未进入 main
> - 访问日志的 `LOG_LEVEL` / `LOG_FILE` 在 PR #13，尚未进入 main

AI 网关是**独立进程**，启动前置条件较多，不能直接复制命令运行：需要 systemd
`LoadCredential` 提供的凭据目录（`CREDENTIALS_DIRECTORY`）、走 127.0.0.1 的专用数据库连接、
以及供应商模型 Key；缺任一条件会明确拒绝启动。部署条件与边界见
`doc/Bottledkzk/2026-10-24-AI助手设计与接入.md`。本地内存账户**不能**与 PG 版 AI 网关联调。

| 配置 | 说明 |
| --- | --- |
| `PORT` | 监听端口，默认 8080 |
| `DATABASE_URL` | 未配置时用内存仓储（仅本地演示）；**生产未配置会拒绝启动** |
| `IDEMPOTENCY_SECRET` | 幂等指纹密钥；生产要求 ≥32 字符，缺失拒绝启动 |
| `BUSINESS_TIMEZONE` | 业务日时区，默认 `Asia/Shanghai` |
| `LOG_LEVEL` / `LOG_FILE` | 访问日志级别与输出文件（**待 PR #13 合并**） |
| `PAYMENT_PROVIDER` | `disabled` 时下单/支付接口不暴露 |

### 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | 注册（支持 `Idempotency-Key`） |
| POST | `/api/v1/auth/login` | 登录（限流：认证前预占额度） |
| POST | `/api/v1/auth/refresh` | 刷新（轮换 + 重放检测，整链撤销） |
| POST | `/api/v1/auth/logout` | 退出（撤销当前会话） |
| GET | `/api/v1/me` | 当前账户 |
| GET | `/api/v1/rewards/daily` | 签到状态与余额 |
| POST | `/api/v1/rewards/daily/claim` | 签到发奖（重复领取 409） |
| GET | `/api/v1/wallet` | 钱包 |
| GET | `/api/v1/wallet/ledger` | 积分流水（复合游标分页） |
| POST | `/api/v1/sync/push` | 同步推送（批次幂等 + 版本冲突回传）**待 PR #12** |
| GET | `/api/v1/sync/pull` | 同步拉取（变更序列游标）**待 PR #12** |

统一响应格式 `{request_id, data}` 或 `{request_id, error:{code,message}}`；
错误码固定 9 个，见 `server/docs/api-reference.md`。

### 快速自测（三个请求）

```bash
curl -s -X POST -H 'Content-Type: application/json' \
  --data '{"username":"demo_user","password":"S3cret-passw0rd"}' \
  http://127.0.0.1:8080/api/v1/auth/register

TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' \
  --data '{"username":"demo_user","password":"S3cret-passw0rd"}' \
  http://127.0.0.1:8080/api/v1/auth/login | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/me
```

### 测试与 CI

| 层 | 命令 | 覆盖 |
| --- | --- | --- |
| 单元/接口 | `npm test` | 领域规则、路由契约、幂等、限流 |
| 真库集成 | `npm run test:database` | 注册事务、RLS、并发签到、同毫秒翻页、同步隔离 |
| CI | `.github/workflows/server.yml` | `verify`（typecheck + 单测）与 `database`（PG16 + 受限角色） |

> 真库测试的前置条件（缺一不可，且**会清理数据，不要指向共享库或生产库**）：
> - 独立库 `whaletodo_verify`（脚本校验库名，误连其他库直接抛错）
> - `VERIFY_DATABASE_URL`（迁移角色）、`VERIFY_APP_URL`（受限应用角色）、`VERIFY_AI_URL`
> - 该库已执行 `npm run db:deploy`，且 CI 创建的 `whaletodo_app` 与迁移角色已就位
>
> 角色、授权与迁移步骤以 `.github/workflows/server.yml` 的 `database` job 为准。
