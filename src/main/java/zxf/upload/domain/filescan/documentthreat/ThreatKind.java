package zxf.upload.domain.filescan.documentthreat;

/**
 * 文档威胁类别与处置语义。
 */
public enum ThreatKind {
    /** VBA 宏：存在正常业务场景（良性业务表格宏），可在 FLAG 策略下放行打标 */
    MACRO,
    /** ActiveX 控件：几乎无正常场景，始终拦截 */
    ACTIVE_X,
    /** PDF 危险动作（/Launch、自动执行 JavaScript）：始终拦截 */
    PDF_ACTION;

    /** 是否允许在 FLAG 策略下放行并打标（宏策略分级的领域判定） */
    public boolean canBeFlagged() {
        return this == MACRO;
    }
}
