package zxf.upload.application.filescan.stage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import zxf.upload.domain.filescan.ScanContext;
import zxf.upload.domain.filescan.ScanVerdict;
import zxf.upload.domain.filescan.documentthreat.DocumentThreat;
import zxf.upload.domain.filescan.documentthreat.DocumentThreatScanner;
import zxf.upload.domain.filescan.documentthreat.ThreatKind;
import zxf.upload.domain.fileupload.UploadFile;
import zxf.upload.infrastructure.config.FileScanProperties;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentThreatScanStage：宏策略分级处置")
class DocumentThreatScanStageTest {

    @Mock DocumentThreatScanner scanner;
    private FileScanProperties properties;
    private DocumentThreatScanStage stage;
    private ScanContext ctx;

    @BeforeEach
    void setUp() {
        properties = new FileScanProperties();
        stage = new DocumentThreatScanStage(scanner, properties);
        // mock 默认按"是文档格式"路由（非文档用例内显式覆盖）
        when(scanner.isDocumentFormat(anyString())).thenReturn(true);
        ctx = new ScanContext(
                UploadFile.unstaged("file.docx", 10).withStagingPath(Path.of("/tmp/staged.docx")));
        ctx.setDetectedMime("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    @DisplayName("非文档格式 → Passed（不调文档检测）")
    void nonDocument_passes() {
        ctx.setDetectedMime("text/plain");
        when(scanner.isDocumentFormat("text/plain")).thenReturn(false);

        var verdict = stage.scan(ctx);

        assertThat(verdict).isEqualTo(new ScanVerdict.Passed());
        verify(scanner, never()).scan(any(), anyString());
    }

    @Test
    @DisplayName("无威胁 → Passed")
    void noThreat_passes() {
        when(scanner.scan(any(), anyString())).thenReturn(null);

        assertThat(stage.scan(ctx)).isEqualTo(new ScanVerdict.Passed());
    }

    @Test
    @DisplayName("宏 + 默认 BLOCK → Infected")
    void macro_blockPolicy_infected() {
        when(scanner.scan(any(), anyString()))
                .thenReturn(new DocumentThreat("Office 文档包含 VBA 宏", ThreatKind.MACRO));

        var verdict = stage.scan(ctx);

        assertThat(verdict).isEqualTo(new ScanVerdict.Infected("Office 文档包含 VBA 宏"));
    }

    @Test
    @DisplayName("宏 + FLAG → Flagged 放行打标")
    void macro_flagPolicy_flagged() {
        properties.setMacroPolicy(FileScanProperties.MacroPolicy.FLAG);
        when(scanner.scan(any(), anyString()))
                .thenReturn(new DocumentThreat("Office 文档包含 VBA 宏", ThreatKind.MACRO));

        var verdict = stage.scan(ctx);

        assertThat(verdict).isEqualTo(new ScanVerdict.Flagged("macro-flagged: Office 文档包含 VBA 宏"));
    }

    @Test
    @DisplayName("ActiveX + FLAG → 仍 Infected（canBeFlagged 为 false 的类别不受宽限）")
    void activeX_flagPolicy_stillInfected() {
        properties.setMacroPolicy(FileScanProperties.MacroPolicy.FLAG);
        when(scanner.scan(any(), anyString()))
                .thenReturn(new DocumentThreat("Office 文档包含 ActiveX 控件", ThreatKind.ACTIVE_X));

        var verdict = stage.scan(ctx);

        assertThat(verdict).isEqualTo(new ScanVerdict.Infected("Office 文档包含 ActiveX 控件"));
    }
}
