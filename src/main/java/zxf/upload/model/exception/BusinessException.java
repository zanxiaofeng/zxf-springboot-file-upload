package zxf.upload.model.exception;

import lombok.Getter;
import zxf.upload.model.ErrorCode;

/**
 * 业务异常基类。所有业务语义异常均继承此类，便于全局异常处理器统一映射错误码。
 */
@Getter
public abstract class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
