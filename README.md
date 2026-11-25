# 青简 Android

青简 PC 版的原生 Android 客户端。原生开发位于 `android-native`。发布包名为 `io.wboat.writer`，调试包名为 `io.wboat.writer.debug`。

## 开发中的功能

- 离线书架与写作，支持分卷分章、查找替换、Markdown、撤销重做、专注模式、章节和全书还原点。
- 全书 JSON 沿用青简 PC 格式。导入保留作品、卷、章、段落 ID 及扩展字段，导出另支持 Markdown、TXT 和 EPUB。
- 创作工作台提供立项、设定、记忆、伏笔、剧情、世界书、资料附件、Skill 和写作提示词。AI 改稿保留候选及原文，采用时校验正文和结构。
- 模型账号支持 ChatGPT 套餐、OpenAI Responses、OpenAI Chat 兼容供应商、Anthropic Messages 和青简服务器 Codex。凭据在本机由 Android Keystore 保护。
- 书架提供独立文本扮演，作品内也可单独开会话。扮演复用 Eta 角色卡、宏、角色世界书和 SSE 解析，支持酒馆 JSON/PNG、开场白、流式回复、停止、重写、会话分支与导出。
- Git 历史记录全书快照和分文件差异，可以创建分支及恢复提交。登录青简服务器后可以下载书稿并上传独立备份副本。

## GitHub Actions 与固定签名

开发分支运行单元测试、lint 和 R8 代码压缩检查。功能验收完成后再手动运行 Android APK 工作流的 `signed` 任务。工作流读取已设置的持久 PKCS#12 发布密钥，逐个验证证书 SHA-256，并上传经过 R8 和资源压缩的 ARM64、x86-64 独立 APK。

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Secret | `QINGJIAN_KEYSTORE_BASE64` | 发布密钥库文件的 Base64 |
| Secret | `QINGJIAN_STORE_PASSWORD` | 密钥库口令 |
| Secret | `QINGJIAN_KEY_ALIAS` | 发布密钥别名 |
| Secret | `QINGJIAN_KEY_PASSWORD` | 发布密钥口令 |
| Variable | `QINGJIAN_CERT_SHA256` | 发布证书 SHA-256 指纹 |

## ChatGPT 套餐

在设置中选择 **Continue with ChatGPT**，在系统浏览器完成授权，然后从套餐可用的模型中选择。写作和文本扮演使用所选账号与模型。账号的注册编号和凭据独立保存，可重新授权或退出。青简采用 [OpenAI 开源客户端套餐授权](https://developers.openai.com/siwc/token-sharing-open-source)，复用 Eta 的本地回调与身份验证实现。

写作 Skill 支持提示词和参考文件。声明脚本入口的 Skill 可在已登录的青简服务器 QuickJS 沙箱中试跑，授权绑定当前服务器、作品与脚本版本。

## 开源来源

- [Eta](https://github.com/Mangi-11/Eta/tree/bbab1b76b354e2f24a2e5f848b959da7f6286365)（GPL-3.0）的角色卡、世界书、社区模型目录和 SSE 源码位于 `app/src/main/kotlin/io/github/mangi/eta/`。SSE 读取器接收外部取消回调，青简负责网络连接和任务生命周期。原授权见 `COPYING`。
- COUI 使用 Eta 同版本的 `io.github.suqi8.coui.kmp` 2.1.0（Apache-2.0）。青简沿用自己的主题配色和界面布局。
- 青简原版图标与移植规则的许可、Qingjian Serif 标题字体的 OFL 许可均在 `app/src/main/assets/licenses/`。标题字体从思源宋体派生，覆盖常用简繁汉字。
