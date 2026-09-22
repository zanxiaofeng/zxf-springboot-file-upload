package zxf.upload.application.filescan.stage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zxf.upload.domain.filescan.ScanContext;
import zxf.upload.domain.filescan.ScanStage;
import zxf.upload.domain.filescan.ScanVerdict;
import zxf.upload.infrastructure.filescan.ClamAvScanner;

/**
 * 阶段 2：ClamAV INSTREAM 病毒扫描（超时快速失败 + 熔断由引擎客户端承担）。
 */
@Component
@RequiredArgsConstructor
public class ClamAvScanStage implements ScanStage {

    private final ClamAvScanner clamAvScanner;

    @Override
    public ScanVerdict scan(ScanContext ctx) {
        String threat = clamAvScanner.scan(ctx.getStaged().stagingPath());
        return threat != null ? new ScanVerdict.Infected(threat) : new ScanVerdict.Passed();
    }
}
