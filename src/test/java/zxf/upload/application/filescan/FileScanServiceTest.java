package zxf.upload.application.filescan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zxf.upload.domain.filescan.ScanPipeline;
import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.domain.filescan.model.ScanStatus;
import zxf.upload.domain.fileupload.UploadFile;
import zxf.upload.infrastructure.config.FileScanProperties;
import zxf.upload.infrastructure.domain.BusinessException;
import zxf.upload.infrastructure.fileupload.FileStorageService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * 只覆盖背压与处置（文件生命周期）；管道判定/短路/宏策略见
 * ScanPipelineTest、DocumentThreatScanStageTest、FileTypeScanStageTest。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileScanService：背压 + 领域处置 + fail 策略收口")
class FileScanServiceTest {

    @Mock ScanPipeline scanPipeline;
    @Mock FileStorageService storageService;

    @TempDir Path tempDir;

    private FileScanProperties properties;
    private Path stagingFile;
    private UploadFile staged;

    @BeforeEach
    void setUp() throws Exception {
        properties = new FileScanProperties();
        properties.setMaxConcurrentScans(16);
        stagingFile = tempDir.resolve("test.txt");
        Files.writeString(stagingFile, "clean content");
        staged = UploadFile.unstaged("test.txt", 13).withStagingPath(stagingFile);
    }

    private FileScanService createService() {
        return new FileScanService(properties, scanPipeline, storageService);
    }

    @Test
    @DisplayName("CLEAN → staging 文件入库后被删除，details 替换为存储文件名")
    void clean_storesAndDeletes() throws Exception {
        when(scanPipeline.execute(any(), anyBoolean()))
                .thenReturn(ScanResult.clean(stagingFile, "text/plain"));
        when(storageService.store(any(), any())).thenReturn("/data/stored/file.txt");

        ScanResult result = createService().scanFile(staged);

        assertThat(result.getStatus()).isEqualTo(ScanStatus.CLEAN);
        assertThat(result.getDetails()).isEqualTo("/data/stored/file.txt");
        verify(storageService).store(stagingFile, "test.txt");
        assertThat(Files.exists(stagingFile)).isFalse();
    }

    @Test
    @DisplayName("INFECTED → 调用 moveToQuarantine，不入库")
    void infected_quarantines() throws Exception {
        when(scanPipeline.execute(any(), anyBoolean()))
                .thenReturn(ScanResult.infected(stagingFile, "ClamAV: Eicar-Test-Signature"));

        ScanResult result = createService().scanFile(staged);

        assertThat(result.getStatus()).isEqualTo(ScanStatus.INFECTED);
        verify(storageService).moveToQuarantine(stagingFile);
        verify(storageService, never()).store(any(), any());
    }

    @Test
    @DisplayName("REJECTED → DISCARD 清理 staging")
    void rejected_discards() throws Exception {
        when(scanPipeline.execute(any(), anyBoolean()))
                .thenReturn(ScanResult.rejected(stagingFile, "文件类型与扩展名不符"));

        ScanResult result = createService().scanFile(staged);

        assertThat(result.getStatus()).isEqualTo(ScanStatus.REJECTED);
        verify(storageService, never()).store(any(), any());
        verify(storageService, never()).moveToQuarantine(any());
        assertThat(Files.exists(stagingFile)).isFalse();
    }

    @Test
    @DisplayName("引擎故障 + CLOSED（默认）→ 异常上抛并清理 staging")
    void scanFailed_closedStrategy_throws() throws Exception {
        when(scanPipeline.execute(any(), anyBoolean()))
                .thenThrow(BusinessException.scanFailed("ClamAV 不可达"));

        assertThatThrownBy(() -> createService().scanFile(staged))
                .isInstanceOf(BusinessException.class);

        verify(storageService, never()).store(any(), any());
        assertThat(Files.exists(stagingFile)).isFalse();
    }

    @Test
    @DisplayName("引擎故障 + OPEN → 降级入库并打标 scan-engine-degraded")
    void scanFailed_openStrategy_degrades() throws Exception {
        properties.setFailStrategy(FileScanProperties.FailStrategy.OPEN);
        when(scanPipeline.execute(any(), anyBoolean()))
                .thenThrow(BusinessException.scanFailed("ClamAV 不可达"));
        when(storageService.store(any(), any())).thenReturn("/data/stored/file.txt");

        ScanResult result = createService().scanFile(staged);

        assertThat(result.getStatus()).isEqualTo(ScanStatus.CLEAN);
        assertThat(result.getThreat()).isEqualTo("scan-engine-degraded");
        // 先 store 后 delete（顺序重要）
        verify(storageService).store(stagingFile, "test.txt");
        assertThat(Files.exists(stagingFile)).isFalse();
    }

    @Test
    @DisplayName("业务拒绝不被 fail-open 降级放行（安全守卫）")
    void rejected_notDegaDEDByFailOpen() throws Exception {
        properties.setFailStrategy(FileScanProperties.FailStrategy.OPEN);
        when(scanPipeline.execute(any(), anyBoolean()))
                .thenThrow(BusinessException.rejected("不支持的文件类型"));

        assertThatThrownBy(() -> createService().scanFile(staged))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(zxf.upload.infrastructure.domain.ErrorCode.FILE_REJECTED);
        verify(storageService, never()).store(any(), any());
    }

    @Test
    @DisplayName("并发闸门：同时扫描数不超过 maxConcurrentScans")
    void concurrencySemaphore() throws Exception {
        properties.setMaxConcurrentScans(2);
        int taskCount = 10;
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();

        when(scanPipeline.execute(any(), anyBoolean())).thenAnswer(inv -> {
            int cur = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(cur, Math::max);
            Thread.sleep(200);   // 模拟扫描耗时
            concurrent.decrementAndGet();
            return ScanResult.clean(stagingFile, "text/plain");
        });
        when(storageService.store(any(), any())).thenReturn("/data/stored/file.txt");

        FileScanService svc = createService();
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            Path file = tempDir.resolve("test-" + i + ".txt");
            Files.writeString(file, "content-" + i);
            executor.submit(() -> {
                try {
                    svc.scanFile(UploadFile.unstaged("test.txt", 13).withStagingPath(file));
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
