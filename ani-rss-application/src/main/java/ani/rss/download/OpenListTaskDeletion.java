package ani.rss.download;

import ani.rss.entity.OpenListTaskInfo;
import ani.rss.util.other.OpenListUtil;

import java.util.List;

/** Removes task records only; cloud files and staging directories are retained. */
public class OpenListTaskDeletion {
    private final OpenListUtil api;
    private final OpenListTaskStore store;

    public OpenListTaskDeletion(OpenListUtil api, OpenListTaskStore store) {
        this.api = api;
        this.store = store;
    }

    public boolean delete(String id) {
        if (store.get(id) == null) {
            return true;
        }
        OpenListTaskInfo remote = findRemote(id);
        if (remote != null) {
            if (!isTerminal(remote) && !api.taskCancel(id)) {
                // The task may have finished or disappeared between listing and cancellation.
                remote = findRemote(id);
                if (remote != null && !isTerminal(remote)) {
                    return false;
                }
            }
            if (!api.taskDelete(id) && findRemote(id) != null) {
                return false;
            }
        }
        store.remove(id);
        return true;
    }

    private OpenListTaskInfo findRemote(String id) {
        // Both calls are checked: authentication/network failures must never count as absence.
        List<OpenListTaskInfo> done = api.taskDoneList();
        List<OpenListTaskInfo> pending = api.taskUnDoneList();
        return java.util.stream.Stream.concat(done.stream(), pending.stream())
                .filter(task -> id.equals(task.getId())).findFirst().orElse(null);
    }

    private boolean isTerminal(OpenListTaskInfo task) {
        return task.getState() == OpenListTaskInfo.State.Succeeded
                || task.getState() == OpenListTaskInfo.State.Failed
                || task.getState() == OpenListTaskInfo.State.Canceled;
    }
}
