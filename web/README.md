# WhaleToDo 下载页面

原生HTML/CSS/JavaScript，无第三方分析器、账户表单或密钥。官网提供介绍与实际版本下载；页面收集箱只作当页交互，不上传或持久化

2026-10-31公开版本为0.5.4-preview.1。固定版本目录包含APK、SHA256SUMS.txt、release.json、RELEASE-NOTES.md，官网与GitHub备用链接对应同版；历史下载保留。首页不要求用户手工核对SHA，签名和读回校验由发行流程完成，详情仍保存在附件

本地可运行`python -m http.server 4173`。打包使用[发行流程](../release/README.md)，从干净提交读取网页模板并按签名产物渲染版本；仅在相应GitHub Release确已公开后传入`--public-release-tag`

部署到全新版本目录，核对manifest和附件后原子切换官网指针；保留上一版以便回退，不修改已公开附件。下载产物不提交Git

官网为[whaletodo.top](https://whaletodo.top:8443/#download)，HTTPS使用8443；现有443服务保留。网站不承担认证，账户和AI使用自有HTTPS API。状态与边界见[0.5.4验收](../doc/Bottledkzk/2026-10-31-0.5.4交付与验收.md)
