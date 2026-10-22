package ani.rss.util.other;

import ani.rss.config.DefaultConfigFactory;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.handle.JsonReader;
import ani.rss.handle.JsonWriter;
import ani.rss.download.BaseDownload;
import ani.rss.download.OpenList;
import ani.rss.download.qBittorrent;
import ani.rss.util.basic.LogUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.extra.spring.SpringUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenListUpstreamCompatibilityTest {
    @TempDir
    Path directory;
    private String previousConfig;

    @BeforeEach
    void useTemporaryConfigDirectory() {
        previousConfig = System.getProperty("CONFIG");
        System.setProperty("CONFIG", directory.toString());
    }

    @AfterEach
    void restoreConfigDirectory() {
        if (previousConfig == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", previousConfig);
        }
    }

    @Test
    void upstreamDefaultFactoryRetainsOpenListSettings() {
        Config config = DefaultConfigFactory.create();
        assertEquals("115 Open", config.getProvider());
        assertEquals(60, config.getOpenListDownloadTimeout());
        assertEquals(5L, config.getOpenListDownloadRetryNumber());
    }

    @Test
    void newRenameDefaultsRemoveYearAndTmdbIdFromVideoTitles() {
        withFreshDefaults(() -> {
            assertTrue(ConfigUtil.CONFIG.getRenameDelYear());
            assertTrue(ConfigUtil.CONFIG.getRenameDelTmdbId());
            assertEquals("Show", RenameUtil.renameDel("Show (2022) [tmdbid=123]"));
            assertEquals("Show", RenameUtil.renameDel("Show (2022) {tmdb-123}"));
        });
    }

    @Test
    void persistedOpenListRenameChoicesOverrideNewDefaultsOnLoad() throws Exception {
        for (boolean year : new boolean[]{false, true}) {
            for (boolean tmdb : new boolean[]{false, true}) {
                Files.writeString(directory.resolve("config.v2.json"), """
                        {"downloadToolType":"OpenList","provider":"saved-driver",
                         "renameDelYear":%s,"renameDelTmdbId":%s}
                        """.formatted(year, tmdb));
                withFreshDefaults(() -> {
                    ConfigUtil.load();
                    assertEquals("OpenList", ConfigUtil.CONFIG.getDownloadToolType());
                    assertEquals("saved-driver", ConfigUtil.CONFIG.getProvider());
                    assertEquals(year, ConfigUtil.CONFIG.getRenameDelYear());
                    assertEquals(tmdb, ConfigUtil.CONFIG.getRenameDelTmdbId());
                    String renamed = RenameUtil.renameDel("Show (2022) [tmdbid=123]");
                    assertEquals(!year, renamed.contains("(2022)"));
                    assertEquals(!tmdb, renamed.contains("[tmdbid=123]"));
                });
            }
        }
    }

    @Test
    void missingRenameOptionsReceiveNewDefaultsWithoutDroppingOpenListSettings() throws Exception {
        Files.writeString(directory.resolve("config.v2.json"), """
                {"downloadToolType":"OpenList","provider":"saved-driver",
                 "openListDownloadTimeout":120,"openListDownloadRetryNumber":8}
                """);
        withFreshDefaults(() -> {
            ConfigUtil.load();
            assertTrue(ConfigUtil.CONFIG.getRenameDelYear());
            assertTrue(ConfigUtil.CONFIG.getRenameDelTmdbId());
            assertEquals("OpenList", ConfigUtil.CONFIG.getDownloadToolType());
            assertEquals("saved-driver", ConfigUtil.CONFIG.getProvider());
            assertEquals(120, ConfigUtil.CONFIG.getOpenListDownloadTimeout());
            assertEquals(8L, ConfigUtil.CONFIG.getOpenListDownloadRetryNumber());
        });
    }

    private void withFreshDefaults(Runnable action) {
        Config previous = ObjectUtil.clone(ConfigUtil.CONFIG);
        try (var downloader = mockStatic(TorrentUtil.class); var logs = mockStatic(LogUtil.class)) {
            BeanUtil.copyProperties(DefaultConfigFactory.create(), ConfigUtil.CONFIG);
            action.run();
        } finally {
            BeanUtil.copyProperties(previous, ConfigUtil.CONFIG);
        }
    }

    @Test
    void existingOpenListConfigurationSurvivesNewDefaultFactory() {
        Config saved = DefaultConfigFactory.create().setDownloadToolType("OpenList")
                .setDownloadToolHost("http://openlist:5244").setDownloadToolPassword("test-token")
                .setProvider("test-driver").setOpenListDownloadTimeout(120)
                .setOpenListDownloadRetryNumber(8L);
        var file = directory.resolve("config.v2.json").toFile();
        JsonWriter.getInstance(file).writer(saved);
        Config loaded = JsonReader.getInstance(file).toObject(Config.class, DefaultConfigFactory.create());
        assertEquals("OpenList", loaded.getDownloadToolType());
        assertEquals("http://openlist:5244", loaded.getDownloadToolHost());
        assertEquals("test-token", loaded.getDownloadToolPassword());
        assertEquals("test-driver", loaded.getProvider());
        assertEquals(120, loaded.getOpenListDownloadTimeout());
        assertEquals(8L, loaded.getOpenListDownloadRetryNumber());
    }

    @Test
    void openListSelectionStillLoadsNativeDownloaderAfterUpstreamRemoval() throws Exception {
        assertDownloader("OpenList", OpenList.class, false);
    }

    @Test
    void legacyAlistSelectionMigratesAndPersistsBeforeLoadingOpenList() throws Exception {
        assertDownloader("Alist", OpenList.class, true);
    }

    @Test
    void unknownDownloaderRetainsUpstreamFallback() throws Exception {
        assertDownloader("unknown", qBittorrent.class, false);
    }

    private void assertDownloader(String selection, Class<? extends BaseDownload> type, boolean migration) throws Exception {
        Config configured = ConfigUtil.CONFIG;
        String previous = configured.getDownloadToolType();
        var downloadField = TorrentUtil.class.getDeclaredField("DOWNLOAD");
        downloadField.setAccessible(true);
        Object previousDownload = downloadField.get(null);
        BaseDownload downloader = mock(type);
        try (var spring = mockStatic(SpringUtil.class); var config = mockStatic(ConfigUtil.class)) {
            configured.setDownloadToolType(selection);
            spring.when(() -> SpringUtil.getBean(type)).thenAnswer(invocation -> downloader);
            TorrentUtil.loadDownloadTool();
            assertSame(downloader, downloadField.get(null));
            if (migration) {
                assertEquals("OpenList", configured.getDownloadToolType());
                config.verify(ConfigUtil::sync);
            } else {
                config.verify(ConfigUtil::sync, never());
            }
        } finally {
            configured.setDownloadToolType(previous);
            downloadField.set(null, previousDownload);
        }
    }

    @Test
    void newSubscriptionsUseUpstreamIdBasedTorrentDirectory() {
        for (boolean ova : new boolean[]{false, true}) {
            assertEquals(directory.resolve("torrents/a/abc-123").toFile(),
                    TorrentUtil.getTorrentDir(subscription(ova)));
        }
    }

    @Test
    void existingSeasonTorrentDirectoriesRemainReadable() throws Exception {
        Path grouped = directory.resolve("torrents/S/Show/Season 1");
        Files.createDirectories(grouped);
        assertEquals(grouped.toFile(), TorrentUtil.getTorrentDir(subscription(false)));
        Path legacy = directory.resolve("torrents/Show/Season 1");
        Files.createDirectories(legacy);
        assertEquals(legacy.toFile(), TorrentUtil.getTorrentDir(subscription(false)));
    }

    @Test
    void existingMovieTorrentDirectoriesRemainReadable() throws Exception {
        Path grouped = directory.resolve("torrents/S/Show");
        Files.createDirectories(grouped);
        assertEquals(grouped.toFile(), TorrentUtil.getTorrentDir(subscription(true)));
        Path legacy = directory.resolve("torrents/Show");
        Files.createDirectories(legacy);
        assertEquals(legacy.toFile(), TorrentUtil.getTorrentDir(subscription(true)));
    }

    private Ani subscription(boolean ova) {
        return new Ani().setId("abc-123").setTitle("Show").setSeason(1).setOva(ova);
    }
}
