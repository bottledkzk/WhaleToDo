# 官网静态服务配置

`whaletodo.conf` 是本轮部署配置的可分享副本，不含证书内容、登录凭据或机器IP。

- 官网域名：`whaletodo.top`、`www.whaletodo.top`，静态目录`/var/www/whaletodo/current`。
- HTTP为80，HTTPS使用8443；已有443服务保留。当前是否已通过公网HTTPS验证，以最新成员交付记录为准。
- HTTPS8443下仅`/api/v1/ai/*`已转发到回环8081，AI health200、匿名模型接口401；其他API仍503。账户服务待队友完成PG适配，不能把AI健康成功当成登录接通。
- 同时部署`whaletodo-ai-limits.conf`到http配置目录，`whaletodo-ai-proxy.conf`到snippets；模型Key和数据库URL都不在Nginx配置。
- 证书位于服务器的`/etc/letsencrypt/live/whaletodo.top/`，不能复制进仓库。80端口的ACME目录必须保持可访问，证书续期后执行`nginx -t`及平滑重载。
- 本配置仅用于已完成首次部署的专用环境。复制到其他机器前，应确认域名、目录、证书及端口，先`nginx -t`，不得覆盖已有其他站点。
- 程序日志、账户数据、数据库与SSH密钥不能放在静态站点目录，目录列表已关闭。
