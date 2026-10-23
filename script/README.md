# 开发脚本

Windows PowerShell 7，JDK 21。所有路径根据仓库位置计算；SDK、AVD、IDE状态与Gradle缓存位于仓库外层 `env`，不改全局PATH。

```powershell
$env:JAVA_HOME = '<JDK21安装目录>'
. ./script/android-env.ps1
./script/check.ps1
./script/start-emulator.ps1
./script/install-debug.ps1 -Device emulator-5580
```

首次没有SDK时先运行 `./script/setup-sdk.ps1 -WithEmulator`，然后 `./script/create-emulator.ps1`。脚本下载官方来源并验证命令行包SHA256；安装会使用官方SDK协议。官方CLI新版SDK包路径采用斜杠；AVD管理器仍使用分号路径，两者不要混用。

`start-studio.ps1` 打开工程，IDE首次启动向导按需完成。已有全局IDE也可传路径复用。`install-debug.ps1` 必须明确指定设备，保留已有应用数据；真机需手机端USB调试授权，不自动卸载、清数据或更改安全设置。

设备测试：先启动专用模拟器，然后 `./gradlew.bat :app:connectedDebugAndroidTest`。不要对个人手机运行会创建测试记录的控件流程。规则测试不需要模型密钥、手机或在线服务。
