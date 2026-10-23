# 官网静态服务配置

`whaletodo.conf` 是本轮部署配置的可分享副本，不含证书内容、登录凭据或机器IP。

- 官网域名：`whaletodo.top`、`www.whaletodo.top`，静态目录`/var/www/whaletodo/current`。
- HTTP为80，HTTPS使用8443；已有443服务保留。当前是否已通过公网HTTPS验证，以最新成员交付记录为准。
- `api.whaletodo.top`目前返回503 `SERVICE_NOT_READY`，不伪装为登录／AI服务。后续后端只绑定本机端口，再通过网关转发`/api/v1/`。
- 证书位于服务器的`/etc/letsencrypt/live/whaletodo.top/`，不能复制进仓库。80端口的ACME目录必须保持可访问，证书续期后执行`nginx -t`及平滑重载。
- 本配置仅用于已完成首次部署的专用环境。复制到其他机器前，应确认域名、目录、证书及端口，先`nginx -t`，不得覆盖已有其他站点。
- 程序日志、账户数据、数据库与SSH密钥不能放在静态站点目录，目录列表已关闭。
