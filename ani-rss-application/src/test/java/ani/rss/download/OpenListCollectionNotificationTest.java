package ani.rss.download;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.notification.BaseNotification;
import ani.rss.service.DownloadService;
import ani.rss.util.other.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenListCollectionNotificationTest {
    @TempDir Path directory;

    private OpenListTaskStore.Task task() {
        return new OpenListTaskStore.Task().setId("collection-1").setHash("hash")
                .setName("[Group] 示例：合集 (2022) 第2季")
                .setTags(List.of("ANI-RSS合集下载", "Group"))
                .setSavePath("/cloud/Anime/Example： Collection (2022)/Season 02")
                .setCollectionFiles(List.of(new OpenListTaskStore.CollectionFile()
                        .setSource("02.mkv").setTarget("Show S02E02.mkv").setLength(100)));
    }

    private OpenListTaskStore store(OpenListTaskStore.Task task) {
        var store = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        store.submitted(task);
        store.completed(task.getId(), List.of("Show S02E02.mkv"), 100);
        // Avoid any file API activity: this test starts after verified archiving/cleanup.
        store.stagingChecked(task.getId(), System.currentTimeMillis(), true);
        return store;
    }

    private TorrentsInfo restoredInfo() {
        var downloader = mock(OpenList.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(downloader, "taskStore",
                new OpenListTaskStore(directory.resolve("tasks.json").toFile()));
        return downloader.getTorrentsInfos().getFirst();
    }

    @Test void snapshotSurvivesRestartAndCannotBeMutatedThroughStoreCopies() {
        var subject = new Ani().setTitle("Show").setSeason(2).setMessage(false)
                .setImage("https://example.test/cover.jpg").setReleaseDate(new Date(0));
        var task = task().setCollectionNotification(subject);
        var store = store(task);
        subject.setTitle("changed input");
        store.get(task.getId()).getCollectionNotification().setTitle("changed output");
        var info = restoredInfo();
        var restored = info.getCollectionNotification();
        assertEquals("Show", restored.getTitle());
        assertEquals(2, restored.getSeason());
        assertFalse(restored.getMessage());
        assertEquals(new Date(0), restored.getReleaseDate());
        assertEquals(task.getSavePath(), restored.getCollectionDownloadPath());
        assertFalse(restored.getEnable());
        assertFalse(GsonStatic.toJson(info).contains("collectionNotification"));
    }

    @Test void legacyCompletedCollectionCanNotifyWithoutAnySubscriptionOrCloudMutation() {
        var task = task();
        var store = store(task);
        var info = restoredInfo();
        var subject = info.getCollectionNotification();
        assertEquals(task.getName(), subject.getTitle());
        assertEquals("Group", subject.getSubgroup());
        assertEquals(task.getSavePath(), subject.getCollectionDownloadPath());
        assertTrue(subject.getMessage());
        assertNull(subject.getSeason());
        assertNull(subject.getReleaseDate());
        var service = spy(new DownloadService());
        try (var notifications = mockStatic(NotificationUtil.class);
             var torrent = mockStatic(TorrentUtil.class);
             var subscriptions = mockStatic(AniUtil.class)) {
            var ack = ArgumentCaptor.forClass(BooleanSupplier.class);
            assertFalse(service.notification(info));
            verify(service, never()).findAniByDownloadPath(any());
            notifications.verify(() -> NotificationUtil.sendCompletion(any(), any(),
                    eq(task.getName() + " 下载完成"), anyString(), ack.capture()));
            assertFalse(ack.getValue().getAsBoolean()); // Cannot acknowledge a failed tag write.
            torrent.when(() -> TorrentUtil.addTags(info, "下载完成"))
                    .thenAnswer(call -> store.addTag(task.getId(), "下载完成"));
            assertTrue(ack.getValue().getAsBoolean());
            subscriptions.verifyNoInteractions();
            notifications.clearInvocations();
            // Restart after acknowledgement must not replay historical notifications.
            assertTrue(service.notification(restoredInfo()));
            notifications.verifyNoInteractions();
        }
    }

    @Test void legacyTemplatesUseLiteralTaskPathAndDoNotInventMissingMetadata() {
        var subject = OpenListCollectionNotifications.subject(task());
        var sender = mock(BaseNotification.class, CALLS_REAL_METHODS);
        try (var bgm = mockStatic(BgmUtil.class); var tmdb = mockStatic(TmdbUtils.class)) {
            String rendered = sender.replaceNotificationTemplate(subject,
                    "${title}|${downloadPath}|${subgroup}|${year}${month}${date}${season}${seasonFormat}"
                            + "${score}${tmdbid}${tmdburl}${jpTitle}${episode}${episodeFormat}"
                            + "${episodeTitle}${bgmEpisodeTitle}${bgmJpEpisodeTitle}${currentEpisodeNumber}${totalEpisodeNumber}",
                    "合集下载完成", NotificationStatusEnum.DOWNLOAD_END);
            assertEquals(subject.getTitle() + "|" + task().getSavePath() + "|Group|", rendered);
            bgm.verifyNoInteractions();
            tmdb.verifyNoInteractions();
        }
    }

    @Test void ordinaryTasksNeverAcquirePlaceholderSubjects() {
        assertNull(OpenListCollectionNotifications.subject(task().setCollectionFiles(List.of())));
    }

    @Test void actualCollectionSubmissionPersistsSubjectBeforePolling() {
        var store = new OpenListTaskStore(directory.resolve("tasks.json").toFile());
        var api = mock(OpenListUtil.class);
        when(api.mkdir(anyString())).thenReturn(true);
        when(api.fsAddOfflineDownload(anyString(), anyString(), nullable(String.class))).thenReturn("new-task");
        var metadata = mock(TorrentMetadata.class);
        when(metadata.getHash()).thenReturn("new-hash");
        when(metadata.getMagnetUri()).thenReturn("magnet:?xt=urn:btih:new-hash");
        var downloader = mock(OpenList.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(downloader, "taskStore", store);
        ReflectionTestUtils.setField(downloader, "openListUtil", api);
        var subject = new Ani().setTitle("Collection").setMessage(false).setSeason(2);
        downloader.downloadCollection("Collection", metadata, "/cloud/Collection/",
                List.of(new ani.rss.entity.Item().setTitle("02.mkv").setReName("Show S02E02.mkv").setLength(100L)),
                List.of("ANI-RSS合集下载", "Group"), subject);
        subject.setMessage(true);
        var recorded = new OpenListTaskStore(directory.resolve("tasks.json").toFile()).get("new-task");
        assertFalse(recorded.isCompleted());
        assertFalse(recorded.getCollectionNotification().getMessage());
        assertEquals("/cloud/Collection", OpenListCollectionNotifications.subject(recorded).getCollectionDownloadPath());
    }

    @Test void legacyCollectionCanRenderAndSubmitMailWithoutSubscriptionMetadata() {
        var subject = OpenListCollectionNotifications.subject(task());
        var channel = new ani.rss.entity.NotificationConfig().setMailFrom("sender@example.test")
                .setMailSMTPHost("smtp.example.test").setMailPassword("test-only")
                .setMailSMTPPort(465).setMailSSLEnable(true).setMailTLSEnable(false)
                .setMailAddressee("recipient@example.test").setMailImage(false)
                .setNotificationTemplate("${text}\n${title}\n${downloadPath}\n${seasonFormat}${year}");
        try (var smtp = mockStatic(cn.hutool.extra.mail.JakartaMailUtil.class)) {
            assertTrue(new ani.rss.notification.MailNotification().send(channel, subject,
                    "合集下载完成", NotificationStatusEnum.DOWNLOAD_END));
            smtp.verify(() -> cn.hutool.extra.mail.JakartaMailUtil.send(
                    any(cn.hutool.extra.mail.MailAccount.class), eq("recipient@example.test"),
                    eq("合集下载完成"), contains(task().getSavePath()), eq(true)));
        }
    }

    @Test void incompleteCollectionCannotDispatchAndDisabledSnapshotIsPreserved() {
        var task = task().setCollectionNotification(new Ani().setTitle("Show").setMessage(false));
        assertFalse(OpenListCollectionNotifications.subject(task).getMessage());
        var info = new TorrentsInfo().setCollectionNotification(OpenListCollectionNotifications.subject(task))
                .setState(ani.rss.enums.TorrentsStateEnum.downloading).setProgress(99.0);
        try (var notifications = mockStatic(NotificationUtil.class)) {
            assertFalse(new DownloadService().notification(info));
            notifications.verifyNoInteractions();
        }
    }
}
