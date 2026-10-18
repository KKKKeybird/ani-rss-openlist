package ani.rss.task;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.service.DownloadService;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.thread.ThreadUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;
class RenameTaskNotificationTest {
    @Test void pendingNotificationPreventsAutomaticDeletion() {
        var item=new TorrentsInfo();var service=mock(DownloadService.class);var task=new RenameTask();
        ReflectionTestUtils.setField(task,"downloadService",service);
        try(var torrent=mockStatic(TorrentUtil.class);var sleep=mockStatic(ThreadUtil.class)) {
            torrent.when(TorrentUtil::login).thenReturn(true);
            torrent.when(TorrentUtil::getTorrentsInfos).thenReturn(List.of(item));
            when(service.notification(item)).thenReturn(false);
            task.accept(new AtomicBoolean(true));
            torrent.verify(()->TorrentUtil.rename(item));
            torrent.verify(()->TorrentUtil.delete(item),never());
        }
    }
}
