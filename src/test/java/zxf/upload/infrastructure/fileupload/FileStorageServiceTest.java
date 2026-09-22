package zxf.upload.infrastructure.fileupload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zxf.upload.infrastructure.config.FileScanProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FileStorageService 入库 + 隔离")
class FileStorageServiceTest {

    @TempDir Path tempDir;
    private FileStorageService service;
    private Path storageDir;
    private Path quarantineDir;

    @BeforeEach
    void setUp() {
        storageDir = tempDir.resolve("storage");
        quarantineDir = tempDir.resolve("quarantine");
        var properties = new FileScanProperties();
        properties.getStorage().setBasePath(storageDir.toString());
        properties.getStorage().setQuarantinePath(quarantineDir.toString());
        service = new FileStorageService(properties);
    }

    @Test
    @DisplayName("store 只返回存储文件名，不泄漏绝对路径")
    void store_returnsFileNameOnly() throws Exception {
        Path source = tempDir.resolve("src.txt");
        Files.writeString(source, "content");

        String stored = service.store(source, "report.pdf");

        assertThat(stored).endsWith(".pdf").doesNotContain("/").doesNotContain("\\");
        assertThat(storageDir).isDirectoryContaining(p -> p.getFileName().toString().equals(stored));
    }

    @Test
    @DisplayName("moveToQuarantine → 文件移入隔离区并重命名，源文件删除")
    void moveToQuarantine_movesFile() throws Exception {
        Path file = tempDir.resolve("eicar.txt");
        Files.writeString(file, "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*");

        service.moveToQuarantine(file);

        assertThat(file).doesNotExist();
        assertThat(quarantineDir).isDirectoryContaining(p -> p.getFileName().toString().endsWith(".quarantined"));
    }
}
