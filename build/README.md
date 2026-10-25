# 构建与发布产物

生成输出位于`build/output/`，不直接提交二进制或缓存。通过构建与设备检查后，将可分发测试APK附到GitHub Release，记录版本、提交SHA、APK SHA256、安装说明和已知限制。

调试包只用于开发和课程演示，不能冒充生产签名版本；正式签名库不进入Git。未产生真实APK之前，下载页面不提供伪造下载链接。


0.4.0本机验证摘要见[agent-v04.json](validation/agent-v04.json)。记录的APK SHA256仅绑定本机验证调试包，非已发布下载地址；CI调试签名可不同。测试与网络/部署边界见[AI助手交接](../doc/Bottledkzk/2026-10-24-AI助手设计与接入.md)。

0.4.1本机图标与列表验证见[launcher-v041.json](validation/launcher-v041.json)，截图与边界见[外观调整记录](../doc/Bottledkzk/2026-10-25-图标与列表细节调整.md)。
