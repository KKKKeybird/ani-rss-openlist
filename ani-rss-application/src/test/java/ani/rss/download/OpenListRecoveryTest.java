package ani.rss.download;

import ani.rss.entity.*;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsStateEnum;
import ani.rss.util.other.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenListRecoveryTest {
    @TempDir Path directory;
    private final OpenListUtil api = mock(OpenListUtil.class);

    private OpenListTaskStore.CollectionFile entry(String source, String target, long size) {
        return new OpenListTaskStore.CollectionFile().setSource(source).setTarget(target).setLength(size);
    }
    private OpenListFileInfo file(String path, String name, long size) {
        return new OpenListFileInfo().setPath(path).setName(name).setSize(size).setIsDir(false);
    }
    private OpenListTaskStore store(List<OpenListTaskStore.CollectionFile> entries) {
        var store = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        store.submitted(new OpenListTaskStore.Task().setId("task").setHash("hash").setName("E01")
                .setSavePath("/anime").setStagingPath("/anime/.stage").setOrdinaryFiles(entries)
                .setSubmittedAt(System.currentTimeMillis()));
        store.failed("task", "code: 10008, 任务已存在");
        when(api.taskInfo("task")).thenReturn(Optional.of(new OpenListTaskInfo().setId("task")
                .setState(OpenListTaskInfo.State.Failed).setError("code: 10008")));
        return store;
    }
    private OpenList downloader(OpenListTaskStore store) {
        var downloader = mock(OpenList.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(downloader, "openListUtil", api);
        ReflectionTestUtils.setField(downloader, "taskStore", store);
        ReflectionTestUtils.setField(downloader, "deletingTasks", ConcurrentHashMap.newKeySet());
        doReturn(List.of()).when(downloader).newTags(any(), any());
        return downloader;
    }
    private void stageComplete() {
        when(api.findFiles("/anime/.stage")).thenReturn(List.of(file("/anime/.stage", "01.mkv", 100)));
        when(api.fsListChecked("/anime", true)).thenReturn(List.of(), List.of(file("/anime", "E01.mkv", 100)));
        when(api.fsBatchRename(anyList(), anyString())).thenReturn(true);
    }
    @Test
    void duplicateProviderFailureWithCompleteFilesArchivesBeforeQueryOrRetry() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100)));
        stageComplete();
        var list = downloader(store).getTorrentsInfos();
        assertEquals(TorrentsStateEnum.stoppedUP, list.getFirst().getState());
        var restored = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        assertTrue(restored.get("task").isCompleted());
        assertNull(restored.get("task").getError());
        assertEquals(100L, restored.get("task").getFileSizes().get("E01.mkv"));
        verify(api, never()).taskInfo(anyString());
        verify(api, never()).taskRetry(anyString());
        verify(api, never()).fsAddOfflineDownload(anyString(), anyString(), anyString());
    }
    @Test
    void incompleteFileIsNotCompletedOrMutated() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100)));
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage", "01.mkv", 99)));
        assertEquals(TorrentsStateEnum.error, downloader(store).getTorrentsInfos().getFirst().getState());
        assertFalse(store.get("task").isCompleted());
        verify(api, never()).fsBatchRename(anyList(), anyString());
        verify(api, never()).fsMoveAndWait(anyString(), anyString(), anyList(), anyLong());
    }
    @Test
    void oneVideoDoesNotProveMissingSubtitleComplete() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100), entry("01.chs.ass", "E01.chs.ass", 10)));
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage", "01.mkv", 100)));
        downloader(store).getTorrentsInfos();
        assertFalse(store.get("task").isCompleted());
        verify(api, never()).fsBatchRename(anyList(), anyString());
    }
    @Test
    void providerSuccessCannotOverrideIncompleteSourceFiles() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100)));
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage", "01.mkv", 99)));
        when(api.taskInfo("task")).thenReturn(Optional.of(new OpenListTaskInfo().setId("task")
                .setState(OpenListTaskInfo.State.Succeeded)));
        downloader(store).getTorrentsInfos();
        assertFalse(store.get("task").isCompleted());
        verify(api, never()).fsBatchRename(anyList(), anyString());
        verify(api, never()).fsMoveAndWait(anyString(), anyString(), anyList(), anyLong());
    }

    @Test
    void missingDestinationAfterMoveRetainsPlanAndDoesNotMarkComplete() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100)));
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage", "01.mkv", 100)));
        when(api.fsListChecked("/anime", true)).thenReturn(List.of());
        when(api.fsBatchRename(anyList(), anyString())).thenReturn(true);
        downloader(store).getTorrentsInfos();
        assertFalse(store.get("task").isCompleted());
        assertTrue(store.get("task").isCollectionPlanned());
        assertEquals("/anime/.stage/01.mkv", store.get("task").getOrdinaryFiles().getFirst().getResolvedPath());
    }

    @Test
    void ambiguousSourcePathsCannotBeAdopted() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100)));
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage/A", "01.mkv", 100),
                file("/anime/.stage/B", "01.mkv", 100)));
        downloader(store).getTorrentsInfos();
        assertFalse(store.get("task").isCompleted());
        verify(api, never()).fsBatchRename(anyList(), anyString());
    }
    @Test
    void preexistingTargetWithoutArchivePlanIsNotMarkedComplete() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100)));
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage", "01.mkv", 100)));
        when(api.fsListChecked("/anime", true)).thenReturn(List.of(file("/anime", "E01.mkv", 100)));
        downloader(store).getTorrentsInfos();
        assertFalse(store.get("task").isCompleted());
        verify(api, never()).fsBatchRename(anyList(), anyString());
        verify(api, never()).fsMoveAndWait(anyString(), anyString(), anyList(), anyLong());
    }
    @Test
    void networkFailureDuringFileCheckDoesNotEraseTaskOrMarkComplete() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100)));
        when(api.findFiles(anyString())).thenThrow(new IllegalStateException("network unavailable"));
        downloader(store).getTorrentsInfos();
        assertNotNull(store.get("task"));
        assertFalse(store.get("task").isCompleted());
        verify(api, never()).taskRetry(anyString());
    }
    @Test
    void restartResumesRenamedSubtitleAfterVideoAlreadyMoved() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100), entry("01.chs.ass", "E01.chs.ass", 10)));
        var entries = store.get("task").getOrdinaryFiles();
        entries.get(0).setResolvedPath("/anime/.stage/01.mkv");
        entries.get(1).setResolvedPath("/anime/.stage/01.chs.ass");
        store.planOrdinary("task", entries);
        var restored = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage", "E01.chs.ass", 10)));
        when(api.fsListChecked("/anime", true)).thenReturn(List.of(file("/anime", "E01.mkv", 100)),
                List.of(file("/anime", "E01.mkv", 100)), List.of(file("/anime", "E01.mkv", 100)),
                List.of(file("/anime", "E01.mkv", 100), file("/anime", "E01.chs.ass", 10)));
        downloader(restored).getTorrentsInfos();
        assertTrue(restored.get("task").isCompleted());
        verify(api, never()).fsBatchRename(anyList(), anyString());
        verify(api).fsMoveAndWait(eq("/anime/.stage"), eq("/anime"), eq(List.of("E01.chs.ass")), anyLong());
    }
    @Test
    void existingFailedTaskDoesNotReturnSuccessOrSubmitAgain() throws Exception {
        var store = store(List.of());
        store.submitted(new OpenListTaskStore.Task().setId("task").setHash("hash").setName("E01")
                .setSavePath("/anime").setStagingPath("/anime/.stage").setRetries(100));
        when(api.findFiles(anyString())).thenReturn(List.of());
        try (var torrents = mockStatic(TorrentUtil.class)) {
            var file = directory.resolve("hash.torrent").toFile();
            torrents.when(() -> TorrentUtil.getMagnet(file)).thenReturn("magnet:?xt=urn:btih:hash");
            assertFalse(downloader(store).download(new Ani(), new Item().setReName("E01"), "/anime", file));
        }
        verify(api, never()).fsAddOfflineDownload(anyString(), anyString(), anyString());
        verify(api, never()).taskRetry(anyString());
    }
    @Test
    void newSubmissionPersistsExpectedFilesBeforeProviderFailure() throws Exception {
        Path seed = directory.resolve("new.torrent");
        Files.writeString(seed, "d4:infod6:lengthi100e4:name6:01.mkv6:pieces20:00000000000000000000ee", StandardCharsets.US_ASCII);
        var store = new OpenListTaskStore(directory.resolve("fresh.json").toFile());
        when(api.mkdir(anyString())).thenReturn(true);
        when(api.fsAddOfflineDownload(anyString(), anyString(), anyString())).thenReturn("new-task");
        when(api.findFiles(anyString())).thenReturn(List.of());
        when(api.taskInfo("new-task")).thenAnswer(call -> {
            var saved = new OpenListTaskStore(directory.resolve("fresh.json").toFile()).get("new-task");
            assertEquals("01.mkv", saved.getOrdinaryFiles().getFirst().getSource());
            assertEquals(100, saved.getOrdinaryFiles().getFirst().getLength());
            assertTrue(saved.getSubmittedAt() > 0);
            return Optional.of(new OpenListTaskInfo().setId("new-task").setState(OpenListTaskInfo.State.Failed));
        });
        Long previous = ConfigUtil.CONFIG.getOpenListDownloadRetryNumber();
        try {
            ConfigUtil.CONFIG.setOpenListDownloadRetryNumber(0L);
            assertFalse(downloader(store).download(new Ani(), new Item().setReName("E01"), "/anime", seed.toFile()));
            assertFalse(store.get("new-task").isCompleted());
            verify(api, never()).taskRetry(anyString());
        } finally { ConfigUtil.CONFIG.setOpenListDownloadRetryNumber(previous); }
    }

    @Test
    void legacyFailedTaskReconstructsPlanFromHashVerifiedTorrentCache() throws Exception {
        String previous = System.getProperty("CONFIG");
        Boolean rename = ConfigUtil.CONFIG.getRename();
        Path torrent = directory.resolve("source.torrent");
        Files.writeString(torrent, "d4:infod6:lengthi100e4:name6:01.mkv6:pieces20:00000000000000000000ee", StandardCharsets.US_ASCII);
        String hash = TorrentMetadata.from(torrent.toFile()).getHash();
        Path cache = directory.resolve("torrents/a/subscription/" + hash + ".torrent");
        Files.createDirectories(cache.getParent());
        Files.copy(torrent, cache);
        var store = store(List.of());
        store.submitted(new OpenListTaskStore.Task().setId("task").setHash(hash).setName("E01")
                .setSavePath("/anime").setStagingPath("/anime/.stage"));
        store.failed("task", "code: 10008");
        stageComplete();
        try {
            System.setProperty("CONFIG", directory.toString());
            ConfigUtil.CONFIG.setRename(true);
            downloader(store).getTorrentsInfos();
            assertTrue(store.get("task").isCompleted());
            assertEquals(100, store.get("task").getOrdinaryFiles().getFirst().getLength());
        } finally {
            if (previous == null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG", previous);
            ConfigUtil.CONFIG.setRename(rename);
        }
    }
}
