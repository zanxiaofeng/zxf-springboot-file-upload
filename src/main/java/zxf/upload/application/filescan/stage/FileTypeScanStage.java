package zxf.upload.application.filescan.stage;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import zxf.upload.domain.filescan.ScanContext;
import zxf.upload.domain.filescan.ScanStage;
import zxf.upload.domain.filescan.ScanVerdict;
import zxf.upload.infrastructure.config.FileScanProperties;
import zxf.upload.infrastructure.domain.BusinessException;
import zxf.upload.infrastructure.io.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 阶段 1：类型校验（Tika 探测一次，detectedMime 写入上下文供阶段 4 复用）
 * + ZIP 炸弹流式防护。
 */
@Slf4j
@Component
public class FileTypeScanStage implements ScanStage {

    private final Tika tika = new Tika();
    private final Set<String> allowedMimeTypes;
    private final FileScanProperties properties;

    public FileTypeScanStage(FileScanProperties properties) {
        this.properties = properties;
        this.allowedMimeTypes = properties.getAllowedMimeTypes().stream()
                .map(mime -> mime.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public ScanVerdict scan(ScanContext ctx) {
        Path file = ctx.getStaged().stagingPath();
        String originalFilename = ctx.getStaged().cleanedName();
        final String detectedMime;
        try {
            detectedMime = tika.detect(file);
        } catch (IOException e) {
            throw BusinessException.scanFailed("文件类型探测失败: " + e.getMessage(), e);
        }

        String extension = ctx.getStaged().extension();
        log.debug("Type check: filename={}, ext={}, detected={}",
                FileUtils.sanitizeForLog(originalFilename), extension, detectedMime);

        if (!allowedMimeTypes.contains(detectedMime.toLowerCase(Locale.ROOT))) {
            return new ScanVerdict.Rejected("不支持的文件类型: " + detectedMime);
        }
        if (!isExtensionConsistent(detectedMime, extension)) {
            return new ScanVerdict.Rejected("文件类型与扩展名不符，检测到: " + detectedMime);
        }
        if ("application/zip".equals(detectedMime)) {
            String zipProblem = inspectZip(file);
            if (zipProblem != null) {
                return new ScanVerdict.Rejected(zipProblem);
            }
        }
        ctx.setDetectedMime(detectedMime);
        return new ScanVerdict.Passed();
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
        String expected = FileScanProperties.MIME_TO_PRIMARY_EXT.get(mimeType);
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
        FileScanProperties.ZipGuard guard = properties.getZip();
        long compressedSize;
        try {
            compressedSize = Files.size(file);
        } catch (IOException e) {
            throw BusinessException.scanFailed("读取文件大小失败", e);
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
            throw BusinessException.scanFailed("ZIP 检查失败: " + e.getMessage(), e);
        }
        return null;
    }

    private String singleEntryBombMessage(FileScanProperties.ZipGuard guard) {
        return "疑似 ZIP 炸弹，单文件解压大小超过 " + (guard.getMaxEntryUncompressed() >> 20) + "MB";
    }
}
