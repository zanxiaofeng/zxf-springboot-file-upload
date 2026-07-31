package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.ScanResult;
import zxf.upload.model.ScanStatus;
import zxf.upload.model.UploadResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 异步扫描 + 结果分发。
 *
 * 并发模型：@Async 在虚拟线程上执行（每任务一个虚拟线程）。
 * 虚拟线程无池化，背压由 Semaphore(maxConcurrentScans) 提供：
 * 许可耗尽时提交方（Web 虚拟线程）阻塞等待，成本极低。
 *
 * 时序安全：
 * 1. 结果先入 completedScans 缓存；
 * 2. registerEmitter 时若结果已存在，立即回放并结束 —— 客户端何时连接都能拿到结果；
 * 3. 轮询端点 getResult 任何时刻可拿当前状态。
 */
@Slf4j
@Service
public class AsyncScanProcessor {
    private final VirusScanService scanService;
    private final Semaphore scanPermits;

    private final Map<String, SseEmitter> pendingEmitters = new ConcurrentHashMap<>();
    private final Map<String, UploadResponse> completedScans = new ConcurrentHashMap<>();

    public AsyncScanProcessor(VirusScanService scanService, VirusScanProperties properties) {
        this.scanService = scanService;
        this.scanPermits = new Semaphore(properties.getMaxConcurrentScans());
    }

    /** 轮询兜底端点使用 */
    public UploadResponse getResult(String scanId) {
        UploadResponse done = completedScans.get(scanId);
        return done != null ? done : UploadResponse.scanning(scanId);
    }

    public void registerEmitter(String scanId, SseEmitter emitter) {
        // 先查结果缓存：扫描可能已先于 SSE 连接完成
        UploadResponse done = completedScans.get(scanId);
        if (done != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName(done.getStatus())).data(done));
                emitter.complete();
            } catch (IOException e) {
                log.warn("SSE 回放失败: {}", scanId, e);
            }
            return;
        }
        pendingEmitters.put(scanId, emitter);
        emitter.onCompletion(() -> pendingEmitters.remove(scanId));
        emitter.onTimeout(() -> {
            pendingEmitters.remove(scanId);
            log.warn("SSE emitter 超时: {}", scanId);
        });
    }

    @Async   // 使用 Boot 装配的虚拟线程执行器（spring.threads.virtual.enabled=true）
    public void processScan(String scanId, Path stagingFile, String filename) {
        UploadResponse response;
        try {
            scanPermits.acquire();          // 背压：超过 maxConcurrentScans 时虚拟线程挂起等待
            try {
                ScanResult result = scanService.scanFile(stagingFile, filename);
                String storedPath = result.isClean() ? result.getDetails() : null;
                response = UploadResponse.of(scanId, result, storedPath);
            } finally {
                scanPermits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response = new UploadResponse(scanId, ScanStatus.ERROR, "扫描被中断", null);
        } catch (Exception e) {
            log.error("异步扫描失败: scanId={}", scanId, e);
            response = new UploadResponse(scanId, ScanStatus.ERROR, "扫描失败: " + e.getMessage(), null);
        }

        completedScans.put(scanId, response);
        SseEmitter emitter = pendingEmitters.remove(scanId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName(response.getStatus())).data(response));
                emitter.complete();
            } catch (IOException e) {
                log.warn("SSE 推送失败（结果已缓存，客户端可轮询）: {}", scanId, e);
            }
        }
    }

    private String eventName(ScanStatus status) {
        return switch (status) {
            case CLEAN -> "complete";
            case INFECTED, REJECTED -> "threat";
            default -> "error";
        };
    }
}