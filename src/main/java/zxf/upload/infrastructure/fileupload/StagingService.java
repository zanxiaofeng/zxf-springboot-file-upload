package zxf.upload.infrastructure.fileupload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zxf.upload.infrastructure.config.FileScanProperties;
import zxf.upload.infrastructure.domain.BusinessException;
import zxf.upload.infrastructure.io.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 上传暂存（纯 IO）：把上传内容同步落盘到 staging 目录。
 * 预检规则（空文件/大小/扩展名）属领域策略 {@code domain/upload/UploadPolicy}，
 * 本类只做落盘与失败清理。之后的同步/异步扫描只传递 Path，
 * 与 multipart 请求级临时文件生命周期解耦。
 */
@Slf4j
@Service
public class StagingService {
    private final Path stagingDir;

    public StagingService(FileScanProperties properties) {
        this.stagingDir = Paths.get(properties.getStorage().getStagingPath());
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建暂存目录: " + stagingDir, e);
        }
    }

    /**
     * 落盘到 staging（UUID 命名，保留原始扩展名）。
     *
     * @return 暂存文件路径
     */
    public Path stage(String originalFilename, InputStream content) {
        String ext = FileUtils.extension(originalFilename);
        Path stagingFile = stagingDir.resolve(UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext));
        try {
            Files.copy(content, stagingFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 落盘失败（磁盘满/IO 错误）时清理残留的不完整文件；
            // 异常细节（含内部路径）只进日志，对外通用文案
            FileUtils.deleteQuietly(stagingFile);
            log.error("文件暂存失败: {}", stagingFile, e);
            throw BusinessException.rejected("文件暂存失败，请稍后重试");
        }
        log.debug("File staged: {} -> {}", FileUtils.sanitizeForLog(originalFilename), stagingFile);
        return stagingFile;
    }
}
