package zxf.upload.model.exception;

import zxf.upload.model.ErrorCode;

/**
 * 扫描基础设施故障（502），fail-close 策略下抛出。
 */
public class ScanFailedException extends BusinessException {
    public ScanFailedException(String message) {
        super(ErrorCode.SCAN_ENGINE_ERROR, message);
    }

    public ScanFailedException(String message, Throwable cause) {
        super(ErrorCode.SCAN_ENGINE_ERROR, message, cause);
    }
}
