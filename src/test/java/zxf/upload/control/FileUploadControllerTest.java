package zxf.upload.control;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.ScanResult;
import zxf.upload.model.ScanStatus;
import zxf.upload.model.UploadResponse;
import zxf.upload.model.exception.FileRejectedException;
import zxf.upload.model.exception.ScanFailedException;
import zxf.upload.service.AsyncScanProcessor;
import zxf.upload.service.StagingService;
import zxf.upload.service.VirusScanService;
import zxf.upload.support.rest.GlobalExceptionHandler;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层测试：同步/异步上传状态码语义、SSE 与轮询端点、异常映射。
 * 异步时序与 SSE 心跳见 {@link AsyncScanProcessorTests}。
 */
@WebMvcTest(FileUploadController.class)
@EnableConfigurationProperties(VirusScanProperties.class)
class FileUploadControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StagingService stagingService;
    @MockitoBean
    private VirusScanService scanService;
    @MockitoBean
    private AsyncScanProcessor asyncProcessor;

    @TempDir
    Path tempDir;

    private MockMultipartFile uploadFile() {
        return new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
    }

    @Test
    void upload_syncClean_returns200() throws Exception {
        Path staging = tempDir.resolve("staging.txt");
        when(stagingService.stage(any())).thenReturn(staging);
        when(scanService.scanFile(any(), anyString())).thenReturn(ScanResult.builder()
                .status(ScanStatus.CLEAN)
                .details("/data/upload-storage/x.txt")
                .build());

        mvc.perform(multipart("/api/files/upload").file(uploadFile()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEAN"))
                .andExpect(jsonPath("$.filePath").value("/data/upload-storage/x.txt"));
    }

    @Test
    void upload_syncRejected_returns400() throws Exception {
        Path staging = tempDir.resolve("staging.bin");
        when(stagingService.stage(any())).thenReturn(staging);
        when(scanService.scanFile(any(), anyString()))
                .thenReturn(ScanResult.rejected(staging, "不支持的文件类型: application/x-msdownload"));

        mvc.perform(multipart("/api/files/upload").file(uploadFile()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }

    @Test
    void upload_syncInfected_returns422() throws Exception {
        Path staging = tempDir.resolve("staging.bin");
        when(stagingService.stage(any())).thenReturn(staging);
        when(scanService.scanFile(any(), anyString()))
                .thenReturn(ScanResult.infected(staging, "ClamAV: Eicar-Test-Signature"));

        mvc.perform(multipart("/api/files/upload").file(uploadFile()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VIRUS_DETECTED"));
    }

    @Test
    void upload_syncCleanFlagged_messageCarriesMarker() throws Exception {
        // FLAG 策略放行的含宏文档：CLEAN 但 message 透传打标
        Path staging = tempDir.resolve("staging.xlsx");
        when(stagingService.stage(any())).thenReturn(staging);
        when(scanService.scanFile(any(), anyString())).thenReturn(ScanResult.builder()
                .status(ScanStatus.CLEAN)
                .details("/data/upload-storage/x.xlsx")
                .threat("macro-flagged: Office 文档包含 VBA 宏")
                .build());

        mvc.perform(multipart("/api/files/upload").file(uploadFile()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEAN"))
                .andExpect(jsonPath("$.message").value("文件安全（macro-flagged: Office 文档包含 VBA 宏）"));
    }

    @Test
    void upload_syncOverSyncThreshold_rejectedWithAsyncHint() throws Exception {
        // 超过同步阈值（默认 20MB）：拒绝并提示走异步通道，不落盘
        MockMultipartFile big = new MockMultipartFile(
                "file", "big.zip", "application/zip", new byte[21 * 1024 * 1024]);

        mvc.perform(multipart("/api/files/upload").file(big))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_REJECTED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("异步上传")));

        verifyNoInteractions(stagingService);
    }

    @Test
    void upload_asyncOverSyncThreshold_accepted() throws Exception {
        // 同样大小走异步通道则放行
        Path staging = tempDir.resolve("staging.zip");
        when(stagingService.stage(any())).thenReturn(staging);
        MockMultipartFile big = new MockMultipartFile(
                "file", "big.zip", "application/zip", new byte[21 * 1024 * 1024]);

        mvc.perform(multipart("/api/files/upload").file(big).header("X-Scan-Async", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SCANNING"));
    }

    @Test
    void upload_syncScanError_returns502WithSanitizedMessage() throws Exception {
        Path staging = tempDir.resolve("staging.bin");
        when(stagingService.stage(any())).thenReturn(staging);
        when(scanService.scanFile(any(), anyString()))
                .thenReturn(ScanResult.error(staging, "ClamAV 扫描失败: connection refused localhost:3310"));

        mvc.perform(multipart("/api/files/upload").file(uploadFile()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("SCAN_ENGINE_ERROR"))
                // 对外脱敏：不含引擎地址等内部细节
                .andExpect(jsonPath("$.message").value("扫描引擎暂时不可用，请稍后重试"));
    }

    @Test
    void upload_unexpectedError_returns500WithSanitizedMessage() throws Exception {
        Path staging = tempDir.resolve("staging.bin");
        when(stagingService.stage(any())).thenReturn(staging);
        when(scanService.scanFile(any(), anyString()))
                .thenThrow(new RuntimeException("unexpected internal detail"));

        mvc.perform(multipart("/api/files/upload").file(uploadFile()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器内部错误"));
    }

    @Test
    void upload_emptyFile_returns400() throws Exception {
        when(stagingService.stage(any()))
                .thenThrow(new FileRejectedException("上传文件不能为空"));
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        mvc.perform(multipart("/api/files/upload").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }

    @Test
    void upload_oversizedFile_handlerMapsTo413() {
        // 容器级 multipart 限制由 MaxUploadSizeExceededException 表达，直接验证异常映射
        var response = new GlobalExceptionHandler()
                .handleMaxSize(new MaxUploadSizeExceededException(100 * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void upload_async_returns202WithScanId() throws Exception {
        Path staging = tempDir.resolve("staging.txt");
        when(stagingService.stage(any())).thenReturn(staging);

        mvc.perform(multipart("/api/files/upload").file(uploadFile())
                        .header("X-Scan-Async", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SCANNING"))
                .andExpect(jsonPath("$.scanId").isNotEmpty());

        verify(asyncProcessor).processScan(anyString(), eq(staging), eq("a.txt"));
    }

    @Test
    void scanStatus_pollingFallback_returnsCurrentState() throws Exception {
        when(asyncProcessor.getResult("sid-1"))
                .thenReturn(new UploadResponse("sid-1", ScanStatus.CLEAN, "文件安全", "/p"));

        mvc.perform(get("/api/files/scan/sid-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEAN"));
    }

    @Test
    void scanEvents_registersSseEmitter() throws Exception {
        doAnswer(inv -> {
            inv.getArgument(1, SseEmitter.class).complete();
            return null;
        }).when(asyncProcessor).registerEmitter(eq("sid-2"), any());

        MvcResult result = mvc.perform(get("/api/files/scan/sid-2/events"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        verify(asyncProcessor).registerEmitter(eq("sid-2"), any(SseEmitter.class));
    }

    /**
     * 异步扫描时序与背压（纯单元，直接调用绕过 @Async 代理同步执行）：
     * 结果缓存回放、先注册后推送、轮询兜底、信号量并发上限。
     */
    @Nested
    class AsyncScanProcessorTests {

        private final VirusScanService scanService = mock(VirusScanService.class);

        private AsyncScanProcessor newProcessor(int permits) {
            VirusScanProperties properties = new VirusScanProperties();
            properties.setMaxConcurrentScans(permits);
            return new AsyncScanProcessor(scanService, properties);
        }

        @Test
        void processScan_resultCachedBeforeSseConnect_replaysOnRegister() throws Exception {
            AsyncScanProcessor processor = newProcessor(4);
            when(scanService.scanFile(any(), anyString()))
                    .thenReturn(ScanResult.clean(Path.of("staged"), "text/plain"));
            processor.processScan("sid", Path.of("staged"), "a.txt");   // 扫描先于 SSE 连接完成

            SseEmitter emitter = mock(SseEmitter.class);
            processor.registerEmitter("sid", emitter);

            verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter).complete();
        }

        @Test
        void processScan_emitterRegisteredFirst_pushesOnComplete() throws Exception {
            AsyncScanProcessor processor = newProcessor(4);
            when(scanService.scanFile(any(), anyString()))
                    .thenReturn(ScanResult.clean(Path.of("staged"), "text/plain"));
            SseEmitter emitter = mock(SseEmitter.class);
            processor.registerEmitter("sid", emitter);

            processor.processScan("sid", Path.of("staged"), "a.txt");

            verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter).complete();
        }

        @Test
        void getResult_unknownOrPendingScanId_returnsScanning() {
            AsyncScanProcessor processor = newProcessor(4);

            assertThat(processor.getResult("nope").status()).isEqualTo(ScanStatus.SCANNING);
        }

        @Test
        void processScan_scanThrows_cachedAsError() {
            AsyncScanProcessor processor = newProcessor(4);
            when(scanService.scanFile(any(), anyString()))
                    .thenThrow(new ScanFailedException("ClamAV 不可达"));

            processor.processScan("sid", Path.of("staged"), "a.txt");

            assertThat(processor.getResult("sid").status()).isEqualTo(ScanStatus.ERROR);
        }

        @Test
        void sendHeartbeats_liveEmitter_receivesCommentFrame() throws Exception {
            AsyncScanProcessor processor = newProcessor(4);
            SseEmitter emitter = mock(SseEmitter.class);
            processor.registerEmitter("sid", emitter);

            processor.sendHeartbeats();

            verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        }

        @Test
        void sendHeartbeats_brokenEmitter_removedAndNoLongerNotified() throws Exception {
            AsyncScanProcessor processor = newProcessor(4);
            when(scanService.scanFile(any(), anyString()))
                    .thenReturn(ScanResult.clean(Path.of("staged"), "text/plain"));
            SseEmitter emitter = mock(SseEmitter.class);
            doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
            processor.registerEmitter("sid", emitter);

            processor.sendHeartbeats();                 // 心跳失败 → emitter 被移除
            processor.processScan("sid", Path.of("staged"), "a.txt");

            // 只有心跳那次失败的 send，扫描完成事件不再推送给已移除的 emitter
            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter, never()).complete();
        }
    }
}
