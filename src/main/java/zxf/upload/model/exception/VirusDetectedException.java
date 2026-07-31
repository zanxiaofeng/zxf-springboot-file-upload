package zxf.upload.model.exception;

import lombok.Getter;
import zxf.upload.model.ScanResult;

/** 检测到威胁（422） */
@Getter
public class VirusDetectedException extends RuntimeException {
    private final ScanResult scanResult;

    public VirusDetectedException(ScanResult scanResult) {
        super(scanResult.getThreat());
        this.scanResult = scanResult;
    }
}