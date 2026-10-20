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

            // A standalone cloud collection uses the same delivery/receipt machinery.
            var localMove=new NotificationConfig().setEnable(true).setSort(2L)
                    .setNotificationType(NotificationTypeEnum.FILE_MOVE)
                    .setStatusList(List.of(NotificationStatusEnum.DOWNLOAD_END));
            var upload=new NotificationConfig().setEnable(true).setSort(3L)
                    .setNotificationType(NotificationTypeEnum.OPEN_LIST_UPLOAD)
                    .setStatusList(List.of(NotificationStatusEnum.DOWNLOAD_END));
            config.setNotificationConfigList(List.of(channel,localMove,upload));
            var collection=new Ani().setTitle("Collection").setMessage(true)
                    .setCollectionDownloadPath("/115/Anime/Collection");
            var collectionDone=new CountDownLatch(1);
            NotificationUtil.sendCompletion(config,collection,"done","collection-task",
                    ()->{collectionDone.countDown();return true;});
            assertTrue(collectionDone.await(10,TimeUnit.SECONDS));
            verify(sender).send(any(),argThat(subject->"/115/Anime/Collection".equals(subject.getCollectionDownloadPath())),
                    eq("done"),eq(NotificationStatusEnum.DOWNLOAD_END));
            reflect.verify(()->ReflectUtil.newInstance(ani.rss.notification.FileMoveNotification.class),never());
            reflect.verify(()->ReflectUtil.newInstance(ani.rss.notification.OpenListUploadNotification.class),never());

            collection.setMessage(false);
            var disabledDone=new CountDownLatch(1);
            NotificationUtil.sendCompletion(config,collection,"done","disabled-collection",
                    ()->{disabledDone.countDown();return true;});
            assertTrue(disabledDone.await(10,TimeUnit.SECONDS));
            verify(sender,times(3)).send(any(),any(),eq("done"),eq(NotificationStatusEnum.DOWNLOAD_END));
        }
    }
}
