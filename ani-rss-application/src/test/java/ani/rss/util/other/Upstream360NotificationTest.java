package ani.rss.util.other;

import ani.rss.commons.GsonStatic;
import ani.rss.commons.MavenUtils;
import ani.rss.config.DefaultConfigFactory;
import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.NotificationTypeEnum;
import ani.rss.notification.AstrBotNotification;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Upstream360NotificationTest {
    @TempDir Path directory;
    private HttpServer server;
    private String previousDirectory;
    private Boolean previousProxy;
    private Object previousVersion;
    private final AtomicInteger messages = new AtomicInteger();
    private final AtomicInteger uploads = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger uploadStatus = new AtomicInteger(200);
    private final AtomicReference<JsonObject> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> multipart = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        previousDirectory = System.getProperty("CONFIG");
        System.setProperty("CONFIG", directory.toString());
        previousProxy = ConfigUtil.CONFIG.getProxy();
        ConfigUtil.CONFIG.setProxy(false);
        previousVersion = ReflectionTestUtils.getField(MavenUtils.class, "version");
        ReflectionTestUtils.setField(MavenUtils.class, "version", "test");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/im/message", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(GsonStatic.fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class));
            String status = messages.incrementAndGet() <= failures.get() ? "error" : "ok";
            reply(exchange, 200, "{\"status\":\"" + status + "\"}");
        });
        server.createContext("/api/v1/file", exchange -> {
            uploads.incrementAndGet();
            multipart.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            reply(exchange, uploadStatus.get(), "{\"data\":{\"attachment_id\":\"test-cover\"}}");
        });
        server.start();
    }

    @AfterEach void stop() {
        if (server != null) server.stop(0);
        if (previousDirectory == null) System.clearProperty("CONFIG");
        else System.setProperty("CONFIG", previousDirectory);
        ConfigUtil.CONFIG.setProxy(previousProxy);
        ReflectionTestUtils.setField(MavenUtils.class, "version", previousVersion);
    }

    private NotificationConfig channel() {
        return NotificationConfig.createNotificationConfig().setNotificationType(NotificationTypeEnum.ASTRBOT)
                .setAstrBotHost("http://127.0.0.1:" + server.getAddress().getPort())
                .setAstrBotApiKey("fake-key").setAstrBotUmo("test:private:1")
                .setNotificationTemplate("${text}\n${downloadPath}").setRetry(2);
    }
    private Ani subject() {
        return new Ani().setTitle("Collection").setImage("").setCover("cover.png")
                .setCollectionDownloadPath("/cloud/Collection").setMessage(true);
    }
    private void cover() throws IOException {
        Files.createDirectories(directory.resolve("files"));
        Files.writeString(directory.resolve("files/cover.png"), "test-image-bytes");
    }

    @Test void missingLegacyCollectionCoverSendsTextWithActualCloudPath() {
        assertTrue(new AstrBotNotification().send(channel(), subject(), "合集下载完成", NotificationStatusEnum.DOWNLOAD_END));
        assertEquals(0, uploads.get());
        assertEquals(1, messages.get());
        assertEquals("Bearer fake-key", authorization.get());
        assertEquals("test:private:1", body.get().get("umo").getAsString());
        assertEquals("合集下载完成\n/cloud/Collection", body.get().get("message").getAsString());
    }

    @Test void imageDisabledRetainsTextProtocolWithoutUploadingExistingCover() throws Exception {
        cover();
        var config = channel().setAstrBotImage(false);
        assertTrue(new AstrBotNotification().send(config, subject(), "done", NotificationStatusEnum.DOWNLOAD_END));
        assertEquals(0, uploads.get());
        assertTrue(body.get().get("message").isJsonPrimitive());
        assertFalse(config.getAstrBotImage());
    }

    @Test void imageAndTextShareOneMessageAfterMultipartUpload() throws Exception {
        cover();
        assertTrue(new AstrBotNotification().send(channel(), subject(), "done", NotificationStatusEnum.DOWNLOAD_END));
        assertEquals(1, uploads.get());
        assertEquals(1, messages.get());
        assertTrue(multipart.get().contains("test-image-bytes"));
        var content = body.get().getAsJsonArray("message");
        assertEquals(2, content.size());
        assertEquals("test-cover", content.get(0).getAsJsonObject().get("attachment_id").getAsString());
        assertEquals("plain", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("done\n/cloud/Collection", content.get(1).getAsJsonObject().get("text").getAsString());
    }

    @Test void unsuccessfulMessageResponseUsesForkRetryMechanism() {
        failures.set(1);
        try (var sleep = mockStatic(ThreadUtil.class)) {
            assertTrue(NotificationUtil.deliver(new AstrBotNotification(), channel(), subject(), "done", NotificationStatusEnum.DOWNLOAD_END));
        }
        assertEquals(2, messages.get());
    }

    @Test void configurationTestCannotReportSuccessForFailedMessageResponse() {
        failures.set(Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> new AstrBotNotification().test(
                channel(), subject(), "done", NotificationStatusEnum.DOWNLOAD_END));
        assertEquals(1, messages.get());
    }

    @Test void uploadFailureCannotBecomeSuccessfulCompletion() throws Exception {
        cover();
        uploadStatus.set(403);
        try (var sleep = mockStatic(ThreadUtil.class)) {
            assertFalse(NotificationUtil.deliver(new AstrBotNotification(), channel(), subject(), "done", NotificationStatusEnum.DOWNLOAD_END));
        }
        assertEquals(2, uploads.get());
        assertEquals(0, messages.get());
    }

    private NotificationConfig legacyMail() {
        return NotificationConfig.createNotificationConfig().setNotificationType(NotificationTypeEnum.MAIL)
                .setEnable(false).setAstrBotHost(null).setAstrBotApiKey(null).setAstrBotUmo(null).setAstrBotImage(null);
    }

    @Test void addingDefaultsKeepsLegacyReceiptsAcrossRestartAndOnlyRetriesFailedChannel() {
        var mail = legacyMail().setEnable(true);
        String oldKey = DigestUtil.sha256Hex("0:" + GsonStatic.toJson(mail));
        var file = directory.resolve("receipts.json").toFile();
        var oldDelivery = new CompletionNotificationDelivery(file, Runnable::run, () -> 1L);
        oldDelivery.submit("task", List.of(
                new CompletionNotificationDelivery.Channel(oldKey, () -> true),
                new CompletionNotificationDelivery.Channel("other-channel", () -> false)), () -> fail("Not all channels succeeded"));

        var config = DefaultConfigFactory.create().setNotificationConfigList(List.of(mail));
        ConfigUtil.format(config);
        assertNotNull(mail.getAstrBotHost());
        assertTrue(mail.getEnable());
        assertEquals(oldKey, NotificationUtil.completionChannelKey(0, mail));
        var restarted = new CompletionNotificationDelivery(file, Runnable::run, () -> 2L);
        var retryCount = new AtomicInteger();
        var acknowledged = new AtomicInteger();
        restarted.submit("task", List.of(
                new CompletionNotificationDelivery.Channel(NotificationUtil.completionChannelKey(0, mail), () -> fail("Mail must not replay")),
                new CompletionNotificationDelivery.Channel("other-channel", () -> { retryCount.incrementAndGet(); return true; })),
                () -> { acknowledged.incrementAndGet(); return true; });
        assertEquals(1, retryCount.get());
        assertEquals(1, acknowledged.get());
    }

    @Test void unrelatedAstrBotEditsCannotInvalidateMailButAstrBotEditsChangeOwnReceiptKey() {
        var mail = legacyMail();
        String key = NotificationUtil.completionChannelKey(0, mail);
        mail.setAstrBotHost("http://example.test").setAstrBotApiKey("unrelated").setAstrBotUmo("other").setAstrBotImage(false);
        assertEquals(key, NotificationUtil.completionChannelKey(0, mail));
        ConfigUtil.format(DefaultConfigFactory.create().setNotificationConfigList(List.of(mail)));
        assertFalse(mail.getEnable());
        var astrbot = channel();
        String astrbotKey = NotificationUtil.completionChannelKey(0, astrbot);
        assertNotEquals(astrbotKey, NotificationUtil.completionChannelKey(0, ObjectUtil.clone(astrbot).setAstrBotUmo("changed")));
        assertNotEquals(astrbotKey, NotificationUtil.completionChannelKey(0, ObjectUtil.clone(astrbot).setAstrBotImage(false)));
    }

    @Test void allExistingAndNewChannelTypesRemainRegistered() {
        for (NotificationTypeEnum type : NotificationTypeEnum.values()) assertNotNull(NotificationUtil.NOTIFICATION_MAP.get(type));
        assertEquals(AstrBotNotification.class, NotificationUtil.NOTIFICATION_MAP.get(NotificationTypeEnum.ASTRBOT));
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
