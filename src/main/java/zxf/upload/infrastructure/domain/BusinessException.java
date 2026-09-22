package zxf.upload.infrastructure.domain;

/**
 * 业务异常统一出口（exception-handling §2.1/§3：BusinessException + ErrorCode 单一体系，
 * 禁止每条件新建异常类）。
 *
 * <p>消息分级（§1 原则 5）：
 * <ul>
 *   <li>{@code clientMessage}：客户端安全文案，缺省回退 {@link ErrorCode#getDefaultMessage()}；</li>
 *   <li>{@code detailMessage}：仅进日志的技术细节（引擎地址、内部路径、堆栈上下文），
 *       经 {@code RuntimeException#getMessage()} 承载，由 GlobalExceptionHandler 记录，不回显客户端。</li>
 * </ul>
 *
 * <p>高频场景用静态工厂表达意图；其他场景直接用构造器。
 */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String clientMessage;

    public BusinessException(ErrorCode errorCode, String clientMessage) {
        this(errorCode, clientMessage, clientMessage, null);
    }

    public BusinessException(ErrorCode errorCode, String clientMessage, Throwable cause) {
        this(errorCode, clientMessage, clientMessage, cause);
    }

    public BusinessException(ErrorCode errorCode, String clientMessage, String detailMessage, Throwable cause) {
        super(detailMessage != null ? detailMessage
                : (clientMessage != null ? clientMessage : errorCode.getDefaultMessage()), cause);
        this.errorCode = errorCode;
        this.clientMessage = clientMessage;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** 对外安全文案；null 回退 ErrorCode 默认文案 */
    public String getClientMessage() {
        return clientMessage != null ? clientMessage : errorCode.getDefaultMessage();
    }

    /** 策略拒绝（400）：文件过大、类型不符、ZIP 炸弹 */
    public static BusinessException rejected(String clientMessage) {
        return new BusinessException(ErrorCode.FILE_REJECTED, clientMessage);
    }

    /** 检测到威胁（422） */
    public static BusinessException virusDetected(String threat) {
        return new BusinessException(ErrorCode.VIRUS_DETECTED, threat);
    }

    /** 扫描基础设施故障（502）：detail 只进日志，对外固定安全文案 */
    public static BusinessException scanFailed(String detailMessage) {
        return new BusinessException(ErrorCode.SCAN_ENGINE_ERROR, null, detailMessage, null);
    }

    /** 扫描基础设施故障（502），保留根因链（exception-handling §4.2） */
    public static BusinessException scanFailed(String detailMessage, Throwable cause) {
        return new BusinessException(ErrorCode.SCAN_ENGINE_ERROR, null, detailMessage, cause);
    }
}
