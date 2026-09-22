package zxf.upload.domain.filescan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.domain.filescan.model.ScanStatus;
import zxf.upload.domain.fileupload.UploadFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScanPipeline：顺序执行 + 短路 + 开关直通")
class ScanPipelineTest {

    @Mock ScanStage stage1;
    @Mock ScanStage stage2;
    @Mock ScanStage stage3;

    private ScanPipeline pipeline(ScanStage... stages) {
        return new ScanPipeline(List.of(stages));
    }

    private UploadFile staged() {
        return UploadFile.unstaged("a.txt", 5);
    }

    @Test
    @DisplayName("全部 Passed → CLEAN，detectedMime 来自上下文")
    void allPassed_returnsCleanWithMime() {
        var pipeline = pipeline(stage1, stage2);
        when(stage1.scan(any())).thenAnswer(inv -> {
            ((ScanContext) inv.getArgument(0)).setDetectedMime("text/plain");
            return new ScanVerdict.Passed();
        });
        when(stage2.scan(any())).thenReturn(new ScanVerdict.Passed());

        var result = pipeline.execute(staged(), true);

        assertThat(result.getStatus()).isEqualTo(ScanStatus.CLEAN);
        assertThat(result.getDetectedMime()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("阶段 1 Rejected → 短路，后续阶段不执行")
    void rejected_shortCircuits() {
        var pipeline = pipeline(stage1, stage2, stage3);
        when(stage1.scan(any())).thenReturn(new ScanVerdict.Rejected("不支持的文件类型"));

        var result = pipeline.execute(staged(), true);

        assertThat(result.getStatus()).isEqualTo(ScanStatus.REJECTED);
        assertThat(result.getThreat()).isEqualTo("不支持的文件类型");
        verify(stage2, never()).scan(any());
        verify(stage3, never()).scan(any());
    }

    @Test
    @DisplayName("阶段 2 Infected → 短路，阶段 3 不执行")
    void infected_shortCircuits() {
        var pipeline = pipeline(stage1, stage2, stage3);
        when(stage1.scan(any())).thenReturn(new ScanVerdict.Passed());
        when(stage2.scan(any())).thenReturn(new ScanVerdict.Infected("ClamAV: Eicar"));

        var result = pipeline.execute(staged(), true);

        assertThat(result.getStatus()).isEqualTo(ScanStatus.INFECTED);
        assertThat(result.getThreat()).isEqualTo("ClamAV: Eicar");
        verify(stage3, never()).scan(any());
    }

    @Test
    @DisplayName("Flagged → CLEAN 且携带打标（macro-flagged 透传）")
    void flagged_returnsCleanWithThreat() {
        var pipeline = pipeline(stage1);
        when(stage1.scan(any())).thenReturn(new ScanVerdict.Flagged("macro-flagged: Office 文档包含 VBA 宏"));

        var result = pipeline.execute(staged(), true);

        assertThat(result.getStatus()).isEqualTo(ScanStatus.CLEAN);
        assertThat(result.getThreat()).isEqualTo("macro-flagged: Office 文档包含 VBA 宏");
    }

    @Test
    @DisplayName("扫描总开关关闭 → 直通 CLEAN，阶段不执行")
    void disabled_bypassesStages() {
        var pipeline = pipeline(stage1, stage2);

        var result = pipeline.execute(staged(), false);

        assertThat(result.getStatus()).isEqualTo(ScanStatus.CLEAN);
        verifyNoInteractions(stage1, stage2);
    }

    private void verifyNoInteractions(ScanStage... stages) {
        for (var s : stages) {
            verify(s, never()).scan(any());
        }
    }
}
