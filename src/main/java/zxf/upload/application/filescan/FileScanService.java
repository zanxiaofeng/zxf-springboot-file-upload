package zxf.upload.application.filescan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zxf.upload.domain.filescan.ScanPipeline;
import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.domain.filescan.model.ScanStatus;
import zxf.upload.domain.filescan.FileDisposition;
import zxf.upload.domain.fileupload.UploadFile;
import zxf.upload.infrastructure.config.FileScanProperties;
import zxf.upload.infrastructure.domain.BusinessException;
import zxf.upload.infrastructure.domain.ErrorCode;
import zxf.upload.infrastructure.io.FileUtils;
import zxf.upload.infrastructure.fileupload.FileStorageService;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/**
 * 扫描用例核心服务：背压闸门 + 调用扫描管道 + 按领域处置映射执行文件生命周期。
 * 管道判定逻辑（阶段顺序、短路）见 {@link ScanPipeline}；本类只关心并发与处置。
 *
 * 输入为已完成暂存的 {@link UploadFile}，拥有该文件的完整生命周期：
 * - STORE（CLEAN）：移交 FileStorageService 入库，删除 staging 文件；
 * - QUARANTINE（INFECTED）：移入隔离区；
 * - DISCARD（REJECTED/ERROR）：删除 staging 文件。
 * 调用方只拿结果，不接触临时文件。
 *
 * 并发闸门：信号量在入口统一 acquire，同步（Web 虚拟线程）与异步
 * 调用共用同一背压，防止并发上传打爆 ClamAV/YARA 引擎。
 */
@Slf4j
@Service
public class FileScanService {
    private final FileScanProperties properties;
    private final ScanPipeline scanPipeline;
    private final FileStorageService storageService;
    private final Semaphore scanPermits;

    public FileScanService(FileScanProperties properties,
                            ScanPipeline scanPipeline,
                            FileStorageService storageService) {
        this.properties = properties;
        this.scanPipeline = scanPipeline;
        this.storageService = storageService;
        this.scanPermits = new Semaphore(properties.getMaxConcurrentScans());
    }

    /**
     * 执行完整扫描管道。
     *
     * @return CLEAN 时 details 为正式存储文件名（不含路径）；其余状态 staging 文件已被妥善处理
     */
    public ScanResult scanFile(UploadFile staged) {
        try {
            scanPermits.acquire();   // 背压闸门：许可耗尽时（虚拟线程）挂起等待，成本极低
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BusinessException.scanFailed("扫描排队被中断", e);
        }
        try {
            return doScanWithCleanup(staged);
        } finally {
            scanPermits.release();
        }
    }

    /** 扫描 + 按领域处置映射处理 staging 文件；引擎故障按 fail 策略收口 */
    private ScanResult doScanWithCleanup(UploadFile staged) {
        try {
            return handleResult(staged, scanPipeline.execute(staged, properties.isEnabled()));
        } catch (BusinessException e) {
            return handleEngineFailure(staged, e);
        } catch (IOException e) {
            FileUtils.deleteQuietly(staged.stagingPath());
            throw BusinessException.scanFailed("扫描管道 IO 异常: " + e.getMessage(), e);
        }
    }

    /** 结果处置：STORE 入库并替换 details 为存储文件名；QUARANTINE 隔离；DISCARD 清理 */
    private ScanResult handleResult(UploadFile staged, ScanResult result) throws IOException {
        return switch (FileDisposition.of(result.getStatus())) {
            case STORE -> storeCleanResult(staged, result);
            case QUARANTINE -> {
                storageService.moveToQuarantine(staged.stagingPath());
                log.warn("Infected file quarantined: {}", FileUtils.sanitizeForLog(staged.cleanedName()));
                yield result;
            }
            case DISCARD -> {
                FileUtils.deleteQuietly(staged.stagingPath());
                yield result;
            }
        };
    }

    private ScanResult storeCleanResult(UploadFile staged, ScanResult result) throws IOException {
        String stored = storageService.store(staged.stagingPath(), staged.cleanedName());
        FileUtils.deleteQuietly(staged.stagingPath());
        log.info("Scan passed: {} -> {}", FileUtils.sanitizeForLog(staged.cleanedName()), stored);
        return ScanResult.builder()
                .status(ScanStatus.CLEAN)
                .detectedMime(result.getDetectedMime())
                .details(stored)
                .threat(result.getThreat())   // 保留打标（macro-flagged / scan-engine-degraded）
                .build();
    }

    /**
     * fail 策略统一收口：CLOSED 上抛（转 502）；OPEN 降级放行并打标。
     * 仅引擎故障可降级——业务拒绝被 fail-open 放行将是安全漏洞。
     */
    private ScanResult handleEngineFailure(UploadFile staged, BusinessException e) {
        if (e.getErrorCode() != ErrorCode.SCAN_ENGINE_ERROR) {
            FileUtils.deleteQuietly(staged.stagingPath());
            throw e;
        }
        if (properties.getFailStrategy() == FileScanProperties.FailStrategy.OPEN) {
            log.error("Scan engine failed, fail-open policy applied: {}", e.getMessage(), e);
            try {
                // 先入库再清理：若先 delete，store 将读不到文件
                String stored = storageService.store(staged.stagingPath(), staged.cleanedName());
                FileUtils.deleteQuietly(staged.stagingPath());
                return ScanResult.builder()
                        .status(ScanStatus.CLEAN)
                        .details(stored)
                        .threat("scan-engine-degraded")
                        .build();
            } catch (IOException ioe) {
                throw BusinessException.scanFailed("fail-open 降级存储失败", ioe);
            }
        }
        FileUtils.deleteQuietly(staged.stagingPath());
        throw e;
    }
}
