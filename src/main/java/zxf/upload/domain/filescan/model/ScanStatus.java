package zxf.upload.domain.filescan.model;

public enum ScanStatus {
    SCANNING,
    CLEAN,
    INFECTED,   // 确认威胁（病毒/YARA 命中/恶意宏）
    REJECTED,   // 策略拒绝（超大/类型不符/伪造扩展名）
    ERROR       // 扫描服务自身故障
}
