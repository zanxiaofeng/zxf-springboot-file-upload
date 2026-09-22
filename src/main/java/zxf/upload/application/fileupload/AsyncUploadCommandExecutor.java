package zxf.upload.application.fileupload;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zxf.upload.application.fileupload.UploadFileCommand;
import zxf.upload.application.fileupload.UploadFileCommandChecker;
import zxf.upload.application.filescan.AsyncScanProcessor;
import zxf.upload.domain.fileupload.UploadFile;
import zxf.upload.infrastructure.fileupload.StagingService;

import java.util.UUID;

/**
 * 异步上传受理用例：预检、落盘、生成受理凭证并分发到虚拟线程后台扫描。
 * 202 响应与 SSE/轮询查询由 rest 层与 query 侧承接。
 */
@Component
@RequiredArgsConstructor
public class AsyncUploadCommandExecutor {

    private final UploadFileCommandChecker checker;
    private final StagingService stagingService;
    private final AsyncScanProcessor asyncScanProcessor;

    /** @return scanId（受理凭证，202 响应由 rest 层组装） */
    public String execute(UploadFileCommand command) {
        checker.check(command);

        UploadFile staged = stage(command);
        String scanId = UUID.randomUUID().toString();
        asyncScanProcessor.processScan(scanId, staged);
        return scanId;
    }

    /** 转换 + 持久化（staging 落盘）；落盘失败已由 StagingService 表达为业务拒绝 */
    private UploadFile stage(UploadFileCommand command) {
        UploadFile uploadFile = command.toUploadFile();
        return uploadFile.withStagingPath(stagingService.stage(uploadFile.cleanedName(), command.content()));
    }
}
