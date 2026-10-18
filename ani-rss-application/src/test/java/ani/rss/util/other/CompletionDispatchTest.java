package ani.rss.util.other;
import ani.rss.entity.*;
import ani.rss.enums.*;
import ani.rss.notification.MailNotification;
import cn.hutool.core.util.ReflectUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CompletionDispatchTest {
    @TempDir Path directory;
    @Test void configuredMailDispatchRetriesFalseAndPersistsReceiptWithoutSecrets() throws Exception {
        var channel=new NotificationConfig().setEnable(true).setRetry(2).setSort(1L)
                .setNotificationType(NotificationTypeEnum.MAIL).setMailPassword("test-secret")
                .setStatusList(List.of(NotificationStatusEnum.DOWNLOAD_END));
        var config=new Config().setNotificationConfigList(List.of(channel));
        var ani=new Ani().setTitle("Student E03").setMessage(true);
        var sender=mock(MailNotification.class);var done=new CountDownLatch(1);
        when(sender.send(any(),any(),eq("done"),eq(NotificationStatusEnum.DOWNLOAD_END))).thenReturn(false,true);
        try(var reflect=mockStatic(ReflectUtil.class);var configs=mockStatic(ConfigUtil.class)) {
            configs.when(ConfigUtil::getConfigDir).thenReturn(directory.toFile());
            reflect.when(()->ReflectUtil.newInstance(MailNotification.class)).thenReturn(sender);
            NotificationUtil.sendCompletion(config,ani,"done","test-task",()->{done.countDown();return true;});
            assertTrue(done.await(10,TimeUnit.SECONDS));
            verify(sender,times(2)).send(any(),any(),eq("done"),eq(NotificationStatusEnum.DOWNLOAD_END));
            String receipt=Files.readString(directory.resolve("cache/notification-deliveries.json"));
            assertFalse(receipt.contains("test-secret"));
        }
    }
}
