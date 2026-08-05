package zxf.upload.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.exception.FileRejectedException;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.*;

@DisplayName("StagingService 预检 + 落盘")
class StagingServiceTest {

    @TempDir
    java.nio.file.Path tempDir;
    private VirusScanProperties properties;
    private StagingService service;

    @BeforeEach
    void setUp() {
        properties = new VirusScanProperties();
        properties.getStorage().setStagingPath(tempDir.toString());
        service = new StagingService(properties);
    }

    @Test
    @DisplayName("空文件 → 400 拦截")
    void emptyFile_rejected() {
        var file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        assertThatThrownBy(() -> service.stage(file))
                .isInstanceOf(FileRejectedException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("非法扩展名 → 400 拦截")
    void illegalExtension_rejected() {
        var file = new MockMultipartFile("file", "malware.exe", "application/octet-stream",
                "content".getBytes());
        assertThatThrownBy(() -> service.stage(file))
                .isInstanceOf(FileRejectedException.class)
                .hasMessageContaining("不支持的文件扩展名");
    }

    @Test
    @DisplayName("正常文件 → 落盘成功")
    void validFile_staged() {
        var file = new MockMultipartFile("file", "data.txt", "text/plain", "hello".getBytes());
        var path = service.stage(file);
        assertThat(Files.exists(path)).isTrue();
        assertThat(path.toString()).endsWith(".txt");
    }

    @Test
    @DisplayName("超大文件 → 400 拦截")
    void oversizedFile_rejected() {
        properties.setMaxFileSize(10);
        var file = new MockMultipartFile("file", "big.txt", "text/plain", new byte[100]);
        assertThatThrownBy(() -> service.stage(file))
                .isInstanceOf(FileRejectedException.class)
                .hasMessageContaining("文件过大");
    }
}
