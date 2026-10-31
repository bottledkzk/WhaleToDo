# WhaleToDo Server

当前服务端为Node24 / TypeScript / Fastify / Prisma / PostgreSQL16。账户认证、签到基础与网络接入由Weiding主责；AI、数据库、主动账户文档同步及当前MOCK支付链路由Bottledkzk主责或承接，详见[实际分工](../doc/All/分工与协作方案.md)

## 当前交付

2026-10-31账户API与AI网关均已部署；公开客户端0.5.4-preview.1。注册／重置使用邮箱验证，密码实际采用异步scrypt与随机盐；不透明访问令牌和刷新令牌只存摘要，轮换／撤销受服务端检查。Prisma/pg持久化仓储、签到钱包、MOCK订单、同步及AI均非仅内存骨架

账户API与AI网关分进程，数据库仅回环。当前十条前向迁移，历史checksum不重写；AI更新无需自动迁移或重启账户API。具体测试和设备范围见[发行验收](../doc/Bottledkzk/2026-10-31-0.5.4交付与验收.md)

## 本地环境

```bash
cd server
npm ci --ignore-scripts
npx prisma generate --schema prisma/schema.prisma
npm run typecheck
npm test
```

按`.env.example`配置开发环境后运行`npm start`。未配置数据库的内存仓储仅用于明确的本地测试，不能代表生产持久化；生产模式缺少必需配置会拒绝启动。不要在1GB生产机器临时npm ci构建，发布包在开发环境冻结，并在服务器复用锁定依赖

AI服务通过systemd LoadCredential读取模型Key和专用数据库凭据，不在APK、网页或日志保存。客户端只使用自有HTTPS基址，不接受任意上游URL或用户导入Key。生产不应复制本地内存默认配置直接启动

## API与一致性

[API参考](docs/api-reference.md)覆盖认证、验证码、签到、钱包、商品订单、同步和AI。私有操作验权并检查对象归属，写入使用幂等键或原请求ID。日程云备份须VIP及明确操作；昵称motto日记资料同步与日程文档分开

AI预占后调用，成功按真实用量结算，明确失败释放；未知结果保留原ID查询。当前累计token计费避免每次请求独立向上取整。MOCK收银台创建真实持久订单并一次发放虚拟积分／VIP，所有账号均无真实资金支付

## 检查与数据库

`npm run test:database`只连接独立验证库，需VERIFY_DATABASE_URL/VERIFY_APP_URL/VERIFY_AI_URL与对应受限角色。集成测试有清理动作，禁止指向共享库或生产库。CI见`.github/workflows/server.yml`，本轮315通过／1跳过为PR61冻结head的离线回归，不替代真机或生产验收

数据库管理与安全见[共享标准](../doc/All/数据库设计迁移与安全标准.md)和[运维入口](../env/postgresql/README.md)。角色最小权限、RLS、参数化SQL、前向迁移、备份与恢复分别核验，不启用未经授权的持续清理
