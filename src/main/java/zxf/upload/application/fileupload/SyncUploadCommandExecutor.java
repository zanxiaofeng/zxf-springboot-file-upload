package zxf.upload.application.fileupload;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zxf.upload.application.fileupload.UploadFileCommand;
import zxf.upload.application.fileupload.UploadFileCommandChecker;
import zxf.upload.application.filescan.FileScanService;
import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.domain.fileupload.UploadFile;
import zxf.upload.infrastructure.domain.BusinessException;
import zxf.upload.infrastructure.fileupload.StagingService;

/**
 * 同步上传扫描用例（architecture §4.2 时序：check → 转换 → 持久化 → 副作用）。
 * 不加事务注解——事务边界在门面（本项目无数据源，扫描管道非事务性）。
 */
@Component
@RequiredArgsConstructor
public class SyncUploadCommandExecutor {

    private final UploadFileCommandChecker checker;
    private final StagingService stagingService;
    private final FileScanService fileScanService;

    public ScanResult execute(UploadFileCommand command) {
        checker.checkSyncLimit(command);   // 大文件强制走异步端点：同步全管道扫描耗时会超客户端/网关超时
        checker.check(command);

        UploadFile staged = stage(command);
        ScanResult result = fileScanService.scanFile(staged);
        return translate(result);
    }

    /** 转换 + 持久化（staging 落盘）；落盘失败已由 StagingService 表达为业务拒绝 */
    private UploadFile stage(UploadFileCommand command) {
        UploadFile uploadFile = command.toUploadFile();
        return uploadFile.withStagingPath(stagingService.stage(uploadFile.cleanedName(), command.content()));
    }

    /** 结果翻译：REJECTED/INFECTED/ERROR → 业务异常，CLEAN 原样返回 */
    private ScanResult translate(ScanResult result) {
        return switch (result.getStatus()) {
            case CLEAN -> result;
            case REJECTED -> throw BusinessException.rejected(result.getThreat());
            case INFECTED -> throw BusinessException.virusDetected(result.getThreat());
            default -> throw BusinessException.scanFailed(result.getDetails());
        };
    }
}
