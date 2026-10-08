# Ani-RSS 与 OpenList 的兼容路线评估

当前基线：Ani-RSS `v3.2.44`。本 fork 的原生 OpenList 下载器从 `v3.2.38` 建立，维护者已持续在实际 OpenList 环境中测试，确认可正常使用。

## 结论

优先在本 fork 维护现有的 `OpenList` 下载器。将 OpenList 包装成 Aria2 JSON-RPC 服务虽然可以接收下载任务，但无法只靠外部插件保留 Ani-RSS 的重命名、完成通知和部分资源格式行为。若仍需修改 Ani-RSS，直接维护原生下载器的代码面更小，现有配置也可以原样沿用。

## 路线 A：Aria2 JSON-RPC 兼容服务

Ani-RSS 的 `Aria2` 后端调用 `getGlobalStat`、`addTorrent`、`tellActive`、`tellWaiting`、`tellStopped`、`removeDownloadResult` 和 `changeGlobalOption`。兼容服务需要把这些方法映射到 OpenList API，并持久保存 Aria2 GID 与 OpenList 任务 ID 的对应关系。查询任务时，还必须构造 Ani-RSS 预期的 `bittorrent.info.name`、`dir`、`files`、`infoHash` 和状态。

下载器选择由 `ConfigService` / `CronConfig` 按 `ani.rss.download.<名称>` 加载 Spring Bean；当前没有可独立安装的下载器插件接口。这里的“插件”实际只能是对外冒充 Aria2 的 RPC 服务。

仅实现 RPC 仍有以下缺口：

1. `Aria2.download()` 拒绝 `.txt` 磁力链接；现有 `OpenList.download()` 会从种子取得磁力链接后提交到 OpenList。
2. `Aria2.rename()` 只在完成后操作本地 `File`，并检查文件存在和大小；现有 OpenList 后端通过 OpenList API 重命名、移动云端文件。RPC 服务无法改变 Ani-RSS 的这段本地文件逻辑。
3. `Aria2.addTags()` 固定返回 `false`，而通用下载完成通知需要先成功添加完成标签；因此外部 RPC 服务无法保持现有 Ani-RSS 完成通知行为。
4. `Aria2.setSavePath()` 不执行操作，且删除任务只调用 `removeDownloadResult`，与 OpenList 的云端任务及文件语义不同。

若选择此路线，仍需修改 Ani-RSS 的 Aria2 后端，并维护一个带持久状态的 RPC 服务。它不再是独立于 Ani-RSS 的简单插件。CloudDrive 等挂载可能让本地文件重命名碰巧可用，但不能作为等价实现的前提。

## 路线 B：维护原生 OpenList 下载器

`v3.2.38` 已有 `OpenList.java`、`OpenListUtil.java`、OpenList 配置和任务/文件实体。下载流程已经处理提交离线任务、等待状态、重试、云端重命名、移动文件及完成通知。沿用此路线不需要转换既有的 `config.v2.json` 和 `ani.v2.json`。

已在本 fork 实现：

1. 按 OpenList `v4.2.6` 的 API 修正任务重试的 `tid` 查询参数、浮点进度与 `total_bytes` 整数类型，并检查 JSON 返回码和批量删除的逐项错误。
2. 将 Ani-RSS 创建的任务 ID、保存路径、标签、进度与输出文件清单持久保存到配置目录的 `cache/openlist-tasks.json`，供任务列表、完成通知、删除与路径调整使用。该文件只记录本 fork 创建的任务，不会导入 OpenList 中其他任务。
3. 下载轮询增加间隔。离线任务成功后等待文件出现，调用云端重命名和移动，并等待移动任务及目标文件可见后才标记完成。暂存目录使用独立名称；只在确认没有剩余文件时清理。
4. 完成标签经任务记录去重，沿用 Ani-RSS 通用完成通知。原先按文件名模糊匹配并删除备用 RSS 文件的逻辑已移除，改由通用任务清理流程处理。

使用状态与能力边界：

维护者已持续实测并确认可正常使用。重命名或多文件移动中途进程退出时，会根据持久化的预期文件清单继续处理暂存目录中剩余文件。

OpenList 没有 qBittorrent 的做种比率、上传速度、全局 Tracker 等通用 API。这些选项由具体 Driver 决定，不能在此后端等价实现。

后续维护重点：

1. 保留下载器选择入口及相关配置字段，避免合并上游改动时把 OpenList 实现或 UI 选项删掉。
2. 为自建镜像使用固定版本或摘要，并在合并上游版本时运行兼容检查。上游移除 OpenList 后，后续合并可能产生冲突，需要由本 fork 维护。

## OpenList API 核对

以官方 [API 文档入口](https://doc.oplist.org/api/apidocs) 和 `v4.2.6` 对应实现为准：[任务接口](https://github.com/OpenListTeam/OpenList/blob/v4.2.6/server/handles/task.go)、[离线下载接口](https://github.com/OpenListTeam/OpenList/blob/v4.2.6/server/handles/offline_download.go)、[文件移动接口](https://github.com/OpenListTeam/OpenList/blob/v4.2.6/server/handles/fsmanage.go)。

## 源码依据

- `ani-rss-application/src/main/java/ani/rss/download/Aria2.java`
- `ani-rss-application/src/main/java/ani/rss/entity/torrent/Aria2RpcBody.java`
- `ani-rss-application/src/main/java/ani/rss/entity/torrent/Aria2TorrentsInfo.java`
- `ani-rss-application/src/main/java/ani/rss/task/RenameTask.java`
- `ani-rss-application/src/main/java/ani/rss/service/DownloadService.java`
- `ani-rss-application/src/main/java/ani/rss/service/ConfigService.java`
- `ani-rss-application/src/main/java/ani/rss/config/CronConfig.java`
- `ani-rss-application/src/main/java/ani/rss/download/OpenList.java`
- `ani-rss-application/src/main/java/ani/rss/util/other/OpenListUtil.java`

本 fork 沿用既有 OpenList 配置和任务记录格式。

## 合集下载兼容（2026-10-06）

`CollectionService` 按下载器分支，将 OpenList 合集预览条目交给 `OpenList.downloadCollection`。提交完整种子的磁力链接，普通任务轮询负责等待离线完成、重试及整理。`OpenListCollectionOrganizer` 按完整相对路径及大小定位每个文件，先检查所有文件和冲突再持久化整理计划；逐文件重命名、等待移动及确认目标文件可见后才完成。重启后继续处理已重命名、部分已移动的任务。

匹配、排除、字幕扩展名、集数偏移和重命名模板沿用原有预览逻辑。通用 OpenList 离线接口不支持种子文件优先级，因此被排除的文件仍会下载，保留于独立暂存目录；用户明确删除任务及文件时也会删除该暂存目录。归档文件及暂存文件都只属于本程序提交的任务。

自动回归覆盖多集及字幕、嵌套路径、缺失文件、重复目标名、已存在的目标、移动失败和重启后部分归档恢复。

## 上游 v3.2.43 同步（2026-10-07）

合入上游 `v3.2.43`，保留原生 OpenList 下载器、合集整理、任务持久化和下载器选择入口。同步上游配置初始化重构、种子保存路径调整、界面优化、半集刮削与自动偏移修复及依赖更新；Docker 运行时跟随上游升级到 JDK 27，Java 编译目标仍为 25。

配置默认值继续保留 OpenList Driver、超时和重试设置。新增回归覆盖默认配置、保存配置中的 OpenList 选项，以及旧版和新版种子路径的读取。

## 上游 v3.2.44 与 WebUI 任务清理（2026-10-08）

合入上游多收件人邮件通知和下载列表样式调整，保留原生 OpenList 实现。下载页增加单任务删除及失败任务批量清理，始终保留文件。OpenList 删除前通过已完成、未完成任务列表确认状态；远端已不存在时删除本地持久记录，终态任务不要求取消成功，网络或权限错误保留记录。批量清理在服务端重新检查失败状态，避免删除已恢复或已完成的任务；单条失败不阻断其他任务。
