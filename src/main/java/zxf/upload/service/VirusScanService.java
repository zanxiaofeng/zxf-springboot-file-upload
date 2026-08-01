package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.ScanResult;
import zxf.upload.model.ScanStatus;
import zxf.upload.model.exception.ScanFailedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 扫描管道。输入为 staging 文件 Path，拥有该文件的完整生命周期：
 * - CLEAN：移交 FileStorageService 入库，删除 staging 文件；
 * - INFECTED：移入隔离区；
 * - REJECTED/ERROR：删除 staging 文件。
 * 调用方只拿结果，不接触临时文件。
 */
@Slf4j
@Service
public class VirusScanService {
    private final VirusScanProperties properties;
    private final FileTypeValidator fileTypeValidator;
    private final ClamAvScanner clamAvScanner;
    private final YaraScanner yaraScanner;
    private final DocumentThreatScanner documentThreatScanner;
    private final FileStorageService storageService;

    public VirusScanService(VirusScanProperties properties,
                            FileTypeValidator fileTypeValidator,
                            ClamAvScanner clamAvScanner,
                            YaraScanner yaraScanner,
                            DocumentThreatScanner documentThreatScanner,
                            FileStorageService storageService) {
        this.properties = properties;
        this.fileTypeValidator = fileTypeValidator;
        this.clamAvScanner = clamAvScanner;
        this.yaraScanner = yaraScanner;
        this.documentThreatScanner = documentThreatScanner;
        this.storageService = storageService;
    }

    /**
     * 执行完整扫描管道。
     * @return CLEAN 时 details 为正式存储路径；其余状态 staging 文件已被妥善处理
     */
    public ScanResult scanFile(Path stagingFile, String originalFilename) {
        try {
            ScanResult result = doScan(stagingFile, originalFilename);
            switch (result.getStatus()) {
                case CLEAN -> {
                    String stored = storageService.store(stagingFile, originalFilename);
                    deleteQuietly(stagingFile);
                    log.info("Scan passed: {} -> {}", originalFilename, stored);
                    return ScanResult.builder()
                            .status(ScanStatus.CLEAN)
                            .detectedMime(result.getDetectedMime())
                            .details(stored)
                            .build();
                }
                case INFECTED -> {
                    storageService.moveToQuarantine(stagingFile);
                    log.warn("Infected file quarantined: {}", originalFilename);
                    return result;
                }
                default -> {
                    deleteQuietly(stagingFile);
                    return result;
                }
            }
        } catch (ScanFailedException e) {
            // fail 策略统一收口：CLOSED 上抛（转 502）；OPEN 降级放行并打标
            if (properties.getFailStrategy() == VirusScanProperties.FailStrategy.OPEN) {
                log.error("Scan engine failed, fail-open policy applied: {}", e.getMessage(), e);
                try {
                    // 先入库再清理：若先 delete，store 将读不到文件
                    String stored = storageService.store(stagingFile, originalFilename);
                    deleteQuietly(stagingFile);
                    return ScanResult.builder()
                            .status(ScanStatus.CLEAN)
                            .details(stored)
                            .threat("scan-engine-degraded")
                            .build();
                } catch (IOException ioe) {
                    throw new ScanFailedException("fail-open 降级存储失败", ioe);
                }
            }
            deleteQuietly(stagingFile);
            throw e;
        } catch (IOException e) {
            deleteQuietly(stagingFile);
            throw new ScanFailedException("扫描管道 IO 异常: " + e.getMessage(), e);
        }
    }

    private ScanResult doScan(Path stagingFile, String originalFilename) {
        if (!properties.isEnabled()) {
            return ScanResult.clean(stagingFile, null);
        }

        // 阶段 1：类型校验（Tika 探测一次，detectedMime 向后传递）
        FileTypeValidator.TypeCheck typeCheck = fileTypeValidator.validate(stagingFile, originalFilename);
        if (!typeCheck.passed()) {
            return ScanResult.rejected(stagingFile, typeCheck.rejectReason());
        }
        String mime = typeCheck.detectedMime();

        // 阶段 2：ClamAV
        String clamThreat = clamAvScanner.scan(stagingFile);
        if (clamThreat != null) {
            return ScanResult.infected(stagingFile, clamThreat);
        }

        // 阶段 3：YARA
        String yaraThreat = yaraScanner.scan(stagingFile);
        if (yaraThreat != null) {
            return ScanResult.infected(stagingFile, yaraThreat);
        }

        // 阶段 4：文档威胁（复用 mime，不重复探测）
        if (fileTypeValidator.isDocumentFormat(mime)) {
            String docThreat = documentThreatScanner.scan(stagingFile, mime);
            if (docThreat != null) {
                return ScanResult.infected(stagingFile, docThreat);
            }
        }

        return ScanResult.clean(stagingFile, mime);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.error("清理暂存文件失败: {}", path, e);
        }
    }
}