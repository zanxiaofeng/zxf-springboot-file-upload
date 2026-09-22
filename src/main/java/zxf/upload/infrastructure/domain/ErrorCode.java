package zxf.upload.infrastructure.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 业务错误码（exception-handling §3.2 三要素：code / defaultMessage / httpStatus）。
 * 新增业务错误 = 新增枚举值，而不是新增异常类。
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    FILE_REJECTED("FILE_REJECTED", "文件被拒绝", HttpStatus.BAD_REQUEST),
    VIRUS_DETECTED("VIRUS_DETECTED", "检测到威胁", HttpStatus.UNPROCESSABLE_ENTITY),
    SCAN_ENGINE_ERROR("SCAN_ENGINE_ERROR", "扫描引擎暂时不可用，请稍后重试", HttpStatus.BAD_GATEWAY),
    FILE_TOO_LARGE("FILE_TOO_LARGE", "文件超过大小限制", HttpStatus.PAYLOAD_TOO_LARGE),
    NOT_FOUND("NOT_FOUND", "资源不存在", HttpStatus.NOT_FOUND),
    VALIDATION_ERROR("VALIDATION_ERROR", "请求参数非法", HttpStatus.BAD_REQUEST),
    BAD_REQUEST("BAD_REQUEST", "请求非法", HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
