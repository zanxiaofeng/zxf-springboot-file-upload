package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import xyz.capybara.clamav.ClamavClient;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.exception.ScanFailedException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * ClamAV 封装。第三方 SDK 类型（xyz.capybara.*）不出本类，
 * 调用方只接收 String threat / null，避免类型耦合。
 * 引擎故障抛 ScanFailedException，由管道按 fail-strategy 处理。
 */
@Slf4j
@Component
public class ClamAvScanner {
    private final ClamavClient client;

    public ClamAvScanner(VirusScanProperties properties) {
        VirusScanProperties.ClamAv cfg = properties.getClamav();
        this.client = new ClamavClient(cfg.getHost(), cfg.getPort(), cfg.getTimeout());
    }

    /**
     * @return null = 干净；非 null = 病毒描述
     * @throws ScanFailedException ClamAV 服务不可达/协议错误
     */
    public String scan(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            xyz.capybara.clamav.commands.scan.result.ScanResult result = client.scan(in);
            if (result instanceof xyz.capybara.clamav.commands.scan.result.ScanResult.VirusFound vf) {
                String threats = vf.getFoundViruses().entrySet().stream()
                        .map(e -> e.getKey() + ": " + String.join(", ", e.getValue()))
                        .collect(Collectors.joining("; "));
                log.warn("ClamAV detected virus in {}: {}", file.getFileName(), threats);
                return "ClamAV: " + threats;
            }
            return null;
        } catch (IOException | RuntimeException e) {
            throw new ScanFailedException("ClamAV 扫描失败: " + e.getMessage(), e);
        }
    }
}