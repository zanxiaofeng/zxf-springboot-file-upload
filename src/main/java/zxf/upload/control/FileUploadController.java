package zxf.upload.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.ScanResult;
import zxf.upload.model.UploadResponse;
import zxf.upload.model.exception.FileRejectedException;
import zxf.upload.model.exception.ScanFailedException;
import zxf.upload.model.exception.VirusDetectedException;
import zxf.upload.service.AsyncScanProcessor;
import zxf.upload.service.StagingService;
import zxf.upload.service.VirusScanService;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileUploadController {
    private final StagingService stagingService;
    private final VirusScanService scanService;
    private final AsyncScanProcessor asyncProcessor;
    private final VirusScanProperties properties;

    private static final Set<String> TRUE_VALUES = Set.of("true", "on", "yes", "1");
    private static final Set<String> FALSE_VALUES = Set.of("false", "off", "no", "0");

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Scan-Async", defaultValue = "false") String asyncHeader) {

        boolean async = parseAsyncHeader(asyncHeader);

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
            default -> throw new ScanFailedException(result.getDetails());
        };
    }

    /**
     * 显式解析 X-Scan-Async：沿用 Spring 原生 boolean 转换接受的取值
     * （true/on/yes/1、false/off/no/0，忽略大小写），其余非法值按 400 拒绝，
     * 避免落入类型转换异常被兜底 handler 映射为 500。
     */
    private boolean parseAsyncHeader(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim();
        for (String t : TRUE_VALUES) {
            if (t.equalsIgnoreCase(normalized)) return true;
        }
        for (String f : FALSE_VALUES) {
            if (f.equalsIgnoreCase(normalized)) return false;
        }
        throw new FileRejectedException("X-Scan-Async 请求头取值非法: " + normalized);
    }

    /** SSE 推送（增强通道） */
    @GetMapping(value = "/scan/{scanId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scanEvents(@PathVariable String scanId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        asyncProcessor.registerEmitter(scanId, emitter);
        return emitter;
    }

    /** 轮询兜底：任何时刻都能拿到当前状态 */
    @GetMapping("/scan/{scanId}")
    public UploadResponse scanStatus(@PathVariable String scanId) {
        return asyncProcessor.getResult(scanId);
    }
}
