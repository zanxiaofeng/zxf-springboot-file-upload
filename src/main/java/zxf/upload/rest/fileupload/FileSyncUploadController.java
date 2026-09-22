package zxf.upload.rest.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import zxf.upload.application.ApplicationService;
import zxf.upload.application.fileupload.UploadFileCommand;
import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.infrastructure.domain.BusinessException;
import zxf.upload.rest.file.representation.UploadResponse;

import java.io.IOException;

/**
 * 同步上传：请求线程内完成完整扫描管道，直接返回最终扫描结果。
 * 本类只做 HTTP ↔ Command 协议转换，编排全部在 {@link ApplicationService}。
 */
@Slf4j
@RestController
@RequestMapping("/api/files/sync")
@RequiredArgsConstructor
public class FileSyncUploadController {

    private final ApplicationService applicationService;

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        ScanResult result = applicationService.fileSyncUpload(commandOf(file));
        return ResponseEntity.ok(UploadResponse.of(null, result, result.getDetails()));
    }

    /** HTTP multipart → Command 协议转换；流打开失败按业务拒绝表达（400） */
    private UploadFileCommand commandOf(MultipartFile file) {
        try {
            return UploadFileCommand.of(file);
        } catch (IOException e) {
            throw BusinessException.rejected("文件暂存失败，请稍后重试");
        }
    }
}
