package ani.rss.download;

import ani.rss.entity.OpenListFileInfo;
import ani.rss.util.other.OpenListUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenListStagingCleanerTest {
    private static final String NAME = ".ani-rss-openlist-12345678-1234-1234-1234-123456789abc";
    @TempDir Path directory;
    private final OpenListUtil api = mock(OpenListUtil.class);

    private OpenListTaskStore store(String stage, boolean completed) {
        var store = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        store.submitted(new OpenListTaskStore.Task().setId("task").setHash("hash")
                .setSavePath("/new-archive").setStagingPath(stage));
        if (completed) {
            store.completed("task", List.of("E01.mkv"), 100);
        }
        return store;
    }

    private void stageExists(String parent) {
        when(api.fsListChecked(parent, true)).thenReturn(List.of(
                new OpenListFileInfo().setName(NAME).setIsDir(true)));
    }

    @Test
    void completedHistoricalTaskRemovesEmptyTreeFromOriginalParentAndPersistsCleanup() {
        var store = store("/old-archive/" + NAME, true);
        stageExists("/old-archive");
        when(api.fsListChecked("/old-archive", true)).thenReturn(
                List.of(new OpenListFileInfo().setName(NAME).setIsDir(true)), List.of());
        when(api.findFiles("/old-archive/" + NAME)).thenReturn(List.of());
        when(api.fsRemove("/old-archive", List.of(NAME))).thenReturn(true);
        var cleaner = new OpenListStagingCleaner(api, store);
        assertTrue(cleaner.cleanIfEmpty("task"));
        var restored = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        assertTrue(restored.get("task").isStagingCleaned());
        assertFalse(new OpenListStagingCleaner(api, restored).cleanIfEmpty("task"));
        verify(api, times(1)).fsRemove("/old-archive", List.of(NAME));
        verify(api, never()).fsListChecked("/new-archive", true);
    }

    @Test
    void excludedOrNestedFilesArePreservedAndChecksAreThrottled() {
        var store = store("/anime/" + NAME, true);
        stageExists("/anime");
        when(api.findFiles("/anime/" + NAME)).thenReturn(List.of(
                new OpenListFileInfo().setPath("/anime/" + NAME + "/Pack")
                        .setName("excluded.mkv").setSize(100L).setIsDir(false)));
        var cleaner = new OpenListStagingCleaner(api, store);
        assertFalse(cleaner.cleanIfEmpty("task"));
        assertFalse(cleaner.cleanIfEmpty("task"));
        assertFalse(store.get("task").isStagingCleaned());
        verify(api, times(1)).findFiles(anyString());
        verify(api, never()).fsRemove(anyString(), anyList());
    }

    @Test
    void completedRootDirectoryTaskUsesRootParent() {
        var store = store("/" + NAME, true);
        stageExists("/");
        when(api.fsListChecked("/", true)).thenReturn(
                List.of(new OpenListFileInfo().setName(NAME).setIsDir(true)), List.of());
        when(api.findFiles("/" + NAME)).thenReturn(List.of());
        when(api.fsRemove("/", List.of(NAME))).thenReturn(true);
        assertTrue(new OpenListStagingCleaner(api, store).cleanIfEmpty("task"));
        verify(api).fsRemove("/", List.of(NAME));
    }

    @Test
    void unfinishedTaskNeverRemovesItsStagingDirectory() {
        var store = store("/anime/" + NAME, false);
        assertFalse(new OpenListStagingCleaner(api, store).cleanIfEmpty("task"));
        verifyNoInteractions(api);
    }

    @Test
    void unrelatedDirectoryIsNeverRemovedEvenForCompletedTask() {
        var store = store("/anime/Season 1", true);
        assertFalse(new OpenListStagingCleaner(api, store).cleanIfEmpty("task"));
        verifyNoInteractions(api);
    }

    @Test
    void failedListDoesNotLookLikeEmptyDirectoryOrRevertCompletion() {
        var store = store("/anime/" + NAME, true);
        when(api.fsListChecked("/anime", true)).thenThrow(new IllegalStateException("network unavailable"));
        var cleaner = new OpenListStagingCleaner(api, store);
        assertThrows(IllegalStateException.class, () -> cleaner.cleanIfEmpty("task"));
        assertTrue(store.get("task").isCompleted());
        assertFalse(store.get("task").isStagingCleaned());
        assertFalse(cleaner.cleanIfEmpty("task"));
        verify(api, never()).fsRemove(anyString(), anyList());
    }

    @Test
    void failedRemovalCanRetryWithoutLosingCompletion() {
        var store = store("/anime/" + NAME, true);
        stageExists("/anime");
        when(api.findFiles("/anime/" + NAME)).thenReturn(List.of());
        when(api.fsRemove("/anime", List.of(NAME))).thenReturn(false, true);
        var folder = List.of(new OpenListFileInfo().setName(NAME).setIsDir(true));
        when(api.fsListChecked("/anime", true)).thenReturn(folder, folder, List.of());
        var cleaner = new OpenListStagingCleaner(api, store);
        assertThrows(IllegalStateException.class, () -> cleaner.cleanIfEmpty("task"));
        assertFalse(store.get("task").isStagingCleaned());
        assertTrue(store.get("task").isCompleted());
        store.stagingChecked("task", 0, false);
        assertTrue(cleaner.cleanIfEmpty("task"));
        verify(api, times(2)).fsRemove("/anime", List.of(NAME));
    }

    @Test
    void alreadyAbsentDirectoryIsRecordedWithoutDeleteRequest() {
        var store = store("/anime/" + NAME, true);
        when(api.fsListChecked("/anime", true)).thenReturn(List.of());
        assertTrue(new OpenListStagingCleaner(api, store).cleanIfEmpty("task"));
        assertTrue(store.get("task").isStagingCleaned());
        verify(api, never()).findFiles(anyString());
        verify(api, never()).fsRemove(anyString(), anyList());
    }

    @Test
    void acceptedDeletionMustBeConfirmedBeforeRecordingCleanup() {
        var store = store("/anime/" + NAME, true);
        stageExists("/anime");
        when(api.findFiles("/anime/" + NAME)).thenReturn(List.of());
        when(api.fsRemove("/anime", List.of(NAME))).thenReturn(true);
        assertFalse(new OpenListStagingCleaner(api, store).cleanIfEmpty("task"));
        assertFalse(store.get("task").isStagingCleaned());
    }
}
