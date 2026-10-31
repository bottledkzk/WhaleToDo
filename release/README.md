# 签名预览版发行

当前公开[0.5.4-preview.1](https://github.com/bottledkzk/WhaleToDo/releases/tag/v0.5.4-preview.1)，code11、Room6。独立包io.whaletodo.app.preview沿用固定证书，与原体验包并存。签名库／密码／SDK在仓库外，证书摘要仅用于公开校验，不能替代私钥

## 从冻结源码到候选

从已提交、已测试的干净源码开始，递增app版本和code，增加对应release说明。用`script/android-env.ps1`设置进程级SDK与JDK21；DPAPI密码文件只供发行者本机解密，不作为通用灾备密码。运行`build-preview.ps1`生成APK、SHA256SUMS.txt、release.json、RELEASE-NOTES.md，验证非调试包、版本、固定证书与模块测试

构建不读取生产或SMTP，默认外部状态为未核验；只有独立服务和设备回执才能在全新发行目录补入对应字段。0.5.3公开包完成小米基础全流程，0.5.4补测四风格日记生成／编辑保存／资料同步，不能把增量补测说成所有流程重跑

## 公开和网站顺序

1. 在独立包／同证书覆盖安装后核对数据和实际APK哈希，保留原应用，不卸载或清数据
2. 创建GitHub draft，上传四个基础附件，按release ID和asset ID逐项下载读回校验，全部一致再公开对应tag
3. 对已公开版本运行`package-website.py <目录> --public-release-tag <已公开tag>`，生成同版备用链接；候选不传参数时保留既有公开备用链接，不指向尚不存在的Release
4. 追加官网ZIP及校验文件，再核对六附件。官网部署到全新目录，检查逐文件manifest、同版APK和历史下载后原子切换；保留上一版以便回退
5. 外部核对页面、下载按钮与release.json的版本，服务器核对实际附件；首页不要求用户手工计算SHA

`publish-preview.ps1`封装draft／读回／公开流程；失败保留draft，不重复create或覆盖资产。应核对实际release ID、目标提交与资产清单后按asset ID恢复，不能把临时draft tag当作公开tag。已公开tag与附件不可覆盖；代码或二进制变化产生新版本

## 可重复与边界

打包工具以冻结提交和逐文件哈希验证来源，网站ZIP含源码与APK提交各自的manifest。可重复执行发布流程不等于承诺APK字节级可重现，后者需另行测量。修改发布附件必须新版本，历史证据保留原日期

[0.5.4交付与验收](../doc/Bottledkzk/2026-10-31-0.5.4交付与验收.md) · [网站说明](../web/README.md) · [Android签名](https://developer.android.com/studio/publish/app-signing) · [GitHub Release](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)
