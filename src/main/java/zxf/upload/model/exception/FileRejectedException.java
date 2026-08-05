package zxf.upload.model.exception;

/** 策略拒绝（400）：文件过大、类型不符、ZIP 炸弹 */
public class FileRejectedException extends RuntimeException {
    public FileRejectedException(String message) { super(message); }
}
