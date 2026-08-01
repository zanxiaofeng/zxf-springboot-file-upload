package zxf.upload.support.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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

    public record ErrorResponse(String code, String message) {}

    @ExceptionHandler(FileRejectedException.class)
    public ResponseEntity<ErrorResponse> handleFileRejected(FileRejectedException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("FILE_REJECTED", ex.getMessage()));
    }

    @ExceptionHandler(VirusDetectedException.class)
    public ResponseEntity<ErrorResponse> handleVirusDetected(VirusDetectedException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorResponse("VIRUS_DETECTED", ex.getScanResult().getThreat()));
    }

    @ExceptionHandler(ScanFailedException.class)
    public ResponseEntity<ErrorResponse> handleScanFailed(ScanFailedException ex) {
        // 引擎故障详情（host:port、内部路径）只进日志，对外通用消息
        log.error("扫描引擎故障: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("SCAN_ENGINE_ERROR", "扫描引擎暂时不可用，请稍后重试"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("FILE_TOO_LARGE", "文件超过大小限制"));
    }

    /** 兜底：未预见异常统一 500，避免容器默认错误页泄漏细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("未预期异常: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "服务器内部错误"));
    }
}
