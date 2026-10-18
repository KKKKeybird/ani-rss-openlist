package ani.rss.util.other;
import ani.rss.entity.*;
import ani.rss.enums.*;
import ani.rss.notification.BaseNotification;
import cn.hutool.core.thread.ThreadUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class NotificationResultTest {
    @Test void falseResultRetriesAndOnlyTrueAcknowledges() {
        var sender=mock(BaseNotification.class);var config=new NotificationConfig().setRetry(2).setNotificationType(NotificationTypeEnum.MAIL);
        var ani=new Ani().setTitle("Student Council");
        when(sender.send(config,ani,"done",NotificationStatusEnum.DOWNLOAD_END)).thenReturn(false,true);
        try(var sleep=mockStatic(ThreadUtil.class)) {
            assertTrue(NotificationUtil.deliver(sender,config,ani,"done",NotificationStatusEnum.DOWNLOAD_END));
            verify(sender,times(2)).send(config,ani,"done",NotificationStatusEnum.DOWNLOAD_END);
        }
    }
    @Test void nullOrExceptionIsNeverAcknowledgedAsSuccess() {
        var sender=mock(BaseNotification.class);var config=new NotificationConfig().setRetry(2).setNotificationType(NotificationTypeEnum.MAIL);
        var ani=new Ani().setTitle("Student Council");
        when(sender.send(config,ani,"done",NotificationStatusEnum.DOWNLOAD_END)).thenReturn(null).thenThrow(new IllegalStateException());
        try(var sleep=mockStatic(ThreadUtil.class)) {
            assertFalse(NotificationUtil.deliver(sender,config,ani,"done",NotificationStatusEnum.DOWNLOAD_END));
            verify(sender,times(2)).send(config,ani,"done",NotificationStatusEnum.DOWNLOAD_END);
        }
    }
}
