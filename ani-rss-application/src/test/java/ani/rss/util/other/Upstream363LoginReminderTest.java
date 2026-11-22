package ani.rss.util.other;

import ani.rss.commons.MavenUtils;
import ani.rss.config.DefaultConfigFactory;
import ani.rss.entity.Config;
import ani.rss.service.AfdianService;
import ani.rss.service.ConfigService;
import ani.rss.util.basic.LogUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.SecureUtil;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.info.GitProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

class Upstream363LoginReminderTest {
    @ParameterizedTest
    @CsvSource({
            "admin, admin, true",
            "changed-user, admin, false",
            "admin, changed-password, false",
            "changed-user, changed-password, false"
    })
    void detectsDefaultCredentialsInLegacyOpenListConfigBeforeRedactingPassword(
            String username, String password, boolean expected, @TempDir Path directory) throws Exception {
        String passwordHash = SecureUtil.md5(password);
        Files.writeString(directory.resolve(ConfigUtil.FILE_NAME), """
                {"downloadToolType":"OpenList","provider":"saved-driver",
                 "login":{"username":"%s","password":"%s"}}
                """.formatted(username, passwordHash));

        Config previous = ObjectUtil.clone(ConfigUtil.CONFIG);
        String previousDirectory = System.getProperty("CONFIG");
        try (var downloader = mockStatic(TorrentUtil.class);
             var logs = mockStatic(LogUtil.class);
             var maven = mockStatic(MavenUtils.class)) {
            maven.when(MavenUtils::getVersion).thenReturn("test");
            System.setProperty("CONFIG", directory.toString());
            BeanUtil.copyProperties(DefaultConfigFactory.create(), ConfigUtil.CONFIG);
            ConfigUtil.load();

            ConfigService service = new ConfigService();
            ReflectionTestUtils.setField(service, "afdianService", new AfdianService());
            ReflectionTestUtils.setField(service, "gitProperties", new GitProperties(new Properties()));
            Config response = service.config();

            assertEquals(expected, response.getLogin().getIsDefault());
            assertEquals(username, response.getLogin().getUsername());
            assertEquals("", response.getLogin().getPassword());
            assertEquals(passwordHash, ConfigUtil.CONFIG.getLogin().getPassword());
            assertEquals("OpenList", response.getDownloadToolType());
            assertEquals("saved-driver", response.getProvider());
        } finally {
            BeanUtil.copyProperties(previous, ConfigUtil.CONFIG);
            if (previousDirectory == null) {
                System.clearProperty("CONFIG");
            } else {
                System.setProperty("CONFIG", previousDirectory);
            }
        }
    }
}
