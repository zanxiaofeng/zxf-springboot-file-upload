package zxf.upload.support.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import zxf.upload.model.exception.FileRejectedException;
import zxf.upload.model.exception.ScanFailedException;
import zxf.upload.model.exception.VirusDetectedException;

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
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("SCAN_ENGINE_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("FILE_TOO_LARGE", "文件超过大小限制"));
    }
}