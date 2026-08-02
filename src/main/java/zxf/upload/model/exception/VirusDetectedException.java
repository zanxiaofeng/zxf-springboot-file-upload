package zxf.upload.model.exception;

import lombok.Getter;
import zxf.upload.model.ErrorCode;
import zxf.upload.model.ScanResult;

/**
 * 检测到威胁（422）。携带完整扫描结果供调用方审计。
 */
@Getter
public class VirusDetectedException extends BusinessException {
    private final ScanResult scanResult;

    public VirusDetectedException(ScanResult scanResult) {
        super(ErrorCode.VIRUS_DETECTED, scanResult.getThreat());
        this.scanResult = scanResult;
    }
}
