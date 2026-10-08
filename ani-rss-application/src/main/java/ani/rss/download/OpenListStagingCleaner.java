package ani.rss.download;

import ani.rss.util.other.OpenListUtil;

import java.util.List;
import java.util.regex.Pattern;

/** Removes only empty, task-owned staging directories after confirmed completion. */
final class OpenListStagingCleaner {
    private static final long CHECK_INTERVAL = 5 * 60_000L;
    private static final Pattern NAME = Pattern.compile(
            "\\.ani-rss-openlist-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final OpenListUtil api;
    private final OpenListTaskStore store;

    OpenListStagingCleaner(OpenListUtil api, OpenListTaskStore store) {
        this.api = api;
        this.store = store;
    }

    boolean cleanIfEmpty(String id) {
        var task = store.get(id);
        if (task == null || !task.isCompleted() || task.isStagingCleaned()) {
            return false;
        }
        String path = task.getStagingPath();
        if (path == null || !path.startsWith("/") || path.contains("/../") || path.contains("/./")) {
            return false;
        }
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        if (!NAME.matcher(name).matches()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - task.getStagingCheckedAt() < CHECK_INTERVAL) {
            return false;
        }
        // Persist the attempt so nonempty folders and API errors do not cause busy polling.
        store.stagingChecked(id, now, false);
        String parent = slash == 0 ? "/" : path.substring(0, slash);
        var entries = api.fsListChecked(parent, true).stream()
                .filter(file -> name.equals(file.getName())).toList();
        if (entries.isEmpty()) {
            store.stagingChecked(id, now, true);
            return true;
        }
        if (entries.size() != 1 || !Boolean.TRUE.equals(entries.getFirst().getIsDir())) {
            return false;
        }
        // Recursive listing counts files inside nested folders, including excluded files.
        if (!api.findFiles(path).isEmpty()) {
            return false;
        }
        if (!api.fsRemove(parent, List.of(name))) {
            throw new IllegalStateException("OpenList 空暂存目录清理失败: " + path);
        }
        boolean removed = api.fsListChecked(parent, true).stream()
                .noneMatch(file -> name.equals(file.getName()));
        if (removed) {
            store.stagingChecked(id, now, true);
        }
        return removed;
    }
}
