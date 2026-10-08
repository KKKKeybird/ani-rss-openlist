package ani.rss.download;

import ani.rss.entity.OpenListTaskInfo;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.util.other.OpenListUtil;
import ani.rss.util.other.TorrentUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenListTaskDeletionTest {
    @TempDir Path directory;
    private final OpenListUtil api = mock(OpenListUtil.class);

    private OpenListTaskStore store() {
        var store = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        store.submitted(new OpenListTaskStore.Task().setId("task").setHash("hash")
                .setSavePath("/anime").setStagingPath("/anime/.ani-rss-openlist-123"));
        store.failed("task", "task not found");
        return store;
    }

    private OpenListTaskInfo remote(OpenListTaskInfo.State state) {
        return new OpenListTaskInfo().setId("task").setState(state);
    }

    @Test
    void missingHistoricalTaskRemovesPersistentRecordWithoutCancelOrFileDeletion() {
        var store = store();
        when(api.taskDoneList()).thenReturn(List.of());
        when(api.taskUnDoneList()).thenReturn(List.of());
        assertTrue(new OpenListTaskDeletion(api, store).delete("task"));
        assertNull(new OpenListTaskStore(directory.resolve("tasks.json").toFile()).get("task"));
        verify(api).taskDoneList();
        verify(api).taskUnDoneList();
        verifyNoMoreInteractions(api);
    }

    @Test
    void failedRemoteTaskDoesNotRequireSuccessfulCancellation() {
        var store = store();
        when(api.taskDoneList()).thenReturn(List.of(remote(OpenListTaskInfo.State.Failed)));
        when(api.taskUnDoneList()).thenReturn(List.of());
        when(api.taskDelete("task")).thenReturn(true);
        assertTrue(new OpenListTaskDeletion(api, store).delete("task"));
        assertNull(store.get("task"));
        verify(api, never()).taskCancel(anyString());
        verify(api).taskDoneList();
        verify(api).taskUnDoneList();
        verify(api).taskDelete("task");
        verifyNoMoreInteractions(api);
    }

    @Test
    void activeTaskIsCancelledBeforeRemoteAndLocalDeletion() {
        var store = store();
        when(api.taskDoneList()).thenReturn(List.of());
        when(api.taskUnDoneList()).thenReturn(List.of(remote(OpenListTaskInfo.State.Running)));
        when(api.taskCancel("task")).thenReturn(true);
        when(api.taskDelete("task")).thenReturn(true);
        assertTrue(new OpenListTaskDeletion(api, store).delete("task"));
        var order = inOrder(api);
        order.verify(api).taskCancel("task");
        order.verify(api).taskDelete("task");
        assertNull(store.get("task"));
    }

    @Test
    void cancellationFailureRetainsRecordWhenTaskStillRuns() {
        var store = store();
        when(api.taskDoneList()).thenReturn(List.of());
        when(api.taskUnDoneList()).thenReturn(List.of(remote(OpenListTaskInfo.State.Running)));
        assertFalse(new OpenListTaskDeletion(api, store).delete("task"));
        assertNotNull(store.get("task"));
        verify(api, never()).taskDelete(anyString());
    }

    @Test
    void taskDisappearingDuringCancellationCanStillBeCleaned() {
        var store = store();
        when(api.taskDoneList()).thenReturn(List.of());
        when(api.taskUnDoneList()).thenReturn(List.of(remote(OpenListTaskInfo.State.Running)), List.of());
        when(api.taskDelete("task")).thenReturn(true);
        assertTrue(new OpenListTaskDeletion(api, store).delete("task"));
        assertNull(store.get("task"));
    }

    @Test
    void deleteFailureRetainsRecordIfRemoteTaskStillExists() {
        var store = store();
        when(api.taskDoneList()).thenReturn(List.of(remote(OpenListTaskInfo.State.Failed)));
        when(api.taskUnDoneList()).thenReturn(List.of());
        assertFalse(new OpenListTaskDeletion(api, store).delete("task"));
        assertNotNull(store.get("task"));
    }

    @Test
    void alreadyRemovedRemoteTaskDuringDeleteIsSuccess() {
        var store = store();
        when(api.taskDoneList()).thenReturn(List.of(remote(OpenListTaskInfo.State.Failed)), List.of());
        when(api.taskUnDoneList()).thenReturn(List.of());
        assertTrue(new OpenListTaskDeletion(api, store).delete("task"));
        assertNull(store.get("task"));
    }

    @Test
    void apiAuthenticationFailureDoesNotEraseLocalRecord() {
        var store = store();
        when(api.taskDoneList()).thenThrow(new IllegalStateException("unauthorized"));
        assertThrows(IllegalStateException.class, () -> new OpenListTaskDeletion(api, store).delete("task"));
        assertNotNull(store.get("task"));
    }

    @Test
    void pendingListFailureDoesNotTreatSuccessfulEmptyDoneListAsAbsence() {
        var store = store();
        when(api.taskDoneList()).thenReturn(List.of());
        when(api.taskUnDoneList()).thenThrow(new IllegalStateException("network unavailable"));
        assertThrows(IllegalStateException.class, () -> new OpenListTaskDeletion(api, store).delete("task"));
        assertNotNull(store.get("task"));
    }

    @Test
    void repeatedLocalDeletionDoesNotContactOpenList() {
        var store = store();
        store.remove("task");
        assertTrue(new OpenListTaskDeletion(api, store).delete("task"));
        verifyNoInteractions(api);
    }

    @Test
    void succeededTaskRetainsFilesAndSkipsCancellation() {
        var store = store();
        store.completed("task", List.of("E01.mkv"), 123);
        when(api.taskDoneList()).thenReturn(List.of(remote(OpenListTaskInfo.State.Succeeded)));
        when(api.taskUnDoneList()).thenReturn(List.of());
        when(api.taskDelete("task")).thenReturn(true);
        assertTrue(new OpenListTaskDeletion(api, store).delete("task"));
        verify(api).taskDoneList();
        verify(api).taskUnDoneList();
        verify(api).taskDelete("task");
        verifyNoMoreInteractions(api);
    }

    @Test
    void deletedActiveTaskSignalsCancellationInsteadOfDownloadFailureOrRetry() {
        var store = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        var downloader = mock(OpenList.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(downloader, "openListUtil", api);
        ReflectionTestUtils.setField(downloader, "taskStore", store);
        ReflectionTestUtils.setField(downloader, "deletingTasks", ConcurrentHashMap.newKeySet());
        doReturn(List.of()).when(downloader).newTags(any(), any());
        when(api.mkdir(anyString())).thenReturn(true);
        when(api.fsAddOfflineDownload(anyString(), anyString(), anyString())).thenReturn("task");
        when(api.taskDoneList()).thenReturn(List.of());
        when(api.taskUnDoneList()).thenReturn(List.of(remote(OpenListTaskInfo.State.Running)));
        when(api.taskCancel("task")).thenReturn(true);
        when(api.taskDelete("task")).thenReturn(true);
        when(api.taskInfo("task")).thenAnswer(call -> {
            assertTrue(downloader.delete(new TorrentsInfo().setId("task"), false));
            return Optional.of(remote(OpenListTaskInfo.State.Running));
        });
        try (var torrents = mockStatic(TorrentUtil.class)) {
            var file = directory.resolve("torrent-hash.torrent").toFile();
            torrents.when(() -> TorrentUtil.getMagnet(file)).thenReturn("magnet:?xt=urn:btih:hash");
            assertThrows(CancellationException.class, () -> downloader.download(new Ani(),
                    new Item().setReName("E01.mkv"), "/anime", file));
        }
        assertNull(store.get("task"));
        verify(api, never()).taskRetry(anyString());
        verify(api, never()).fsRemove(anyString(), anyList());
    }
}
