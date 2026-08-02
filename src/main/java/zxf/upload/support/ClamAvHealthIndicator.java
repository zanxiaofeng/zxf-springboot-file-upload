package zxf.upload.support;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import zxf.upload.service.ClamAvScanner;

/**
 * ClamAV 健康检查（PING/PONG），暴露于 /actuator/health。
 * ClamAV 不可达时整体标记 DOWN，供 K8s readiness/负载均衡摘流。
 */
@Component("clamav")
public class ClamAvHealthIndicator implements HealthIndicator {
    private final ClamAvScanner clamAvScanner;

    public ClamAvHealthIndicator(ClamAvScanner clamAvScanner) {
        this.clamAvScanner = clamAvScanner;
    }

    /**
     * 检查 ClamAV 是否可达。
     *
     * @return UP 表示 PING 成功；DOWN 包含异常原因
     */
    @Override
    public Health health() {
        try {
            clamAvScanner.ping();
            return Health.up().build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
