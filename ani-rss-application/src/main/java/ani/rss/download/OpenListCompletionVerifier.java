package ani.rss.download;

import ani.rss.entity.OpenListFileInfo;
import ani.rss.util.other.OpenListUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Confirms a persisted archive plan independently of the provider's task history. */
final class OpenListCompletionVerifier {
    private final OpenListUtil api;
    private final OpenListTaskStore store;

    OpenListCompletionVerifier(OpenListUtil api, OpenListTaskStore store) {
        this.api = api;
        this.store = store;
    }

    boolean completeIfPresent(String id) {
        var task = store.get(id);
        if (task == null) {
            return false;
        }
        if (task.isCompleted()) {
            return true;
        }
        // Names must come from a persisted plan that already passed collision checks.
        if (task.getFiles().isEmpty()
                || (!task.getCollectionFiles().isEmpty() && !task.isCollectionPlanned())) {
            return false;
        }
        Map<String, Long> expected = new LinkedHashMap<>(task.getFileSizes());
        for (var entry : task.getCollectionFiles()) {
            expected.put(entry.getTarget(), entry.getLength());
        }
        var destination = api.fsListChecked(task.getSavePath(), true);
        long size = 0;
        for (String name : task.getFiles()) {
            var matches = destination.stream().filter(file -> name.equals(file.getName())).toList();
            if (matches.size() != 1) {
                return false;
            }
            OpenListFileInfo file = matches.getFirst();
            if (!Boolean.FALSE.equals(file.getIsDir()) || file.getSize() == null || file.getSize() <= 0) {
                return false;
            }
            // Older ordinary tasks did not store reliable per-file lengths. For those,
            // require every exact planned name to be a nonempty file; never guess names.
            if (expected.containsKey(name) && !Objects.equals(expected.get(name), file.getSize())) {
                return false;
            }
            size += file.getSize();
        }
        store.completed(id, task.getFiles(), size);
        return true;
    }
}
