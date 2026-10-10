package ani.rss.util.other;

import ani.rss.commons.GsonStatic;
import ani.rss.config.OpenListConfig;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.OpenListTaskInfo;
import ani.rss.entity.web.Header;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Stream;

@Slf4j
public class OpenListUtil {
    private final OpenListConfig openListConfig;

    private OpenListUtil(OpenListConfig openListConfig) {
        this.openListConfig = openListConfig;
    }

    public static OpenListUtil getInstance(String server, String apiKey) {
        return new OpenListUtil(new OpenListConfig(server, apiKey));
    }

    public static OpenListUtil getInstance(OpenListConfig openListConfig) {
        return new OpenListUtil(openListConfig);
    }

    public Boolean test() {
        return getApi("me")
                .thenFunction(res -> success(res, "me"));
    }

    /**
     * 创建文件夹
     *
     * @param path 路径
     */
    public boolean mkdir(String path) {
        return postApi("fs/mkdir")
                .body(GsonStatic.toJson(Map.of(
                        "path", path
                )))
                .thenFunction(res -> {
                    if (success(res, "fs/mkdir")) {
                        log.info("创建文件夹: {}", path);
                        return true;
                    }
                    return false;
                });
    }

    /**
     * 移动文件
     *
     * @param srcDir 原目录
     * @param dstDir 目标目录
     * @param names  文件名
     */
    public boolean fsMove(String srcDir, String dstDir, List<String> names) {
        return postApi("fs/move")
                .body(GsonStatic.toJson(Map.of(
                        "src_dir", srcDir,
                        "dst_dir", dstDir,
                        "names", names
                )))
                .thenFunction(res -> success(res, "fs/move"));
    }

    /** OpenList 4.2.x may return background move tasks; wait for those tasks to finish. */
    public void fsMoveAndWait(String srcDir, String dstDir, List<String> names, long deadline) {
        List<String> ids = postApi("fs/move")
                .body(GsonStatic.toJson(Map.of(
                        "src_dir", srcDir,
                        "dst_dir", dstDir,
                        "names", names
                )))
                .thenFunction(res -> {
                    requireSuccess(res, "fs/move");
                    JsonObject body = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonObject data = body.getAsJsonObject("data");
                    if (data == null || !data.has("tasks") || data.get("tasks").isJsonNull()) {
                        return List.<String>of();
                    }
                    List<String> result = new ArrayList<>();
                    for (JsonElement element : data.getAsJsonArray("tasks")) {
                        result.add(element.getAsJsonObject().get("id").getAsString());
                    }
                    return result;
                });
        for (String id : ids) {
            while (System.currentTimeMillis() < deadline) {
                OpenListTaskInfo info = taskInfo("move", id).orElseThrow(
                        () -> new IllegalStateException("OpenList move task disappeared: " + id));
                if (info.getState() == OpenListTaskInfo.State.Succeeded) {
                    break;
                }
                if (List.of(OpenListTaskInfo.State.Error, OpenListTaskInfo.State.Failing,
                        OpenListTaskInfo.State.Failed, OpenListTaskInfo.State.Canceled)
                        .contains(info.getState())) {
                    throw new IllegalStateException("OpenList move failed: " + info.getError());
                }
                ThreadUtil.sleep(1000);
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("OpenList move timed out: " + id);
            }
        }
    }

    /**
     * 删除文件
     *
     * @param dir   目录
     * @param names 文件名
     */
    public boolean fsRemove(String dir, List<String> names) {
        return postApi("fs/remove")
                .body(GsonStatic.toJson(Map.of(
                        "dir", dir,
                        "names", names
                )))
                .thenFunction(res -> success(res, "fs/remove"));
    }

    /**
     * 批量重命名
     *
     * @param mapList 重命名列表
     * @param srcDir  目录
     */
    public boolean fsBatchRename(List<Map<String, String>> mapList, String srcDir) {
        return postApi("fs/batch_rename")
                .body(GsonStatic.toJson(Map.of(
                        "src_dir", srcDir,
                        "rename_objects", mapList
                )))
                .thenFunction(res -> success(res, "fs/batch_rename"));
    }

    /**
     * 添加离线下载
     *
     * @param magnet 磁力链接
     * @param path   离线位置
     * @return tid
     */
    public String fsAddOfflineDownload(String magnet, String path, String tool) {
        return postApi("fs/add_offline_download")
                .body(GsonStatic.toJson(Map.of(
                        "path", path,
                        "urls", List.of(magnet),
                        "tool", tool,
                        "delete_policy", "delete_on_upload_succeed"
                )))
                .thenFunction(res -> {
                    requireSuccess(res, "fs/add_offline_download");
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    log.debug(jsonObject.toString());
                    return jsonObject.getAsJsonObject("data")
                            .getAsJsonArray("tasks")
                            .get(0).getAsJsonObject()
                            .get("id").getAsString();
                });
    }

    /**
     * 文件列表
     *
     * @param path 目录
     * @return 文件列表
     */
    public List<OpenListFileInfo> fsList(String path, Boolean refresh) {
        try {
            return fsListChecked(path, refresh);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return List.of();
    }

    /** Unlike fsList, an API failure must not look like an empty directory. */
    public List<OpenListFileInfo> fsListChecked(String path, Boolean refresh) {
        return postApi("fs/list")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path,
                            "page", 1,
                            "per_page", 0,
                            "refresh", refresh
                    )))
                    .thenFunction(res -> {
                        requireSuccess(res, "fs/list");
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        JsonElement data = jsonObject.get("data");
                        if (Objects.isNull(data) || data.isJsonNull()) {
                            return List.of();
                        }
                        JsonElement content = data.getAsJsonObject()
                                .get("content");
                        if (Objects.isNull(content) || content.isJsonNull()) {
                            return List.of();
                        }
                        List<OpenListFileInfo> infos = GsonStatic.fromJsonList(content.getAsJsonArray(), OpenListFileInfo.class);
                        for (OpenListFileInfo info : infos) {
                            info.setPath(path);
                        }
                        return ListUtil.sort(new ArrayList<>(infos), Comparator.comparing(fileInfo -> {
                            Long size = fileInfo.getSize();
                            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
                        }));
                    });
    }

    /**
     * 查看任务
     *
     * @param tid 任务id
     * @return 任务信息
     */
    public Optional<OpenListTaskInfo> taskInfo(String tid) {
        return taskInfo("offline_download", tid);
    }

    public Optional<OpenListTaskInfo> taskInfo(String type, String tid) {
        try {
            OpenListTaskInfo taskInfo = postApi("task/" + type + "/info?tid=" + tid)
                    .thenFunction(res -> {
                        requireSuccess(res, "task/offline_download/info");
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        JsonElement dataElement = jsonObject.get("data");
                        if (dataElement == null || dataElement.isJsonNull()) {
                            return null;
                        }
                        JsonObject data = dataElement.getAsJsonObject();
                        return GsonStatic.fromJson(data, OpenListTaskInfo.class);
                    });
            return Optional.ofNullable(taskInfo);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * 删除残留任务
     *
     * @param magnet 磁力
     */
    public void deleteResidualTasks(String magnet) {
        List<OpenListTaskInfo> taskDoneList = taskDoneList();
        List<OpenListTaskInfo> taskUnDoneList = taskUnDoneList();

        List<OpenListTaskInfo> tasks = new ArrayList<>();
        tasks.addAll(taskDoneList);
        tasks.addAll(taskUnDoneList);

        for (OpenListTaskInfo task : tasks) {
            String id = task.getId();
            String name = task.getName();
            if (name.contains(magnet)) {
                log.info("删除残留任务: {} {}", id, name);
                taskDelete(id);
            }
        }
    }

    /**
     * 未完成的离线任务
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskUnDoneList() {
        return getApi("task/offline_download/undone")
                .thenFunction(res -> {
                    requireSuccess(res, "task/offline_download/undone");
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonArray jsonArray = jsonObject.get("data").getAsJsonArray();
                    return GsonStatic.fromJsonList(jsonArray, OpenListTaskInfo.class);
                });
    }

    /**
     * 已完成的离线任务
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskDoneList() {
        return getApi("task/offline_download/done")
                .thenFunction(res -> {
                    requireSuccess(res, "task/offline_download/done");
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonArray jsonArray = jsonObject.get("data").getAsJsonArray();
                    return GsonStatic.fromJsonList(jsonArray, OpenListTaskInfo.class);
                });
    }

    /**
     * 重试任务
     *
     * @param tid 任务id
     */
    public boolean taskRetry(String tid) {
        return postApi("task/offline_download/retry?tid=" + tid)
                .thenFunction(res -> success(res, "task/offline_download/retry"));
    }

    public boolean taskCancel(String tid) {
        return postApi("task/offline_download/cancel?tid=" + tid)
                .thenFunction(res -> success(res, "task/offline_download/cancel"));
    }

    /**
     * 删除任务
     *
     * @param tid 任务id
     */
    public boolean taskDelete(String tid) {
        return postApi("task/offline_download/delete_some")
                .body(GsonStatic.toJson(List.of(tid)))
                .thenFunction(res -> {
                    if (!success(res, "task/offline_download/delete_some")) {
                        return false;
                    }
                    JsonObject data = GsonStatic.fromJson(res.body(), JsonObject.class)
                            .getAsJsonObject("data");
                    return data == null || !data.has(tid);
                });
    }

    /**
     * 获取目录下及子目录的文件
     *
     * @param path 目录
     * @return 文件列表
     */
    public List<OpenListFileInfo> findFiles(String path) {
        List<OpenListFileInfo> openListFileInfos = fsListChecked(path, true);
        List<OpenListFileInfo> list = openListFileInfos.stream()
                .flatMap(openListFileInfo -> {
                    if (openListFileInfo.getIsDir()) {
                        try {
                            return findFiles(path + "/" + openListFileInfo.getName()).stream();
                        } catch (IllegalStateException error) {
                            if (error.getMessage() != null
                                    && error.getMessage().contains("object not found")) {
                                log.debug("OpenList 递归目录已不存在，跳过: {}", openListFileInfo.getName());
                                return Stream.empty();
                            }
                            throw error;
                        }
                    }
                    return Stream.of(openListFileInfo);
                }).toList();

        return ListUtil.sort(new ArrayList<>(list), Comparator.comparing(fileInfo -> {
            Long size = fileInfo.getSize();
            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
        }));
    }

    /**
     * get api
     *
     * @param action Action
     * @return HttpRequest
     */
    public HttpRequest getApi(String action) {
        String server = openListConfig.getServer();
        String apiKey = openListConfig.getApiKey();
        return HttpReq.get(server + "/api/" + action)
                .header(Header.AUTHORIZATION, apiKey);
    }

    /**
     * post api
     *
     * @param action Action
     * @return HttpRequest
     */
    public HttpRequest postApi(String action) {
        String server = openListConfig.getServer();
        String apiKey = openListConfig.getApiKey();
        return HttpReq.post(server + "/api/" + action)
                .header(Header.AUTHORIZATION, apiKey);
    }

    private static boolean success(HttpResponse response, String operation) {
        try {
            requireSuccess(response, operation);
            return true;
        } catch (RuntimeException error) {
            log.warn("OpenList {} failed: {}", operation, error.getMessage());
            return false;
        }
    }

    private static void requireSuccess(HttpResponse response, String operation) {
        HttpReq.assertStatus(response);
        JsonObject body = GsonStatic.fromJson(response.body(), JsonObject.class);
        if (body == null || !body.has("code") || body.get("code").getAsInt() != 200) {
            String message = body != null && body.has("message")
                    ? body.get("message").getAsString() : "missing API success code";
            throw new IllegalStateException("OpenList " + operation + ": " + message);
        }
    }
}
