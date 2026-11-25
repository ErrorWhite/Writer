# 青简 Android

Android 客户端连接部署了青简的 HTTPS 服务。默认入口是 `https://server.080726.xyz/novel/`。安卓版的书稿、AI 账号和文本扮演能力取决于此地址实际部署的青简版本。

在仓库的 Actions 页面运行 Android APK 工作流，下载 `Qingjian-Android-test` 产物获取测试 APK。工作流在 GitHub runner 上编译并使用 Android 调试签名。

可通过仓库变量 `QINGJIAN_STUDIO_URL` 改用另一套已部署的 HTTPS 服务。应用支持系统文件选择器，配合青简网页的 `QingjianFiles` 接口通过系统保存器导出 JSON、TXT、Markdown 和 EPUB。角色卡、世界书及模型请求由青简服务处理。
