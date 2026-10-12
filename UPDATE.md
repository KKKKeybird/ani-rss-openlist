社区维护的 ANI-RSS OpenList fork，基于上游 v3.2.47。

- feat: 合入 v3.2.46 的 WASM 播放器及多音字处理优化。
- refactor: 合入 v3.2.47 的封面标题 tooltip 调整。
- compat: 上游已移除 OpenList 下载支持，本 fork 继续维护原生下载器、配置和界面入口；旧 Alist 配置仍可迁移，OpenList 不会被切换到其他下载器。

保留 OpenList r1 的文件完成恢复、原任务跟踪、持久化重试与归档计划，以及合集下载、WebUI 任务清理和空暂存目录清理。保留上游批量订阅流程与备用 RSS 独立偏移。

手动 build 工作流发布 JAR、EXE 与 Temurin/OpenJ9 的 amd64、arm64 镜像，同时更新 latest 和 openj9。上游自动同步仍关闭。
