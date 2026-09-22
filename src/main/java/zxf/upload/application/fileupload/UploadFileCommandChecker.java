package zxf.upload.application.fileupload;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zxf.upload.application.fileupload.UploadFileCommand;
import zxf.upload.domain.fileupload.UploadPolicy;
import zxf.upload.infrastructure.domain.BusinessException;

/**
 * 上传命令校验器（architecture §4.2：Checker 只读校验、只抛异常、绝不写数据）。
 * 规则委托领域策略 {@link UploadPolicy}（唯一来源），本类只做异常表达。
 */
@Component
@RequiredArgsConstructor
public class UploadFileCommandChecker {

    private final UploadPolicy uploadPolicy;

    /** 通用预检：空文件 / 大小上限 / 扩展名白名单 */
    public void check(UploadFileCommand command) {
        String reason = uploadPolicy.rejectionReason(command.toUploadFile());
        if (reason != null) {
            throw BusinessException.rejected(reason);
        }
    }

    /** 同步路由专属：超出同步阈值应改走异步端点 */
    public void checkSyncLimit(UploadFileCommand command) {
        String overflow = uploadPolicy.syncOverflowReason(command.toUploadFile());
        if (overflow != null) {
            throw BusinessException.rejected(overflow);
        }
    }
}
