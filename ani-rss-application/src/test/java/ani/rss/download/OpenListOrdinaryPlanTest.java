package ani.rss.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class OpenListOrdinaryPlanTest {
    @TempDir Path directory;
    private Path seed(String data) throws Exception {
        Path file = directory.resolve("seed.torrent");
        Files.writeString(file, data, StandardCharsets.US_ASCII);
        return file;
    }
    @Test
    void singleVideoPlanUsesMetadataLengthAndChosenTarget() throws Exception {
        var file = seed("d4:infod6:lengthi100e4:name6:01.mkv6:pieces20:00000000000000000000ee");
        var entries = OpenListOrdinaryPlan.from(file.toFile(), name -> "Show E01.mkv");
        assertEquals("01.mkv", entries.getFirst().getSource());
        assertEquals("Show E01.mkv", entries.getFirst().getTarget());
        assertEquals(100, entries.getFirst().getLength());
    }
    @Test
    void videoAndSubtitleAreBothRequiredInPlan() throws Exception {
        var file = seed("d4:infod5:filesld6:lengthi100e4:pathl6:01.mkveed6:lengthi10e4:pathl10:01.chs.asseee4:name4:Pack6:pieces20:00000000000000000000ee");
        var entries = OpenListOrdinaryPlan.from(file.toFile(), name -> name);
        assertEquals(2, entries.size());
        assertEquals("01.chs.ass", entries.get(1).getSource());
        assertEquals(10, entries.get(1).getLength());
    }
    @Test
    void magnetWithoutMetadataDoesNotInventExpectedFiles() throws Exception {
        Path file = directory.resolve("seed.txt");
        Files.writeString(file, "magnet:?xt=urn:btih:hash");
        assertTrue(OpenListOrdinaryPlan.from(file.toFile(), name -> name).isEmpty());
    }
    @Test
    void unknownOrZeroVideoSizeCannotBeUsedForRecovery() throws Exception {
        var file = seed("d4:infod6:lengthi0e4:name6:01.mkv6:pieces20:00000000000000000000ee");
        assertThrows(IllegalArgumentException.class, () -> OpenListOrdinaryPlan.from(file.toFile(), name -> name));
    }
    @Test
    void multipleVideosRequireCollectionInsteadOfGuessingFirstFile() throws Exception {
        var file = seed("d4:infod5:filesld6:lengthi100e4:pathl6:01.mkveed6:lengthi200e4:pathl6:02.mkveee4:name4:Pack6:pieces20:00000000000000000000ee");
        assertThrows(IllegalArgumentException.class, () -> OpenListOrdinaryPlan.from(file.toFile(), name -> name));
    }
}
