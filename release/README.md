# 签名预览版发行

公开预览包独立使用 `io.whaletodo.app.preview`；现有 `io.whaletodo.app` 调试体验包保留。发行 APK 不启用调试，固定证书 SHA256 记录在 `preview-certificate.sha256`。签名库、密码、SDK和设备数据始终在仓库外。证书摘要是公开校验值，不能替代私钥。

1. 从已冻结、已测试的干净提交开始；递增 app/build.gradle.kts 中 versionCode 与版本，增加对应 `release/<版本>.md`。
2. 用 `script/android-env.ps1 -SdkRoot <SDK> -JavaHome <JDK21>` 设置当前进程。密码文件使用 Windows 当前用户 DPAPI 的 `ConvertFrom-SecureString` 格式；签名库和该文件由发行者私下保存，不在 CI 输出或仓库中传递。此格式仅供本机解密，灾备应独立安全保存原签名库和密码。
3. 运行 `script/build-preview.ps1 -Keystore <私有JKS> -PasswordFile <私有DPAPI文件> -OutputDirectory <全新产物目录>`。脚本执行 core/network/app 单元测试、固定缓存构建、签名和证书核对，产出 APK、SHA256SUMS.txt、release.json 与 RELEASE-NOTES.md。
4. 在独立包中安装、创建合成记录，再使用同签名 APK 覆盖升级，检查记录保留。原调试包不卸载、不清数据。另行记录窄屏、手机浏览器下载和双方真机结果。
5. 使用 `python script/package-website.py <产物目录>` 生成带四个发行附件的官网 ZIP 和校验文件；ZIP 包含逐文件 manifest，源码与 APK 提交分别记录。再运行 `script/publish-preview.ps1 -ArtifactDirectory <产物目录>`：先建 GitHub draft，上传四个发行附件及可选官网 ZIP／校验文件，全部下载读回逐个比较哈希后才发布为 prerelease。已有 tag/release 不覆盖，失败 draft 留待检查。
6. 网站按钮固定指向该版本附件；部署版本化静态目录与 APK，比较远端哈希后原子切换网站软链接。公开下载再读回一次核对哈希。未通过发布与读回前，不切换官网按钮。预发布不使用 latest 下载 URL。

修改源码、签名或附件必须形成新版本和新校验值；相同源码的构建过程可重复执行，APK 二进制是否逐字节相同需单独验证，不作可重现构建承诺。`release.json` 记录源码提交、签名、SDK、大小、SHA256 和功能边界。

0.5.3-preview.1 已公开，源码冻结 `0e7468239079de72c7e753c37d98a4d1b724228e`，code10、Room6、同一预览证书，包含账户/AI、手动 VIP 日程同步和六档 MOCK 收银台。六个 Release 附件、官网新旧 APK、版本元数据均已读回校验，见[发布回执](../doc/Bottledkzk/2026-10-30-0.5.3公开发布回执.md)。构建本身不读取生产秘密，默认生产/邮件状态仍为 null；发行者仅在独立回执完成后，在新的最终产物目录补充已验证生产状态。新版签名包真机全流程尚未补测，因此 online_acceptance_passed 保持 false，不篡改为全量通过。

先公开四份已校验附件（APK、SHA256SUMS.txt、release.json、RELEASE-NOTES.md），核对 GitHub 非 draft 及 tag 指向冻结提交，再在新目录执行 `python script/package-website.py <最终产物目录> --public-release-tag v0.5.3-preview.1`，追加网站 ZIP/校验附件并读回。这样网站主入口与备用入口同版本，候选阶段默认仍保留旧公开入口。同目录重跑须拒绝覆盖；部署时另保留旧版本 downloads 目录，原子切换失败时恢复原链接。

本次复用了未公开的旧草稿并先私下完整归档；更新草稿目标时应同时显式给出 tag_name 与 target_commitish，确认 tag 未变成 untagged 后按 Release ID 处理附件。已经公开的版本、tag 和附件不覆写。full 为十迁移，gateway 仍为八迁移且禁止启动账户 API。

依据：[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[GitHub Release 管理](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)。

状态字段采用三态：true/false仅表示有对应验收证据的已验证结果，null表示构建未验证该外部状态；邮件not_verified_by_build不表示已送达或未配置。真实邮件回执与APK构建分开记录。
