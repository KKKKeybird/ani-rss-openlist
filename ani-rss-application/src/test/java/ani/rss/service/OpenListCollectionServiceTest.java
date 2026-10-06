package ani.rss.service;

import ani.rss.download.OpenList;
import ani.rss.entity.Ani;
import ani.rss.entity.CollectionInfo;
import ani.rss.entity.Item;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenListCollectionServiceTest {
    @Test
    void openListSubmissionUsesThePreviewAndRealTorrentMetadata() {
        String previous = ConfigUtil.CONFIG.getDownloadToolType();
        var openList = mock(OpenList.class);
        var service = spy(new CollectionService());
        ReflectionTestUtils.setField(service, "openList", openList);
        var preview = List.of(new Item().setTitle("01.mkv").setReName("Show E01.mkv").setLength(100L));
        String torrent = Base64.getEncoder().encodeToString(
                "d4:infod6:lengthi100e4:name6:01.mkv6:pieces20:00000000000000000000ee".getBytes(StandardCharsets.US_ASCII));
        var collection = new CollectionInfo().setTorrent(torrent).setAni(new Ani()
                .setTitle("Show").setSubgroup("Group").setSeason(1).setCustomDownloadPathTemplate("/anime/Show"));
        doReturn(preview).when(service).preview(collection);
        try (var downloader = mockStatic(TorrentUtil.class)) {
            ConfigUtil.CONFIG.setDownloadToolType("OpenList");
            downloader.when(TorrentUtil::login).thenReturn(true);
            service.startCollection(collection);
            verify(openList).downloadCollection(eq("[Group] Show 第1季"),
                    argThat(metadata -> metadata.getHash().length() == 40 && metadata.getMagnetUri().contains("urn:btih:")),
                    eq("/anime/Show"), eq(preview), eq(List.of("ANI-RSS合集下载", "Group")));
        } finally {
            ConfigUtil.CONFIG.setDownloadToolType(previous);
        }
    }
}
