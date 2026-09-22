package zxf.upload.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zxf.upload.application.fileupload.UploadFileCommand;
import zxf.upload.application.fileupload.AsyncUploadCommandExecutor;
import zxf.upload.application.fileupload.SyncUploadCommandExecutor;
import zxf.upload.application.filescan.AsyncScanProcessor;
import zxf.upload.application.filescan.PollScanResultExecutor;
import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.rest.file.representation.UploadResponse;

/**
 * 应用层唯一门面（architecture §4.1）：方法按 {domain}{Action} 命名，
 * 只做用例转发，无业务逻辑；写侧编排在 CommandExecutor，读侧在 QueryExecutor。
 *
 * <p>务实偏离（architecture §4.3/§8 反模式 #16，注释说明）：{@link #fileScanStatus} 返回
 * rest 层的 {@link UploadResponse}、{@link #fileScanEvents} 直接注册 SSE——二者是
 * SSE data 与轮询 body 的统一响应模型（已由 GetScanResultQueryExecutor 承接读侧），
 * 订阅语义非标准 CQRS 读取，强行拆分只会产生纯搬运代码。
 */
@Service
@RequiredArgsConstructor
public class ApplicationService {

    /** SSE 连接超时；需低于 AsyncScanProcessor 中 pendingEmitters 的 TTL（10 分钟） */
    private static final long SSE_EMITTER_TIMEOUT_MS = 300_000L;

    private final SyncUploadCommandExecutor syncScanCommandExecutor;
    private final AsyncUploadCommandExecutor asyncScanCommandExecutor;
    private final PollScanResultExecutor pollScanResultExecutor;
    private final AsyncScanProcessor asyncScanProcessor;

    /** 同步上传：门面转发，预检/落盘/扫描/结果翻译见 {@link SyncUploadCommandExecutor} */
    public ScanResult fileSyncUpload(UploadFileCommand command) {
        return syncScanCommandExecutor.execute(command);
    }

    /** 异步上传：门面转发，受理与分发见 {@link AsyncUploadCommandExecutor} */
    public String fileAsyncUpload(UploadFileCommand command) {
        return asyncScanCommandExecutor.execute(command);
    }

    /** 轮询兜底：门面转发，见 {@link PollScanResultExecutor} */
    public UploadResponse fileScanStatus(String scanId) {
        return pollScanResultExecutor.execute(scanId);
    }

    /** SSE 推送（增强通道）：订阅语义，非标准 CQRS 读取（见类注释偏离说明） */
    public SseEmitter fileScanEvents(String scanId) {
        SseEmitter emitter = new SseEmitter(SSE_EMITTER_TIMEOUT_MS);
        asyncScanProcessor.registerEmitter(scanId, emitter);
        return emitter;
    }
}
