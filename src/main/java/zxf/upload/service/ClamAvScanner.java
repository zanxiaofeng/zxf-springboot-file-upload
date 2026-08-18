package zxf.upload.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * ClamAV 封装。第三方 SDK 类型（xyz.capybara.*）不出本类，
 * 调用方只接收 String threat / null，避免类型耦合。
 * 引擎故障抛 ScanFailedException，由管道按 fail-strategy 处理。
 *
 * 熔断 + 超时保护：capybara 2.1.2 无 socket 超时配置，ClamAV 挂起时调用线程会
 * 永久阻塞在 socket read 上——且挂起的调用既不成功也不失败，熔断器只统计已结束
 * 的调用，永远不会因此打开。故实际扫描提交到虚拟线程限时等待（见 scanWithTimeout）：
 * 超时即抛 ScanFailedException，既及时释放调用线程（不耗尽信号量许可），
 * 又计入熔断失败率，持续超时后熔断打开快速失败，防止故障级联导致全服务不可用。
 */
@Slf4j
@Component
public class ClamAvScanner {
    private final ClamavClient client;
    private final CircuitBreaker circuitBreaker;
    private final long timeoutSeconds;
    /** 执行 socket IO 的线程池（每任务一个虚拟线程，被放弃的挂起任务阻塞成本极低） */
    private final ExecutorService scanExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ClamAvScanner(VirusScanProperties properties) {
        this(createClient(properties.getClamav()), properties.getClamav().getTimeoutSeconds());
    }

    /** capybara 2.1.2 构造器不支持 socket timeout 配置（挂起防护见类注释） */
    private static ClamavClient createClient(VirusScanProperties.ClamAv cfg) {
        return new ClamavClient(cfg.getHost(), cfg.getPort(), Platform.JVM_PLATFORM);
    }

    /** 包私有构造：测试注入 mock client */
    ClamAvScanner(ClamavClient client, long timeoutSeconds) {
        this.client = client;
        this.timeoutSeconds = timeoutSeconds;
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
     * @throws ScanFailedException ClamAV 服务不可达/协议错误/超时/熔断中
     */
    public String scan(Path file) {
        try {
            return circuitBreaker.executeSupplier(() -> scanWithTimeout(file));
        } catch (CallNotPermittedException e) {
            throw new ScanFailedException("ClamAV 熔断中，扫描暂时不可用", e);
        }
    }

    /** Actuator 健康检查使用（PING/PONG），同样限时防挂起 */
    public void ping() {
        Future<?> ping = scanExecutor.submit(client::ping);
        try {
            ping.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            ping.cancel(true);
            throw new IllegalStateException("ClamAV ping 超时", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("ClamAV ping 失败: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ClamAV ping 被中断", e);
        }
    }

    /**
     * 限时执行：超时抛 ScanFailedException（计入熔断失败率）。被放弃的虚拟线程
     * 阻塞在 socket read 上不可中断，等待 TCP 超时/服务端关闭连接时自然回收。
     */
    private String scanWithTimeout(Path file) {
        Future<String> task = scanExecutor.submit(() -> doScan(file));
        try {
            return task.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new ScanFailedException("ClamAV 扫描超时(" + timeoutSeconds + "s): " + file.getFileName(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ScanFailedException failure) {
                throw failure;
            }
            throw new ScanFailedException("ClamAV 扫描失败: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScanFailedException("ClamAV 扫描被中断", e);
        }
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
