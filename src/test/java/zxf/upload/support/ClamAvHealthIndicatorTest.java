package zxf.upload.support;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import zxf.upload.service.ClamAvScanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * ClamAV 健康检查测试。
 */
class ClamAvHealthIndicatorTest {

    @Test
    void health_clamavReachable_reportsUp() {
        Health health = new ClamAvHealthIndicator(mock(ClamAvScanner.class)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void health_clamavUnreachable_reportsDown() {
        ClamAvScanner scanner = mock(ClamAvScanner.class);
        doThrow(new RuntimeException("connection refused")).when(scanner).ping();

        Health health = new ClamAvHealthIndicator(scanner).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
