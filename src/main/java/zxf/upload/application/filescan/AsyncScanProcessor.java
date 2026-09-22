package zxf.upload.application.filescan;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zxf.upload.infrastructure.config.FileScanProperties;
import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.domain.fileupload.UploadFile;
import zxf.upload.infrastructure.domain.ErrorCode;
import zxf.upload.domain.filescan.model.ScanStatus;
import zxf.upload.rest.file.representation.UploadResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 异步扫描 + 结果分发（扫描应用支撑组件，非管道 Stage）。
 * <p>务实偏离（architecture §8 反模式 #16）：缓存与推送 rest 层的 UploadResponse——
 * 它是 SSE data 与轮询 body 的统一响应模型，事件化拆分属负收益，偏离就地声明。
 *
 * 并发模型：@Async 在虚拟线程上执行（每任务一个虚拟线程）。
 * 背压由 FileScanService 入口的 Semaphore 统一提供（同步/异步共用闸门）。
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

    /** pendingEmitters TTL：需高于 SSE emitter 超时（Controller 300s），保证 emitter 超时前仍在缓存内可被心跳清理 */
    private static final long PENDING_EMITTER_TTL_MINUTES = 10;
    private static final long PENDING_EMITTER_MAX_SIZE = 10_000;
    private static final long COMPLETED_SCAN_MAX_SIZE = 100_000;

    private final FileScanService fileScanService;

    /** SSE 连接等待中的 emitter，正常路径由 onCompletion/onTimeout 移除 */
    private final Cache<String, SseEmitter> pendingEmitters;
    /** 已完成扫描结果，供 SSE 回放与轮询兜底 */
    private final Cache<String, UploadResponse> completedScans;

    public AsyncScanProcessor(FileScanService fileScanService, FileScanProperties properties) {
        this.fileScanService = fileScanService;
        long retention = properties.getResultRetentionMinutes();
        this.pendingEmitters = Caffeine.newBuilder()
                .expireAfterWrite(PENDING_EMITTER_TTL_MINUTES, TimeUnit.MINUTES)
                .maximumSize(PENDING_EMITTER_MAX_SIZE)
                .build();
        this.completedScans = Caffeine.newBuilder()
                .expireAfterWrite(retention, TimeUnit.MINUTES)
                .maximumSize(COMPLETED_SCAN_MAX_SIZE)
                .build();
    }

    /** 轮询兜底端点使用 */
    public UploadResponse getResult(String scanId) {
        UploadResponse done = completedScans.getIfPresent(scanId);
        return done != null ? done : UploadResponse.scanning(scanId);
    }

    /**
     * SSE 心跳：向等待中的 emitter 周期发送注释帧，防止中间代理
     * （nginx 默认 60s idle）在长扫描期间断开连接。发送失败的 emitter 即刻移除。
     */
    @Scheduled(fixedRateString = "${zxf.virus-scan.sse-heartbeat-seconds:15}", timeUnit = TimeUnit.SECONDS)
    public void sendHeartbeats() {
        pendingEmitters.asMap().forEach((scanId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                // 两参 remove：只摘当前 emitter，避免误删同 scanId 重连后的新 emitter
                pendingEmitters.asMap().remove(scanId, emitter);
                log.debug("SSE 心跳发送失败，移除 emitter: {}", scanId);
            }
        });
    }

    public void registerEmitter(String scanId, SseEmitter emitter) {
        // 先查结果缓存：扫描可能已先于 SSE 连接完成
        UploadResponse done = completedScans.getIfPresent(scanId);
        if (done == null) {
            pendingEmitters.put(scanId, emitter);
            emitter.onCompletion(() -> pendingEmitters.asMap().remove(scanId, emitter));
            emitter.onTimeout(() -> {
                pendingEmitters.asMap().remove(scanId, emitter);
                log.warn("SSE emitter 超时: {}", scanId);
            });
            // double-check：processScan 可能在上面查缓存与 put 之间完成，其 remove
            // 拿不到本 emitter，需在此补发；若 processScan 已摘走 emitter（remove 返回
            // false），推送由它负责，此处不重复发送（避免双发与 complete 后再 send）
            done = completedScans.getIfPresent(scanId);
            if (done == null || !pendingEmitters.asMap().remove(scanId, emitter)) {
                return;
            }
        }
        try {
            emitter.send(SseEmitter.event().name(eventName(done.status())).data(done));
            emitter.complete();
        } catch (IOException e) {
            // complete 有 sendFailed 守卫，send 失败后调用安全：显式结束连接，避免客户端挂到超时
            log.warn("SSE 回放失败: {}", scanId, e);
            emitter.complete();
        }
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
        SseEmitter emitter = pendingEmitters.asMap().remove(scanId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName(response.status())).data(response));
                emitter.complete();
            } catch (IOException e) {
                log.warn("SSE 推送失败（结果已缓存，客户端可轮询）: {}", scanId, e);
                emitter.complete();
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
