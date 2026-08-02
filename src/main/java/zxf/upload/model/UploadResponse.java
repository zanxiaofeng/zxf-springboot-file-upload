package zxf.upload.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 上传扫描响应。
 *
 * @param scanId   扫描 ID（异步时返回）
 * @param status   扫描状态
 * @param message  对外可展示的消息
 * @param filePath 入库后的存储路径（CLEAN 时返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UploadResponse(String scanId, ScanStatus status, String message, String filePath) {

    /**
     * 构造 SCANNING 占位响应。
     *
     * @param scanId 扫描 ID
     * @return SCANNING 响应
     */
    public static UploadResponse scanning(String scanId) {
        return new UploadResponse(scanId, ScanStatus.SCANNING, "扫描进行中", null);
    }

    /**
     * 根据扫描结果构造响应。
     *
     * @param scanId     扫描 ID
     * @param result     扫描结果
     * @param storedPath 入库路径
     * @return 对应状态的响应
     */
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
