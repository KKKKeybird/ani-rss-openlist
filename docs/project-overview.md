# 项目结构与下载流程

本次整理基于 `main` 的应用版本 `3.2.43`。项目在 ANI-RSS 上游代码上维护原生 OpenList 后端，GitHub 仓库目前保留 fork 关系。维护者已持续在实际 OpenList 环境中测试，确认可正常使用。

## 模块

| 模块或目录 | 职责 |
| --- | --- |
| `ani-rss-application` | Java 25、Spring Boot 4.1.1 后端，HTTP API、RSS 订阅、下载管理、重命名、刮削及通知 |
| `ani-rss-ui` | Vue 3、Element Plus、Vite 前端，Maven 调用 Node/pnpm 构建后打包进应用 |
| `download` | `BaseDownload` 接口及 qBittorrent、Transmission、Aria2、OpenList 实现 |
| `util/other/TorrentUtil` | 按配置加载下载器 Bean，统一登录、下载、任务查询、标签、删除和保存位置操作 |
| `task` | RSS 检查、任务整理/通知、Bangumi 更新等循环任务，由 `TaskService` 启动 |
| `service/DownloadService` | 订阅下载策略、下载路径、完成通知等业务逻辑 |
| `service/CollectionService` | 上传种子的合集预览、过滤、集数和命名规则、合集下载分支 |
| `.github/workflows` | OpenList 回归与打包、手动发布 JAR/EXE/镜像、上游仅在所有者明确要求时手动同步 |

## 普通 OpenList 下载

`TorrentUtil` → `OpenList.download` → 种子转磁力链接 → 创建独立暂存目录 → 提交 Driver 离线任务 → 轮询状态及重试 → 云端重命名和移动 → 验证文件可见 → 标记完成。

任务信息、标签和归档文件清单保存到配置目录的 `cache/openlist-tasks.json`。`OpenListUtil` 封装文件/任务 API，并检查 OpenList JSON 返回码和后台移动任务。`RenameTask` 周期调用任务查询、完成通知及配置允许的清理。

## 合集下载

前端 `CollectionView` → `CollectionController` → `CollectionService.preview`。预览从 `TorrentMetadata` 读取文件及大小，应用匹配/排除规则，再通过 `RenameUtil` 生成目标名，支持集数偏移和字幕语言扩展名。

qBittorrent：暂停提交种子 → 查询种子文件 → 对排除文件设置优先级 0 → 按预览重命名 → 开始下载。

OpenList：先检查预览 → 提交整包离线任务到独立暂存目录 → 保存每个预览条目的源路径、目标名和大小 → 常规任务查询检测离线完成 → `OpenListCollectionOrganizer` 检查所有源文件与命名冲突 → 持久化整理计划 → 逐文件重命名和移动 → 核对目标文件大小 → 完成。服务重启后可以恢复已重命名或部分已移动的计划。

OpenList 的通用离线 API 没有种子内文件优先级，排除规则只控制归档，排除文件留在暂存目录。空预览、重复目标名、源文件缺失/不唯一、已有目标均不会覆盖归档文件。

## 验证

```sh
mvn -B -pl ani-rss-application -am \
  -Dtest=OpenListTaskStoreTest,OpenListUtilTest,OpenListCollectionOrganizerTest,OpenListCollectionServiceTest,OpenListUpstreamCompatibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl ani-rss-application -am -DskipTests package
```

回归覆盖任务记录持久化、API 方法与错误处理、后台移动等待、合集多集及字幕归档、路径匹配、冲突与缺失文件、中断恢复，以及上游配置默认值和种子保存路径兼容。
