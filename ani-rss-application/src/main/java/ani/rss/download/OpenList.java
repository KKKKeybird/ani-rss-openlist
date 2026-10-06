package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.config.OpenListConfig;
import ani.rss.entity.*;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsStateEnum;
import ani.rss.enums.TorrentsTagEnum;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.OpenListUtil;
import ani.rss.util.other.TorrentUtil;
import ani.rss.util.other.TorrentMetadata;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenList implements BaseDownload {
    private static final Config CONFIG = ConfigUtil.CONFIG;

    private final OpenListUtil openListUtil = OpenListUtil.getInstance(new OpenListConfig() {
        @Override
        public String getServer() {
            return CONFIG.getDownloadToolHost();
        }

        @Override
        public String getApiKey() {
            return CONFIG.getDownloadToolPassword();
        }
    });
    private final OpenListTaskStore taskStore = new OpenListTaskStore(
            new File(ConfigUtil.getConfigDir(), "cache/openlist-tasks.json"));

    @Override
    public Boolean login(Boolean test, Config config) {
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        if (StrUtil.isBlank(host) || StrUtil.isBlank(password)) {
            log.warn("OpenList 未配置完成");
            return false;
        }
        String downloadPath = config.getDownloadPathTemplate();
        Assert.notBlank(downloadPath, "未设置下载位置");
        String provider = config.getProvider();
        Assert.notBlank(provider, "请选择 Driver");
        try {
            return OpenListUtil.getInstance(host, password).test();
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error("登录 OpenList 失败 {}", message);
        }
        return false;
    }


    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        List<TorrentsInfo> result = new ArrayList<>();
        for (OpenListTaskStore.Task task : taskStore.list()) {
            String taskId = task.getId();
            if (!task.isCompleted()) {
                openListUtil.taskInfo(taskId).ifPresent(info -> {
                    taskStore.progress(taskId, info);
                    if (!taskStore.get(taskId).getCollectionFiles().isEmpty()) {
                        reconcileCollection(taskId, info);
                    } else if (info.getState() == OpenListTaskInfo.State.Succeeded) {
                        try {
                            finishTask(taskId, System.currentTimeMillis() + 60_000L);
                        } catch (Exception e) {
                            log.warn("OpenList 文件整理待重试 {}: {}", info.getId(), e.getMessage());
                        }
                    }
                });
                var current = taskStore.get(taskId);
                if (!current.getCollectionFiles().isEmpty() && !current.isCompleted()
                        && !OpenListTaskInfo.State.Succeeded.name().equals(current.getState())
                        && System.currentTimeMillis() >= current.getSubmittedAt()
                        + Math.max(1, CONFIG.getOpenListDownloadTimeout()) * 60_000L) {
                    taskStore.failed(taskId, "合集下载超时");
                }
            }
            task = taskStore.get(task.getId());
            long size = task.getSize();
            long completed = task.isCompleted() ? size : size * task.getProgress() / 100;
            List<String> taskFiles = List.copyOf(task.getFiles());
            TorrentsInfo info = new TorrentsInfo()
                    .setId(task.getId()).setHash(task.getHash()).setName(task.getName())
                    .setState(task.isCompleted() ? TorrentsStateEnum.stoppedUP
                            : OpenListTaskInfo.State.Failed.name().equals(task.getState())
                            ? TorrentsStateEnum.error : TorrentsStateEnum.downloading)
                    .setCategory(TorrentsTagEnum.ANI_RSS.getValue())
                    .setTagList(List.copyOf(task.getTags()))
                    .setSavePath(task.getSavePath())
                    .setFilesSupplier(() -> taskFiles)
                    .progress(completed, size);
            if (!task.isCompleted()) {
                info.setProgress((double) task.getProgress());
            }
            result.add(info);
        }
        return result;
    }

    /** Submit immediately; the regular task poller handles completion and restart recovery. */
    public synchronized void downloadCollection(String name, TorrentMetadata torrent, String savePath,
                                                List<Item> items, List<String> tags) {
        List<OpenListTaskStore.CollectionFile> entries = items.stream().map(item ->
                new OpenListTaskStore.CollectionFile().setSource(item.getTitle())
                        .setTarget(item.getReName()).setLength(item.getLength())).toList();
        OpenListCollectionOrganizer.validate(entries);
        Assert.notBlank(savePath, "未设置合集下载位置");
        savePath = ReUtil.replaceAll(savePath, "^[A-Za-z]:", "").replace('\\', '/');
        savePath = ReUtil.replaceAll(savePath, "/+$", "");
        if (savePath.isEmpty()) {
            savePath = "/";
        }
        String hash = torrent.getHash();
        Assert.isFalse(taskStore.list().stream().anyMatch(task -> hash.equals(task.getHash())),
                "OpenList 合集任务已存在，请先在下载列表中处理原任务");
        Assert.isTrue(openListUtil.mkdir(savePath), "OpenList 创建下载目录失败: {}", savePath);
        String stage = (savePath.equals("/") ? "" : savePath) + "/.ani-rss-openlist-" + UUID.randomUUID();
        Assert.isTrue(openListUtil.mkdir(stage), "OpenList 创建暂存目录失败: {}", stage);
        String tid = openListUtil.fsAddOfflineDownload(torrent.getMagnetUri(), stage, CONFIG.getProvider());
        taskStore.submitted(new OpenListTaskStore.Task().setId(tid).setHash(hash).setName(name)
                .setSavePath(savePath).setStagingPath(stage).setTags(new ArrayList<>(tags))
                .setCollectionFiles(entries).setSubmittedAt(System.currentTimeMillis()));
    }

    private void reconcileCollection(String id, OpenListTaskInfo info) {
        var task = taskStore.get(id);
        long deadline = task.getSubmittedAt() + Math.max(1, CONFIG.getOpenListDownloadTimeout()) * 60_000L;
        try {
            if (info.getState() == OpenListTaskInfo.State.Succeeded) {
                // Transfer completion and cloud file visibility are separate stages.
                finishTask(id, System.currentTimeMillis() + 60_000L);
            } else if (System.currentTimeMillis() >= deadline) {
                taskStore.failed(id, "合集下载超时");
            } else if (List.of(OpenListTaskInfo.State.Error, OpenListTaskInfo.State.Failing,
                    OpenListTaskInfo.State.Failed).contains(info.getState())) {
                long limit = CONFIG.getOpenListDownloadRetryNumber();
                if (limit < 0 || task.getRetries() < limit) {
                    Assert.isTrue(openListUtil.taskRetry(id), "OpenList 合集重试失败: {}", id);
                    taskStore.retried(id);
                } else {
                    taskStore.failed(id, "合集离线下载失败，已达到重试次数: " + info.getError());
                }
            } else if (List.of(OpenListTaskInfo.State.Canceling, OpenListTaskInfo.State.Canceled)
                    .contains(info.getState())) {
                taskStore.failed(id, "合集任务已取消");
            }
        } catch (Exception e) {
            taskStore.failed(id, e.getMessage());
            log.warn("OpenList 合集整理待重试 {}: {}", id, e.getMessage());
        }
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        // windows 真该死啊
        savePath = ReUtil.replaceAll(savePath, "^[A-z]:", "");

        String magnet = TorrentUtil.getMagnet(torrentFile);
        String reName = item.getReName();
        String path = savePath + "/.ani-rss-openlist-" + UUID.randomUUID();
        String tid = null;
        try {
            for (OpenListTaskStore.Task existing : taskStore.list()) {
                if (FileUtil.mainName(torrentFile).equals(existing.getHash())) {
                    log.info("OpenList 任务已存在: {}", reName);
                    return true;
                }
            }
            Assert.isTrue(openListUtil.mkdir(path), "OpenList 创建暂存目录失败: {}", path);

            try {
                tid = openListUtil.fsAddOfflineDownload(magnet, path, CONFIG.getProvider());
                log.info("添加离线下载成功 {}", reName);
            } catch (Exception e) {
                log.error("添加离线下载失败 {}", reName);
                throw new IllegalStateException("添加离线下载失败 " + reName);
            }
            taskStore.submitted(new OpenListTaskStore.Task()
                    .setId(tid).setHash(FileUtil.mainName(torrentFile))
                    .setName(reName).setSavePath(savePath).setStagingPath(path)
                    .setTags(new ArrayList<>(newTags(ani, item))));

            // 记录开始时间
            long deadline = System.currentTimeMillis()
                    + Math.max(1, CONFIG.getOpenListDownloadTimeout()) * 60_000L;

            // 重试次数
            long retry = 0;
            while (true) {
                Long openListDownloadRetryNumber = CONFIG.getOpenListDownloadRetryNumber();

                if (System.currentTimeMillis() >= deadline) {
                    // 超过下载超时限制
                    log.error("{} {} 分钟还未下载完成, 停止检测下载", reName, CONFIG.getOpenListDownloadTimeout());
                    taskStore.failed(tid, "下载超时");
                    return false;
                }

                Optional<OpenListTaskInfo> taskInfoOpt = openListUtil.taskInfo(tid);

                if (taskInfoOpt.isEmpty()) {
                    ThreadUtil.sleep(2000);
                    continue;
                }

                OpenListTaskInfo taskInfo = taskInfoOpt.get();
                taskStore.progress(tid, taskInfo);
                OpenListTaskInfo.State state = taskInfo.getState();
                String error = taskInfo.getError();

                // errored 重试
                if (
                        List.of(
                                OpenListTaskInfo.State.Error,
                                OpenListTaskInfo.State.Failing,
                                OpenListTaskInfo.State.Failed
                        ).contains(state)
                ) {
                    // 已到达最大重试次数 5 次, -1 不限制
                    if (openListDownloadRetryNumber > -1) {
                        if (retry >= openListDownloadRetryNumber) {
                            // bug fix: 新资源下载完成后，OpenList 状态可能未及时刷新
                            // 此处通过检查文件是否存在来兜底，存在则直接继续后续逻辑
                            Optional<OpenListFileInfo> first = openListUtil.findFiles(path)
                                    .stream()
                                    .filter(openListFileInfo -> FileUtils.isVideoFormat(openListFileInfo.getName()))
                                    .findFirst();
                            if (first.isPresent()) {
                                log.info("资源已下载完毕，OpenList 可能处于卡死状态，此处跳过");
                                break;
                            }
                            log.error("离线下载失败 {}", error);
                            taskStore.failed(tid, error);
                            return false;
                        }
                        retry++;
                        log.info("离线任务正在进行重试 {}, 当前重试次数 {}, 最大重试次数 {}", tid, retry, openListDownloadRetryNumber);
                    }
                    Assert.isTrue(openListUtil.taskRetry(tid), "OpenList 重试失败: {}", tid);
                    ThreadUtil.sleep(2000);
                    continue;
                }

                if (
                        List.of(
                                OpenListTaskInfo.State.Canceling,
                                OpenListTaskInfo.State.Canceled
                        ).contains(state)
                ) {
                    log.error("离线任务已被取消 {}", reName);
                    taskStore.failed(tid, "任务已取消");
                    return false;
                }

                // 成功
                if (state == OpenListTaskInfo.State.Succeeded) {
                    break;
                }
                ThreadUtil.sleep(2000);
            }

            return finishTask(tid, deadline);
        } catch (Exception e) {
            if (tid != null) {
                taskStore.failed(tid, e.getMessage());
            }
            log.error(e.getMessage(), e);
        }
        return false;
    }

    private synchronized Boolean finishTask(String tid, long deadline) {
        OpenListTaskStore.Task task = taskStore.get(tid);
        if (task == null) {
            return false;
        }
        if (task.isCompleted()) {
            return true;
        }
        if (!task.getCollectionFiles().isEmpty()) {
            return new OpenListCollectionOrganizer(openListUtil, taskStore).finish(tid, deadline);
        }
        String savePath = task.getSavePath();
        String path = task.getStagingPath();
        String reName = task.getName();
        Set<String> presentBefore = new HashSet<>(openListUtil.fsListChecked(savePath, true)
                .stream().map(OpenListFileInfo::getName).toList());
        if (!task.getFiles().isEmpty()) {
            if (presentBefore.containsAll(task.getFiles())) {
                taskStore.completed(tid, task.getFiles(), task.getSize());
                return true;
            }
        }
            List<OpenListFileInfo> openListFileInfos;
            Optional<OpenListFileInfo> videoFileOpt;
            do {
                openListFileInfos = openListUtil.findFiles(path);
                videoFileOpt = openListFileInfos.stream()
                        .filter(file -> FileUtils.isVideoFormat(file.getName())).findFirst();
                // A planned task may have moved its video before Ani-RSS restarted.
                if (videoFileOpt.isPresent() || !task.getFiles().isEmpty()) {
                    break;
                }
                // Offline download may succeed before the transfer task exposes the file.
                ThreadUtil.sleep(2000);
            } while (System.currentTimeMillis() < deadline);
            if (videoFileOpt.isEmpty() && task.getFiles().isEmpty()) {
                throw new IllegalStateException("OpenList 离线任务完成但未找到视频: " + path);
            }
            List<OpenListFileInfo> selected = new ArrayList<>();
            if (task.getFiles().isEmpty()) {
                selected.add(videoFileOpt.orElseThrow());
                selected.addAll(openListFileInfos.stream()
                        .filter(file -> FileUtils.isSubtitleFormat(file.getName())).toList());
            } else {
                selected.addAll(openListFileInfos.stream()
                        .filter(file -> {
                            String target = CONFIG.getRename()
                                    ? getFileReName(file.getName(), reName) : file.getName();
                            return task.getFiles().contains(target) && !presentBefore.contains(target);
                        }).toList());
            }
            Map<String, List<String>> moves = new LinkedHashMap<>();
            List<String> names = new ArrayList<>(task.getFiles());
            long size = task.getSize();
            for (OpenListFileInfo file : selected) {
                String newName = CONFIG.getRename()
                        ? getFileReName(file.getName(), reName) : file.getName();
                Assert.isFalse(presentBefore.contains(newName) ||
                                (task.getFiles().isEmpty() && names.contains(newName)),
                        "OpenList 目标文件已存在: {}", newName);
                if (!file.getName().equals(newName)) {
                    Assert.isTrue(openListUtil.fsBatchRename(List.of(Map.of(
                            "src_name", file.getName(), "new_name", newName)), file.getPath()),
                            "OpenList 重命名失败: {}", file.getName());
                }
                moves.computeIfAbsent(file.getPath(), ignored -> new ArrayList<>()).add(newName);
                if (task.getFiles().isEmpty()) {
                    names.add(newName);
                    size += file.getSize() == null ? 0 : file.getSize();
                }
            }
            if (task.getFiles().isEmpty()) {
                taskStore.planned(tid, names, size);
            }
            for (Map.Entry<String, List<String>> move : moves.entrySet()) {
                openListUtil.fsMoveAndWait(move.getKey(), savePath, move.getValue(), deadline);
            }
            while (System.currentTimeMillis() < deadline) {
                Set<String> present = new HashSet<>(openListUtil.fsListChecked(savePath, true)
                        .stream().map(OpenListFileInfo::getName).toList());
                if (present.containsAll(names)) {
                    taskStore.completed(tid, names, size);
                    if (openListUtil.findFiles(path).isEmpty()) {
                        openListUtil.fsRemove(savePath, List.of(path.substring(savePath.length() + 1)));
                    }
                    return true;
                }
                ThreadUtil.sleep(2000);
            }
            throw new IllegalStateException("OpenList 移动后未能确认文件: " + names);
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        OpenListTaskStore.Task task = taskStore.get(torrentsInfo.getId());
        if (task == null) {
            return false;
        }
        if (!task.isCompleted() && !openListUtil.taskCancel(task.getId())) {
            return false;
        }
        if (deleteFiles) {
            List<String> present = openListUtil.fsListChecked(task.getSavePath(), true)
                    .stream().map(OpenListFileInfo::getName).toList();
            List<String> owned = task.getFiles().stream().filter(present::contains).toList();
            if (!owned.isEmpty() && !openListUtil.fsRemove(task.getSavePath(), owned)) {
                return false;
            }
            if (!task.isCompleted() || !task.getCollectionFiles().isEmpty()) {
                String stageName = task.getStagingPath().substring(task.getStagingPath().lastIndexOf('/') + 1);
                if (present.contains(stageName)
                        && !openListUtil.fsRemove(task.getSavePath(), List.of(stageName))) {
                    return false;
                }
            }
        }
        if (!openListUtil.taskDelete(task.getId())) {
            return false;
        }
        taskStore.remove(task.getId());
        return true;
    }

    @Override
    public Boolean rename(TorrentsInfo torrentsInfo) {
        OpenListTaskStore.Task task = taskStore.get(torrentsInfo.getId());
        return task != null && task.isCompleted();
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        OpenListTaskStore.Task task = taskStore.get(torrentsInfo.getId());
        if (task == null || StrUtil.isBlank(tags)) {
            return false;
        }
        boolean added = false;
        for (String tag : tags.split(",")) {
            if (StrUtil.isNotBlank(tag)) {
                added |= taskStore.addTag(task.getId(), tag.trim());
            }
        }
        return added;
    }

    @Override
    public void updateTrackers(Set<String> trackers) {
        // The OpenList provider owns the transfer and exposes no global tracker setting.
        if (!trackers.isEmpty()) {
            log.debug("OpenList Driver 不支持全局 Tracker 配置");
        }
    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {
        OpenListTaskStore.Task task = taskStore.get(torrentsInfo.getId());
        if (task == null || !task.isCompleted()) {
            throw new IllegalStateException("OpenList 任务尚未完成，无法移动: " + torrentsInfo.getName());
        }
        path = ReUtil.replaceAll(path, "^[A-Za-z]:", "").replace('\\', '/');
        if (path.equals(task.getSavePath())) {
            return;
        }
        Assert.isTrue(openListUtil.mkdir(path), "OpenList 创建目标目录失败: {}", path);
        long deadline = System.currentTimeMillis()
                + Math.max(1, CONFIG.getOpenListDownloadTimeout()) * 60_000L;
        openListUtil.fsMoveAndWait(task.getSavePath(), path, task.getFiles(), deadline);
        while (System.currentTimeMillis() < deadline) {
            Set<String> present = new HashSet<>(openListUtil.fsListChecked(path, true)
                    .stream().map(OpenListFileInfo::getName).toList());
            if (present.containsAll(task.getFiles())) {
                taskStore.moved(task.getId(), path);
                return;
            }
            ThreadUtil.sleep(2000);
        }
        throw new IllegalStateException("OpenList 移动后未能确认目标文件: " + task.getFiles());
    }


}
