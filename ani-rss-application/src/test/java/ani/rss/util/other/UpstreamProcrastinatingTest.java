package ani.rss.util.other;

import ani.rss.cache.CacheUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.enums.NotificationStatusEnum;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpstreamProcrastinatingTest {
    @Test
    void completedSeriesDoesNotSendStaleFeedReminder() {
        check(12, true, List.of(item(12, true)), false, false);
    }

    @Test
    void unfinishedSeriesStillSendsReminder() {
        check(12, true, List.of(item(11, true)), false, true);
    }

    @Test
    void unknownTotalStillSendsReminder() {
        check(0, true, List.of(item(12, true)), false, true);
    }

    @Test
    void completedEpisodeCountModeAlsoSkipsReminder() {
        check(2, false, List.of(item(1, true), item(2, true)), false, false);
    }

    @Test
    void standbyCompletionDoesNotHideStaleUnfinishedMasterFeed() {
        check(12, true, List.of(item(11, true), item(12, false)), true, true);
    }

    private Item item(double episode, boolean master) {
        return new Item().setEpisode(episode).setMaster(master)
                .setPubDate(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)));
    }

    private void check(int total, boolean latest, List<Item> items, boolean masterOnly, boolean reminder) {
        var config = ConfigUtil.CONFIG;
        var enabled = config.getProcrastinating();
        var days = config.getProcrastinatingDay();
        var onlyMaster = config.getProcrastinatingMasterOnly();
        var standby = config.getStandbyRss();
        var coexist = config.getCoexist();
        var ani = new Ani().setId("test-procrastinating").setTitle("Test")
                .setProcrastinating(true).setTotalEpisodeNumber(total).setDownloadNew(latest);
        try (var notifications = mockStatic(NotificationUtil.class); var cache = mockStatic(CacheUtils.class)) {
            config.setProcrastinating(true).setProcrastinatingDay(7)
                    .setProcrastinatingMasterOnly(masterOnly).setStandbyRss(true).setCoexist(false);
            ItemsUtil.procrastinating(ani, items);
            if (reminder) {
                notifications.verify(() -> NotificationUtil.send(eq(config), eq(ani), anyString(),
                        eq(NotificationStatusEnum.PROCRASTINATING)));
                cache.verify(() -> CacheUtils.put(eq("procrastinating:test-procrastinating"), anyString(), anyLong()));
            } else {
                notifications.verifyNoInteractions();
                cache.verifyNoInteractions();
            }
        } finally {
            config.setProcrastinating(enabled).setProcrastinatingDay(days)
                    .setProcrastinatingMasterOnly(onlyMaster).setStandbyRss(standby).setCoexist(coexist);
        }
    }
}
