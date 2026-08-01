package zxf.upload.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.multipart.MultipartFile;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.exception.FileRejectedException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上传暂存测试：预检拦截、落盘失败清理残留。
 */
class StagingServiceTest {

    @TempDir
    Path stagingDir;
    private StagingService stagingService;

    @BeforeEach
    void setUp() {
        VirusScanProperties properties = new VirusScanProperties();
        properties.getStorage().setStagingPath(stagingDir.toString());
        stagingService = new StagingService(properties);
    }

    @Test
    void stage_copyFails_cleansUpPartialFile() throws Exception {
        // 落盘中途失败（磁盘满/IO 错误）不得残留不完整暂存文件
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(100L);
        when(file.getOriginalFilename()).thenReturn("a.txt");
        when(file.getInputStream()).thenReturn(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk full");
            }
        });

        assertThatThrownBy(() -> stagingService.stage(file))
                .isInstanceOf(FileRejectedException.class)
                .hasMessageContaining("暂存失败");
        try (var files = Files.list(stagingDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void stage_emptyFile_rejected() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        assertThatThrownBy(() -> stagingService.stage(file))
                .isInstanceOf(FileRejectedException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void stage_disallowedExtension_rejected() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(100L);
        when(file.getOriginalFilename()).thenReturn("evil.exe");

        assertThatThrownBy(() -> stagingService.stage(file))
                .isInstanceOf(FileRejectedException.class)
                .hasMessageContaining("不支持的文件扩展名");
    }
}
