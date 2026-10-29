# 签名预览版发行

公开预览包独立使用 `io.whaletodo.app.preview`；现有 `io.whaletodo.app` 调试体验包保留。发行 APK 不启用调试，固定证书 SHA256 记录在 `preview-certificate.sha256`。签名库、密码、SDK和设备数据始终在仓库外。证书摘要是公开校验值，不能替代私钥。

1. 从已冻结、已测试的干净提交开始；递增 app/build.gradle.kts 中 versionCode 与版本，增加对应 `release/<版本>.md`。
2. 用 `script/android-env.ps1 -SdkRoot <SDK> -JavaHome <JDK21>` 设置当前进程。密码文件使用 Windows 当前用户 DPAPI 的 `ConvertFrom-SecureString` 格式；签名库和该文件由发行者私下保存，不在 CI 输出或仓库中传递。此格式仅供本机解密，灾备应独立安全保存原签名库和密码。
3. 运行 `script/build-preview.ps1 -Keystore <私有JKS> -PasswordFile <私有DPAPI文件> -OutputDirectory <全新产物目录>`。脚本执行 core/network/app 单元测试、固定缓存构建、签名和证书核对，产出 APK、SHA256SUMS.txt、release.json 与 RELEASE-NOTES.md。
4. 在独立包中安装、创建合成记录，再使用同签名 APK 覆盖升级，检查记录保留。原调试包不卸载、不清数据。另行记录窄屏、手机浏览器下载和双方真机结果。
5. 使用 `python script/package-website.py <产物目录>` 生成带四个发行附件的官网 ZIP 和校验文件；ZIP 包含逐文件 manifest，源码与 APK 提交分别记录。再运行 `script/publish-preview.ps1 -ArtifactDirectory <产物目录>`：先建 GitHub draft，上传四个发行附件及可选官网 ZIP／校验文件，全部下载读回逐个比较哈希后才发布为 prerelease。已有 tag/release 不覆盖，失败 draft 留待检查。
6. 网站按钮固定指向该版本附件；部署版本化静态目录与 APK，比较远端哈希后原子切换网站软链接。公开下载再读回一次核对哈希。未通过发布与读回前，不切换官网按钮。预发布不使用 latest 下载 URL。

修改源码、签名或附件必须形成新版本和新校验值；相同源码的构建过程可重复执行，APK 二进制是否逐字节相同需单独验证，不作可重现构建承诺。`release.json` 记录源码提交、签名、SDK、大小、SHA256 和功能边界。

0.5.3-preview.1候选包含账户/AI桥接、Room6账户分区、PNG导出和验证码/空POST修复。构建不读取生产秘密或探测服务，manifest的production_deployed和email_delivery_integrated为null，状态文字要求查看独立回执；online_acceptance_passed仍为false。网站包按候选元数据渲染版本、code和链接，源网页继续引用已存在的公开版本；候选尚未发布时不切官网或替换旧Release附件。实际生产发布与健康路由见[2026-10-28交接](../doc/Bottledkzk/2026-10-28-生产账户与发行状态交接.md)。

依据：[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[GitHub Release 管理](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)。
