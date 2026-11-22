社区维护的 ANI-RSS OpenList fork，基于上游 v3.2.63，发布标签 v3.2.63-openlist。

- v3.2.61 按需加载 WASM 播放器，打开播放窗口时才加载播放器组件。
- v3.2.62 在用户名和密码均为默认值时提示修改，可直接打开安全设置，也可在当前浏览器关闭提醒。
- v3.2.63 更新 Spring Boot、Spring AI 和前端依赖，JAR 与 EXE 使用 Maven 的 windows profile 打包。
- Temurin 和 OpenJ9 镜像构建主动拉取基础镜像，跟进上游针对 `CVE-2026-114938` 的基础镜像更新。
- 保留原生 OpenList 下载器、Alist 配置迁移、合集预览及归档、重启恢复和按渠道持久化的完成通知。

升级时保留配置、OpenList 任务记录、通知成功记录、种子缓存和下载文件。
