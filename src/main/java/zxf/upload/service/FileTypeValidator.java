package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.exception.ScanFailedException;
import zxf.upload.support.io.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文件类型校验：Tika 探测一次，detectedMime 随返回值传递到后续管道阶段。
 */
@Slf4j
@Component
public class FileTypeValidator {

    private final VirusScanProperties properties;
    private final Tika tika = new Tika();
    /** 小写化快照：探测结果小写后 O(1) 匹配，避免每请求线性扫描 */
    private final Set<String> allowedMimeTypes;

    public FileTypeValidator(VirusScanProperties properties) {
        this.properties = properties;
        this.allowedMimeTypes = properties.getAllowedMimeTypes().stream()
                .map(mime -> mime.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 校验结果。detectedMime 供管道后续阶段复用。 */
    public record TypeCheck(boolean passed, String detectedMime, String rejectReason) {}

    public TypeCheck validate(Path file, String originalFilename) {
        final String detectedMime;
        try {
            detectedMime = tika.detect(file);
        } catch (IOException e) {
            throw new ScanFailedException("文件类型探测失败: " + e.getMessage(), e);
        }

        String extension = FileUtils.extension(originalFilename);
        log.debug("Type check: filename={}, ext={}, detected={}", originalFilename, extension, detectedMime);

        if (!allowedMimeTypes.contains(detectedMime.toLowerCase(Locale.ROOT))) {
            return new TypeCheck(false, detectedMime, "不支持的文件类型: " + detectedMime);
        }
        if (!isExtensionConsistent(detectedMime, extension)) {
            return new TypeCheck(false, detectedMime, "文件类型与扩展名不符，检测到: " + detectedMime);
        }
        if ("application/zip".equals(detectedMime)) {
            String zipProblem = inspectZip(file);
            if (zipProblem != null) {
                return new TypeCheck(false, detectedMime, zipProblem);
            }
        }
        return new TypeCheck(true, detectedMime, null);
    }

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
        String expected = VirusScanProperties.MIME_TO_PRIMARY_EXT.get(mimeType);
        if (expected != null) {
            return expected.equals(extension);
        }
        return properties.getAllowedExtensions().contains(extension);
    }

    /**
     * 流式 ZIP 检查（OWASP 解压炸弹防护）：
     * - 条目总数上限（海量空 entry 炸弹，遍历本身即 DoS 向量）；
     * - 单 entry 实际解压大小上限；
     * - 累计实际解压总量绝对上限；
     * - 实际解压总量 / 压缩文件大小的压缩比上限。
     *
     * header 声明的大小完全由攻击者可控，一律不信任：所有 entry 均实际解压
     * 读取并逐字节计数，超限即时中断（声明的 size 与实际不符会导致流错位、
     * 后续 header 解析失败，同样走 fail-closed 拒绝）。
     *
     * 不递归解压：内层 .zip 条目不展开检查，嵌套内容的威胁检测由
     * ClamAV/YARA 对原始字节扫描兜底。
     *
     * @return null = 通过；非 null = 拒绝原因
     */
    private String inspectZip(Path file) {
        VirusScanProperties.ZipGuard guard = properties.getZip();
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
                // 实际解压计数：单 entry 与累计超限均即时中断
                long entryBytes = 0;
                int n;
                while ((n = archive.read(buffer)) != -1) {
                    entryBytes += n;
                    if (entryBytes > guard.getMaxEntryUncompressed()) {
                        return singleEntryBombMessage(guard);
                    }
                    totalUncompressed += n;
                    if (totalUncompressed > guard.getMaxTotalUncompressed()) {
                        return "疑似 ZIP 炸弹，累计解压大小超过 " + (guard.getMaxTotalUncompressed() >> 20) + "MB";
                    }
                }
                if (compressedSize > 0 && totalUncompressed > compressedSize * guard.getMaxCompressionRatio()) {
                    return "疑似 ZIP 炸弹，压缩比超过 " + guard.getMaxCompressionRatio() + ":1";
                }
            }
        } catch (IOException e) {
            // 损坏的 zip 或声明值与实际不符（流错位）按引擎故障上抛，fail-closed 拒绝
            throw new ScanFailedException("ZIP 检查失败: " + e.getMessage(), e);
        }
        return null;
    }

    private String singleEntryBombMessage(VirusScanProperties.ZipGuard guard) {
        return "疑似 ZIP 炸弹，单文件解压大小超过 " + (guard.getMaxEntryUncompressed() >> 20) + "MB";
    }
}
