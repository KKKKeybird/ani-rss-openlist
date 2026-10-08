package ani.rss.service;

import ani.rss.download.BaseDownload;
import ani.rss.download.qBittorrent;
import ani.rss.commons.MavenUtils;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsStateEnum;
import ani.rss.util.other.ConfigUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadTaskDeletionTest {
    private final BaseDownload downloader = mock(BaseDownload.class);

    private TorrentsInfo task(String id, TorrentsStateEnum state) {
        return new TorrentsInfo().setId(id).setHash("hash-" + id).setState(state);
    }

    @Test
    void bulkCleanupPreservesRecoveredAndCompletedTasksAndAlwaysKeepsFiles() {
        var failed = task("failed", TorrentsStateEnum.error);
        var recovered = task("recovered", TorrentsStateEnum.downloading);
        var completed = task("completed", TorrentsStateEnum.stoppedUP);
        when(downloader.getTorrentsInfos()).thenReturn(List.of(failed, recovered, completed));
        when(downloader.delete(failed, false)).thenReturn(true);
        var result = DownloadTaskDeletion.delete(downloader, List.of("failed", "recovered", "completed"), true);
        assertEquals(List.of("failed"), result.deleted());
        assertEquals(List.of("recovered", "completed"), result.failed());
        verify(downloader).delete(failed, false);
        verify(downloader, never()).delete(recovered, false);
        verify(downloader, never()).delete(completed, false);
    }

    @Test
    void partialFailureContinuesOtherTasksAndReportsOnlyConfirmedSuccesses() {
        var first = task("first", TorrentsStateEnum.error);
        var second = task("second", TorrentsStateEnum.error);
        var third = task("third", TorrentsStateEnum.error);
        when(downloader.getTorrentsInfos()).thenReturn(List.of(first, second, third));
        when(downloader.delete(first, false)).thenThrow(new IllegalStateException("network"));
        when(downloader.delete(second, false)).thenReturn(false);
        when(downloader.delete(third, false)).thenReturn(true);
        var result = DownloadTaskDeletion.delete(downloader, List.of("first", "second", "third"), true);
        assertEquals(List.of("third"), result.deleted());
        assertEquals(List.of("first", "second"), result.failed());
    }

    @Test
    void individualDeletionAllowsActiveTaskAndDeduplicatesRequests() {
        var active = task("active", TorrentsStateEnum.downloading);
        when(downloader.getTorrentsInfos()).thenReturn(List.of(active));
        when(downloader.delete(active, false)).thenReturn(true);
        var result = DownloadTaskDeletion.delete(downloader, List.of("active", "active"), false);
        assertEquals(List.of("active"), result.deleted());
        verify(downloader, times(1)).delete(active, false);
    }

    @Test
    void unknownTaskDoesNotSendArbitraryIdsToDownloader() {
        when(downloader.getTorrentsInfos()).thenReturn(List.of());
        var result = DownloadTaskDeletion.delete(downloader, List.of("missing"), false);
        assertEquals(List.of("missing"), result.deleted());
        verify(downloader, never()).delete(any(), anyBoolean());
    }

    @Test
    void hashIdentifiesTasksWithoutDownloaderId() {
        var task = new TorrentsInfo().setHash("torrent-hash").setState(TorrentsStateEnum.error);
        when(downloader.getTorrentsInfos()).thenReturn(List.of(task));
        when(downloader.delete(task, false)).thenReturn(true);
        var result = DownloadTaskDeletion.delete(downloader, List.of("torrent-hash"), true);
        assertEquals(List.of("torrent-hash"), result.deleted());
        verify(downloader).delete(task, false);
    }

    @Test
    void qBittorrentTaskRemovalKeepsFilesInReportedTorrentDirectory(@TempDir Path directory) throws Exception {
        Path video = directory.resolve("Pack/keep.mkv");
        Files.createDirectories(video.getParent());
        Files.writeString(video, "existing video");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new AtomicReference<String>();
        server.createContext("/api/v2/torrents/files", exchange -> {
            byte[] data = "[{\"name\":\"Pack/E01.mkv\",\"size\":100}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.createContext("/api/v2/torrents/delete", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        String previousHost = ConfigUtil.CONFIG.getDownloadToolHost();
        Object previousVersion = ReflectionTestUtils.getField(MavenUtils.class, "version");
        server.start();
        try {
            ReflectionTestUtils.setField(MavenUtils.class, "version", "test");
            ConfigUtil.CONFIG.setDownloadToolHost("http://127.0.0.1:" + server.getAddress().getPort());
            var task = new TorrentsInfo().setHash("hash").setName("Anime S01E01")
                    .setSavePath(directory.toString());
            assertTrue(new qBittorrent(mock(DownloadService.class)).delete(task, false));
            assertTrue(body.get().contains("deleteFiles=false"));
            assertEquals("existing video", Files.readString(video));
        } finally {
            server.stop(0);
            ConfigUtil.CONFIG.setDownloadToolHost(previousHost);
            ReflectionTestUtils.setField(MavenUtils.class, "version", previousVersion);
        }
    }
}
