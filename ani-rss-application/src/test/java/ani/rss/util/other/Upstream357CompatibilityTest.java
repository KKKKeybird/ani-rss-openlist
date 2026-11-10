package ani.rss.util.other;

import ani.rss.commons.GsonStatic;
import ani.rss.config.DefaultConfigFactory;
import ani.rss.controller.AniController;
import ani.rss.download.OpenListTaskStore;
import ani.rss.entity.*;
import ani.rss.entity.dto.RssToAniDTO;
import ani.rss.enums.OffsetTypeEnum;
import ani.rss.mcp.AniMcpTools;
import ani.rss.mcp.dto.DeleteSubscriptionDTO;
import ani.rss.offset.AutoOffset;
import ani.rss.offset.BgmOffset;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Upstream357CompatibilityTest {
    @TempDir Path directory;
    private Config previous;
    @BeforeEach void defaults() {
        previous = ObjectUtil.clone(ConfigUtil.CONFIG);
        BeanUtil.copyProperties(DefaultConfigFactory.create(), ConfigUtil.CONFIG);
    }
    @AfterEach void restore() { BeanUtil.copyProperties(previous, ConfigUtil.CONFIG); }

    @Test void automaticStrategyRetainsIntegerHalfEpisodeAndEmptyFeedSemantics() {
        var ani = new Ani().setSeason(2);
        var strategy = new AutoOffset();
        assertEquals(-24, strategy.getOffset(ani, List.of(new Item().setEpisode(25.0))));
        assertEquals(-12, strategy.getOffset(ani, List.of(new Item().setEpisode(12.5))));
        assertEquals(0, strategy.getOffset(ani, List.of()));
        assertEquals(2, ani.getSeason());
        assertEquals(OffsetTypeEnum.AUTO_OFFSET, ConfigUtil.CONFIG.getOffsetType());
    }

    @Test void bangumiStrategySharesMetadataDespiteMutationOfSeasonAndOffset() {
        var ani = new Ani().setSeason(2).setOffset(0).setBgmUrl("https://bgm.tv/subject/123");
        try (var bgm = mockStatic(BgmUtil.class)) {
            bgm.when(() -> BgmUtil.getSubjectId(ani)).thenReturn("123");
            bgm.when(() -> BgmUtil.getEpisodes("123", 0)).thenReturn(List.of(
                    new BgmEpisodes.BgmEpisode().setSort(13), new BgmEpisodes.BgmEpisode().setSort(14)));
            var strategy = new BgmOffset();
            assertEquals(12, strategy.getOffset(ani, List.of(new Item().setEpisode(1.0))));
            ani.setOffset(12);
            assertEquals(1, ani.getSeason());
            assertEquals(-12, strategy.getOffset(ani, List.of(new Item().setEpisode(25.0))));
            assertEquals(0, strategy.getOffset(ani, List.of(new Item().setEpisode(12.5))));
            bgm.verify(() -> BgmUtil.getEpisodes("123", 0), times(1));
        }
    }

    @Test void bangumiModeComputesMainAndStandbyIndependentlyThroughSubscriptionParsing() {
        ConfigUtil.CONFIG.setOffset(true).setOffsetType(OffsetTypeEnum.BGM_OFFSET);
        var dto = new RssToAniDTO().setType("mikan").setUrl("https://example.test/main")
                .setBgmUrl("https://bgm.tv/subject/123").setSubgroup("Group")
                .setStandbyRssList(List.of(new StandbyRss().setUrl("https://example.test/backup").setLabel("Backup")));
        try (var bgm = mockStatic(BgmUtil.class); var items = mockStatic(ItemsUtil.class, CALLS_REAL_METHODS)) {
            bgm.when(() -> BgmUtil.getSubjectId(any(Ani.class))).thenReturn("123");
            bgm.when(() -> BgmUtil.getEpisodes("123", 0))
                    .thenReturn(List.of(new BgmEpisodes.BgmEpisode().setSort(13)));
            items.when(() -> ItemsUtil.getItems(any(Ani.class), eq(dto.getUrl()), eq("")))
                    .thenReturn(List.of(new Item().setEpisode(1.0)));
            items.when(() -> ItemsUtil.getItems(any(Ani.class), eq("https://example.test/backup"), eq("")))
                    .thenReturn(List.of(new Item().setEpisode(25.0)));
            Ani ani = AniUtil.getAni(dto);
            assertEquals(12, ani.getOffset());
            assertEquals(-12, ani.getStandbyRssList().getFirst().getOffset());
            assertEquals(1, ani.getSeason());
            assertEquals("Backup", ani.getStandbyRssList().getFirst().getLabel());
            bgm.verify(() -> BgmUtil.getEpisodes("123", 0), times(1));
        }
    }

    @Test void episodeLookupAndTitleCacheFollowSelectedNumberingStrategy() {
        var ani = new Ani().setOva(false).setBgmUrl("https://bgm.tv/subject/test-357");
        var episode = new BgmEpisodes.BgmEpisode().setId("episode-id").setEp(1).setSort(13)
                .setName("Episode title").setNameCn("剧集标题");
        try (var bgm = mockStatic(BgmUtil.class, CALLS_REAL_METHODS)) {
            bgm.when(() -> BgmUtil.getSubjectId(ani)).thenReturn("test-357");
            bgm.when(() -> BgmUtil.getEpisodes("test-357", 0)).thenReturn(List.of(episode));
            ConfigUtil.CONFIG.setOffsetType(OffsetTypeEnum.AUTO_OFFSET);
            assertEquals("episode-id", BgmUtil.getEpisodeId("test-357", 1.0));
            assertEquals("", BgmUtil.getEpisodeId("test-357", 13.0));
            assertTrue(BgmUtil.getEpisodeTitleMap(ani).containsKey(1));
            ConfigUtil.CONFIG.setOffsetType(OffsetTypeEnum.BGM_OFFSET);
            assertEquals("episode-id", BgmUtil.getEpisodeId("test-357", 13.0));
            assertEquals("", BgmUtil.getEpisodeId("test-357", 1.0));
            assertTrue(BgmUtil.getEpisodeTitleMap(ani).containsKey(13));
        }
    }

    @Test void partialBangumiDatesAndCollectionNotificationSnapshotSurviveRestart() {
        for (String[] sample : List.of(new String[]{"2026", "2026-01-01"},
                new String[]{"2026-11", "2026-11-01"}, new String[]{"2026-11-08", "2026-11-08"})) {
            var ani = GsonStatic.fromJson("{\"releaseDate\":\"" + sample[0] + "\"}", Ani.class);
            assertEquals(sample[1], DateUtil.formatDate(ani.getReleaseDate()));
            var file = directory.resolve(sample[0] + ".json").toFile();
            var store = new OpenListTaskStore(file);
            store.submitted(new OpenListTaskStore.Task().setId("collection").setHash("hash")
                    .setCollectionNotification(ani));
            assertEquals(sample[1], DateUtil.formatDate(new OpenListTaskStore(file)
                    .get("collection").getCollectionNotification().getReleaseDate()));
        }
    }

    @Test void renamedItemKeepsNcopEdMarkerAndUsesConfiguredEpisodeOffset() {
        var ani = AniUtil.createAni().setTitle("Show").setSeason(1).setOffset(12)
                .setCustomRenameTemplateEnable(true).setCustomRenameTemplate("${itemTitle} S${seasonFormat}E${episodeFormat}");
        var item = new Item().setTitle("[Group] Show - 01 +NCOPED");
        try (var bgm = mockStatic(BgmUtil.class)) {
            bgm.when(() -> BgmUtil.getSubjectId(ani)).thenReturn("");
            assertTrue(RenameUtil.rename(ani, item));
            assertEquals(13.0, item.getEpisode());
            assertTrue(item.getReName().contains("+NCOPED"));
            assertTrue(item.getReName().endsWith("S01E13"));
        }
    }

    @Test void mcpFullObjectCreationStillRequiresExplicitOverrideAndEditingPreservesFields() {
        var controller = mock(AniController.class);
        var tools = new AniMcpTools();
        ReflectionTestUtils.setField(tools, "aniController", controller);
        var subscription = Map.<String, Object>of("id", "show-id", "title", "Show", "season", 2,
                "offset", 12, "message", false, "customDownloadPath", true,
                "customDownloadPathTemplate", "/cloud/Show/Season 02");
        var created = tools.addSubscription(subscription);
        verify(controller).addAni(eq(created), eq(false));
        var edited = tools.editSubscription(subscription);
        verify(controller).setAni(edited);
        assertEquals("show-id", edited.getId());
        assertEquals(12, edited.getOffset());
        assertFalse(edited.getMessage());
        assertEquals("/cloud/Show/Season 02", edited.getCustomDownloadPathTemplate());
    }

    @Test void mcpDeletionRetainsFilesUnlessExplicitlyRequested() {
        var controller = mock(AniController.class);
        var tools = new AniMcpTools();
        ReflectionTestUtils.setField(tools, "aniController", controller);
        var ids = List.of("show-id");
        assertEquals(ids, tools.deleteSubscription(new DeleteSubscriptionDTO().setIds(ids)));
        verify(controller).deleteAni(ids, false);
        tools.deleteSubscription(new DeleteSubscriptionDTO().setIds(ids).setDeleteFiles(true));
        verify(controller).deleteAni(ids, true);
    }
}
