package zxf.upload.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.ScanResult;
import zxf.upload.model.ScanStatus;
import zxf.upload.model.UploadResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 异步扫描 + 结果分发。
 *
 * 并发模型：@Async 在虚拟线程上执行（每任务一个虚拟线程）。
 * 背压由 VirusScanService 入口的 Semaphore 统一提供（同步/异步共用闸门）。
 *
 * 时序安全：
 * 1. 结果先入 completedScans 缓存；
 * 2. registerEmitter 时若结果已存在，立即回放并结束 —— 客户端何时连接都能拿到结果；
 * 3. 轮询端点 getResult 任何时刻可拿当前状态。
 *
 * 内存治理：两个缓存均为 Caffeine，expireAfterWrite 自动淘汰，
 * 防止结果与孤儿 emitter 无限堆积导致内存泄漏。
 */
@Slf4j
@Service
public class AsyncScanProcessor {
    private final VirusScanService scanService;

    /** SSE 连接等待中的 emitter；TTL 略高于 emitter 超时（300s），正常路径由 onCompletion/onTimeout 移除 */
    private final Cache<String, SseEmitter> pendingEmitters;
    /** 已完成扫描结果，供 SSE 回放与轮询兜底 */
    private final Cache<String, UploadResponse> completedScans;

    public AsyncScanProcessor(VirusScanService scanService, VirusScanProperties properties) {
        this.scanService = scanService;
        long retention = properties.getResultRetentionMinutes();
        this.pendingEmitters = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();
        this.completedScans = Caffeine.newBuilder()
                .expireAfterWrite(retention, TimeUnit.MINUTES)
                .maximumSize(100_000)
                .build();
    }

    /**
     * 轮询兜底端点使用。
     *
     * @param scanId 扫描 ID，必须非空
     * @return 已完成响应或 SCANNING 占位响应
     */
    public UploadResponse getResult(String scanId) {
        Assert.hasText(scanId, "scanId must not be blank");
        UploadResponse done = completedScans.getIfPresent(scanId);
        return done != null ? done : UploadResponse.scanning(scanId);
    }

    /**
     * SSE 心跳：向等待中的 emitter 周期发送注释帧，防止中间代理
     * （nginx 默认 60s idle）在长扫描期间断开连接。发送失败的 emitter 即刻移除。
     */
    @Scheduled(fixedRateString = "${zxf.virus-scan.sse-heartbeat-seconds:15}", timeUnit = TimeUnit.SECONDS)
    public void sendHeartbeats() {
        var emitters = pendingEmitters.asMap();
        emitters.forEach((scanId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                emitters.remove(scanId);
                log.debug("SSE 心跳发送失败，移除 emitter: {}", scanId);
            }
        });
    }

    /**
     * 注册 SSE emitter。若扫描结果已存在则立即回放。
     *
     * @param scanId 扫描 ID，必须非空
     * @param emitter SSE emitter，必须非空
     */
    public void registerEmitter(String scanId, SseEmitter emitter) {
        Assert.hasText(scanId, "scanId must not be blank");
        Assert.notNull(emitter, "emitter must not be null");
        // 先查结果缓存：扫描可能已先于 SSE 连接完成
        UploadResponse done = completedScans.getIfPresent(scanId);
        if (done != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName(done.status())).data(done));
                emitter.complete();
            } catch (IOException e) {
                log.warn("SSE 回放失败: {}", scanId, e);
            }
            return;
        }
        pendingEmitters.put(scanId, emitter);
        emitter.onCompletion(() -> removeEmitter(scanId));
        emitter.onTimeout(() -> {
            removeEmitter(scanId);
            log.warn("SSE emitter 超时: {}", scanId);
        });
    }

    /**
     * 异步执行扫描管道并推送结果。
     *
     * @param scanId      扫描 ID，必须非空
     * @param stagingFile 暂存文件路径，必须非空
     * @param filename    原始文件名
     */
    @Async   // 使用 Boot 装配的虚拟线程执行器（spring.threads.virtual.enabled=true）
    public void processScan(String scanId, Path stagingFile, String filename) {
        Assert.hasText(scanId, "scanId must not be blank");
        Assert.notNull(stagingFile, "stagingFile must not be null");
        UploadResponse response;
        try {
            ScanResult result = scanService.scanFile(stagingFile, filename);
            String storedPath = result.isClean() ? result.getDetails() : null;
            response = UploadResponse.of(scanId, result, storedPath);
        } catch (Exception e) {
            log.error("异步扫描失败: scanId={}", scanId, e);
            // 对外脱敏：内部异常详情只进日志，不泄漏给客户端（与 GlobalExceptionHandler 同一原则）
            response = new UploadResponse(scanId, ScanStatus.ERROR, "扫描失败，请稍后重试", null);
        }

        completedScans.put(scanId, response);
        SseEmitter emitter = removeEmitter(scanId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName(response.status())).data(response));
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

    /** 从等待缓存移除 emitter，返回移除的实例（可能为 null） */
    private SseEmitter removeEmitter(String scanId) {
        return pendingEmitters.asMap().remove(scanId);
    }
}
