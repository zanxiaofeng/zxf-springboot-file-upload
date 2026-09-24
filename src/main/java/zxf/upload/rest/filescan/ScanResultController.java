package zxf.upload.rest.filescan;

import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zxf.upload.application.ApplicationService;
import zxf.upload.rest.fileupload.representation.UploadResponse;

/**
 * 扫描结果查询（FileScan 域端点）：异步受理后的轮询接口。
 * URL 保留 /api/files/async 前缀（对外契约，scanId 由异步受理生成）。
 */
@RestController
@RequestMapping("/api/files/async")
@RequiredArgsConstructor
public class ScanResultController {

    /** scanId 为 UUID（由 AsyncUploadCommandExecutor 生成） */
    private static final String SCAN_ID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final ApplicationService applicationService;

    /** 轮询查询：任何时刻都能拿到当前状态 */
    @GetMapping("/scan/{scanId}")
    public UploadResponse scanStatus(@PathVariable @Pattern(regexp = SCAN_ID_PATTERN, message = "scanId 格式非法") String scanId) {
        return applicationService.fileScanStatus(scanId);
    }
}
