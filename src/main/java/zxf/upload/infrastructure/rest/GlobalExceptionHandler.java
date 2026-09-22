package zxf.upload.infrastructure.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import zxf.upload.infrastructure.domain.BusinessException;
import zxf.upload.infrastructure.domain.ErrorCode;

/**
 * 全局异常映射（exception-handling §6 单一出口）。对外响应只携带错误码与
 * 客户端安全消息，内部细节（引擎地址、路径、堆栈）一律落内部日志，不泄漏给客户端。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorResponse(String code, String message) {}

    /**
     * 业务异常统一出口：code / HTTP 状态取自 {@link ErrorCode}，消息用客户端安全文案。
     * 日志分级（§8）：引擎故障 ERROR 附完整堆栈；4xx 业务拒绝 WARN 无堆栈。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        ErrorCode ec = ex.getErrorCode();
        if (ec == ErrorCode.SCAN_ENGINE_ERROR) {
            // 引擎故障详情（host:port、内部路径）在 detailMessage/堆栈中，只进日志
            log.error("扫描引擎故障: {}", ex.getMessage(), ex);
        } else {
            log.warn("业务拒绝: code={}, message={}", ec.getCode(), ex.getClientMessage());
        }
        return ResponseEntity.status(ec.getHttpStatus())
                .body(new ErrorResponse(ec.getCode(), ex.getClientMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse(ErrorCode.FILE_TOO_LARGE.getCode(),
                        ErrorCode.FILE_TOO_LARGE.getDefaultMessage()));
    }

    /** 缺 file part / 非 multipart 请求等客户端错误 → 400（避免落入兜底 handler 映射为 500） */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MultipartException.class})
    public ResponseEntity<ErrorResponse> handleMissingFilePart(Exception ex) {
        log.debug("非法上传请求: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCode.FILE_REJECTED.getCode(), "缺少上传文件或请求格式非法"));
    }

    /** 无匹配路由 → 404（§6.2 矩阵，避免被兜底 handler 映射为 500） */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        log.debug("无匹配路由: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ErrorCode.NOT_FOUND.getCode(),
                        ErrorCode.NOT_FOUND.getDefaultMessage()));
    }

    /** 方法级校验失败（类级 @Validated + 参数约束，如 scanId 格式）→ 400（§6.2 矩阵，不逐字段对外暴露） */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex) {
        log.debug("方法级校验失败: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCode.VALIDATION_ERROR.getCode(),
                        ErrorCode.VALIDATION_ERROR.getDefaultMessage()));
    }

    /** HTTP 方法不支持 → 405（§6.2 矩阵：405 状态 + 通用 BAD_REQUEST 错误码） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.debug("HTTP 方法不支持: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse(ErrorCode.BAD_REQUEST.getCode(), "不支持的请求方法"));
    }

    /** 兜底：未预见异常统一 500，固定文案不回显 ex.getMessage()，避免容器默认错误页泄漏细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("未预期异常: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR.getCode(),
                        ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
    }
}
