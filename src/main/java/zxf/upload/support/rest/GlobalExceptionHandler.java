package zxf.upload.support.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import zxf.upload.model.ErrorCode;
import zxf.upload.model.exception.BusinessException;
import zxf.upload.model.exception.FileRejectedException;
import zxf.upload.model.exception.ScanFailedException;
import zxf.upload.model.exception.VirusDetectedException;

/**
 * 全局异常映射。对外响应只携带错误码与通用消息，
 * 内部细节（引擎地址、路径、堆栈）一律落内部日志，不泄漏给客户端。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 统一错误响应体。
     *
     * @param code    错误码
     * @param message 对外可展示的消息
     */
    public record ErrorResponse(String code, String message) {}

    /**
     * 处理策略拒绝类异常（400）。
     *
     * @param ex 包含具体拒绝原因的异常
     * @return 400 错误响应
     */
    @ExceptionHandler(FileRejectedException.class)
    public ResponseEntity<ErrorResponse> handleFileRejected(FileRejectedException ex) {
        return buildResponse(ex, ex.getMessage());
    }

    /**
     * 处理病毒检出异常（422）。
     *
     * @param ex 包含扫描结果与威胁描述的异常
     * @return 422 错误响应
     */
    @ExceptionHandler(VirusDetectedException.class)
    public ResponseEntity<ErrorResponse> handleVirusDetected(VirusDetectedException ex) {
        return buildResponse(ex, ex.getMessage());
    }

    /**
     * 处理扫描引擎故障（502）。内部详情只进日志，对外使用错误码默认消息。
     *
     * @param ex 扫描失败异常
     * @return 502 错误响应
     */
    @ExceptionHandler(ScanFailedException.class)
    public ResponseEntity<ErrorResponse> handleScanFailed(ScanFailedException ex) {
        log.error("扫描引擎故障: {}", ex.getMessage(), ex);
        return buildResponse(ex, ex.getErrorCode().getDefaultMessage());
    }

    /**
     * 处理容器级 multipart 大小超限（413）。
     *
     * @param ex multipart 大小超限异常
     * @return 413 错误响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxSize(MaxUploadSizeExceededException ex) {
        return buildResponse(ErrorCode.FILE_TOO_LARGE, ErrorCode.FILE_TOO_LARGE.getDefaultMessage());
    }

    /**
     * 兜底：未预见异常统一 500，避免容器默认错误页泄漏细节。
     *
     * @param ex 未预期异常
     * @return 500 错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("未预期异常: {}", ex.getMessage(), ex);
        return buildResponse(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage());
    }

    private ResponseEntity<ErrorResponse> buildResponse(BusinessException ex, String message) {
        return buildResponse(ex.getErrorCode(), message);
    }

    private ResponseEntity<ErrorResponse> buildResponse(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.getStatus())
                .body(new ErrorResponse(errorCode.getCode(), message));
    }
}
