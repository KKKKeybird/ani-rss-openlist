package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.NotificationTypeEnum;
import ani.rss.notification.*;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReflectUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class NotificationUtil {
    private static final ExecutorService EXECUTOR_SERVICE = ExecutorBuilder.create()
            .setCorePoolSize(1)
            .setMaxPoolSize(1)
            .setWorkQueue(new LinkedBlockingQueue<>(256))
            .build();

    public final static Map<NotificationTypeEnum, Class<? extends BaseNotification>>
            NOTIFICATION_MAP =
            Map.of(
                    NotificationTypeEnum.EMBY_REFRESH, EmbyRefreshNotification.class,
                    NotificationTypeEnum.MAIL, MailNotification.class,
                    NotificationTypeEnum.SERVER_CHAN, ServerChanNotification.class,
                    NotificationTypeEnum.SYSTEM, SystemNotification.class,
                    NotificationTypeEnum.TELEGRAM, TelegramNotification.class,
                    NotificationTypeEnum.WEB_HOOK, WebHookNotification.class,
                    NotificationTypeEnum.SHELL, ShellNotification.class,
                    NotificationTypeEnum.FILE_MOVE, FileMoveNotification.class,
                    NotificationTypeEnum.OPEN_LIST_UPLOAD, OpenListUploadNotification.class,
                    NotificationTypeEnum.BARK, BarkNotification.class
            );

    /**
     * 发送通知
     *
     * @param config                 设置
     * @param ani                    订阅
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     */
    public static void send(Config config, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        Boolean isMessage = ani.getMessage();

        if (!isMessage) {
            // 未开启此订阅通知
            return;
        }

        List<NotificationConfig> notificationConfigList = config.getNotificationConfigList();
        notificationConfigList = notificationConfigList
                .stream()
                .sorted(Comparator.comparingLong(NotificationConfig::getSort))
                .toList();

        for (NotificationConfig notificationConfig : notificationConfigList) {
            boolean enable = notificationConfig.getEnable();
            NotificationTypeEnum notificationType = notificationConfig.getNotificationType();
            List<NotificationStatusEnum> statusList = notificationConfig.getStatusList();

            // 通知状态可能被删除
            statusList = statusList.stream().filter(Objects::nonNull).toList();

            if (!enable) {
                // 未开启
                continue;
            }

            if (!statusList.contains(notificationStatusEnum)) {
                // 未启用 通知状态
                continue;
            }

            if (Objects.isNull(notificationType)) {
                // 通知类型可能已经被删除
                continue;
            }

            if (!NOTIFICATION_MAP.containsKey(notificationType)) {
                continue;
            }

            Class<? extends BaseNotification> aClass = NOTIFICATION_MAP.get(notificationType);

            BaseNotification baseNotification = ReflectUtil.newInstance(aClass);
            EXECUTOR_SERVICE.execute(() -> deliver(baseNotification, notificationConfig, ani, text, notificationStatusEnum));
        }
    }
    static boolean deliver(BaseNotification sender, NotificationConfig config, Ani ani, String text,
                           NotificationStatusEnum status) {
        int attempts = Math.max(1, config.getRetry() == null ? 1 : config.getRetry());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                if (Boolean.TRUE.equals(sender.send(config, ani, text, status))) {
                    log.info("通知渠道返回成功 type={} event={} title={}", config.getNotificationType(), status, ani.getTitle());
                    return true;
                }
                log.warn("通知渠道返回失败 type={} event={} title={} attempt={}/{}",
                        config.getNotificationType(), status, ani.getTitle(), attempt, attempts);
            } catch (Exception e) {
                log.warn("通知发送异常 type={} event={} title={} attempt={}/{} exception={}",
                        config.getNotificationType(), status, ani.getTitle(), attempt, attempts, e.getClass().getSimpleName());
            }
            if (attempt < attempts) ThreadUtil.sleep(1000);
        }
        return false;
    }

    private static class CompletionHolder {
        static final CompletionNotificationDelivery DELIVERY = new CompletionNotificationDelivery(
                new java.io.File(ConfigUtil.getConfigDir(), "cache/notification-deliveries.json"),
                EXECUTOR_SERVICE, System::currentTimeMillis);
    }

    public static void sendCompletion(Config config, Ani ani, String text, String task,
                                      java.util.function.BooleanSupplier acknowledge) {
        var channels = new java.util.ArrayList<CompletionNotificationDelivery.Channel>();
        if (Boolean.TRUE.equals(ani.getMessage())) {
            int index = 0;
            for (var source : config.getNotificationConfigList().stream()
                    .sorted(Comparator.comparingLong(NotificationConfig::getSort)).toList()) {
                int ordinal = index++;
                if (!Boolean.TRUE.equals(source.getEnable()) || source.getStatusList() == null
                        || !source.getStatusList().contains(NotificationStatusEnum.DOWNLOAD_END)
                        || source.getNotificationType() == null || !NOTIFICATION_MAP.containsKey(source.getNotificationType())) continue;
                if (ani.getCollectionDownloadPath() != null
                        && (source.getNotificationType() == NotificationTypeEnum.FILE_MOVE
                        || source.getNotificationType() == NotificationTypeEnum.OPEN_LIST_UPLOAD)) {
                    // This subject describes cloud files, not a local subscription directory.
                    log.info("OpenList 合集跳过本地文件处理渠道 task={} type={}", task, source.getNotificationType());
                    continue;
                }
                var snapshot = cn.hutool.core.util.ObjectUtil.clone(source);
                var subject = cn.hutool.core.util.ObjectUtil.clone(ani);
                String key = cn.hutool.crypto.digest.DigestUtil.sha256Hex(ordinal + ":"
                        + GsonStatic.toJson(snapshot));
                BaseNotification sender = ReflectUtil.newInstance(NOTIFICATION_MAP.get(snapshot.getNotificationType()));
                channels.add(new CompletionNotificationDelivery.Channel(key, () -> deliver(
                        sender, snapshot, subject, text, NotificationStatusEnum.DOWNLOAD_END)));
            }
        }
        if (channels.isEmpty()) log.info("完成通知按配置跳过 task={} title={}", task, ani.getTitle());
        CompletionHolder.DELIVERY.submit(task, channels, acknowledge);
    }

}
