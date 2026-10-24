# PostgreSQL 16 小型演示环境

配置与角色策略见 `doc/Bottledkzk/2026-10-23-数据库与AI网关交接.md`。

- `whaletodo.conf`：约1GiB混用主机的保守起始配置，20总连接与32MiB共享缓存；不代表负载基准。
- `grants.sql`：迁移部署后由管理员设置运行/AI权限；迁移角色管理schema，不给常驻应用DDL。
- `ci-roles.sql`：仅一次性CI数据库角色，内有明显假密码；不能用于实际部署。
- 正式连接串与供应商Key仅服务器私有配置，示例不能填写真实秘密后提交。

管理顺序：先创建非superuser角色与 `whaletodo` schema，使用迁移角色设置 `DATABASE_URL` 并在server运行 `npm run db:deploy`，然后管理员执行grants.sql。备份在迁移前进行；不要在正式库使用 `migrate reset`/`db push`。RLS需事务级 app.user_id。

CI在独立PostgreSQL16容器运行相同迁移和11项集成测试，不调用商业模型。实际备份恢复证据见成员交接；定时器见env/systemd。
