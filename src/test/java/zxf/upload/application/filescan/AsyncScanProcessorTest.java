package zxf.upload.application.filescan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zxf.upload.infrastructure.config.FileScanProperties;
import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.domain.fileupload.UploadFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AsyncScanProcessor 结果分发")
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
    @DisplayName("扫描先于 SSE 连接完成 → registerEmitter 立即回放并完成")
    void lateSseConnection_replaysCachedResult() throws Exception {
        Path staged = tempDir.resolve("staged.txt");
        Files.writeString(staged, "x");
        when(fileScanService.scanFile(any())).thenReturn(ScanResult.clean(staged, "text/plain"));

        // 直接调用（测试环境无 @Async 代理，同步执行完毕）
        processor.processScan("scan-1", UploadFile.unstaged("a.txt", 1).withStagingPath(staged));

        SseEmitter emitter = mock(SseEmitter.class);
        processor.registerEmitter("scan-1", emitter);
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }
}
