package ani.rss.service;

import ani.rss.download.BaseDownload;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsStateEnum;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class DownloadTaskDeletion {
    public record Outcome(List<String> deleted, List<String> failed) {}

    public static Outcome delete(BaseDownload downloader, List<String> ids, boolean failedOnly) {
        synchronized (downloader) {
            var tasks = downloader.getTorrentsInfos().stream()
                    .collect(Collectors.toMap(DownloadTaskDeletion::taskKey, Function.identity(), (first, second) -> first));
            List<String> deleted = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (String id : ids.stream().distinct().toList()) {
                var task = tasks.get(id);
                if (task == null) {
                    deleted.add(id); // Repeated requests for an already removed task are harmless.
                    continue;
                }
                if (failedOnly && task.getState() != TorrentsStateEnum.error) {
                    failed.add(id); // A task may have recovered since the user saw the error tab.
                    continue;
                }
                try {
                    if (Boolean.TRUE.equals(downloader.delete(task, false))) {
                        deleted.add(id);
                    } else {
                        failed.add(id);
                    }
                } catch (Exception e) {
                    log.warn("删除下载任务失败 {}: {}", id, e.getMessage());
                    failed.add(id);
                }
            }
            return new Outcome(deleted, failed);
        }
    }

    private static String taskKey(TorrentsInfo task) {
        return task.getId() == null || task.getId().isBlank() ? task.getHash() : task.getId();
    }
}
