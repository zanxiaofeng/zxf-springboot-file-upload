package zxf.upload.domain.filescan;

/**
 * 阶段结论（java-coding-standard §3.2 代数数据类型：sealed + record）。
 */
public sealed interface ScanVerdict {

    /** 本阶段通过，继续下一阶段 */
    record Passed() implements ScanVerdict {}

    /** 检出威胁，终止管道（处置：INFECTED → 隔离） */
    record Infected(String threat) implements ScanVerdict {}

    /** 策略拒绝，终止管道（处置：REJECTED → 清理 staging） */
    record Rejected(String reason) implements ScanVerdict {}

    /** 放行但打标（macro-flagged），终止管道（处置：CLEAN + threat 打标透传客户端） */
    record Flagged(String threat) implements ScanVerdict {}
}
