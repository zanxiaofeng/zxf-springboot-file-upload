package zxf.upload.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import zxf.upload.application.fileupload.UploadFileCommand;
import zxf.upload.application.fileupload.AsyncUploadCommandExecutor;
import zxf.upload.application.fileupload.SyncUploadCommandExecutor;
import zxf.upload.application.filescan.PollScanResultExecutor;
import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.rest.fileupload.representation.UploadResponse;

/**
 * 应用层唯一门面（architecture §4.1）：方法按 {domain}{Action} 命名，
 * 只做用例转发，无业务逻辑；写侧编排在 CommandExecutor，读侧在 QueryExecutor。
 *
 * <p>务实偏离（architecture §8 反模式 #16，注释说明）：{@link #fileScanStatus} 返回
 * rest 层的 {@link UploadResponse}——它是异步受理回执与轮询 body 的统一响应模型
 * （由 AsyncScanProcessor 缓存），强行拆分只会产生纯搬运代码。
 */
@Service
@RequiredArgsConstructor
public class ApplicationService {

    private final SyncUploadCommandExecutor syncUploadCommandExecutor;
    private final AsyncUploadCommandExecutor asyncUploadCommandExecutor;
    private final PollScanResultExecutor pollScanResultExecutor;

    /** 同步上传：门面转发，预检/落盘/扫描/结果翻译见 {@link SyncUploadCommandExecutor} */
    public ScanResult fileSyncUpload(UploadFileCommand command) {
        return syncUploadCommandExecutor.execute(command);
    }

    /** 异步上传：门面转发，受理与分发见 {@link AsyncUploadCommandExecutor} */
    public String fileAsyncUpload(UploadFileCommand command) {
        return asyncUploadCommandExecutor.execute(command);
    }

    /** 轮询查询：门面转发，见 {@link PollScanResultExecutor} */
    public UploadResponse fileScanStatus(String scanId) {
        return pollScanResultExecutor.execute(scanId);
    }
}
