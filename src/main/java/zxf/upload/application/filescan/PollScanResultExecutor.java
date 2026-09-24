package zxf.upload.application.filescan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zxf.upload.rest.fileupload.representation.UploadResponse;

/**
 * 轮询结果查询执行器（读侧用例）：任何时刻返回扫描当前状态。
 *
 * <p>务实偏离（architecture §8 反模式 #16，注释说明）：直接返回 rest 层的
 * {@link UploadResponse}——它是异步受理回执与轮询 body 的统一响应模型，由
 * AsyncScanProcessor 缓存，强行拆分领域查询模型只会产生纯搬运代码。
 */
@Component
@RequiredArgsConstructor
public class PollScanResultExecutor {

    private final AsyncScanProcessor asyncScanProcessor;

    public UploadResponse execute(String scanId) {
        return asyncScanProcessor.getResult(scanId);
    }
}
