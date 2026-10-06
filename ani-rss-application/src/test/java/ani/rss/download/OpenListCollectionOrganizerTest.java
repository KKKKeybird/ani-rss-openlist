package ani.rss.download;

import ani.rss.entity.OpenListFileInfo;
import ani.rss.util.other.OpenListUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenListCollectionOrganizerTest {
    @TempDir Path directory;
    private final OpenListUtil api = mock(OpenListUtil.class);

    private OpenListTaskStore store(List<OpenListTaskStore.CollectionFile> entries) {
        var store = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        store.submitted(new OpenListTaskStore.Task().setId("task").setHash("hash")
                .setSavePath("/anime").setStagingPath("/anime/.stage")
                .setCollectionFiles(entries));
        return store;
    }

    private OpenListTaskStore.CollectionFile entry(String source, String target, long size) {
        return new OpenListTaskStore.CollectionFile().setSource(source).setTarget(target).setLength(size);
    }

    private OpenListFileInfo file(String path, String name, long size) {
        return new OpenListFileInfo().setPath(path).setName(name).setSize(size).setIsDir(false);
    }

    @Test
    void organizesAllSelectedEpisodesAndSubtitlesButLeavesExcludedFiles() {
        var store = store(List.of(entry("01.mkv", "Show E01.mkv", 100),
                entry("02.mkv", "Show E02.mkv", 200), entry("02.chs.ass", "Show E02.chs.ass", 10)));
        when(api.findFiles("/anime/.stage")).thenReturn(List.of(
                file("/anime/.stage/Pack", "01.mkv", 100), file("/anime/.stage/Pack", "02.mkv", 200),
                file("/anime/.stage/Pack", "02.chs.ass", 10), file("/anime/.stage/Pack", "NCOP.mkv", 50)));
        when(api.fsListChecked("/anime", true)).thenReturn(List.of(), List.of(
                file("/anime", "Show E01.mkv", 100), file("/anime", "Show E02.mkv", 200),
                file("/anime", "Show E02.chs.ass", 10)));
        when(api.fsBatchRename(anyList(), anyString())).thenReturn(true);
        assertTrue(new OpenListCollectionOrganizer(api, store).finish("task", Long.MAX_VALUE));
        assertEquals(List.of("Show E01.mkv", "Show E02.mkv", "Show E02.chs.ass"), store.get("task").getFiles());
        assertEquals(310, store.get("task").getSize());
        verify(api).fsMoveAndWait("/anime/.stage/Pack", "/anime", List.of("Show E02.mkv"), Long.MAX_VALUE);
        verify(api, never()).fsRemove(anyString(), anyList());
        verify(api, never()).fsBatchRename(List.of(Map.of("src_name", "NCOP.mkv", "new_name", "NCOP.mkv")), "/anime/.stage/Pack");
    }

    @Test
    void restartResumesAlreadyRenamedAndPartiallyMovedCollection() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100), entry("02.mkv", "E02.mkv", 200)));
        var entries = store.get("task").getCollectionFiles();
        entries.get(0).setResolvedPath("/anime/.stage/Pack/01.mkv");
        entries.get(1).setResolvedPath("/anime/.stage/Pack/02.mkv");
        store.planCollection("task", entries);
        var restored = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        when(api.findFiles("/anime/.stage")).thenReturn(List.of(file("/anime/.stage/Pack", "E02.mkv", 200)));
        when(api.fsListChecked("/anime", true)).thenReturn(List.of(file("/anime", "E01.mkv", 100)),
                List.of(file("/anime", "E01.mkv", 100), file("/anime", "E02.mkv", 200)));
        assertTrue(new OpenListCollectionOrganizer(api, restored).finish("task", Long.MAX_VALUE));
        verify(api, never()).fsBatchRename(anyList(), anyString());
        verify(api, times(1)).fsMoveAndWait(anyString(), anyString(), anyList(), anyLong());
        assertTrue(new OpenListTaskStore(directory.resolve("tasks.json").toFile()).get("task").isCompleted());
    }

    @Test
    void matchesNestedPathsInsteadOfAmbiguousBasenames() {
        var store = store(List.of(entry("Season2/01.mkv", "E01.mkv", 100)));
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage/Pack/Season1", "01.mkv", 100),
                file("/anime/.stage/Pack/Season2", "01.mkv", 100)));
        when(api.fsListChecked(anyString(), eq(true))).thenReturn(List.of(), List.of(file("/anime", "E01.mkv", 100)));
        when(api.fsBatchRename(anyList(), anyString())).thenReturn(true);
        assertTrue(new OpenListCollectionOrganizer(api, store).finish("task", Long.MAX_VALUE));
        verify(api).fsMoveAndWait("/anime/.stage/Pack/Season2", "/anime", List.of("E01.mkv"), Long.MAX_VALUE);
    }

    @Test
    void missingEpisodeStopsBeforeAnyMutation() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100), entry("02.mkv", "E02.mkv", 200)));
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage", "01.mkv", 100)));
        when(api.fsListChecked(anyString(), eq(true))).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> new OpenListCollectionOrganizer(api, store).finish("task", Long.MAX_VALUE));
        verify(api, never()).fsBatchRename(anyList(), anyString());
        verify(api, never()).fsMoveAndWait(anyString(), anyString(), anyList(), anyLong());
        assertFalse(store.get("task").isCollectionPlanned());
    }

    @Test
    void existingTargetIsNeverOverwrittenEvenWithSameSize() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100)));
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage", "01.mkv", 100)));
        when(api.fsListChecked(anyString(), eq(true))).thenReturn(List.of(file("/anime", "E01.mkv", 100)));
        assertThrows(IllegalStateException.class, () -> new OpenListCollectionOrganizer(api, store).finish("task", Long.MAX_VALUE));
        verify(api, never()).fsMoveAndWait(anyString(), anyString(), anyList(), anyLong());
    }

    @Test
    void moveFailureKeepsPlanAndDoesNotMarkComplete() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100)));
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage", "01.mkv", 100)));
        when(api.fsListChecked(anyString(), eq(true))).thenReturn(List.of());
        when(api.fsBatchRename(anyList(), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("move failed")).when(api).fsMoveAndWait(anyString(), anyString(), anyList(), anyLong());
        assertThrows(IllegalStateException.class, () -> new OpenListCollectionOrganizer(api, store).finish("task", Long.MAX_VALUE));
        assertFalse(store.get("task").isCompleted());
        assertTrue(new OpenListTaskStore(directory.resolve("tasks.json").toFile()).get("task").isCollectionPlanned());
    }

    @Test
    void stagedRenameCollisionAndAmbiguousPathsStopBeforeMutation() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100)));
        when(api.fsListChecked(anyString(), eq(true))).thenReturn(List.of());
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage/Pack", "01.mkv", 100),
                file("/anime/.stage/Pack", "E01.mkv", 100)));
        assertThrows(IllegalStateException.class, () -> new OpenListCollectionOrganizer(api, store).finish("task", Long.MAX_VALUE));
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage/A", "01.mkv", 100),
                file("/anime/.stage/B", "01.mkv", 100)));
        assertThrows(IllegalStateException.class, () -> new OpenListCollectionOrganizer(api, store).finish("task", Long.MAX_VALUE));
        verify(api, never()).fsBatchRename(anyList(), anyString());
    }

    @Test
    void doesNotCompleteUntilAllDestinationFilesAreVisibleWithCorrectSizes() {
        var store = store(List.of(entry("01.mkv", "E01.mkv", 100)));
        when(api.fsListChecked(anyString(), eq(true))).thenReturn(List.of(), List.of(file("/anime", "E01.mkv", 99)));
        when(api.findFiles(anyString())).thenReturn(List.of(file("/anime/.stage", "01.mkv", 100)));
        when(api.fsBatchRename(anyList(), anyString())).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> new OpenListCollectionOrganizer(api, store).finish("task", Long.MAX_VALUE));
        assertFalse(store.get("task").isCompleted());
    }

    @Test
    void rejectsEmptyPreviewDuplicateNamesAndPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> OpenListCollectionOrganizer.validate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> OpenListCollectionOrganizer.validate(List.of(
                entry("01.mkv", "E01.mkv", 1), entry("02.mkv", "E01.mkv", 2))));
        assertThrows(IllegalArgumentException.class, () -> OpenListCollectionOrganizer.validate(List.of(entry("01.mkv", "../E01.mkv", 1))));
    }
}
