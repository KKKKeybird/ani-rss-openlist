package ani.rss.download;

import ani.rss.entity.OpenListFileInfo;
import ani.rss.util.other.OpenListUtil;

import java.util.*;

/** Organizes only preview-selected files; the provider downloads the entire torrent. */
final class OpenListCollectionOrganizer {
    private final OpenListUtil api;
    private final OpenListTaskStore store;

    OpenListCollectionOrganizer(OpenListUtil api, OpenListTaskStore store) {
        this.api = api;
        this.store = store;
    }

    static void validate(List<OpenListTaskStore.CollectionFile> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("合集预览为空，请检查匹配、排除和集数规则");
        }
        Set<String> targets = new HashSet<>();
        Set<String> sources = new HashSet<>();
        for (var entry : entries) {
            String target = entry.getTarget();
            if (target == null || target.isBlank() || target.contains("/") || target.contains("\\")
                    || target.equals(".") || target.equals("..")) {
                throw new IllegalArgumentException("合集目标文件名无效: " + target);
            }
            if (!targets.add(target) || !sources.add(entry.getSource())) {
                throw new IllegalArgumentException("合集文件名重复: " + target);
            }
        }
    }

    boolean finish(String id, long deadline) {
        var task = store.get(id);
        if (task.isCompleted()) {
            return true;
        }
        List<OpenListFileInfo> destination = api.fsListChecked(task.getSavePath(), true);
        List<OpenListFileInfo> staged = api.findFiles(task.getStagingPath());
        if (!task.isCollectionPlanned()) {
            // Validate all files and collisions before renaming or moving anything.
            Set<String> resolved = new HashSet<>();
            for (var entry : task.getCollectionFiles()) {
                if (destination.stream().anyMatch(file -> file.getName().equals(entry.getTarget()))) {
                    throw new IllegalStateException("OpenList 目标文件已存在: " + entry.getTarget());
                }
                List<OpenListFileInfo> matches = staged.stream()
                        .filter(file -> Objects.equals(file.getSize(), entry.getLength()))
                        .filter(file -> {
                            String relative = fullPath(file).substring(task.getStagingPath().length() + 1);
                            return relative.equals(entry.getSource()) || relative.endsWith("/" + entry.getSource());
                        }).toList();
                if (matches.size() != 1) {
                    throw new IllegalStateException("OpenList 合集文件缺失或路径不唯一: " + entry.getSource());
                }
                String path = fullPath(matches.getFirst());
                if (!resolved.add(path)) {
                    throw new IllegalStateException("OpenList 合集源文件重复: " + path);
                }
                String renamedPath = matches.getFirst().getPath() + "/" + entry.getTarget();
                if (!path.equals(renamedPath) && staged.stream().anyMatch(file -> fullPath(file).equals(renamedPath))) {
                    throw new IllegalStateException("OpenList 暂存目标文件已存在: " + renamedPath);
                }
                entry.setResolvedPath(path);
            }
            store.planCollection(id, task.getCollectionFiles());
        }
        for (var entry : store.get(id).getCollectionFiles()) {
            // A previous attempt may have moved some files before restart.
            Optional<OpenListFileInfo> existing = destination.stream()
                    .filter(file -> file.getName().equals(entry.getTarget())).findFirst();
            if (existing.isPresent()) {
                if (!Objects.equals(existing.get().getSize(), entry.getLength())) {
                    throw new IllegalStateException("OpenList 目标文件大小不匹配: " + entry.getTarget());
                }
                continue;
            }
            String sourcePath = entry.getResolvedPath();
            int slash = sourcePath.lastIndexOf('/');
            String parent = sourcePath.substring(0, slash);
            String oldName = sourcePath.substring(slash + 1);
            String renamedPath = parent + "/" + entry.getTarget();
            Optional<OpenListFileInfo> renamed = staged.stream()
                    .filter(file -> fullPath(file).equals(renamedPath)).findFirst();
            if (renamed.isPresent() && !Objects.equals(renamed.get().getSize(), entry.getLength())) {
                throw new IllegalStateException("OpenList 暂存目标文件冲突: " + renamedPath);
            }
            if (renamed.isEmpty()) {
                boolean sourcePresent = staged.stream().anyMatch(file -> fullPath(file).equals(sourcePath)
                        && Objects.equals(file.getSize(), entry.getLength()));
                if (!sourcePresent) {
                    throw new IllegalStateException("OpenList 合集源文件缺失: " + sourcePath);
                }
                if (!oldName.equals(entry.getTarget()) && !api.fsBatchRename(List.of(Map.of(
                        "src_name", oldName, "new_name", entry.getTarget())), parent)) {
                    throw new IllegalStateException("OpenList 合集重命名失败: " + oldName);
                }
            }
            api.fsMoveAndWait(parent, task.getSavePath(), List.of(entry.getTarget()), deadline);
        }
        List<OpenListFileInfo> confirmed = api.fsListChecked(task.getSavePath(), true);
        if (!store.get(id).getCollectionFiles().stream().allMatch(entry -> confirmed.stream()
                .anyMatch(file -> file.getName().equals(entry.getTarget())
                        && Objects.equals(file.getSize(), entry.getLength())))) {
            throw new IllegalStateException("OpenList 合集移动后文件尚不可见，稍后重试");
        }
        var planned = store.get(id);
        long size = planned.getCollectionFiles().stream().mapToLong(OpenListTaskStore.CollectionFile::getLength).sum();
        store.completed(id, planned.getFiles(), size);
        // Keep excluded files in the isolated staging directory for user inspection.
        return true;
    }

    private static String fullPath(OpenListFileInfo file) {
        return file.getPath() + "/" + file.getName();
    }
}
