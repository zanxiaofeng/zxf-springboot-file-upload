package zxf.upload.model;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;

/**
 * 扫描结果，不可变。
 * stagingPath：管道内暂存文件路径，所有状态均携带，供管道统一清理/隔离。
 * detectedMime：Tika 探测结果，随管道传递避免重复探测。
 * CLEAN 状态下 details 携带正式存储路径。
 */
@Value
@Builder
public class ScanResult {
    ScanStatus status;
    String threat;
    String details;
    Path stagingPath;
    String detectedMime;

    /**
     * @return 是否为 CLEAN 状态
     */
    public boolean isClean() {
        return status == ScanStatus.CLEAN;
    }

    /**
     * 构造 CLEAN 结果。
     *
     * @param stagingPath  暂存文件路径
     * @param detectedMime 探测到的 MIME 类型
     * @return CLEAN 结果
     */
    public static ScanResult clean(Path stagingPath, String detectedMime) {
        return ScanResult.builder()
                .status(ScanStatus.CLEAN)
                .stagingPath(stagingPath)
                .detectedMime(detectedMime)
                .build();
    }

    /**
     * 构造 INFECTED 结果。
     *
     * @param stagingPath 暂存文件路径
     * @param threat      威胁描述
     * @return INFECTED 结果
     */
    public static ScanResult infected(Path stagingPath, String threat) {
        return ScanResult.builder()
                .status(ScanStatus.INFECTED)
                .stagingPath(stagingPath)
                .threat(threat)
                .build();
    }

    /**
     * 构造 REJECTED 结果。
     *
     * @param stagingPath 暂存文件路径
     * @param reason      拒绝原因
     * @return REJECTED 结果
     */
    public static ScanResult rejected(Path stagingPath, String reason) {
        return ScanResult.builder()
                .status(ScanStatus.REJECTED)
                .stagingPath(stagingPath)
                .threat(reason)
                .build();
    }

    /**
     * 构造 ERROR 结果。
     *
     * @param stagingPath 暂存文件路径
     * @param message     错误消息
     * @return ERROR 结果
     */
    public static ScanResult error(Path stagingPath, String message) {
        return ScanResult.builder()
                .status(ScanStatus.ERROR)
                .stagingPath(stagingPath)
                .details(message)
                .build();
    }
}
