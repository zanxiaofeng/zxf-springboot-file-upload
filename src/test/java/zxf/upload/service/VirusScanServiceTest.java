package zxf.upload.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.ScanResult;
import zxf.upload.model.ScanStatus;
import zxf.upload.model.exception.ScanFailedException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 扫描管道单元测试（Mock 各扫描器与存储）。
 * 覆盖：阶段短路顺序、各终态的 staging 文件处置、fail-strategy 两种策略。
 */
class VirusScanServiceTest {

    private final FileTypeValidator fileTypeValidator = mock(FileTypeValidator.class);
    private final ClamAvScanner clamAvScanner = mock(ClamAvScanner.class);
    private final YaraScanner yaraScanner = mock(YaraScanner.class);
    private final DocumentThreatScanner documentThreatScanner = mock(DocumentThreatScanner.class);
    private final FileStorageService storageService = mock(FileStorageService.class);

    private VirusScanProperties properties;
    private VirusScanService scanService;

    @TempDir
    Path tempDir;
    private Path stagingFile;

    @BeforeEach
    void setUp() throws Exception {
        properties = new VirusScanProperties();
        scanService = new VirusScanService(properties, fileTypeValidator, clamAvScanner,
                yaraScanner, documentThreatScanner, storageService);
        stagingFile = Files.writeString(tempDir.resolve("staging.txt"), "hello");
    }

    /** 放行类型校验 + ClamAV/YARA 均干净 */
    private void passThrough(String mime) {
        when(fileTypeValidator.validate(any(), anyString()))
                .thenReturn(new FileTypeValidator.TypeCheck(true, mime, null));
        when(clamAvScanner.scan(any())).thenReturn(null);
        when(yaraScanner.scan(any())).thenReturn(null);
    }

    @Test
    void scanFile_typeRejected_shortCircuitsAndDeletesStaging() {
        when(fileTypeValidator.validate(any(), anyString()))
                .thenReturn(new FileTypeValidator.TypeCheck(false, "application/x-msdownload", "不支持的文件类型"));

        ScanResult result = scanService.scanFile(stagingFile, "evil.pdf");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.REJECTED);
        // 类型拒绝后不再调用任何扫描引擎与存储
        verifyNoInteractions(clamAvScanner, yaraScanner, documentThreatScanner, storageService);
        assertThat(stagingFile).doesNotExist();
    }

    @Test
    void scanFile_clean_storesAndDeletesStaging() throws Exception {
        passThrough("text/plain");
        when(storageService.store(any(), anyString())).thenReturn("/stored/abc.txt");

        ScanResult result = scanService.scanFile(stagingFile, "a.txt");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.CLEAN);
        assertThat(result.getDetails()).isEqualTo("/stored/abc.txt");
        verify(storageService).store(stagingFile, "a.txt");
        // 非文档类型不触发文档威胁检测
        verifyNoInteractions(documentThreatScanner);
        assertThat(stagingFile).doesNotExist();
    }

    @Test
    void scanFile_clamavHit_quarantinesAndShortCircuits() {
        when(fileTypeValidator.validate(any(), anyString()))
                .thenReturn(new FileTypeValidator.TypeCheck(true, "text/plain", null));
        when(clamAvScanner.scan(any())).thenReturn("ClamAV: Eicar-Test-Signature");

        ScanResult result = scanService.scanFile(stagingFile, "eicar.txt");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.INFECTED);
        assertThat(result.getThreat()).contains("Eicar");
        verify(storageService).moveToQuarantine(stagingFile);
        // ClamAV 命中后 YARA/文档检测不再执行
        verify(yaraScanner, never()).scan(any());
        verifyNoInteractions(documentThreatScanner);
    }

    @Test
    void scanFile_documentThreat_quarantines() {
        when(fileTypeValidator.validate(any(), anyString()))
                .thenReturn(new FileTypeValidator.TypeCheck(true, "application/pdf", null));
        when(fileTypeValidator.isDocumentFormat("application/pdf")).thenReturn(true);
        when(clamAvScanner.scan(any())).thenReturn(null);
        when(yaraScanner.scan(any())).thenReturn(null);
        when(documentThreatScanner.scan(any(), eq("application/pdf")))
                .thenReturn(new DocumentThreatScanner.DocThreat(
                        "PDF 包含 Launch 动作（可能执行外部程序）",
                        DocumentThreatScanner.DocThreat.Kind.PDF_ACTION));

        ScanResult result = scanService.scanFile(stagingFile, "evil.pdf");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.INFECTED);
        assertThat(result.getThreat()).contains("Launch");
        verify(storageService).moveToQuarantine(stagingFile);
    }

    @Test
    void scanFile_macroThreat_blockPolicy_quarantines() {
        // 默认 BLOCK：含宏文档判威胁（有正常业务场景的良性宏也会被拦，属预期误伤）
        passThrough("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        when(fileTypeValidator.isDocumentFormat(anyString())).thenReturn(true);
        when(documentThreatScanner.scan(any(), anyString()))
                .thenReturn(new DocumentThreatScanner.DocThreat(
                        "Office 文档包含 VBA 宏", DocumentThreatScanner.DocThreat.Kind.MACRO));

        ScanResult result = scanService.scanFile(stagingFile, "report.xlsx");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.INFECTED);
        verify(storageService).moveToQuarantine(stagingFile);
    }

    @Test
    void scanFile_macroThreat_flagPolicy_passesWithMarker() throws Exception {
        properties.setMacroPolicy(VirusScanProperties.MacroPolicy.FLAG);
        passThrough("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        when(fileTypeValidator.isDocumentFormat(anyString())).thenReturn(true);
        when(documentThreatScanner.scan(any(), anyString()))
                .thenReturn(new DocumentThreatScanner.DocThreat(
                        "Office 文档包含 VBA 宏", DocumentThreatScanner.DocThreat.Kind.MACRO));
        when(storageService.store(any(), anyString())).thenReturn("/stored/report.xlsx");

        ScanResult result = scanService.scanFile(stagingFile, "report.xlsx");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.CLEAN);
        assertThat(result.getThreat()).startsWith("macro-flagged");   // 放行打标贯穿到最终结果
        verify(storageService).store(stagingFile, "report.xlsx");
        verify(storageService, never()).moveToQuarantine(any());
    }

    @Test
    void scanFile_activexThreat_flagPolicy_stillBlocked() {
        // ActiveX 几乎无正常业务场景，FLAG 策略不适用，始终拦截
        properties.setMacroPolicy(VirusScanProperties.MacroPolicy.FLAG);
        passThrough("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        when(fileTypeValidator.isDocumentFormat(anyString())).thenReturn(true);
        when(documentThreatScanner.scan(any(), anyString()))
                .thenReturn(new DocumentThreatScanner.DocThreat(
                        "Office 文档包含 ActiveX 控件", DocumentThreatScanner.DocThreat.Kind.ACTIVE_X));

        ScanResult result = scanService.scanFile(stagingFile, "evil.docx");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.INFECTED);
        verify(storageService).moveToQuarantine(stagingFile);
    }

    @Test
    void scanFile_engineFailure_failClosed_rethrowsAndDeletesStaging() {
        passThrough("text/plain");
        when(clamAvScanner.scan(any())).thenThrow(new ScanFailedException("ClamAV 不可达"));

        assertThatThrownBy(() -> scanService.scanFile(stagingFile, "a.txt"))
                .isInstanceOf(ScanFailedException.class)
                .hasMessageContaining("ClamAV 不可达");
        assertThat(stagingFile).doesNotExist();
        verifyNoInteractions(storageService);
    }

    @Test
    void scanFile_engineFailure_failOpen_degradesToStore() throws Exception {
        properties.setFailStrategy(VirusScanProperties.FailStrategy.OPEN);
        passThrough("text/plain");
        when(clamAvScanner.scan(any())).thenThrow(new ScanFailedException("ClamAV 不可达"));
        when(storageService.store(any(), anyString())).thenAnswer(inv -> {
            // fail-open 必须先入库再清理 staging，否则 store 读不到文件
            assertThat(stagingFile).exists();
            return "/stored/degraded.txt";
        });

        ScanResult result = scanService.scanFile(stagingFile, "a.txt");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.CLEAN);
        assertThat(result.getThreat()).isEqualTo("scan-engine-degraded");
        assertThat(result.getDetails()).isEqualTo("/stored/degraded.txt");
        assertThat(stagingFile).doesNotExist();
    }

    @Test
    void scanFile_scanDisabled_storesDirectlyWithoutScanning() throws Exception {
        properties.setEnabled(false);
        when(storageService.store(any(), anyString())).thenReturn("/stored/raw.txt");

        ScanResult result = scanService.scanFile(stagingFile, "a.txt");

        assertThat(result.getStatus()).isEqualTo(ScanStatus.CLEAN);
        assertThat(result.getDetails()).isEqualTo("/stored/raw.txt");
        verifyNoInteractions(fileTypeValidator, clamAvScanner, yaraScanner, documentThreatScanner);
        assertThat(stagingFile).doesNotExist();
    }

    @Test
    void scanFile_concurrentCalls_neverExceedPermits() throws Exception {
        // 信号量闸门在管道入口：同步/异步调用统一背压
        properties.setMaxConcurrentScans(4);
        scanService = new VirusScanService(properties, fileTypeValidator, clamAvScanner,
                yaraScanner, documentThreatScanner, storageService);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(fileTypeValidator.validate(any(), anyString())).thenAnswer(inv -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(20);
            } finally {
                active.decrementAndGet();
            }
            return new FileTypeValidator.TypeCheck(true, "text/plain", null);
        });
        when(clamAvScanner.scan(any())).thenReturn(null);
        when(yaraScanner.scan(any())).thenReturn(null);
        when(storageService.store(any(), anyString())).thenReturn("/stored/x");

        ExecutorService pool = Executors.newFixedThreadPool(50);
        for (int i = 0; i < 50; i++) {
            int id = i;
            pool.submit(() -> scanService.scanFile(Path.of("virtual-" + id), "f" + id + ".txt"));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(maxActive.get()).isLessThanOrEqualTo(4);   // 背压生效
        assertThat(maxActive.get()).isGreaterThan(1);         // 且确实发生了并发
    }
}
