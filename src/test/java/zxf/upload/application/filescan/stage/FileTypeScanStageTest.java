package zxf.upload.application.filescan.stage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zxf.upload.domain.filescan.ScanContext;
import zxf.upload.domain.filescan.ScanVerdict;
import zxf.upload.domain.fileupload.UploadFile;
import zxf.upload.infrastructure.config.FileScanProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FileTypeScanStage 类型校验 + ZIP 炸弹防护")
class FileTypeScanStageTest {

    @TempDir Path tempDir;
    private FileScanProperties properties;
    private FileTypeScanStage stage;

    @BeforeEach
    void setUp() {
        properties = new FileScanProperties();
        stage = new FileTypeScanStage(properties);
    }

    /** 写真实临时文件后构造上下文（Tika 按内容探测） */
    private ScanContext ctxOf(Path file, String name) throws Exception {
        return new ScanContext(UploadFile.unstaged(name, Files.size(file)).withStagingPath(file));
    }

    @Test
    @DisplayName("伪造扩展名：exe 内容改名 .pdf → Rejected")
    void fakeExtension_rejected() throws Exception {
        Path exe = tempDir.resolve("fake.pdf");
        // MZ header (Windows executable)
        Files.write(exe, new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00});

        var verdict = stage.scan(ctxOf(exe, "fake.pdf"));

        assertThat(verdict).isInstanceOf(ScanVerdict.Rejected.class);
    }

    @Test
    @DisplayName("csv 通过类型校验，detectedMime 写入上下文")
    void csv_detectedAndPassed() throws Exception {
        Path csv = tempDir.resolve("data.csv");
        Files.writeString(csv, "name,age\nAlice,30\nBob,25");
        var ctx = ctxOf(csv, "data.csv");

        var verdict = stage.scan(ctx);

        assertThat(verdict).isEqualTo(new ScanVerdict.Passed());
        assertThat(ctx.getDetectedMime()).isIn("text/plain", "text/csv");
    }

    @Test
    @DisplayName("EICAR 串写入 .txt → 类型层通过")
    void eicarText_typeCheckPasses() throws Exception {
        Path txt = tempDir.resolve("eicar.txt");
        Files.writeString(txt, "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*");

        assertThat(stage.scan(ctxOf(txt, "eicar.txt"))).isEqualTo(new ScanVerdict.Passed());
    }

    @Test
    @DisplayName("ZIP 炸弹：高压缩比构造 → Rejected")
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

        var verdict = stage.scan(ctxOf(zipFile, "bomb.zip"));

        assertThat(verdict).isInstanceOfSatisfying(ScanVerdict.Rejected.class,
                r -> assertThat(r.reason()).contains("ZIP 炸弹"));
    }
}
