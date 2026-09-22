package zxf.upload.application.filescan.stage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import zxf.upload.domain.filescan.ScanContext;
import zxf.upload.domain.filescan.ScanStage;
import zxf.upload.domain.filescan.ScanVerdict;
import zxf.upload.domain.filescan.documentthreat.DocumentThreat;
import zxf.upload.domain.filescan.documentthreat.DocumentThreatScanner;
import zxf.upload.infrastructure.config.FileScanProperties;
import zxf.upload.infrastructure.io.FileUtils;

/**
 * 阶段 4：文档威胁检测（复用阶段 1 的 detectedMime 路由扫描分支），
 * 威胁按 {@code ThreatKind} 领域语义分级处置：
 * 宏在 FLAG 策略下放行打标；ActiveX/PDF 危险动作始终拦截。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentThreatScanStage implements ScanStage {

    private final DocumentThreatScanner documentThreatScanner;
    private final FileScanProperties properties;

    @Override
    public ScanVerdict scan(ScanContext ctx) {
        String mime = ctx.getDetectedMime();
        if (!documentThreatScanner.isDocumentFormat(mime)) {
            return new ScanVerdict.Passed();
        }
        DocumentThreat threat = documentThreatScanner.scan(ctx.getStaged().stagingPath(), mime);
        if (threat == null) {
            return new ScanVerdict.Passed();
        }
        // 宏策略分级：FLAG 放行并打标告警（仅 canBeFlagged 的类别）；ActiveX/PDF 危险动作始终拦截
        if (threat.kind().canBeFlagged()
                && properties.getMacroPolicy() == FileScanProperties.MacroPolicy.FLAG) {
            log.warn("含宏文档按 FLAG 策略放行: {} - {}",
                    FileUtils.sanitizeForLog(ctx.getStaged().cleanedName()), threat.description());
            return new ScanVerdict.Flagged("macro-flagged: " + threat.description());
        }
        return new ScanVerdict.Infected(threat.description());
    }
}
