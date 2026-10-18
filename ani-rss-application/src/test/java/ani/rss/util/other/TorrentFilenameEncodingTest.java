package ani.rss.util.other;

import com.dampcake.bencode.Bencode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TorrentFilenameEncodingTest {
    @TempDir Path directory;
    private TorrentMetadata seed(Map<String,Object> info) throws Exception {
        var bencode = new Bencode(StandardCharsets.UTF_8);
        var file = directory.resolve("seed.torrent");
        Files.write(file, bencode.encode(new TreeMap<>(Map.of("info",info))));
        var metadata = TorrentMetadata.from(file.toFile());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bencode.encode(info)));
        assertEquals(hash, metadata.getHashV1());
        return metadata;
    }
    @Test void utf8SingleFileKeepsChineseAndFullWidthColonWithoutChangingHash() throws Exception {
        var name = "[Sakurato] Steel Ball Run： 中文 04.mp4";
        assertArrayEquals(new String[]{name}, seed(new TreeMap<>(Map.of("name",name,"length",100L))).getFilenames());
    }
    @Test void utf8MultiFilePathsAndPreferredUtf8FieldsAreDecoded() throws Exception {
        var entry = new TreeMap<String,Object>(Map.of("path",List.of("legacy.mkv"),
                "path.utf-8",List.of("字幕组","视频：01.mkv"),"length",100L));
        assertArrayEquals(new String[]{"字幕组/视频：01.mkv"},seed(new TreeMap<>(Map.of("files",List.of(entry)))).getFilenames());
    }
    @Test void v2TreeKeysAreDecoded() throws Exception {
        var tree = new TreeMap<>(Map.of("中文：.mkv",new TreeMap<>(Map.of("",new TreeMap<>(Map.of("length",100L))))));
        assertArrayEquals(new String[]{"中文：.mkv"},seed(new TreeMap<>(Map.of("file tree",tree,"meta version",2L))).getFilenames());
    }
    @Test void normalUnicodeAndInvalidUtf8AreNotDestructivelyReplaced() {
        assertEquals("é.mkv",TorrentMetadata.decodeFilename("é.mkv"));
        assertEquals("字幕.mkv",TorrentMetadata.decodeFilename("字幕.mkv"));
        assertEquals("video.mkv",TorrentMetadata.decodeFilename("video.mkv"));
    }
}
