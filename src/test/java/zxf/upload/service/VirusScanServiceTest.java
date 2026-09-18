package zxf.upload.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.ScanResult;
import zxf.upload.model.ScanStatus;
import zxf.upload.model.exception.ScanFailedException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VirusScanService 核心管道")
class VirusScanServiceTest {

    @Mock FileTypeValidator fileTypeValidator;
    @Mock ClamAvScanner clamAvScanner;
    @Mock YaraScanner yaraScanner;
    @Mock DocumentThreatScanner documentThreatScanner;
    @Mock FileStorageService storageService;

    @TempDir Path tempDir;

    private VirusScanProperties properties;
    private Path stagingFile;

    @BeforeEach
    void setUp() throws Exception {
        properties = new VirusScanProperties();
        properties.setMaxConcurrentScans(16);
        stagingFile = tempDir.resolve("test.txt");
        Files.writeString(stagingFile, "clean content");
    }

    private VirusScanService createService() {
        return new VirusScanService(properties, fileTypeValidator,
                clamAvScanner, yaraScanner, documentThreatScanner, storageService);
    }

    @Test
    @DisplayName("类型拒绝后不再调 ClamAV")
    void typeReject_shortCircuits() throws Exception {
        when(fileTypeValidator.validate(any(), any()))
                .thenReturn(new FileTypeValidator.TypeCheck(false, "application/x-msdownload", "不支持的文件类型"));

        VirusScanService svc = createService();
        ScanResult result = svc.scanFile(stagingFile, "test.exe");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.REJECTED);
        verifyNoInteractions(clamAvScanner);
        verifyNoInteractions(yaraScanner);
        verify(storageService, never()).moveToQuarantine(any());
        verify(storageService, never()).store(any(), any());
        assertThat(Files.exists(stagingFile)).isFalse();   // staging 文件已清理
    }

    @Test
    @DisplayName("CLEAN → staging 文件被删除且调用 store")
    void clean_storesAndDeletes() throws Exception {
        when(fileTypeValidator.validate(any(), any()))
                .thenReturn(new FileTypeValidator.TypeCheck(true, "text/plain", null));
        when(clamAvScanner.scan(any())).thenReturn(null);
        when(yaraScanner.scan(any())).thenReturn(null);
        when(storageService.store(any(), any())).thenReturn("/data/stored/file.txt");

        VirusScanService svc = createService();
        ScanResult result = svc.scanFile(stagingFile, "test.txt");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.CLEAN);
        assertThat(result.getDetails()).isEqualTo("/data/stored/file.txt");
        verify(storageService).store(stagingFile, "test.txt");
        assertThat(Files.exists(stagingFile)).isFalse();
    }

    @Test
    @DisplayName("INFECTED → 调用 moveToQuarantine")
    void infected_quarantines() throws Exception {
        when(fileTypeValidator.validate(any(), any()))
                .thenReturn(new FileTypeValidator.TypeCheck(true, "text/plain", null));
        when(clamAvScanner.scan(any())).thenReturn("ClamAV: Eicar-Test-Signature");

        VirusScanService svc = createService();
        ScanResult result = svc.scanFile(stagingFile, "eicar.txt");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.INFECTED);
        verify(storageService).moveToQuarantine(stagingFile);
        verify(storageService, never()).store(any(), any());
    }

    @Test
    @DisplayName("ScanFailedException + CLOSED → 异常上抛")
    void scanFailed_closedStrategy_throws() throws Exception {
        when(fileTypeValidator.validate(any(), any()))
                .thenReturn(new FileTypeValidator.TypeCheck(true, "text/plain", null));
        when(clamAvScanner.scan(any())).thenThrow(new ScanFailedException("ClamAV 不可达"));

        VirusScanService svc = createService();  // 默认 CLOSED
        assertThatThrownBy(() -> svc.scanFile(stagingFile, "test.txt"))
                .isInstanceOf(ScanFailedException.class);

        verify(storageService, never()).store(any(), any());
        assertThat(Files.exists(stagingFile)).isFalse();
    }

    @Test
    @DisplayName("ScanFailedException + OPEN → 降级入库并打标 scan-engine-degraded")
    void scanFailed_openStrategy_degrades() throws Exception {
        properties.setFailStrategy(VirusScanProperties.FailStrategy.OPEN);
        when(fileTypeValidator.validate(any(), any()))
                .thenReturn(new FileTypeValidator.TypeCheck(true, "text/plain", null));
        when(clamAvScanner.scan(any())).thenThrow(new ScanFailedException("ClamAV 不可达"));
        when(storageService.store(any(), any())).thenReturn("/data/stored/file.txt");

        VirusScanService svc = createService();
        ScanResult result = svc.scanFile(stagingFile, "test.txt");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.CLEAN);
        assertThat(result.getThreat()).isEqualTo("scan-engine-degraded");
        // 先 store 后 delete（顺序重要）
        verify(storageService).store(stagingFile, "test.txt");
        assertThat(Files.exists(stagingFile)).isFalse();
    }

    @Test
    @DisplayName("宏策略：MACRO + FLAG → 放行且打标 macro-flagged")
    void macro_flagPolicy_passesWithFlag() throws Exception {
        properties.setMacroPolicy(VirusScanProperties.MacroPolicy.FLAG);
        when(fileTypeValidator.validate(any(), any()))
                .thenReturn(new FileTypeValidator.TypeCheck(true,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null));
        when(fileTypeValidator.isDocumentFormat(any())).thenReturn(true);
        when(clamAvScanner.scan(any())).thenReturn(null);
        when(yaraScanner.scan(any())).thenReturn(null);
        when(documentThreatScanner.scan(any(), any()))
                .thenReturn(new DocumentThreatScanner.DocThreat("包含 VBA 宏", DocumentThreatScanner.DocThreat.Kind.MACRO));
        when(storageService.store(any(), any())).thenReturn("/data/stored/file.docx");

        VirusScanService svc = createService();
        ScanResult result = svc.scanFile(stagingFile, "file.docx");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.CLEAN);
        assertThat(result.getThreat()).startsWith("macro-flagged:");
    }

    @Test
    @DisplayName("宏策略：ACTIVE_X + FLAG → 仍拦截")
    void activeX_flagPolicy_stillBlocks() {
        properties.setMacroPolicy(VirusScanProperties.MacroPolicy.FLAG);
        when(fileTypeValidator.validate(any(), any()))
                .thenReturn(new FileTypeValidator.TypeCheck(true,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null));
        when(fileTypeValidator.isDocumentFormat(any())).thenReturn(true);
        when(clamAvScanner.scan(any())).thenReturn(null);
        when(yaraScanner.scan(any())).thenReturn(null);
        when(documentThreatScanner.scan(any(), any()))
                .thenReturn(new DocumentThreatScanner.DocThreat("包含 ActiveX 控件", DocumentThreatScanner.DocThreat.Kind.ACTIVE_X));

        VirusScanService svc = createService();
        ScanResult result = svc.scanFile(stagingFile, "file.docx");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.INFECTED);
        verify(storageService).moveToQuarantine(stagingFile);
    }

    @Test
    @DisplayName("并发闸门：同时扫描数不超过 maxConcurrentScans")
    void concurrencySemaphore() throws Exception {
        properties.setMaxConcurrentScans(2);
        int taskCount = 10;
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();

        when(fileTypeValidator.validate(any(), any()))
                .thenReturn(new FileTypeValidator.TypeCheck(true, "text/plain", null));
        when(clamAvScanner.scan(any())).thenAnswer(inv -> {
            int cur = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(cur, Math::max);
            Thread.sleep(200);   // 模拟扫描耗时
            concurrent.decrementAndGet();
            return null;
        });
        when(yaraScanner.scan(any())).thenReturn(null);
        when(storageService.store(any(), any())).thenReturn("/data/stored/file.txt");

        VirusScanService svc = createService();
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            Path file = tempDir.resolve("test-" + i + ".txt");
            Files.writeString(file, "content-" + i);
            executor.submit(() -> {
                try {
                    svc.scanFile(file, "test.txt");
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(maxConcurrent.get()).isLessThanOrEqualTo(2);
    }
}
