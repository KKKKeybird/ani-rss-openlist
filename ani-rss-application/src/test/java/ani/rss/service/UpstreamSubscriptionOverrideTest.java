package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.util.other.AniUtil;
import cn.hutool.core.thread.ThreadUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpstreamSubscriptionOverrideTest {
    @Test
    void duplicateIdWithoutOverridePreservesOriginal() {
        check("original", "Other title", 1, false, true);
    }

    @Test
    void duplicateTitleAndSeasonWithoutOverridePreservesOriginal() {
        check("new", "Show", 1, false, true);
    }

    @Test
    void explicitOverrideReplacesDuplicateId() {
        check("original", "Other title", 1, true, false);
    }

    @Test
    void explicitOverrideReplacesDuplicateTitleAndSeason() {
        check("new", "Show", 1, true, false);
    }

    @Test
    void differentSeasonCanBeAddedWithoutOverride() {
        check("new", "Show", 2, false, false);
    }

    private void check(String id, String title, int season, boolean override, boolean rejected) {
        var saved = new ArrayList<>(AniUtil.ANI_LIST);
        var original = new Ani().setId("original").setTitle("Show").setSeason(1).setEnable(false);
        var incoming = new Ani().setId(id).setTitle(title).setSeason(season).setEnable(false);
        try (var ani = mockStatic(AniUtil.class); var threads = mockStatic(ThreadUtil.class)) {
            AniUtil.ANI_LIST.clear();
            AniUtil.ANI_LIST.add(original);
            var service = new AniService();
            if (rejected) {
                assertThrows(IllegalArgumentException.class, () -> service.addAni(incoming, override));
                assertEquals(1, AniUtil.ANI_LIST.size());
                assertSame(original, AniUtil.ANI_LIST.getFirst());
                ani.verify(AniUtil::sync, never());
                threads.verifyNoInteractions();
            } else {
                service.addAni(incoming, override);
                assertTrue(AniUtil.ANI_LIST.contains(incoming));
                assertEquals(override ? 1 : 2, AniUtil.ANI_LIST.size());
                assertEquals(!override, AniUtil.ANI_LIST.contains(original));
                ani.verify(AniUtil::sync);
            }
        } finally {
            AniUtil.ANI_LIST.clear();
            AniUtil.ANI_LIST.addAll(saved);
        }
    }
}
