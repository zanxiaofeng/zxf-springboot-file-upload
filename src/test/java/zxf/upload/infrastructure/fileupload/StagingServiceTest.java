package zxf.upload.infrastructure.fileupload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zxf.upload.infrastructure.config.FileScanProperties;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仅覆盖落盘 IO 职责（纯 IO）；预检规则（空文件/大小/扩展名）已下沉领域策略
 * {@code domain/upload/UploadPolicy}，见 UploadPolicyTest。
 */
@DisplayName("StagingService 落盘")
class StagingServiceTest {

    @TempDir
    java.nio.file.Path tempDir;
    private StagingService service;

    @BeforeEach
    void setUp() {
        var properties = new FileScanProperties();
        properties.getStorage().setStagingPath(tempDir.toString());
        service = new StagingService(properties);
    }

    @Test
    @DisplayName("落盘成功：UUID 命名保留原始扩展名")
    void staged_writesFileWithExtension() {
        var path = service.stage("data.txt", new ByteArrayInputStream("hello".getBytes()));
        assertThat(Files.exists(path)).isTrue();
        assertThat(path.toString()).endsWith(".txt");
    }

    @Test
    @DisplayName("无扩展名文件 → 落盘不带后缀")
    void staged_withoutExtension() {
        var path = service.stage("README", new ByteArrayInputStream("hello".getBytes()));
        assertThat(Files.exists(path)).isTrue();
        assertThat(path.getFileName().toString()).doesNotContain(".");
    }
}
