# 青简 Android

原生开发分支 `android-native`。当前版本支持离线书架、卷章目录、Markdown 源文编辑和预览、章节还原点、大纲和设定，以及青简 PC 版全书备份的导入和导出。Android 8.0 起可安装。手机使用底部导航，宽屏在编辑区旁显示目录。

## GitHub Actions

推送到 `android-native` 后，Android APK 工作流运行单元测试和 lint，并生成 `Qingjian-Native-debug`。调试 APK 的包名为 `xyz.qingjian.writer.debug`。

固定签名 APK 使用 `xyz.qingjian.writer`。仓库管理员需要在 **Settings → Secrets and variables → Actions** 配置以下项目。

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Secret | `QINGJIAN_KEYSTORE_BASE64` | 持久发布密钥库文件的 Base64 |
| Secret | `QINGJIAN_STORE_PASSWORD` | 密钥库口令 |
| Secret | `QINGJIAN_KEY_ALIAS` | 发布密钥别名 |
| Secret | `QINGJIAN_KEY_PASSWORD` | 发布密钥口令 |
| Variable | `QINGJIAN_CERT_SHA256` | 发布证书的 SHA-256 指纹 |

选择分支 `android-native` 并手动运行 Android APK 工作流，勾选 `signed`。工作流会验证 APK 的证书指纹，并将 APK、证书信息和文件校验值上传为 `Qingjian-Android-signed`。后续发布沿用同一发布密钥库和证书指纹。密钥库和口令由维护者保管。旧 WebView 调试包的签名与发布密钥不同时，Android 会拒绝用发布包直接覆盖安装。

## 数据兼容与开源复用

全书 JSON 保留作品、卷、章和段落的 ID，以及研究资料、候选稿、文本扮演及其他扩展字段。保留副本时会分配新作品 ID。章节还原点存在本机数据库中。导入上限为 64 MB。

- Kotlin、Jetpack Compose、Room、Navigation Compose 和 DataStore 提供原生界面与离线存储。
- CommonMark 用于 Markdown 解析和 EPUB 章节渲染，Coil 用于封面。
- Eta 的 COUI 工作流作为移动端交互参考。现阶段通过 Compose Material 3 保持 Android 8.0 兼容，COUI-Kit 的 Android 12 门槛和 Compose 工具链留待后续集成时评估。
- 图标、色板、段落同步规则和思源宋体沿用青简现有资产。许可证随应用打包在 `assets/licenses/`。

本阶段的章节「定稿」操作保存标记。AI 审批、定稿归档、角色扮演运行、供应商管理和跨设备同步仍在移植范围内。
