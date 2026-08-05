package zxf.upload.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.exception.ScanFailedException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FileTypeValidator 类型校验 + ZIP 炸弹防护")
class FileTypeValidatorTest {

    @TempDir Path tempDir;
    private VirusScanProperties properties;
    private FileTypeValidator validator;

    @BeforeEach
    void setUp() {
        properties = new VirusScanProperties();
        validator = new FileTypeValidator(properties);
    }

    @Test
    @DisplayName("伪造扩展名：exe 内容改名 .pdf → REJECTED")
    void fakeExtension_rejected() throws Exception {
        Path exe = tempDir.resolve("fake.pdf");
        // MZ header (Windows executable)
        Files.write(exe, new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00});
        var result = validator.validate(exe, "fake.pdf");
        assertThat(result.passed()).isFalse();
    }

    @Test
    @DisplayName("csv 被 Tika 探测为 text/csv → 通过")
    void csv_detectedAsText_plain() throws Exception {
        Path csv = tempDir.resolve("data.csv");
        Files.writeString(csv, "name,age\nAlice,30\nBob,25");
        var result = validator.validate(csv, "data.csv");
        assertThat(result.passed()).isTrue();
        assertThat(result.detectedMime()).isIn("text/plain", "text/csv");
    }

    @Test
    @DisplayName("EICAR 串写入 .txt → 类型层通过")
    void eicarText_typeCheckPasses() throws Exception {
        Path txt = tempDir.resolve("eicar.txt");
        Files.writeString(txt, "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*");
        var result = validator.validate(txt, "eicar.txt");
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("ZIP 炸弹：高压缩比构造 → REJECTED")
    void zipBomb_highRatio_rejected() throws Exception {
        properties.getZip().setMaxCompressionRatio(100);
        properties.getZip().setMaxEntries(10_000);

        Path zipFile = tempDir.resolve("bomb.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("huge.txt"));
            // 写入大量重复数据（高压缩比）
            byte[] chunk = new byte[8192];
            java.util.Arrays.fill(chunk, (byte) 'A');
            for (int i = 0; i < 200; i++) {   // 200 * 8192 = ~1.6MB uncompressed, tiny compressed
                zos.write(chunk);
            }
            zos.closeEntry();
        }

        var result = validator.validate(zipFile, "bomb.zip");
        assertThat(result.passed()).isFalse();
        assertThat(result.rejectReason()).contains("ZIP 炸弹");
    }

    @Test
    @DisplayName("海量空 entry：条目数超限 → REJECTED")
    void zipBomb_tooManyEntries_rejected() throws Exception {
        properties.getZip().setMaxEntries(5);

        Path zipFile = tempDir.resolve("entries.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (int i = 0; i < 10; i++) {
                zos.putNextEntry(new ZipEntry("entry" + i + ".txt"));
                zos.write("x".getBytes());
                zos.closeEntry();
            }
        }

        var result = validator.validate(zipFile, "entries.zip");
        assertThat(result.passed()).isFalse();
        assertThat(result.rejectReason()).contains("条目数超过");
    }

    @Test
    @DisplayName("正常 ZIP → 通过")
    void normalZip_passes() throws Exception {
        Path zipFile = tempDir.resolve("normal.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("readme.txt"));
            zos.write("hello".getBytes());
            zos.closeEntry();
        }

        var result = validator.validate(zipFile, "normal.zip");
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("isDocumentFormat 对文档类型返回 true")
    void isDocumentFormat() {
        assertThat(validator.isDocumentFormat("application/pdf")).isTrue();
        assertThat(validator.isDocumentFormat(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isTrue();
        assertThat(validator.isDocumentFormat("application/msword")).isTrue();
        assertThat(validator.isDocumentFormat("text/plain")).isFalse();
        assertThat(validator.isDocumentFormat(null)).isFalse();
    }
}
