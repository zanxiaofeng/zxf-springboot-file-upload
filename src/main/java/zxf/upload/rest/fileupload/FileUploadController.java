package zxf.upload.rest.fileupload;

import lombok.RequiredArgsConstructor;
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
import zxf.upload.rest.fileupload.representation.UploadResponse;

import java.io.IOException;

/**
 * 上传受理（FileUpload 域端点）：同步 /sync 在请求线程内完成完整扫描管道，直接返回最终扫描结果；
 * 异步 /async 受理后立即返回 scanId，扫描在虚拟线程后台执行，
 * 结果查询（轮询/SSE）见 filescan 的 {@code ScanResultController}。
 * 本类只做 HTTP ↔ Command 协议转换。
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileUploadController {

    private final ApplicationService applicationService;

    @PostMapping("/sync/upload")
    public ResponseEntity<UploadResponse> syncUpload(@RequestParam("file") MultipartFile file) {
        ScanResult result = applicationService.fileSyncUpload(commandOf(file));
        return ResponseEntity.ok(UploadResponse.of(null, result, result.getDetails()));
    }

    @PostMapping("/async/upload")
    public ResponseEntity<UploadResponse> asyncUpload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.accepted()
                .body(UploadResponse.scanning(applicationService.fileAsyncUpload(commandOf(file))));
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
