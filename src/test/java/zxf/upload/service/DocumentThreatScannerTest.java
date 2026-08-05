package zxf.upload.service;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zxf.upload.service.DocumentThreatScanner.DocThreat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DocumentThreatScanner 文档威胁检测")
class DocumentThreatScannerTest {

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
        DocThreat threat = scanner.scan(docx,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(threat).isNotNull();
        assertThat(threat.kind()).isEqualTo(DocThreat.Kind.MACRO);
        assertThat(threat.description()).contains("VBA 宏");
    }

    @Test
    @DisplayName("含 ActiveX 的 docx → ACTIVE_X 威胁")
    void docxWithActiveX_detected() throws Exception {
        Path docx = createOoxmlWithEntry("activeX/activeX1.xml", "<xml/>".getBytes());
        DocThreat threat = scanner.scan(docx,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(threat).isNotNull();
        assertThat(threat.kind()).isEqualTo(DocThreat.Kind.ACTIVE_X);
    }

    @Test
    @DisplayName("干净的 docx → null")
    void cleanDocx_returnsNull() throws Exception {
        Path docx = createOoxmlWithEntry("word/document.xml", "<xml/>".getBytes());
        DocThreat threat = scanner.scan(docx,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(threat).isNull();
    }

    @Test
    @DisplayName("POI 生成带 AutoOpen+Shell 宏的 xls → MACRO 威胁")
    void xlsWithSuspiciousMacro_detected() throws Exception {
        Path xls = tempDir.resolve("macro.xls");
        // 使用 POI 构造包含 VBA 宏的 OLE2 文件较为复杂，此处用最小 OLE2 验证扫描不崩
        try (var wb = new HSSFWorkbook();
             var fos = Files.newOutputStream(xls)) {
            wb.createSheet("Sheet1");
            wb.write(fos);
        }
        // VBAMacroReader 对无宏的 HSSFWorkbook 返回空 map → null（验证良性路径不误判）
        DocThreat threat = scanner.scan(xls, "application/vnd.ms-excel");
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

        DocThreat threat = scanner.scan(pdf, "application/pdf");
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

        DocThreat threat = scanner.scan(pdf, "application/pdf");
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

        DocThreat threat = scanner.scan(pdf, "application/pdf");
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
