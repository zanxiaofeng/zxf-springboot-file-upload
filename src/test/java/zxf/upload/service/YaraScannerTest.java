package zxf.upload.service;

import org.junit.jupiter.api.Test;
import zxf.upload.config.VirusScanProperties;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * YARA 封装测试：enabled=false 是完整逃生舱。
 */
class YaraScannerTest {

    @Test
    void constructor_disabled_skipsRulesResolution() {
        // 规则文件不存在也不应影响启动：关闭开关即完全绕过
        VirusScanProperties properties = new VirusScanProperties();
        properties.getYara().setEnabled(false);
        properties.getYara().setRulesPath("classpath:rules/nonexistent.yar");

        assertThatCode(() -> new YaraScanner(properties)).doesNotThrowAnyException();
    }

    @Test
    void scan_disabled_returnsNullWithoutRulesFile() {
        VirusScanProperties properties = new VirusScanProperties();
        properties.getYara().setEnabled(false);
        properties.getYara().setRulesPath("classpath:rules/nonexistent.yar");

        assertThat(new YaraScanner(properties).scan(Path.of("any-file"))).isNull();
    }
}
