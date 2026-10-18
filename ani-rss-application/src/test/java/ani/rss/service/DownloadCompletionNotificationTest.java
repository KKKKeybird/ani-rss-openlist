package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.util.other.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadCompletionNotificationTest {
    private TorrentsInfo task() {
        var task=mock(TorrentsInfo.class);
        when(task.finished()).thenReturn(true);when(task.getTagList()).thenReturn(List.of());
        when(task.getName()).thenReturn("Student E03");when(task.getId()).thenReturn("id");
        return task;
    }
    @Test void lookupFailureDoesNotConsumeCompletionTag() {
        var task=task();var service=mock(DownloadService.class,CALLS_REAL_METHODS);
        doReturn(Optional.empty()).when(service).findAniByDownloadPath(task);
        try(var torrent=mockStatic(TorrentUtil.class);var notifications=mockStatic(NotificationUtil.class)) {
            assertFalse(service.notification(task));
            torrent.verifyNoInteractions();notifications.verifyNoInteractions();
        }
    }
    @Test void onlySuccessfulDeliveryCallbackAddsTagAndAllowsSubscriptionMigration() {
        var task=task();var service=mock(DownloadService.class,CALLS_REAL_METHODS);
        var ani=new Ani().setTitle("Student Council").setSubgroup("group").setStandbyRssList(List.of());
        doReturn(Optional.of(ani)).when(service).findAniByDownloadPath(task);
        var previous=ConfigUtil.CONFIG.getScrape();ConfigUtil.CONFIG.setScrape(false);
        try(var torrent=mockStatic(TorrentUtil.class);var notifications=mockStatic(NotificationUtil.class);var aniUtil=mockStatic(AniUtil.class)) {
            var ack=ArgumentCaptor.forClass(BooleanSupplier.class);
            assertFalse(service.notification(task));
            notifications.verify(()->NotificationUtil.sendCompletion(any(),eq(ani),anyString(),anyString(),ack.capture()));
            torrent.verifyNoInteractions();aniUtil.verifyNoInteractions();
            torrent.when(()->TorrentUtil.addTags(task,"下载完成")).thenReturn(true);
            assertTrue(ack.getValue().getAsBoolean());
            torrent.verify(()->TorrentUtil.addTags(task,"下载完成"));aniUtil.verify(()->AniUtil.completed(ani));
        } finally {ConfigUtil.CONFIG.setScrape(previous);}
    }
    @Test void existingCompletionTagIsNotReplayedOnUpgrade() {
        var task=task();when(task.getTagList()).thenReturn(List.of("下载完成"));
        try(var notifications=mockStatic(NotificationUtil.class)) {
            assertTrue(new DownloadService().notification(task));notifications.verifyNoInteractions();
        }
    }
}
