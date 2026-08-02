package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.exception.FileRejectedException;
import zxf.upload.support.io.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 上传暂存：在请求线程内把 MultipartFile 同步落盘到 staging 目录。
 * 之后的同步/异步扫描只传递 Path，与 multipart 请求级临时文件生命周期解耦。
 */
@Slf4j
@Service
public class StagingService {
    private final Path stagingDir;
    private final VirusScanProperties properties;

    public StagingService(VirusScanProperties properties) {
        this.properties = properties;
        this.stagingDir = Paths.get(properties.getStorage().getStagingPath());
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建暂存目录: " + stagingDir, e);
        }
    }

    /**
     * 预检（未落盘，低成本）+ 落盘。
     *
     * @param file 上传文件，必须非空
     * @return 暂存文件路径
     * @throws FileRejectedException 文件为空、过大或扩展名不支持
     */
    public Path stage(MultipartFile file) {
        Assert.notNull(file, "file must not be null");
        if (file.isEmpty()) {
            throw new FileRejectedException("上传文件不能为空");
        }
        // 大小预检：落盘前拦截，防止超大文件打爆磁盘（容器级限制见 application.yml）
        if (file.getSize() > properties.getMaxFileSize()) {
            throw new FileRejectedException("文件过大，最大允许 "
                    + properties.getMaxFileSize() / 1024 / 1024 + "MB");
        }
        String original = file.getOriginalFilename();
        String ext = FileUtils.extension(original);
        // 扩展名预检（快路径，内容防伪由 Tika 负责）
        if (!ext.isEmpty() && !properties.getAllowedExtensions().contains(ext)) {
            throw new FileRejectedException("不支持的文件扩展名: " + ext);
        }

        Path stagingFile = stagingDir.resolve(UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext));
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, stagingFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 落盘失败（磁盘满/IO 错误）时清理残留的不完整文件
            FileUtils.deleteQuietly(stagingFile);
            throw new FileRejectedException("文件暂存失败");
        }
        log.debug("File staged: {} -> {}", original, stagingFile);
        return stagingFile;
    }
}
