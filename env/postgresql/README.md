# PostgreSQL 16 管理入口

团队标准：[数据库设计迁移与安全标准](../../doc/All/数据库设计迁移与安全标准.md)。

| 文件 | 用途 |
| --- | --- |
| whaletodo.conf | 20总连接、回环、小内存起始配置 |
| grants.sql | 管理员应用显式运行角色权限，新表默认不开放 |
| ci-roles.sql | 仅一次性隔离CI；明显假密码，禁止部署到真实服务 |
| audit.sql | 只读版本/连接/角色/RLS/迁移检查，无账户明细 |
| maintenance.sql | 固定清单、每表1000行的过期清理 |

在server目录：`npm run db:security`、`npm run db:plan -- whaletodo`；实际迁移只在评审、恢复升级演练通过且有30分钟内同库备份后运行 `npm run db:apply -- whaletodo /var/backups/whaletodo/whaletodo-时间-进程号.dump`。DATABASE_URL由受控私有环境提供，不放命令历史/文档；迁移后管理员执行grants.sql，再验受限角色。

完整破坏性集成测试仅对一次性集群运行。Linux装有PG16工具时可由管理员运行`bash script/test-database-isolated.sh`：独立目录/socket/25432端口，退出只停止自己的实例。不能把`test:database`直接对着共享验证库或正式库运行。

备份、恢复、清理分别是script下backup-database.sh、verify-restore.sh、maintain-database.sh；systemd模板在env/systemd。备份恢复只读出生产数据，恢复写入新临时库，维护仅按标准到期清理；恢复脚本没有覆盖正式库入口。
