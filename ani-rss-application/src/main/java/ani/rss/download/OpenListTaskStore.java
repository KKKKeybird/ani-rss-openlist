package ani.rss.download;

import ani.rss.entity.OpenListTaskInfo;
import ani.rss.handle.JsonFile;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Local metadata for tasks created by Ani-RSS. OpenList does not support the
 * category and tags used by the ordinary downloader workflow, so those values
 * must survive restarts on the Ani-RSS side.
 */
public class OpenListTaskStore {
    private final JsonFile file;
    private final Map<String, Task> tasks = new LinkedHashMap<>();

    public OpenListTaskStore(File path) {
        File parent = path.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create OpenList task directory: " + parent);
        }
        file = JsonFile.getInstance(path);
        for (Task task : file.toList(Task.class, new ArrayList<>())) {
            if (task != null && task.getId() != null) {
                tasks.put(task.getId(), task);
            }
        }
    }

    public synchronized void submitted(Task task) {
        tasks.values().removeIf(existing ->
                Objects.equals(existing.getHash(), task.getHash()));
        tasks.put(task.getId(), copy(task));
        save();
    }

    public synchronized Task get(String id) {
        return copy(tasks.get(id));
    }

    public synchronized List<Task> list() {
        return tasks.values().stream().map(OpenListTaskStore::copy).toList();
    }

    public synchronized void progress(String id, OpenListTaskInfo info) {
        Task task = tasks.get(id);
        if (task == null || task.isCompleted() || info == null) {
            return;
        }
        boolean changed = false;
        Double progress = info.getProgress();
        if (progress != null) {
            int value = (int) Math.clamp(progress, 0, 99);
            if (task.getProgress() != value) {
                task.setProgress(value);
                changed = true;
            }
        }
        if (info.getTotalBytes() != null && task.getSize() != info.getTotalBytes()) {
            task.setSize(info.getTotalBytes());
            changed = true;
        }
        OpenListTaskInfo.State state = info.getState();
        if (state != null && !state.name().equals(task.getState())) {
            task.setState(state.name());
            changed = true;
        }
        if (!Objects.equals(task.getError(), info.getError())) {
            task.setError(info.getError());
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    public synchronized void completed(String id, List<String> files, long size) {
        Task task = require(id);
        task.setFiles(new ArrayList<>(files));
        task.setSize(size);
        task.setProgress(100);
        task.setCompleted(true);
        task.setState(OpenListTaskInfo.State.Succeeded.name());
        task.setError(null);
        save();
    }

    public synchronized void planned(String id, List<String> files, long size) {
        Task task = require(id);
        task.setFiles(new ArrayList<>(files));
        task.setSize(size);
        save();
    }

    public synchronized void failed(String id, String reason) {
        Task task = tasks.get(id);
        if (task == null) {
            return;
        }
        task.setState(OpenListTaskInfo.State.Failed.name());
        task.setError(reason);
        save();
    }

    /** Returns false when the tag was already present, preventing duplicate notifications. */
    public synchronized boolean addTag(String id, String tag) {
        Task task = require(id);
        if (task.getTags().contains(tag)) {
            return false;
        }
        task.getTags().add(tag);
        save();
        return true;
    }

    public synchronized void moved(String id, String path) {
        Task task = require(id);
        task.setSavePath(path);
        save();
    }

    public synchronized void remove(String id) {
        if (tasks.remove(id) != null) {
            save();
        }
    }

    private Task require(String id) {
        Task task = tasks.get(id);
        if (task == null) {
            throw new IllegalArgumentException("Unknown OpenList task: " + id);
        }
        return task;
    }

    private void save() {
        file.writer(new ArrayList<>(tasks.values()));
    }

    private static Task copy(Task source) {
        if (source == null) {
            return null;
        }
        return new Task()
                .setId(source.getId())
                .setHash(source.getHash())
                .setName(source.getName())
                .setSavePath(source.getSavePath())
                .setStagingPath(source.getStagingPath())
                .setCollectionFiles(source.getCollectionFiles().stream().map(CollectionFile::copy).toList())
                .setCollectionPlanned(source.isCollectionPlanned())
                .setSubmittedAt(source.getSubmittedAt())
                .setRetries(source.getRetries())
                .setTags(new ArrayList<>(source.getTags()))
                .setFiles(new ArrayList<>(source.getFiles()))
                .setSize(source.getSize())
                .setProgress(source.getProgress())
                .setState(source.getState())
                .setError(source.getError())
                .setCompleted(source.isCompleted());
    }

    public synchronized void planCollection(String id, List<CollectionFile> entries) {
        Task task = require(id);
        task.setCollectionFiles(entries.stream().map(CollectionFile::copy).toList());
        task.setCollectionPlanned(true);
        task.setFiles(entries.stream().map(CollectionFile::getTarget).toList());
        task.setSize(entries.stream().mapToLong(CollectionFile::getLength).sum());
        save();
    }

    public synchronized void retried(String id) {
        require(id).setRetries(require(id).getRetries() + 1);
        save();
    }

    @Data
    @Accessors(chain = true)
    public static class CollectionFile {
        private String source;
        private String target;
        private long length;
        // Absolute source file path, resolved before any cloud mutation.
        private String resolvedPath;

        private CollectionFile copy() {
            return new CollectionFile().setSource(source).setTarget(target)
                    .setLength(length).setResolvedPath(resolvedPath);
        }
    }

    @Data
    @Accessors(chain = true)
    public static class Task {
        private String id;
        private String hash;
        private String name;
        private String savePath;
        private String stagingPath;
        private List<String> tags = new ArrayList<>();
        private List<String> files = new ArrayList<>();
        private List<CollectionFile> collectionFiles = new ArrayList<>();
        private boolean collectionPlanned;
        private long submittedAt;
        private long retries;
        private long size;
        private int progress;
        private String state = OpenListTaskInfo.State.Pending.name();
        private String error;
        private boolean completed;
    }
}
