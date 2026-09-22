package zxf.upload.rest.file.representation;

import com.fasterxml.jackson.annotation.JsonInclude;
import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.domain.filescan.model.ScanStatus;

/**
 * 上传/扫描状态响应（java-coding-standard §3.1：DTO 用 record）。
 * 同时作为 SSE data 与轮询 body 的统一响应模型。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UploadResponse(String scanId, ScanStatus status, String message, String filePath) {

    public static UploadResponse scanning(String scanId) {
        return new UploadResponse(scanId, ScanStatus.SCANNING, "扫描进行中", null);
    }

    public static UploadResponse of(String scanId, ScanResult result, String storedPath) {
        String message = switch (result.getStatus()) {
            // CLEAN 携带打标（macro-flagged / scan-engine-degraded）时透传给客户端
            case CLEAN -> result.getThreat() == null ? "文件安全" : "文件安全（" + result.getThreat() + "）";
            case INFECTED -> "检测到威胁: " + result.getThreat();
            case REJECTED -> "文件被拒绝: " + result.getThreat();
            case ERROR -> "扫描失败: " + result.getDetails();
            case SCANNING -> "扫描进行中";
        };
        return new UploadResponse(scanId, result.getStatus(), message, storedPath);
    }
}
