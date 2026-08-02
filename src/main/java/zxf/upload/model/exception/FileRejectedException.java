package zxf.upload.model.exception;

import zxf.upload.model.ErrorCode;

/**
 * 策略拒绝（400）：文件过大、类型不符、ZIP 炸弹等。
 */
public class FileRejectedException extends BusinessException {
    public FileRejectedException(String message) {
        super(ErrorCode.FILE_REJECTED, message);
    }
}
