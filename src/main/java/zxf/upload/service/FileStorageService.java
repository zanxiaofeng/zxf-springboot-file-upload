package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zxf.upload.config.VirusScanProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
public class FileStorageService {
    private final Path storagePath;
    private final Path quarantinePath;

    public FileStorageService(VirusScanProperties properties) {
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

    public String store(Path sourceFile, String originalFilename) throws IOException {
        String ext = extractExtension(originalFilename);
        Path target = storagePath.resolve(UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext));
        Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("File stored: {} -> {}", originalFilename, target);
        return target.toAbsolutePath().toString();
    }

    public void moveToQuarantine(Path file) {
        try {
            // 隔离文件重命名为 UUID，防止原名冲突与路径信息泄漏
            Path target = quarantinePath.resolve(UUID.randomUUID() + ".quarantined");
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("File quarantined: {} -> {}", file.getFileName(), target.getFileName());
        } catch (IOException e) {
            log.error("移入隔离区失败: {}", file, e);
        }
    }

    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}