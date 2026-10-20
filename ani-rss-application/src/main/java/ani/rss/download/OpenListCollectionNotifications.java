package ani.rss.download;

import ani.rss.entity.Ani;
import cn.hutool.core.util.ObjectUtil;

import java.util.List;
import java.util.Objects;

/** Notification-only subjects, never inserted into the RSS subscription list. */
public final class OpenListCollectionNotifications {
    private OpenListCollectionNotifications() {}

    public static Ani subject(OpenListTaskStore.Task task) {
        if (task.getCollectionFiles().isEmpty()) return null;
        Ani subject = ObjectUtil.clone(task.getCollectionNotification());
        if (subject == null) {
            // Legacy tasks only retain this display name, tags and destination.
            // Follow current global completion channels without inventing metadata.
            subject = new Ani().setTitle(Objects.toString(task.getName(), "合集"))
                    .setSubgroup(task.getTags().stream()
                            .filter(tag -> !List.of("ANI-RSS合集下载", "下载完成").contains(tag))
                            .findFirst().orElse("未知字幕组"));
        }
        if (subject.getMessage() == null) subject.setMessage(true);
        if (subject.getImage() == null) subject.setImage("");
        subject.setStandbyRssList(List.of()).setEnable(false)
                .setCollectionDownloadPath(task.getSavePath());
        return subject;
    }
}
