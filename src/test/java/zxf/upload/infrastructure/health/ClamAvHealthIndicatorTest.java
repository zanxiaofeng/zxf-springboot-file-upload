package zxf.upload.infrastructure.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import zxf.upload.infrastructure.filescan.ClamAvScanner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ClamAvHealthIndicator 健康检查")
class ClamAvHealthIndicatorTest {

    @Test
    @DisplayName("PING 成功 → UP")
    void pingSuccess_healthUp() {
        ClamAvScanner scanner = mock(ClamAvScanner.class);
        doNothing().when(scanner).ping();

        ClamAvHealthIndicator indicator = new ClamAvHealthIndicator(scanner);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.UP);
        assertThat(health.getDetails()).containsEntry("engine", "ClamAV");
    }

    @Test
    @DisplayName("PING 异常 → DOWN")
    void pingFailure_healthDown() {
        ClamAvScanner scanner = mock(ClamAvScanner.class);
        doThrow(new RuntimeException("Connection refused")).when(scanner).ping();

        ClamAvHealthIndicator indicator = new ClamAvHealthIndicator(scanner);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}
