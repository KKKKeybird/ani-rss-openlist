社区维护的 ANI-RSS OpenList fork，基于上游 v3.2.48。

- refactor: 合入上游订阅添加流程优化，手动和单条添加复用解析、编辑与确认流程。
- feat: 批量添加支持选择匹配规则，按字幕组生成匹配条件。
- feat: 检测到重复订阅时由用户决定是否覆盖，取消时保留原订阅。

继续维护原生 OpenList 下载器、配置和界面入口，保留文件完成恢复、原任务跟踪、合集下载、WebUI 任务清理和空暂存目录清理。保留上游 WASM 播放器、多音字优化和备用 RSS 独立偏移。

手动 build 工作流发布 JAR、EXE 与 Temurin/OpenJ9 的 amd64、arm64 镜像，同时更新 latest 和 openj9。上游自动同步仍关闭。
