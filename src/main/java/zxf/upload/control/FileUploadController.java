package zxf.upload.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.ScanResult;
import zxf.upload.model.ScanStatus;
import zxf.upload.model.UploadResponse;
import zxf.upload.model.exception.FileRejectedException;
import zxf.upload.model.exception.ScanFailedException;
import zxf.upload.model.exception.VirusDetectedException;
import zxf.upload.service.AsyncScanProcessor;
import zxf.upload.service.StagingService;
import zxf.upload.service.VirusScanService;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 文件上传与扫描状态查询入口。
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileUploadController {
    private final StagingService stagingService;
    private final VirusScanService scanService;
    private final AsyncScanProcessor asyncProcessor;
    private final VirusScanProperties properties;

    /**
     * 同步或异步上传文件。
     *
     * @param file  上传文件，由 Spring 保证非空
     * @param async 是否走异步扫描通道
     * @return 同步时返回扫描结果；异步时返回 202 与 scanId
     */
    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Scan-Async", defaultValue = "false") boolean async) {

        // 大文件强制异步：同步全管道扫描耗时会超客户端/网关超时
        long syncMax = properties.getSyncMaxFileSize();
        if (!async && syncMax > 0 && file.getSize() > syncMax) {
            throw new FileRejectedException("文件超过 " + syncMax / 1024 / 1024
                    + "MB，同步扫描耗时过长，请使用异步上传（X-Scan-Async: true）");
        }

        String filename = StringUtils.cleanPath(
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown");

        // 请求线程内同步落盘，同步/异步扫描都只消费 Path
        Path stagingFile = stagingService.stage(file);

        if (async) {
            String scanId = UUID.randomUUID().toString();
            asyncProcessor.processScan(scanId, stagingFile, filename);
            return ResponseEntity.accepted().body(UploadResponse.scanning(scanId));
        }

        ScanResult result = scanService.scanFile(stagingFile, filename);
        return switch (result.getStatus()) {
            case CLEAN -> ResponseEntity.ok(UploadResponse.of(null, result, result.getDetails()));
            case REJECTED -> throw new FileRejectedException(result.getThreat());
            case INFECTED -> throw new VirusDetectedException(result);
            case ERROR -> throw new ScanFailedException(result.getDetails());
            case SCANNING -> throw new IllegalStateException("同步扫描不应返回 SCANNING 状态");
        };
    }

    /**
     * SSE 推送（增强通道）。
     *
     * @param scanId 扫描 ID，必须非空
     * @return SSE emitter
     */
    @GetMapping(value = "/scan/{scanId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scanEvents(@PathVariable String scanId) {
        Assert.hasText(scanId, "scanId must not be blank");
        SseEmitter emitter = new SseEmitter(300_000L);
        asyncProcessor.registerEmitter(scanId, emitter);
        return emitter;
    }

    /**
     * 轮询兜底：任何时刻都能拿到当前状态。
     *
     * @param scanId 扫描 ID，必须非空
     * @return 当前扫描状态
     */
    @GetMapping("/scan/{scanId}")
    public UploadResponse scanStatus(@PathVariable String scanId) {
        Assert.hasText(scanId, "scanId must not be blank");
        return asyncProcessor.getResult(scanId);
    }
}
