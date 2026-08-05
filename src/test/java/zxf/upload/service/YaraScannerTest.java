package zxf.upload.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zxf.upload.config.VirusScanProperties;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@DisplayName("YaraScanner disabled 逃生舱")
class YaraScannerTest {

    @TempDir Path tempDir;
    private VirusScanProperties properties;

    @BeforeEach
    void setUp() {
        properties = new VirusScanProperties();
        properties.getYara().setEnabled(false);
    }

    @Test
    @DisplayName("enabled=false 时 scan 直接返回 null（不触碰 yara 进程）")
    void disabled_returnsNull() throws Exception {
        // 即使规则路径无效，enabled=false 也不会解析
        properties.getYara().setRulesPath("classpath:nonexistent.yar");
        var scanner = new YaraScanner(properties);

        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "content");
        assertThat(scanner.scan(file)).isNull();
    }

    @Test
    @DisplayName("enabled=false 时构造不抛异常（规则文件缺失也能启动）")
    void disabled_constructionWithoutRules() {
        properties.getYara().setRulesPath("classpath:does-not-exist.yar");
        assertThatCode(() -> new YaraScanner(properties)).doesNotThrowAnyException();
    }
}
