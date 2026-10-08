package ani.rss.download;

import ani.rss.entity.OpenListFileInfo;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.OpenListUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenListCompletionVerifierTest {
    @TempDir Path directory;
    private final OpenListUtil api = mock(OpenListUtil.class);

    private OpenListTaskStore store() {
        var store = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        store.submitted(new OpenListTaskStore.Task().setId("task").setHash("hash")
                .setName("Show E01").setSavePath("/anime").setStagingPath("/anime/.stage"));
        return store;
    }

    private OpenListFileInfo file(String name, long size) {
        return new OpenListFileInfo().setPath("/anime").setName(name).setSize(size).setIsDir(false);
    }

    private OpenList downloader(OpenListTaskStore store) {
        // Avoid constructing the production store in the application's config directory.
        OpenList downloader = mock(OpenList.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(downloader, "openListUtil", api);
        ReflectionTestUtils.setField(downloader, "taskStore", store);
        return downloader;
    }

    @Test
    void pollingCompletedHistoricalTaskAlsoCleansItsEmptyStagingDirectory() {
        var store = store();
        String name = ".ani-rss-openlist-12345678-1234-1234-1234-123456789abc";
        store.submitted(store.get("task").setStagingPath("/anime/" + name));
        store.completed("task", List.of("E01.mkv"), 100);
        when(api.fsListChecked("/anime", true)).thenReturn(
                List.of(new OpenListFileInfo().setName(name).setIsDir(true)), List.of());
        when(api.findFiles("/anime/" + name)).thenReturn(List.of());
        when(api.fsRemove("/anime", List.of(name))).thenReturn(true);
        downloader(store).getTorrentsInfos();
        assertTrue(store.get("task").isStagingCleaned());
        verify(api, never()).taskInfo(anyString());
    }

    @Test
    void archivedFilesRecoverCompletionWithoutQueryingMissingTaskAndSurviveRestart() {
        var store = store();
        store.planned("task", List.of("E01.mkv", "E01.chs.ass"), 110,
                Map.of("E01.mkv", 100L, "E01.chs.ass", 10L));
        store.failed("task", "下载超时");
        when(api.fsListChecked("/anime", true)).thenReturn(List.of(file("E01.mkv", 100), file("E01.chs.ass", 10)));
        var downloader = downloader(store);
        var tasks = downloader.getTorrentsInfos();
        assertEquals(100.0, tasks.getFirst().getProgress());
        assertTrue(store.get("task").isCompleted());
        assertNull(store.get("task").getError());
        // A still-running download poller must not overwrite recovered completion.
        store.failed("task", "下载超时");
        assertNull(store.get("task").getError());
        assertEquals(ani.rss.entity.OpenListTaskInfo.State.Succeeded.name(), store.get("task").getState());
        var restored = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        assertTrue(restored.get("task").isCompleted());
        assertEquals(Map.of("E01.mkv", 100L, "E01.chs.ass", 10L), restored.get("task").getFileSizes());
        downloader.getTorrentsInfos();
        verify(api, never()).taskInfo(anyString());
        verify(api, times(1)).fsListChecked("/anime", true);
    }

    @Test
    void legacyPlansRecoverByExactNonemptyFilesAndCorrectPreviouslyInflatedSize() {
        var store = store();
        store.planned("task", List.of("E01.mkv", "E01.ass"), 220);
        when(api.fsListChecked("/anime", true)).thenReturn(List.of(file("E01.mkv", 100), file("E01.ass", 10)));
        assertTrue(new OpenListCompletionVerifier(api, store).completeIfPresent("task"));
        assertEquals(110, store.get("task").getSize());
    }

    @Test
    void missingSubtitleOrWrongLengthDoesNotCompleteTask() {
        var store = store();
        store.planned("task", List.of("E01.mkv", "E01.ass"), 110,
                Map.of("E01.mkv", 100L, "E01.ass", 10L));
        when(api.fsListChecked("/anime", true)).thenReturn(
                List.of(file("E01.mkv", 100)), List.of(file("E01.mkv", 99), file("E01.ass", 10)));
        var verifier = new OpenListCompletionVerifier(api, store);
        assertFalse(verifier.completeIfPresent("task"));
        assertFalse(verifier.completeIfPresent("task"));
        assertFalse(store.get("task").isCompleted());
    }

    @Test
    void unrelatedFilesDoNotCompleteAnUnplannedTask() {
        var store = store();
        assertFalse(new OpenListCompletionVerifier(api, store).completeIfPresent("task"));
        verifyNoInteractions(api);
    }

    @Test
    void emptyFilesDirectoriesAndDuplicateNamesDoNotCompleteLegacyTask() {
        var store = store();
        store.planned("task", List.of("E01.mkv"), 100);
        when(api.fsListChecked("/anime", true)).thenReturn(List.of(file("E01.mkv", 0)),
                List.of(file("E01.mkv", 100).setIsDir(true)),
                List.of(file("E01.mkv", 100), file("E01.mkv", 100)));
        var verifier = new OpenListCompletionVerifier(api, store);
        for (int i = 0; i < 3; i++) {
            assertFalse(verifier.completeIfPresent("task"));
        }
        assertFalse(store.get("task").isCompleted());
    }

    @Test
    void collectionPlanRequiresEveryExactLengthBeforeCompleting() {
        var store = store();
        var entries = List.of(new OpenListTaskStore.CollectionFile()
                .setSource("01.mkv").setTarget("E01.mkv").setLength(100));
        store.planCollection("task", entries);
        when(api.fsListChecked("/anime", true)).thenReturn(List.of(file("E01.mkv", 99)), List.of(file("E01.mkv", 100)));
        var verifier = new OpenListCompletionVerifier(api, store);
        assertFalse(verifier.completeIfPresent("task"));
        assertTrue(verifier.completeIfPresent("task"));
        verify(api, never()).findFiles(anyString());
        verify(api, never()).fsMoveAndWait(anyString(), anyString(), anyList(), anyLong());
    }

    @Test
    void collectionWithoutValidatedPlanDoesNotAdoptExistingTargets() {
        var store = store();
        store.submitted(store.get("task").setFiles(List.of("E01.mkv"))
                .setCollectionFiles(List.of(new OpenListTaskStore.CollectionFile()
                        .setSource("01.mkv").setTarget("E01.mkv").setLength(100))));
        assertFalse(new OpenListCompletionVerifier(api, store).completeIfPresent("task"));
        verifyNoInteractions(api);
    }

    @Test
    void failedDirectoryRequestDoesNotCompleteTaskAndStillQueriesProvider() {
        var store = store();
        store.planned("task", List.of("E01.mkv"), 100);
        when(api.fsListChecked("/anime", true)).thenThrow(new IllegalStateException("network unavailable"));
        when(api.taskInfo("task")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> downloader(store).getTorrentsInfos());
        assertFalse(store.get("task").isCompleted());
        verify(api).taskInfo("task");
    }

    @Test
    void ordinaryArchivePlanStoresIndividualLengthsWithoutDoubleCountingTransferSize() {
        var store = store();
        store.progress("task", new ani.rss.entity.OpenListTaskInfo().setTotalBytes(110L));
        when(api.fsListChecked("/anime", true)).thenReturn(List.of(),
                List.of(file("Show E01.mkv", 100), file("Show E01.chs.ass", 10)));
        when(api.findFiles("/anime/.stage")).thenReturn(List.of(
                file("original.mkv", 100).setPath("/anime/.stage"),
                file("original.chs.ass", 10).setPath("/anime/.stage")), List.of());
        when(api.fsBatchRename(anyList(), anyString())).thenReturn(true);
        boolean previous = ConfigUtil.CONFIG.getRename();
        try {
            ConfigUtil.CONFIG.setRename(true);
            Boolean completed = ReflectionTestUtils.invokeMethod(downloader(store), "finishTask", "task", Long.MAX_VALUE);
            assertTrue(completed);
            assertEquals(110, store.get("task").getSize());
            assertEquals(Map.of("Show E01.mkv", 100L, "Show E01.chs.ass", 10L), store.get("task").getFileSizes());
        } finally {
            ConfigUtil.CONFIG.setRename(previous);
        }
    }
}
