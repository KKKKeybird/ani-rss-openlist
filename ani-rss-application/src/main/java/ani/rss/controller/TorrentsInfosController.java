package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.entity.web.Result;
import ani.rss.util.other.TorrentUtil;
import ani.rss.service.DownloadTaskDeletion;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TorrentsInfosController extends BaseController {

    @Auth
    @Operation(summary = "下载列表")
    @PostMapping("/torrentsInfos")
    public Result<List<TorrentsInfo>> torrentsInfos() {
        List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();
        return Result.success(torrentsInfos);
    }

    @Auth
    @Operation(summary = "删除下载任务，保留已下载文件")
    @PostMapping("/deleteDownloadTasks")
    public Result<DownloadTaskDeletion.Outcome> deleteDownloadTasks(@RequestBody List<String> ids,
            @RequestParam(defaultValue = "false") boolean failedOnly) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            return Result.error("请选择需要删除的下载任务");
        }
        return Result.success(TorrentUtil.deleteTasks(ids, failedOnly));
    }

}
