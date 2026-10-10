package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.StandbyRss;
import ani.rss.entity.dto.RssToAniDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpstreamBatchSubscriptionTest {
    private Boolean previousOffset;

    @BeforeEach
    void enableOffset() {
        previousOffset = ConfigUtil.CONFIG.getOffset();
        ConfigUtil.CONFIG.setOffset(true);
    }

    @AfterEach
    void restoreOffset() {
        ConfigUtil.CONFIG.setOffset(previousOffset);
    }

    private RssToAniDTO dto(List<StandbyRss> standby) {
        return new RssToAniDTO().setType("mikan").setUrl("https://test.invalid/main")
                .setBgmUrl("https://bgm.tv/subject/1").setSubgroup("main team")
                .setStandbyRssList(standby);
    }

    @Test
    void batchStandbyFeedsUseTheirOwnEpisodeOffsetsAndRetainMetadata() {
        var first = new StandbyRss().setUrl("https://test.invalid/first").setLabel("first team").setOffset(0);
        var second = new StandbyRss().setUrl("https://test.invalid/second").setLabel("second team").setOffset(0);
        try (var bgm = mockStatic(BgmUtil.class); var items = mockStatic(ItemsUtil.class, CALLS_REAL_METHODS)) {
            items.when(() -> ItemsUtil.getItems(any(Ani.class), eq("https://test.invalid/main"), eq("")))
                    .thenReturn(List.of(new Item().setEpisode(13.0), new Item().setEpisode(14.0)));
            items.when(() -> ItemsUtil.getItems(any(Ani.class), eq(first.getUrl()), eq("")))
                    .thenReturn(List.of(new Item().setEpisode(1.0)));
            items.when(() -> ItemsUtil.getItems(any(Ani.class), eq(second.getUrl()), eq("")))
                    .thenReturn(List.of(new Item().setEpisode(12.5)));
            Ani ani = AniUtil.getAni(dto(List.of(first, second)));
            assertEquals(-12, ani.getOffset());
            assertEquals(0, ani.getStandbyRssList().get(0).getOffset());
            assertEquals(-12, ani.getStandbyRssList().get(1).getOffset());
            assertEquals("first team", ani.getStandbyRssList().get(0).getLabel());
            assertEquals(second.getUrl(), ani.getStandbyRssList().get(1).getUrl());
            assertTrue(ani.getEnable());
        }
    }

    @Test
    void emptyMainFeedDoesNotPreventCalculatingStandbyOffset() {
        var standby = new StandbyRss().setUrl("https://test.invalid/standby").setLabel("backup");
        try (var bgm = mockStatic(BgmUtil.class); var items = mockStatic(ItemsUtil.class, CALLS_REAL_METHODS)) {
            items.when(() -> ItemsUtil.getItems(any(Ani.class), eq("https://test.invalid/main"), eq("")))
                    .thenReturn(List.of());
            items.when(() -> ItemsUtil.getItems(any(Ani.class), eq(standby.getUrl()), eq("")))
                    .thenReturn(List.of(new Item().setEpisode(25.0)));
            Ani ani = AniUtil.getAni(dto(List.of(standby)));
            assertEquals(0, ani.getOffset());
            assertEquals(-24, ani.getStandbyRssList().getFirst().getOffset());
        }
    }

    @Test
    void disabledAutoOffsetPreservesExplicitStandbyOffsetsAndEnableFlag() {
        ConfigUtil.CONFIG.setOffset(false);
        var standby = new StandbyRss().setUrl("https://test.invalid/backup").setLabel("backup").setOffset(7);
        try (var bgm = mockStatic(BgmUtil.class); var items = mockStatic(ItemsUtil.class)) {
            Ani ani = AniUtil.getAni(dto(List.of(standby)).setEnable(false));
            assertEquals(7, ani.getStandbyRssList().getFirst().getOffset());
            assertFalse(ani.getEnable());
            items.verifyNoInteractions();
        }
    }

    @Test
    void omittedStandbyListRemainsCompatibleWithSingleFeedCreation() {
        ConfigUtil.CONFIG.setOffset(false);
        try (var bgm = mockStatic(BgmUtil.class)) {
            Ani ani = AniUtil.getAni(dto(null));
            assertTrue(ani.getStandbyRssList().isEmpty());
            assertTrue(ani.getEnable());
        }
    }
}
