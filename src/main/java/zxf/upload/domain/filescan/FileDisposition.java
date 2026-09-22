package zxf.upload.domain.filescan;

import zxf.upload.domain.filescan.model.ScanStatus;

/**
 * 文件处置动作：按扫描状态查表（CLEAN 入库、INFECTED 隔离、其余丢弃）。
 * 动作执行（入库/隔离/删除）由 application 编排依赖基础设施完成。
 */
public enum FileDisposition {
    STORE, QUARANTINE, DISCARD;

    public static FileDisposition of(ScanStatus status) {
        return switch (status) {
            case CLEAN -> STORE;
            case INFECTED -> QUARANTINE;
            default -> DISCARD;
        };
    }
}
