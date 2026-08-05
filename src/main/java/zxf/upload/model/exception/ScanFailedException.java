package zxf.upload.model.exception;

/** 扫描基础设施故障（502），fail-close 策略下抛出 */
public class ScanFailedException extends RuntimeException {
    public ScanFailedException(String message) { super(message); }
    public ScanFailedException(String message, Throwable cause) { super(message, cause); }
}
