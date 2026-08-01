package zxf.upload.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Data
@Validated
@ConfigurationProperties(prefix = "zxf.virus-scan")
public class VirusScanProperties {

    /** 总开关。false 时管道直接入库（仍执行大小/扩展名预检）。 */
    private boolean enabled = true;

    /** 扫描引擎故障策略：CLOSED=拒绝上传（默认，安全优先）；OPEN=放行并告警 */
    private FailStrategy failStrategy = FailStrategy.CLOSED;

    @Min(1024)
    private long maxFileSize = 100 * 1024 * 1024; // 100MB

    /** 并发扫描许可数（虚拟线程 + 信号量背压） */
    @Min(1)
    private int maxConcurrentScans = 16;

    @Valid
    private ZipGuard zip = new ZipGuard();

    private List<String> allowedExtensions = List.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "jpg", "jpeg", "png", "gif", "bmp",
            "txt", "csv", "zip");

    private List<String> allowedMimeTypes = List.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "image/jpeg", "image/png", "image/gif", "image/bmp",
            "text/plain", "text/csv",
            "application/zip");

    @Valid
    private ClamAv clamav = new ClamAv();
    @Valid
    private Yara yara = new Yara();
    @Valid
    private Storage storage = new Storage();

    public enum FailStrategy { OPEN, CLOSED }

    @Data
    public static class ZipGuard {
        /** 单文件压缩比上限 */
        private long maxCompressionRatio = 100L;
        /** 累计解压字节上限（默认 1GB） */
        private long maxTotalUncompressed = 1L << 30;
        /** 嵌套压缩包最大深度 */
        private int maxNestingDepth = 2;
    }

    @Data
    public static class ClamAv {
        @NotBlank
        private String host = "localhost";
        private int port = 3310;
    }

    @Data
    public static class Yara {
        private boolean enabled = true;
        private String binaryPath = "yara";
        private String rulesPath = "classpath:rules/malware.yar";
        /** 单文件扫描超时（秒） */
        private long timeoutSeconds = 60;
    }

    @Data
    public static class Storage {
        private String basePath = "./data/upload-storage";
        private String quarantinePath = "./data/upload-quarantine";
        /** 上传暂存区（扫描前落盘位置） */
        private String stagingPath = "./data/upload-staging";
    }
}