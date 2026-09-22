package zxf.upload.application.filescan;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zxf.upload.domain.filescan.ScanPipeline;
import zxf.upload.application.filescan.stage.ClamAvScanStage;
import zxf.upload.application.filescan.stage.DocumentThreatScanStage;
import zxf.upload.application.filescan.stage.FileTypeScanStage;
import zxf.upload.application.filescan.stage.YaraScanStage;

import java.util.List;

/**
 * 管道装配：阶段顺序即领域规则（类型校验 → ClamAV → YARA → 文档威胁），
 * 显式列出以保持顺序可读；新增阶段 = 新增参数 + 列表加一项。
 */
@Configuration
public class ScanPipelineConfig {

    @Bean
    public ScanPipeline scanPipeline(FileTypeScanStage fileTypeStage,
                                     ClamAvScanStage clamAvStage,
                                     YaraScanStage yaraStage,
                                     DocumentThreatScanStage documentThreatStage) {
        return new ScanPipeline(List.of(fileTypeStage, clamAvStage, yaraStage, documentThreatStage));
    }
}
