package zxf.upload.application.filescan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zxf.upload.domain.filescan.model.ScanStatus;
import zxf.upload.domain.fileupload.UploadFile;
import zxf.upload.infrastructure.config.FileScanProperties;
import zxf.upload.rest.fileupload.representation.UploadResponse;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AsyncScanProcessor 异步执行 + 结果缓存")
class AsyncScanProcessorTest {

    @TempDir Path tempDir;
    private FileScanService fileScanService;
    private AsyncScanProcessor processor;

    @BeforeEach
    void setUp() {
        fileScanService = mock(FileScanService.class);
        processor = new AsyncScanProcessor(fileScanService, new FileScanProperties());
    }

    @Test
    @DisplayName("扫描完成后轮询 → 返回终态结果")
    void processScan_completes_pollReturnsFinalResult() throws Exception {
        Path staged = tempDir.resolve("staged.txt");
        Files.writeString(staged, "x");
        when(fileScanService.scanFile(any())).thenReturn(
                zxf.upload.domain.filescan.model.ScanResult.clean(staged, "text/plain"));

        // 直接调用（测试环境无 @Async 代理，同步执行完毕）
        processor.processScan("scan-1", UploadFile.unstaged("a.txt", 1).withStagingPath(staged));

        UploadResponse result = processor.getResult("scan-1");
        assertThat(result.status()).isEqualTo(ScanStatus.CLEAN);
    }

    @Test
    @DisplayName("扫描失败 → 轮询返回 ERROR 终态（对外通用文案）")
    void processScan_engineFails_pollReturnsError() throws Exception {
        Path staged = tempDir.resolve("staged.txt");
        Files.writeString(staged, "x");
        when(fileScanService.scanFile(any())).thenThrow(new IllegalStateException("ClamAV connection refused"));

        processor.processScan("scan-2", UploadFile.unstaged("a.txt", 1).withStagingPath(staged));

        UploadResponse result = processor.getResult("scan-2");
        assertThat(result.status()).isEqualTo(ScanStatus.ERROR);
        assertThat(result.message()).isEqualTo("扫描引擎暂时不可用，请稍后重试");
    }

    @Test
    @DisplayName("未受理/结果已淘汰的 scanId → SCANNING 占位")
    void getResult_unknownScanId_returnsScanning() {
        UploadResponse result = processor.getResult("scan-404");

        assertThat(result.status()).isEqualTo(ScanStatus.SCANNING);
    }
}
