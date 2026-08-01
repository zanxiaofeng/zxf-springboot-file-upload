package zxf.upload.service;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zxf.upload.config.VirusScanProperties;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件类型校验测试：伪造扩展名、文本互认、ZIP 炸弹与流式未知大小 entry。
 */
class FileTypeValidatorTest {

    private FileTypeValidator validator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        validator = new FileTypeValidator(new VirusScanProperties());
    }

    @Test
    void validate_exeContentRenamedToPdf_rejected() throws Exception {
        // MZ 头（Windows 可执行文件 magic），Tika 探测为 application/x-msdownload
        byte[] mz = new byte[256];
        mz[0] = 'M';
        mz[1] = 'Z';
        Path file = Files.write(tempDir.resolve("evil.pdf"), mz);

        FileTypeValidator.TypeCheck result = validator.validate(file, "evil.pdf");

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectReason()).contains("不支持的文件类型");
    }

    @Test
    void validate_csvDetectedAsTextPlain_passes() throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.csv"), "name,age\nAlice,30\nBob,25\n");

        FileTypeValidator.TypeCheck result = validator.validate(file, "data.csv");

        assertThat(result.passed()).isTrue();
        assertThat(result.rejectReason()).isNull();
    }

    @Test
    void validate_eicarStringInTxt_passesTypeLayer() throws Exception {
        // EICAR 是无害 ASCII 串，类型层应放行（病毒判定留给 ClamAV 层）
        String eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
        Path file = Files.writeString(tempDir.resolve("eicar.txt"), eicar);

        FileTypeValidator.TypeCheck result = validator.validate(file, "eicar.txt");

        assertThat(result.passed()).isTrue();
    }

    @Test
    void validate_highCompressionRatioZip_rejectedAsZipBomb() throws Exception {
        // 3MB 全零 entry，deflate 后仅数 KB，压缩比远超 100:1
        Path zip = tempDir.resolve("bomb.zip");
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("big.bin"));
            zos.write(new byte[3 * 1024 * 1024]);
            zos.closeEntry();
        }

        FileTypeValidator.TypeCheck result = validator.validate(zip, "bomb.zip");

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectReason()).contains("ZIP 炸弹");
    }

    @Test
    void validate_streamingZipWithUnknownEntrySize_countsActualBytesAndPasses() throws Exception {
        // commons-compress 写出的 entry 不带预声明大小，读取端 getSize() = -1，
        // 校验应按实际读取字节计数而非误判
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 50 * 1024) {
            sb.append(UUID.randomUUID());   // 高熵内容，压缩比接近 1:1
        }
        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);

        Path zip = tempDir.resolve("stream.zip");
        try (OutputStream out = Files.newOutputStream(zip);
             ZipArchiveOutputStream zaos = new ZipArchiveOutputStream(out)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("data.txt");   // 不 setSize
            zaos.putArchiveEntry(entry);
            zaos.write(payload);
            zaos.closeArchiveEntry();
        }

        FileTypeValidator.TypeCheck result = validator.validate(zip, "stream.zip");

        assertThat(result.passed()).isTrue();
    }

    @Test
    void validate_tooManyEntries_rejectedAsZipBomb() throws Exception {
        // 海量空 entry 炸弹：字节量正常，但遍历条目本身即 DoS 向量
        VirusScanProperties properties = new VirusScanProperties();
        properties.getZip().setMaxEntries(100);
        FileTypeValidator strictValidator = new FileTypeValidator(properties);

        Path zip = tempDir.resolve("many-entries.zip");
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            for (int i = 0; i < 101; i++) {
                zos.putNextEntry(new ZipEntry("e" + i + ".txt"));
                zos.closeEntry();
            }
        }

        FileTypeValidator.TypeCheck result = strictValidator.validate(zip, "many-entries.zip");

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectReason()).contains("条目数超过");
    }

    @Test
    void validate_singleEntryExceedsLimit_rejectedAsZipBomb() throws Exception {
        // 单 entry 解压超限（总量未超限时也应拒绝）
        VirusScanProperties properties = new VirusScanProperties();
        properties.getZip().setMaxEntryUncompressed(1 << 20);   // 1MB
        properties.getZip().setMaxCompressionRatio(100_000L);   // 放开压缩比，隔离变量
        FileTypeValidator strictValidator = new FileTypeValidator(properties);

        StringBuilder sb = new StringBuilder();
        while (sb.length() < 2 * 1024 * 1024) {
            sb.append(UUID.randomUUID());
        }
        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);

        Path zip = tempDir.resolve("big-entry.zip");
        try (OutputStream out = Files.newOutputStream(zip);
             ZipArchiveOutputStream zaos = new ZipArchiveOutputStream(out)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("big.txt");   // 未知大小 → 实际读取分支
            zaos.putArchiveEntry(entry);
            zaos.write(payload);
            zaos.closeArchiveEntry();
        }

        FileTypeValidator.TypeCheck result = strictValidator.validate(zip, "big-entry.zip");

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectReason()).contains("单文件解压大小超过");
    }
}
