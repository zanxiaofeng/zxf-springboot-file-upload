package zxf.upload.control;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层测试：同步/异步上传状态码语义、SSE 与轮询端点、异常映射。
 * 异步时序与信号量背压见 {@link AsyncScanProcessorTests}。
 */
@WebMvcTest(FileUploadController.class)
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
    void upload_syncScanError_returns502() throws Exception {
        Path staging = tempDir.resolve("staging.bin");
        when(stagingService.stage(any())).thenReturn(staging);
        when(scanService.scanFile(any(), anyString()))
                .thenReturn(ScanResult.error(staging, "ClamAV 不可达"));

        mvc.perform(multipart("/api/files/upload").file(uploadFile()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("SCAN_ENGINE_ERROR"));
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

            assertThat(processor.getResult("nope").getStatus()).isEqualTo(ScanStatus.SCANNING);
        }

        @Test
        void processScan_scanThrows_cachedAsError() {
            AsyncScanProcessor processor = newProcessor(4);
            when(scanService.scanFile(any(), anyString()))
                    .thenThrow(new ScanFailedException("ClamAV 不可达"));

            processor.processScan("sid", Path.of("staged"), "a.txt");

            assertThat(processor.getResult("sid").getStatus()).isEqualTo(ScanStatus.ERROR);
        }

        @Test
        void processScan_concurrentSubmissions_neverExceedPermits() throws Exception {
            int permits = 4;
            AsyncScanProcessor processor = newProcessor(permits);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            when(scanService.scanFile(any(), anyString())).thenAnswer(inv -> {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                try {
                    Thread.sleep(30);
                } finally {
                    active.decrementAndGet();
                }
                return ScanResult.clean(Path.of("staged"), "text/plain");
            });

            ExecutorService pool = Executors.newFixedThreadPool(50);
            for (int i = 0; i < 50; i++) {
                int id = i;
                pool.submit(() -> processor.processScan("scan-" + id, Path.of("f" + id), "f" + id + ".txt"));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(maxActive.get()).isLessThanOrEqualTo(permits);   // 信号量背压生效
            assertThat(maxActive.get()).isGreaterThan(1);               // 且确实发生了并发
            assertThat(processor.getResult("scan-0").getStatus()).isEqualTo(ScanStatus.CLEAN);
        }
    }
}
