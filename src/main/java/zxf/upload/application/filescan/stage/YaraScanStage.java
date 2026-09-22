package zxf.upload.application.filescan.stage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zxf.upload.domain.filescan.ScanContext;
import zxf.upload.domain.filescan.ScanStage;
import zxf.upload.domain.filescan.ScanVerdict;
import zxf.upload.infrastructure.filescan.YaraScanner;

/**
 * 阶段 3：YARA 自定义恶意软件规则匹配（进程超时防护由引擎客户端承担）。
 */
@Component
@RequiredArgsConstructor
public class YaraScanStage implements ScanStage {

    private final YaraScanner yaraScanner;

    @Override
    public ScanVerdict scan(ScanContext ctx) {
        String threat = yaraScanner.scan(ctx.getStaged().stagingPath());
        return threat != null ? new ScanVerdict.Infected(threat) : new ScanVerdict.Passed();
    }
}
