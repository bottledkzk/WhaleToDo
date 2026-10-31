# WhaleToDo

把待办、时间和行动放在一起

WhaleToDo 是 Android 本地优先的待办与日程应用，包含可解释排程、受控 AI 助手、账户资料与手动日程备份、图片分享，以及配套官网和自有服务端

## 当前交付

更新于 2026-10-31。公开版本为 [0.5.4-preview.1](https://github.com/bottledkzk/WhaleToDo/releases/tag/v0.5.4-preview.1)，code11、Room6、minSdk26 / targetSdk36，独立包名 `io.whaletodo.app.preview`。同发行证书覆盖升级保留已有预览数据，原体验包独立保留。[官网下载](https://whaletodo.top:8443/#download)与 GitHub 附件对应同一版本，旧版下载保留

账户 API 与 AI 网关已部署。小米14 Pro Android16上的公开0.5.3签名包完成基础全流程，本版完成同签名升级、四种日记风格入口、真实生成→编辑确认保存→账户资料同步的增量补测；不将增量补测表述为所有流程在所有设备重新验证。详见 [0.5.4交付与验收](doc/Bottledkzk/2026-10-31-0.5.4交付与验收.md)

- 访客可记录、整理、手动与自动安排、查看待办和预览日历、完成与自评，退出账号后访客记录保留
- AI 支持聊天、计划建议、复盘、四风格日记、配文和受控日程编辑；复杂建议经本地排程校验，预览后人工 Accept 才写入
- 个人资料与日记随账户同步，助手会话仅在本地；VIP日程备份和覆盖／兼容同步均由用户主动发起
- 相机／相册头像、PNG卡片与系统分享已接入；QQ实际发送有用户确认，不宣称其他平台专项SDK均通过
- 六档充值全部使用 MOCK，在线收银台确认后持久化订单及积分／VIP；不发生真实扣款
- 独立日周月／学期视图与长期／多阶段模型已取消

## 技术与目录

Kotlin 2.2.21、Compose Material3、ViewModel/StateFlow、Room2.8.3；纯Kotlin core；网络层采用 HttpURLConnection 与 kotlinx.serialization。服务端为 Node24 / TypeScript / Fastify / Prisma / PostgreSQL16，账户API和AI网关分进程。静态官网使用 HTML/CSS/JavaScript，Nginx与systemd负责部署

| 目录 | 责任 |
| --- | --- |
| app | 交互、账户分区、提醒、分享、同步、助手编排 |
| core | 区间、排程、提案约束与领域规则 |
| network | 自有API传输、数据契约与受控错误 |
| server | 账户、签到、钱包、订单、同步、AI与迁移工具 |
| web / release / script | 官网、发行元数据、签名发布与验证脚本 |
| doc/All | 当前共享需求、架构、分工、验收与演示 |
| doc/Bottledkzk / doc/Weiding | 各成员阶段记录，旧日期文件保留历史语义 |

## 分工与演示

Bottledkzk负责整体前端与排程、Agent、数据库与部署、官网发行；按最新授权也负责主动日程同步／资料同步和MOCK支付链路。Weiding负责认证账户、邮件／网络桥接、签到基础、提醒、媒体与ShareStudio；共同维护接口、权益一致性和交叉评审。原始作者与后续集成职责见 [实际分工](doc/All/分工与协作方案.md)

[架构与技术栈](doc/All/架构与技术栈.md) · [目标功能](doc/All/目标功能文档.md) · [验收状态](doc/All/迭代与验收.md) · [演示流程](doc/All/答辩准备.md) · [共享文档入口](doc/All/README.md)

## 开发和检查

Android使用JDK21运行Gradle8.13、Java17字节码，固定依赖见 [toolchain](env/toolchain.json)，操作见 [脚本说明](script/README.md)。SDK、签名库、凭据与设备日志均在仓库外，不通过卸载或清数据处理升级

服务端使用Node24：

```bash
cd server
npm ci --ignore-scripts
npx prisma generate --schema prisma/schema.prisma
npm run typecheck
npm test
```

数据库集成仅在独立验证库执行，前置角色和连接要求见 [server](server/README.md)，禁止连接生产运行测试或TRUNCATE。生产迁移遵守 [数据库标准](doc/All/数据库设计迁移与安全标准.md)。AI凭据仅由服务端LoadCredential提供，客户端不接收用户Key，不绕过自有API

[API参考](server/docs/api-reference.md) · [发行流程](release/README.md) · [网站说明](web/README.md)
