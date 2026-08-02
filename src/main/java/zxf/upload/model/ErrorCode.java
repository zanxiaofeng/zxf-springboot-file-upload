package zxf.upload.model;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务错误码。统一维护错误标识、HTTP 状态码与默认对外消息，避免在 Handler 中硬编码。
 */
@Getter
public enum ErrorCode {
    FILE_REJECTED("FILE_REJECTED", HttpStatus.BAD_REQUEST, "文件被拒绝"),
    VIRUS_DETECTED("VIRUS_DETECTED", HttpStatus.UNPROCESSABLE_ENTITY, "检测到威胁"),
    SCAN_ENGINE_ERROR("SCAN_ENGINE_ERROR", HttpStatus.BAD_GATEWAY, "扫描引擎暂时不可用，请稍后重试"),
    FILE_TOO_LARGE("FILE_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, "文件超过大小限制"),
    INTERNAL_ERROR("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(String code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
