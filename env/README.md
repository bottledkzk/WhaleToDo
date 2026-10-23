# 可分享环境配置

`toolchain.json` 固定项目依赖与API范围，`android-emulator.json` 给出标准测试配置，`.env.example` 仅演示未来部署变量。安装脚本见 `script/README.md`。

SDK、AVD镜像、IDE、Gradle缓存、手机日志及个人凭据只放仓库外层环境区。Windows首轮已验证JDK21运行、Java17字节码、Gradle8.13与API36构建；完整实测结果见成员验证报告。

应用最低Android8.0/API26，当前目标Android16/API36；最低版本能编译不等于已完成全部设备测试。小米14 Pro真机验证等待用户连接与手机端授权。
