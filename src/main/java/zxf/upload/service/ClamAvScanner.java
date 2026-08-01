package zxf.upload.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.Platform;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.exception.ScanFailedException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * ClamAV 封装。第三方 SDK 类型（xyz.capybara.*）不出本类，
 * 调用方只接收 String threat / null，避免类型耦合。
 * 引擎故障抛 ScanFailedException，由管道按 fail-strategy 处理。
 *
 * 熔断保护：capybara 2.1.2 无 socket 超时配置，ClamAV 挂起时扫描线程会
 * 长时间阻塞。熔断器在失败率超阈值后快速失败（不再触碰引擎），
 * 防止故障级联耗尽信号量许可导致全服务不可用。
 */
@Slf4j
@Component
public class ClamAvScanner {
    private final ClamavClient client;
    private final CircuitBreaker circuitBreaker;

    @Autowired   // 多构造器时显式指定 Spring 使用此构造器注入
    public ClamAvScanner(VirusScanProperties properties) {
        this(createClient(properties.getClamav()));
    }

    /** capybara 2.1.2 构造器不支持 socket timeout 配置（ClamAV 挂起时依赖熔断器兜底） */
    private static ClamavClient createClient(VirusScanProperties.ClamAv cfg) {
        return new ClamavClient(cfg.getHost(), cfg.getPort(), Platform.JVM_PLATFORM);
    }

    /** 包私有构造：测试注入 mock client */
    ClamAvScanner(ClamavClient client) {
        this.client = client;
        this.circuitBreaker = CircuitBreaker.of("clamav", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build());
    }

    /**
     * @return null = 干净；非 null = 病毒描述
     * @throws ScanFailedException ClamAV 服务不可达/协议错误/熔断中
     */
    public String scan(Path file) {
        try {
            return circuitBreaker.executeSupplier(() -> doScan(file));
        } catch (CallNotPermittedException e) {
            throw new ScanFailedException("ClamAV 熔断中，扫描暂时不可用", e);
        }
    }

    /** Actuator 健康检查使用（PING/PONG） */
    public void ping() {
        client.ping();
    }

    private String doScan(Path file) {
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
