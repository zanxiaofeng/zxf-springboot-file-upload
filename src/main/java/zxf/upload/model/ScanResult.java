package zxf.upload.model;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;

/**
 * 扫描结果，不可变。
 * stagingPath：管道内暂存文件路径，所有状态均携带，供管道统一清理/隔离。
 * detectedMime：Tika 探测结果，随管道传递避免重复探测。
 * CLEAN 状态下 details 携带正式存储文件名（不含路径，防内部路径外泄）。
 */
@Value
@Builder
public class ScanResult {
    ScanStatus status;
    String threat;
    String details;
    Path stagingPath;
    String detectedMime;

    public boolean isClean() {
        return status == ScanStatus.CLEAN;
    }

    public static ScanResult clean(Path stagingPath, String detectedMime) {
        return ScanResult.builder()
                .status(ScanStatus.CLEAN)
                .stagingPath(stagingPath)
                .detectedMime(detectedMime)
                .build();
    }

    public static ScanResult infected(Path stagingPath, String threat) {
        return ScanResult.builder()
                .status(ScanStatus.INFECTED)
                .stagingPath(stagingPath)
                .threat(threat)
                .build();
    }

    public static ScanResult rejected(Path stagingPath, String reason) {
        return ScanResult.builder()
                .status(ScanStatus.REJECTED)
                .stagingPath(stagingPath)
                .threat(reason)
                .build();
    }

    public static ScanResult error(Path stagingPath, String message) {
        return ScanResult.builder()
                .status(ScanStatus.ERROR)
                .stagingPath(stagingPath)
                .details(message)
                .build();
    }
}
