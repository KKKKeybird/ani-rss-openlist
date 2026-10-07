package ani.rss.util.other;

import ani.rss.config.DefaultConfigFactory;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.handle.JsonReader;
import ani.rss.handle.JsonWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

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
