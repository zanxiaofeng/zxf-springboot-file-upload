package zxf.upload.infrastructure.fileupload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zxf.upload.infrastructure.config.FileScanProperties;
import zxf.upload.infrastructure.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Slf4j
@Service
public class FileStorageService {
    private final Path storagePath;
    private final Path quarantinePath;

    public FileStorageService(FileScanProperties properties) {
        this.storagePath = Paths.get(properties.getStorage().getBasePath());
        this.quarantinePath = Paths.get(properties.getStorage().getQuarantinePath());
        try {
            Files.createDirectories(storagePath);
            Files.createDirectories(quarantinePath);
            // 隔离区权限收敛（POSIX 系统生效）
            if (quarantinePath.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(quarantinePath,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException | UnsupportedOperationException e) {
            throw new IllegalStateException("存储目录初始化失败", e);
        }
    }

    /**
     * 入库并返回存储文件名（UUID.ext）。完整物理路径只进日志，
     * 不随 ScanResult/UploadResponse 返回给客户端（防内部路径泄漏）。
     */
    public String store(Path sourceFile, String originalFilename) throws IOException {
        String ext = FileUtils.extension(originalFilename);
        Path target = storagePath.resolve(UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext));
        Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("File stored: {} -> {}", FileUtils.sanitizeForLog(originalFilename), target);
        return target.getFileName().toString();
    }

    public void moveToQuarantine(Path file) {
        // 隔离文件重命名为 UUID，防止原名冲突与路径信息泄漏
        Path target = quarantinePath.resolve(UUID.randomUUID() + ".quarantined");
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // move 失败（如 staging 与隔离区跨文件系统）降级 copy+delete，
            // 确保威胁文件不滞留 staging；仍失败则只能记录（文件留在 staging）
            try {
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(file);
            } catch (IOException fallbackError) {
                log.error("移入隔离区失败，文件滞留 staging: {}", file, fallbackError);
                return;
            }
        }
        log.info("File quarantined: {} -> {}", file.getFileName(), target.getFileName());
    }
}
