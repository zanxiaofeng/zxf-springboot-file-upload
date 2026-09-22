package zxf.upload.domain.filescan;

import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.domain.filescan.model.ScanStatus;
import zxf.upload.domain.fileupload.UploadFile;

import java.util.List;

/**
 * 扫描管道：按业务顺序执行过滤器链，首个非 Passed 结论短路；
 * 全部通过 → CLEAN（携带阶段 1 探测的 detectedMime）。
 */
public class ScanPipeline {

    private final List<ScanStage> stages;

    public ScanPipeline(List<ScanStage> stages) {
        this.stages = List.copyOf(stages);
    }

    /**
     * @param scanEnabled 总开关（false 时管道直通入库，仍由上游执行大小/扩展名预检）
     */
    public ScanResult execute(UploadFile staged, boolean scanEnabled) {
        if (!scanEnabled) {
            return ScanResult.clean(staged.stagingPath(), null);
        }
        var ctx = new ScanContext(staged);
        for (ScanStage stage : stages) {
            ScanVerdict verdict = stage.scan(ctx);
            if (verdict instanceof ScanVerdict.Rejected rejected) {
                return ScanResult.rejected(staged.stagingPath(), rejected.reason());
            }
            if (verdict instanceof ScanVerdict.Infected infected) {
                return ScanResult.infected(staged.stagingPath(), infected.threat());
            }
            if (verdict instanceof ScanVerdict.Flagged flagged) {
                return flaggedResult(ctx, flagged.threat());
            }
        }
        return ScanResult.clean(staged.stagingPath(), ctx.getDetectedMime());
    }

    private ScanResult flaggedResult(ScanContext ctx, String threat) {
        return ScanResult.builder()
                .status(ScanStatus.CLEAN)
                .stagingPath(ctx.getStaged().stagingPath())
                .detectedMime(ctx.getDetectedMime())
                .threat(threat)
                .build();
    }
}
