package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.poifs.macros.VBAMacroReader;
import org.springframework.stereotype.Component;
import zxf.upload.model.exception.ScanFailedException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 文档威胁检测（浅检测层，位于 ClamAV/YARA 之后）：
 * - OOXML (docx/xlsx/pptx)：检查 vbaProject.bin、ActiveX 前缀（遍历条目名，不依赖可选的目录条目）
 * - OLE2 (doc/xls/ppt)：POI VBAMacroReader 真实提取宏模块，存在可疑 API 才判威胁
 * - PDF：分块 + 重叠窗口扫描 /JavaScript+/OpenAction、/Launch；
 *   对 FlateDecode 压缩对象流无效属已知限制，深度检测由 YARA 规则与 ClamAV 兜底
 *
 * 内存策略：PDF 分块流式读取；OOXML 仅遍历条目名；OLE2 宏模块由 POI
 * VBAMacroReader 提取（POI API 全量返回 Map，属已知限制），检测阶段
 * 逐模块进行、不额外拼接副本。
 */
@Slf4j
@Component
public class DocumentThreatScanner {
    /** 分块窗口 1MB，重叠 64B 防止关键字跨块漏检 */
    private static final int CHUNK = 1 << 20;
    private static final int OVERLAP = 64;

    private static final String[] SUSPICIOUS_MACRO_TOKENS = {
            "autoopen", "autoexec", "document_open", "workbook_open",
            "shell", "wscript.shell", "cmd.exe", "powershell", "urldownloadtofile", "createobject"
    };

    /**
     * 文档威胁检出。kind 供管道按策略分级处置：
     * MACRO（VBA 宏）存在正常业务场景，可按 macro-policy 放行打标；
     * ACTIVE_X / PDF_ACTION 几乎无正常场景，始终拦截。
     */
    public record DocThreat(String description, Kind kind) {
        public enum Kind { MACRO, ACTIVE_X, PDF_ACTION }
    }

    /**
     * @return null = 干净/非文档；非 null = 威胁检出
     */
    public DocThreat scan(Path file) {
        String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (filename.endsWith(".docx") || filename.endsWith(".xlsx") || filename.endsWith(".pptx")) {
                return scanOoxml(file);
            }
            if (filename.endsWith(".doc") || filename.endsWith(".xls") || filename.endsWith(".ppt")) {
                return scanOle2(file);
            }
            if (filename.endsWith(".pdf")) {
                return scanPdf(file);
            }
            return null;
        } catch (ScanFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ScanFailedException("文档威胁检测失败: " + e.getMessage(), e);
        }
    }

    private DocThreat scanOoxml(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            boolean hasVba = false;
            boolean hasActiveX = false;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith("vbaproject.bin")) {
                    hasVba = true;
                }
                if (name.contains("/activex/") || name.startsWith("activex/")) {
                    hasActiveX = true;
                }
            }
            if (hasVba) {
                log.warn("OOXML contains VBA project: {}", file.getFileName());
                return new DocThreat("Office 文档包含 VBA 宏", DocThreat.Kind.MACRO);
            }
            if (hasActiveX) {
                log.warn("OOXML contains ActiveX controls: {}", file.getFileName());
                return new DocThreat("Office 文档包含 ActiveX 控件", DocThreat.Kind.ACTIVE_X);
            }
            return null;
        }
    }

    private DocThreat scanOle2(Path file) throws IOException {
        try (VBAMacroReader reader = new VBAMacroReader(file.toFile())) {
            Map<String, String> macros = reader.readMacros();
            if (macros.isEmpty()) {
                return null;
            }
            // 逐模块小写化后匹配（命中即返回），不拼接全局大字符串：
            // 拼接会在 readMacros 已加载的宏之外再产生约一倍的内存副本
            for (String code : macros.values()) {
                String lowered = code.toLowerCase(Locale.ROOT);
                for (String token : SUSPICIOUS_MACRO_TOKENS) {
                    if (lowered.contains(token)) {
                        log.warn("OLE2 contains suspicious macro token '{}' in {}", token, file.getFileName());
                        return new DocThreat("OLE2 文档包含可疑 VBA 宏（命中: " + token + "）", DocThreat.Kind.MACRO);
                    }
                }
            }
            log.info("OLE2 contains benign macros: {}", file.getFileName());
            return null;
        } catch (IllegalArgumentException e) {
            // 非 OLE2 文件（伪装场景已被 Tika 拦截，此处为双保险）
            return null;
        }
    }

    private DocThreat scanPdf(Path file) throws IOException {
        boolean hasJavaScript = false;
        boolean hasOpenAction = false;
        int headLen = 0;   // chunk 首部保留的上一块重叠字节数（首块为 0，数据从 chunk[0] 起连续排布）
        try (InputStream in = Files.newInputStream(file)) {
            byte[] chunk = new byte[CHUNK];
            int n;
            while ((n = in.read(chunk, headLen, CHUNK - headLen)) != -1) {
                int len = headLen + n;
                String text = new String(chunk, 0, len, StandardCharsets.ISO_8859_1)
                        .toLowerCase(Locale.ROOT);
                if (text.contains("/javascript")) hasJavaScript = true;
                if (text.contains("/openaction")) hasOpenAction = true;
                if (text.contains("/launch")) {
                    log.warn("PDF contains /Launch action: {}", file.getFileName());
                    return new DocThreat("PDF 包含 Launch 动作（可能执行外部程序）", DocThreat.Kind.PDF_ACTION);
                }
                if (hasJavaScript && hasOpenAction) {
                    log.warn("PDF contains JavaScript with OpenAction: {}", file.getFileName());
                    return new DocThreat("PDF 包含自动执行的 JavaScript", DocThreat.Kind.PDF_ACTION);
                }
                // 末尾重叠字节移回首部防止关键字跨块漏检；末块不足 OVERLAP 时按实际长度
                headLen = Math.min(len, OVERLAP);
                System.arraycopy(chunk, len - headLen, chunk, 0, headLen);
            }
        }
        return null;
    }
}
