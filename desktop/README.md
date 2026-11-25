# 青简 PC 与服务端

GitHub Actions 的 **PC and server packages** 工作流输出 Windows 免安装包和服务端压缩包。Windows 解压后启动 `Qingjian.exe`。服务端包解压后执行 `npm ci --omit=dev` 和 `npm start`，沿用当前部署的 HTTPS 反向代理、`QINGJIAN_HTTPS_ORIGIN`、`QINGJIAN_URL_PATH`、`QINGJIAN_AUTHORIZATION_SHA256` 与 `QINGJIAN_DATA_DIR`。

跨端同步在《保存与备份》按书开启。Windows 的《服务器书架》及 Android 的服务器导入入口支持同步并打开现有书稿。新版服务端在同一数据目录中保存 `sync-v1-*.json`，包含已同步书稿的权威版本或删除记录。更新前备份完整数据目录。部署期间先启动新版服务端，再启用新客户端同步。需要停用同步时保留新版服务端继续读取同步文件，并在各端暂停同步。
