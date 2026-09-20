package zxf.upload.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zxf.upload.model.UploadResponse;
import zxf.upload.service.AsyncScanProcessor;
import zxf.upload.service.StagingService;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 异步上传：受理后立即返回 scanId，扫描在虚拟线程后台执行，
 * 结果通过轮询或 SSE 获取。
 */
@Slf4j
@RestController
@RequestMapping("/api/files/async")
@RequiredArgsConstructor
public class FileAsyncUploadController {

    /** SSE 连接超时；需低于 AsyncScanProcessor 中 pendingEmitters 的 TTL（10 分钟） */
    private static final long SSE_EMITTER_TIMEOUT_MS = 300_000L;

    private final StagingService stagingService;
    private final AsyncScanProcessor asyncProcessor;

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        String filename = StringUtils.cleanPath(
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown");

        // 请求线程内同步落盘，异步扫描只消费 Path
        Path stagingFile = stagingService.stage(file);

        String scanId = UUID.randomUUID().toString();
        asyncProcessor.processScan(scanId, stagingFile, filename);
        return ResponseEntity.accepted().body(UploadResponse.scanning(scanId));
    }

    /** SSE 推送（增强通道） */
    @GetMapping(value = "/scan/{scanId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scanEvents(@PathVariable String scanId) {
        SseEmitter emitter = new SseEmitter(SSE_EMITTER_TIMEOUT_MS);
        asyncProcessor.registerEmitter(scanId, emitter);
        return emitter;
    }

    /** 轮询兜底：任何时刻都能拿到当前状态 */
    @GetMapping("/scan/{scanId}")
    public UploadResponse scanStatus(@PathVariable String scanId) {
        return asyncProcessor.getResult(scanId);
    }
}
