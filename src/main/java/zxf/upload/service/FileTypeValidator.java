package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.exception.ScanFailedException;
import zxf.upload.support.io.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * 文件类型校验：Tika 探测一次，detectedMime 随返回值传递到后续管道阶段。
 */
@Slf4j
@Component
public class FileTypeValidator {
    private static final Map<String, String> MIME_TO_PRIMARY_EXT = Map.ofEntries(
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
            Map.entry("application/zip", "zip"));

    private final VirusScanProperties properties;
    private final Tika tika = new Tika();

    public FileTypeValidator(VirusScanProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验结果。detectedMime 供管道后续阶段复用。
     *
     * @param passed      是否通过
     * @param detectedMime 探测到的 MIME 类型
     * @param rejectReason 拒绝原因（通过时为 null）
     */
    public record TypeCheck(boolean passed, String detectedMime, String rejectReason) {
    }

    /**
     * 校验文件类型与扩展名一致性，并对 ZIP 进行炸弹检测。
     *
     * @param file            待校验文件，必须非空
     * @param originalFilename 原始文件名，用于扩展名一致性校验
     * @return 校验结果
     */
    public TypeCheck validate(Path file, String originalFilename) {
        Assert.notNull(file, "file must not be null");
        final String detectedMime;
        try {
            detectedMime = tika.detect(file);
        } catch (IOException e) {
            throw new ScanFailedException("文件类型探测失败: " + e.getMessage(), e);
        }

        String extension = FileUtils.extension(originalFilename);
        log.debug("Type check: filename={}, ext={}, detected={}", originalFilename, extension, detectedMime);

        if (properties.getAllowedMimeTypes().stream().noneMatch(detectedMime::equalsIgnoreCase)) {
            return new TypeCheck(false, detectedMime, "不支持的文件类型: " + detectedMime);
        }
        if (!isExtensionConsistent(detectedMime, extension)) {
            return new TypeCheck(false, detectedMime, "文件类型与扩展名不符，检测到: " + detectedMime);
        }
        if ("application/zip".equals(detectedMime)) {
            String zipProblem = inspectZip(file, 1);
            if (zipProblem != null) {
                return new TypeCheck(false, detectedMime, zipProblem);
            }
        }
        return new TypeCheck(true, detectedMime, null);
    }

    /**
     * 判断 MIME 类型是否属于办公文档或 PDF。
     *
     * @param mimeType MIME 类型
     * @return true 表示是文档格式
     */
    public boolean isDocumentFormat(String mimeType) {
        return mimeType != null && (mimeType.contains("officedocument")
                || mimeType.contains("msword") || mimeType.contains("ms-excel")
                || mimeType.contains("ms-powerpoint") || mimeType.equals("application/pdf"));
    }

    private boolean isExtensionConsistent(String mimeType, String extension) {
        // jpg/jpeg 互认
        if ("image/jpeg".equals(mimeType)) {
            return "jpg".equals(extension) || "jpeg".equals(extension);
        }
        // CSV 常被 Tika 探测为 text/plain，双向互认
        if ("text/plain".equals(mimeType)) {
            return "txt".equals(extension) || "csv".equals(extension);
        }
        String expected = MIME_TO_PRIMARY_EXT.get(mimeType);
        if (expected != null) {
            return expected.equals(extension);
        }
        return properties.getAllowedExtensions().contains(extension);
    }

    /**
     * 流式 ZIP 检查（OWASP 解压炸弹防护五维度）：
     * - 条目总数上限（海量空 entry 炸弹，遍历本身即 DoS 向量）；
     * - 单 entry 解压大小上限；
     * - getSize() 返回 -1 时按实际读取字节计数（边读边校验，超限即中断）；
     * - 累计解压总量绝对上限；
     * - 压缩比上限 + 嵌套压缩包深度限制（递归炸弹）。
     *
     * @return null = 通过；非 null = 拒绝原因
     */
    private String inspectZip(Path file, int depth) {
        VirusScanProperties.ZipGuard guard = properties.getZip();
        if (depth > guard.getMaxNestingDepth()) {
            return "疑似嵌套 ZIP 炸弹，压缩包嵌套深度超过 " + guard.getMaxNestingDepth();
        }
        long compressedSize;
        try {
            compressedSize = Files.size(file);
        } catch (IOException e) {
            throw new ScanFailedException("读取文件大小失败", e);
        }

        long totalUncompressed = 0;
        long entryCount = 0;
        try (InputStream in = Files.newInputStream(file);
             ZipArchiveInputStream archive = new ZipArchiveInputStream(in)) {
            ZipArchiveEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = archive.getNextZipEntry()) != null) {
                if (++entryCount > guard.getMaxEntries()) {
                    return "疑似 ZIP 炸弹，条目数超过 " + guard.getMaxEntries();
                }
                long entrySize = entry.getSize();
                if (entrySize >= 0) {
                    if (entrySize > guard.getMaxEntryUncompressed()) {
                        return singleEntryBombMessage(guard);
                    }
                    totalUncompressed += entrySize;
                } else {
                    // 未知大小：实际读取计数，单 entry 与累计超限均即时中断
                    EntryCount count = readEntryBytes(archive, buffer, totalUncompressed, guard);
                    if (count.bombReason() != null) {
                        return count.bombReason();
                    }
                    totalUncompressed += count.bytes();
                }
                if (totalUncompressed > guard.getMaxTotalUncompressed()) {
                    return totalSizeBombMessage(guard);
                }
                if (compressedSize > 0 && totalUncompressed > compressedSize * guard.getMaxCompressionRatio()) {
                    return "疑似 ZIP 炸弹，压缩比超过 " + guard.getMaxCompressionRatio() + ":1";
                }
                if (!entry.isDirectory()
                        && entry.getName().toLowerCase(Locale.ROOT).endsWith(".zip")
                        && depth + 1 > guard.getMaxNestingDepth()) {
                    return "疑似嵌套 ZIP 炸弹，压缩包嵌套深度超过 " + guard.getMaxNestingDepth();
                }
            }
        } catch (IOException e) {
            // 损坏的 zip 交由 ClamAV/YARA 判定；此处异常按引擎故障上抛
            throw new ScanFailedException("ZIP 检查失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 读取单个未知大小 entry 的字节数，同时做炸弹校验。
     *
     * @param archive           ZIP 输入流
     * @param buffer            读取缓冲区
     * @param totalUncompressed 已统计的解压字节数
     * @param guard             ZIP 防护配置
     * @return entry 字节数与可能的炸弹原因
     */
    private EntryCount readEntryBytes(ZipArchiveInputStream archive, byte[] buffer,
                                      long totalUncompressed, VirusScanProperties.ZipGuard guard) throws IOException {
        long entryBytes = 0;
        int n;
        while ((n = archive.read(buffer)) != -1) {
            entryBytes += n;
            if (entryBytes > guard.getMaxEntryUncompressed()) {
                return new EntryCount(entryBytes, singleEntryBombMessage(guard));
            }
            if (totalUncompressed + entryBytes > guard.getMaxTotalUncompressed()) {
                return new EntryCount(entryBytes, totalSizeBombMessage(guard));
            }
        }
        return new EntryCount(entryBytes, null);
    }

    private String singleEntryBombMessage(VirusScanProperties.ZipGuard guard) {
        return "疑似 ZIP 炸弹，单文件解压大小超过 " + guard.getMaxEntryUncompressed() / 1024 / 1024 + "MB";
    }

    private String totalSizeBombMessage(VirusScanProperties.ZipGuard guard) {
        return "疑似 ZIP 炸弹，累计解压大小超过 " + guard.getMaxTotalUncompressed() / 1024 / 1024 + "MB";
    }

    private record EntryCount(long bytes, String bombReason) {
    }
}
