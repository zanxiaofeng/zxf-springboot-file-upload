package zxf.upload.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Data
@Validated
@ConfigurationProperties(prefix = "zxf.virus-scan")
public class VirusScanProperties {

    /**
     * MIME → 主扩展名映射：允许类型的唯一数据源，
     * allowedExtensions / allowedMimeTypes 默认值及 FileTypeValidator 的一致性校验均由此派生。
     */
    public static final Map<String, String> MIME_TO_PRIMARY_EXT = Map.ofEntries(
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/vnd.ms-powerpoint", "ppt"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/bmp", "bmp"),
            Map.entry("text/plain", "txt"),
            Map.entry("text/csv", "csv"),
            Map.entry("application/zip", "zip"));

    /** 总开关。false 时管道直接入库（仍执行大小/扩展名预检）。 */
    private boolean enabled = true;

    /** 扫描引擎故障策略：CLOSED=拒绝上传（默认，安全优先）；OPEN=放行并告警 */
    private FailStrategy failStrategy = FailStrategy.CLOSED;

    /**
     * 含 VBA 宏文档处置策略：BLOCK=判威胁隔离（默认，安全优先）；FLAG=放行并打标告警。
     * 仅适用于宏（正常业务表格存在良性宏）；ActiveX、PDF 危险动作不受此策略影响，始终拦截。
     */
    private MacroPolicy macroPolicy = MacroPolicy.BLOCK;

    /** 同步上传大小阈值（字节）：超过则要求走异步端点（POST /api/files/async/upload）。默认 20MB；0=不限制 */
    @Min(0)
    private long syncMaxFileSize = 20L << 20;

    /** SSE 心跳间隔（秒）：防止中间代理 idle 断连（nginx 默认 60s） */
    @Min(1)
    private long sseHeartbeatSeconds = 15;

    @Min(1024)
    private long maxFileSize = 100 * 1024 * 1024; // 100MB

    /** 并发扫描许可数（虚拟线程 + 信号量背压，同步/异步统一闸门） */
    @Min(1)
    private int maxConcurrentScans = 16;

    /** 异步扫描结果缓存保留时长（分钟），到期自动淘汰防内存泄漏 */
    @Min(1)
    private long resultRetentionMinutes = 30;

    @Valid
    private ZipGuard zip = new ZipGuard();

    /** 默认值由 MIME_TO_PRIMARY_EXT 派生：主扩展名 + jpg/jpeg 互认别名 */
    private List<String> allowedExtensions = deriveDefaultExtensions();

    private List<String> allowedMimeTypes = List.copyOf(MIME_TO_PRIMARY_EXT.keySet());

    private static List<String> deriveDefaultExtensions() {
        Set<String> exts = new TreeSet<>(MIME_TO_PRIMARY_EXT.values());
        exts.add("jpeg");   // jpg/jpeg 互认别名（isExtensionConsistent 特判）
        return List.copyOf(exts);
    }

    @Valid
    private ClamAv clamav = new ClamAv();
    @Valid
    private Yara yara = new Yara();
    @Valid
    private Storage storage = new Storage();

    public enum FailStrategy { OPEN, CLOSED }

    public enum MacroPolicy { BLOCK, FLAG }

    @Data
    public static class ZipGuard {
        /** 单文件压缩比上限 */
        private long maxCompressionRatio = 100L;
        /** 累计解压字节上限（默认 1GB） */
        private long maxTotalUncompressed = 1L << 30;
        /** 条目总数上限（防海量空 entry 炸弹：遍历本身即 DoS 向量） */
        private long maxEntries = 10_000L;
        /** 单 entry 解压后大小上限（默认 100MB，与 maxFileSize 对齐） */
        private long maxEntryUncompressed = 100L << 20;
    }

    @Data
    public static class ClamAv {
        @NotBlank
        private String host = "localhost";
        private int port = 3310;
        /** 单次扫描/socket 等待超时（秒）：capybara 2.1.2 无 socket 超时，超时计为熔断失败 */
        @Min(1)
        private long timeoutSeconds = 60;
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
