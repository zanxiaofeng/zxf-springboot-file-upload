package zxf.upload.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.ScanResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AsyncScanProcessor 结果分发")
class AsyncScanProcessorTest {

    @TempDir Path tempDir;
    private VirusScanService scanService;
    private AsyncScanProcessor processor;

    @BeforeEach
    void setUp() {
        scanService = mock(VirusScanService.class);
        processor = new AsyncScanProcessor(scanService, new VirusScanProperties());
    }

    @Test
    @DisplayName("扫描先于 SSE 连接完成 → registerEmitter 立即回放并完成")
    void lateSseConnection_replaysCachedResult() throws Exception {
        Path staged = tempDir.resolve("staged.txt");
        Files.writeString(staged, "x");
        when(scanService.scanFile(any(), any())).thenReturn(ScanResult.clean(staged, "text/plain"));

        // 直接调用（测试环境无 @Async 代理，同步执行完毕）
        processor.processScan("scan-1", staged, "a.txt");

        SseEmitter emitter = mock(SseEmitter.class);
        processor.registerEmitter("scan-1", emitter);
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }
}
