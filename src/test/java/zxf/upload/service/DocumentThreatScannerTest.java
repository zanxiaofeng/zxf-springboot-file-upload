package zxf.upload.service;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zxf.upload.service.DocumentThreatScanner.DocThreat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DocumentThreatScanner 文档威胁检测")
class DocumentThreatScannerTest {

    private static final String OOXML_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLS_MIME = "application/vnd.ms-excel";
    private static final String PDF_MIME = "application/pdf";

    @TempDir Path tempDir;
    private DocumentThreatScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new DocumentThreatScanner();
    }

    @Test
    @DisplayName("含 vbaProject.bin 的 docx → MACRO 威胁")
    void docxWithVba_detectedAsMacro() throws Exception {
        Path docx = createOoxmlWithEntry("word/vbaProject.bin", "fake vba".getBytes());
        DocThreat threat = scanner.scan(docx, OOXML_MIME);
        assertThat(threat).isNotNull();
        assertThat(threat.kind()).isEqualTo(DocThreat.Kind.MACRO);
        assertThat(threat.description()).contains("VBA 宏");
    }

    @Test
    @DisplayName("含 ActiveX 的 docx → ACTIVE_X 威胁")
    void docxWithActiveX_detected() throws Exception {
        Path docx = createOoxmlWithEntry("activeX/activeX1.xml", "<xml/>".getBytes());
        DocThreat threat = scanner.scan(docx, OOXML_MIME);
        assertThat(threat).isNotNull();
        assertThat(threat.kind()).isEqualTo(DocThreat.Kind.ACTIVE_X);
    }

    @Test
    @DisplayName("VBA + ActiveX 并存 → ACTIVE_X 优先拦截（不受宏 FLAG 策略放行影响）")
    void docxWithVbaAndActiveX_activeXWins() throws Exception {
        Path docx = tempDir.resolve("both.docx");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(docx))) {
            zos.putNextEntry(new ZipEntry("word/vbaProject.bin"));
            zos.write("fake vba".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("activeX/activeX1.xml"));
            zos.write("<xml/>".getBytes());
            zos.closeEntry();
        }
        DocThreat threat = scanner.scan(docx, OOXML_MIME);
        assertThat(threat).isNotNull();
        assertThat(threat.kind()).isEqualTo(DocThreat.Kind.ACTIVE_X);
    }

    @Test
    @DisplayName("干净的 docx → null")
    void cleanDocx_returnsNull() throws Exception {
        Path docx = createOoxmlWithEntry("word/document.xml", "<xml/>".getBytes());
        DocThreat threat = scanner.scan(docx, OOXML_MIME);
        assertThat(threat).isNull();
    }

    @Test
    @DisplayName("非文档 MIME（text/plain）→ null（按 mime 路由，不进文档分支）")
    void nonDocumentMime_returnsNull() throws Exception {
        Path txt = tempDir.resolve("note.txt");
        Files.writeString(txt, "just text");
        assertThat(scanner.scan(txt, "text/plain")).isNull();
    }

    @Test
    @DisplayName("无宏的良性 xls → null（POI 空宏路径不误判）")
    void benignXlsWithoutMacro_returnsNull() throws Exception {
        Path xls = tempDir.resolve("benign.xls");
        try (var wb = new HSSFWorkbook();
             var fos = Files.newOutputStream(xls)) {
            wb.createSheet("Sheet1");
            wb.write(fos);
        }
        // VBAMacroReader 对无宏的 HSSFWorkbook 返回空 map → null（验证良性路径不误判）
        DocThreat threat = scanner.scan(xls, XLS_MIME);
        assertThat(threat).isNull();
    }

    @Test
    @DisplayName("含 /JavaScript + /OpenAction 的 PDF → PDF_ACTION 威胁")
    void pdfWithJavaScript_detected() throws Exception {
        Path pdf = tempDir.resolve("malicious.pdf");
        // 构造最小 PDF 字符串，包含 /JavaScript + /OpenAction
        String content = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R "
                + "/OpenAction 3 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n"
                + "3 0 obj\n<< /Type /Action /S /JavaScript "
                + "/JS (app.alert('test')) >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R >>\n%%EOF";
        Files.writeString(pdf, content);

        DocThreat threat = scanner.scan(pdf, PDF_MIME);
        assertThat(threat).isNotNull();
        assertThat(threat.kind()).isEqualTo(DocThreat.Kind.PDF_ACTION);
    }

    @Test
    @DisplayName("含 /Launch 的 PDF → PDF_ACTION 威胁")
    void pdfWithLaunch_detected() throws Exception {
        Path pdf = tempDir.resolve("launch.pdf");
        String content = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R "
                + "/AA << /D << /S /Launch /F (/bin/sh) >> >> >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R >>\n%%EOF";
        Files.writeString(pdf, content);

        DocThreat threat = scanner.scan(pdf, PDF_MIME);
        assertThat(threat).isNotNull();
        assertThat(threat.kind()).isEqualTo(DocThreat.Kind.PDF_ACTION);
        assertThat(threat.description()).contains("Launch");
    }

    @Test
    @DisplayName("干净 PDF → null")
    void cleanPdf_returnsNull() throws Exception {
        Path pdf = tempDir.resolve("clean.pdf");
        String content = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R >>\n%%EOF";
        Files.writeString(pdf, content);

        DocThreat threat = scanner.scan(pdf, PDF_MIME);
        assertThat(threat).isNull();
    }

    private Path createOoxmlWithEntry(String entryName, byte[] content) throws Exception {
        Path zip = tempDir.resolve(entryName.contains("/") ? "test.docx" : "test.docx");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content);
            zos.closeEntry();
        }
        return zip;
    }
}
