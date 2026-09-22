package zxf.upload.infrastructure.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import zxf.upload.infrastructure.filescan.ClamAvScanner;

/**
 * ClamAV PING 健康检查（/actuator/health）。
 * PING 成功 → UP；PING 异常 → DOWN。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClamAvHealthIndicator implements HealthIndicator {

    private final ClamAvScanner clamAvScanner;

    @Override
    public Health health() {
        try {
            clamAvScanner.ping();
            return Health.up().withDetail("engine", "ClamAV").build();
        } catch (Exception e) {
            log.warn("ClamAV health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("engine", "ClamAV")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
