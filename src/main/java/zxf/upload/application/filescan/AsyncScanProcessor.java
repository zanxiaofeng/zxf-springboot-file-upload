package zxf.upload.application.filescan;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import zxf.upload.infrastructure.config.FileScanProperties;
import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.domain.fileupload.UploadFile;
import zxf.upload.infrastructure.domain.ErrorCode;
import zxf.upload.domain.filescan.model.ScanStatus;
import zxf.upload.rest.fileupload.representation.UploadResponse;

import java.util.concurrent.TimeUnit;

/**
 * 异步扫描执行器 + 结果缓存（扫描应用支撑组件，非管道 Stage）。
 * <p>务实偏离（architecture §8 反模式 #16）：缓存 rest 层的 UploadResponse——
 * 它是轮询 body 的响应模型，事件化拆分属负收益，偏离就地声明。
 *
 * 并发模型：@Async 在虚拟线程上执行（每任务一个虚拟线程）。
 * 背压由 FileScanService 入口的 Semaphore 统一提供（同步/异步共用闸门）。
 *
 * 内存治理：completedScans 为 Caffeine 缓存，expireAfterWrite 自动淘汰，
 * 防止结果无限堆积导致内存泄漏。
 */
@Slf4j
@Service
public class AsyncScanProcessor {

    private static final long COMPLETED_SCAN_MAX_SIZE = 100_000;

    private final FileScanService fileScanService;

    /** 已完成扫描结果，供轮询查询 */
    private final Cache<String, UploadResponse> completedScans;

    public AsyncScanProcessor(FileScanService fileScanService, FileScanProperties properties) {
        this.fileScanService = fileScanService;
        this.completedScans = Caffeine.newBuilder()
                .expireAfterWrite(properties.getResultRetentionMinutes(), TimeUnit.MINUTES)
                .maximumSize(COMPLETED_SCAN_MAX_SIZE)
                .build();
    }

    /** 轮询查询：未完成（或结果已过期淘汰）返回 SCANNING 占位，客户端继续轮询 */
    public UploadResponse getResult(String scanId) {
        UploadResponse done = completedScans.getIfPresent(scanId);
        return done != null ? done : UploadResponse.scanning(scanId);
    }

    @Async   // 使用 Boot 装配的虚拟线程执行器（spring.threads.virtual.enabled=true）
    public void processScan(String scanId, UploadFile staged) {
        UploadResponse response;
        try {
            ScanResult result = fileScanService.scanFile(staged);
            String storedPath = result.isClean() ? result.getDetails() : null;
            response = UploadResponse.of(scanId, result, storedPath);
        } catch (Exception e) {
            // 引擎故障细节（host:port、内部路径、异常消息）只进日志；对外通用文案，
            // 与同步路径 GlobalExceptionHandler 对 SCAN_ENGINE_ERROR 的收口一致
            log.error("异步扫描失败: scanId={}", scanId, e);
            response = new UploadResponse(scanId, ScanStatus.ERROR, ErrorCode.SCAN_ENGINE_ERROR.getDefaultMessage(), null);
        }

        completedScans.put(scanId, response);
    }
}
