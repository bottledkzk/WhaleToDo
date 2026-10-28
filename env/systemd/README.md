# AI服务和备份

账户生产单元为`whaletodo-api.service`，使用独立`whaletodo-api`用户及`api-current`目录，回环8080；私有数据库URL与持久幂等密钥由LoadCredential传入。启动入口固定受限数据库角色、两连接池、生产模式和禁用支付，拒绝源码.env与DEMO变量。`--check`只校验配置，不代替数据库迁移/权限检查。

完整六迁移发布、8443反代与限量验收步骤见[生产发布与在线验收](../../doc/Bottledkzk/2026-10-27-生产发布与在线验收.md)。单元文件入仓库不等于服务已经安装或启用。

AI服务使用独立系统用户、回环8081、160MiB内存上限。运行时固定Node24.15.0，下载应核对官方SHASUMS。

`LoadCredential` 读取root专属文件：DeepSeek Key与AI数据库URL；密钥不作为命令行参数、仓库内容或普通.env。`ai-limits.env.example`只有非秘密限额。不要在日志中输出凭据、Authorization、原始请求/上游响应。

先完成数据库迁移/权限和后端验证，再设置 `/opt/whaletodo/current` 指向经过验证的release。运行目录不可由服务用户写入。Nginx的AI snippet仅用于TLS虚拟主机，不要在HTTP上代理令牌请求。

`whaletodo-backup.timer` 每日03:15 UTC（随机延后至10分钟）运行；备份文件只在服务器0700目录，单文件0600。备份不是异地容灾，课程期间应定期由管理员保存加密异地副本；不要把数据库转存到公共仓库。脚本必须LF换行。

回滚网关：停止新增whaletodo-ai服务并将API虚拟主机恢复为原503占位，nginx -t后reload；不要占用或重启原443服务。数据库已写入后不随代码回滚删表；使用审过的前向迁移和备份恢复流程。

## 数据库运维

backup每日03:15 UTC；restore-check每周日04:00 UTC，恢复到新临时库后清理；maintenance每小时分批清理过期临时认证数据。三个服务从`/opt/whaletodo/database-tools/current/script`运行，需root管理、PG16工具和python3。部署前校验脚本、保留旧版本、先手动成功再enable timer；不假定timer配置已触发。备份恢复失败会有systemd失败状态，尚无自动邮件告警。
