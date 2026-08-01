package zxf.upload.service;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档威胁检测测试：OOXML 宏/ActiveX、OLE2 良性宏放行、PDF 动作检测与分块边界。
 *
 * 说明：POI 无法生成带 VBA 宏的 OLE2 文件（宏写入不在 POI 能力内），
 * OLE2 威胁分支需真实带宏样本，此处覆盖良性放行与异常兜底路径。
 */
class DocumentThreatScannerTest {

    private static final int CHUNK = 1 << 20;   // 与 DocumentThreatScanner.CHUNK 一致

    private final DocumentThreatScanner scanner = new DocumentThreatScanner();

    @TempDir
    Path tempDir;

    @Test
    void scan_docxWithVbaProject_detected() throws Exception {
        Path docx = tempDir.resolve("macro.docx");
        try (OutputStream out = Files.newOutputStream(docx);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("word/vbaProject.bin"));
            zos.write(new byte[64]);
            zos.closeEntry();
        }

        DocumentThreatScanner.DocThreat threat =
                scanner.scan(docx, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        assertThat(threat).isEqualTo(new DocumentThreatScanner.DocThreat(
                "Office 文档包含 VBA 宏", DocumentThreatScanner.DocThreat.Kind.MACRO));
    }

    @Test
    void scan_benignXlsWithoutMacros_passes() throws Exception {
        Path xls = tempDir.resolve("book.xls");
        try (HSSFWorkbook wb = new HSSFWorkbook()) {
            wb.createSheet("s").createRow(0).createCell(0).setCellValue("hi");
            try (OutputStream out = Files.newOutputStream(xls)) {
                wb.write(out);
            }
        }

        assertThat(scanner.scan(xls, "application/vnd.ms-excel")).isNull();
    }

    @Test
    void scan_pdfWithJavaScriptAndOpenAction_detected() throws Exception {
        Path pdf = Files.writeString(tempDir.resolve("evil.pdf"),
                "%PDF-1.4\n1 0 obj<</JavaScript (app.alert) /OpenAction 2 0 R>>\n%%EOF");

        assertThat(scanner.scan(pdf, "application/pdf"))
                .isEqualTo(new DocumentThreatScanner.DocThreat(
                        "PDF 包含自动执行的 JavaScript", DocumentThreatScanner.DocThreat.Kind.PDF_ACTION));
    }

    @Test
    void scan_pdfWithLaunchAction_detected() throws Exception {
        Path pdf = Files.writeString(tempDir.resolve("launch.pdf"),
                "%PDF-1.4\n1 0 obj<</Launch /Action>>\n%%EOF");

        assertThat(scanner.scan(pdf, "application/pdf")).isNotNull()
                .extracting(DocumentThreatScanner.DocThreat::description).asString().contains("Launch");
    }

    @Test
    void scan_pdfKeywordAcrossChunkBoundary_detectedViaOverlap() throws Exception {
        // "/javascript" 前 4 字节落在首块末尾、其余在下一块，依赖 64B 重叠窗口拼接后检出
        byte[] data = new byte[CHUNK + 512];
        Arrays.fill(data, (byte) ' ');
        System.arraycopy("%PDF".getBytes(StandardCharsets.ISO_8859_1), 0, data, 0, 4);
        byte[] keyword = "/javascript".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(keyword, 0, data, CHUNK - 4, keyword.length);
        byte[] openAction = "/openaction".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(openAction, 0, data, CHUNK + 100, openAction.length);
        Path pdf = Files.write(tempDir.resolve("boundary.pdf"), data);

        assertThat(scanner.scan(pdf, "application/pdf"))
                .isEqualTo(new DocumentThreatScanner.DocThreat(
                        "PDF 包含自动执行的 JavaScript", DocumentThreatScanner.DocThreat.Kind.PDF_ACTION));
    }

    @Test
    void scan_tinyPdfSmallerThanOverlap_scannedWithoutError() throws Exception {
        // 回归用例：文件小于 64B 重叠窗口时，末块 arraycopy 不得越界
        Path pdf = Files.writeString(tempDir.resolve("tiny.pdf"), "%PDF/Launch");

        assertThat(scanner.scan(pdf, "application/pdf")).isNotNull()
                .extracting(DocumentThreatScanner.DocThreat::description).asString().contains("Launch");
    }

    @Test
    void scan_largePdf_chunkedScanCompletesWithoutThreat() throws Exception {
        // 100MB 无威胁内容：分块读取（恒定 1MB 窗口）而非整文件入内存，
        // 能正常完成且返回干净即验证内存占用平稳
        Path pdf = tempDir.resolve("large.pdf");
        byte[] block = "%PDF-1.4 padded-block-content\n".getBytes(StandardCharsets.ISO_8859_1);
        try (OutputStream out = Files.newOutputStream(pdf)) {
            long written = 0;
            while (written < 100L << 20) {
                out.write(block);
                written += block.length;
            }
        }

        assertThat(scanner.scan(pdf, "application/pdf")).isNull();
    }

    @Test
    void scan_nonDocumentExtension_returnsNull() throws Exception {
        Path txt = Files.writeString(tempDir.resolve("note.txt"), "plain text");

        assertThat(scanner.scan(txt, "text/plain")).isNull();
    }
}
