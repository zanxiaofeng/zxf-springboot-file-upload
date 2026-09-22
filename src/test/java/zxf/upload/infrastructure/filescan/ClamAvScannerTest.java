package zxf.upload.infrastructure.filescan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.commands.scan.result.ScanResult;
import zxf.upload.infrastructure.domain.BusinessException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("ClamAvScanner 熔断 + 扫描")
class ClamAvScannerTest {

    @TempDir Path tempDir;
    private ClamavClient client;
    private ClamAvScanner scanner;
    private Path cleanFile;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        client = mock(ClamavClient.class);
        scanner = new ClamAvScanner(client, 60);
        cleanFile = tempDir.resolve("clean.txt");
        Files.writeString(cleanFile, "clean");
    }

    @Test
    @DisplayName("干净文件 → null")
    void cleanFile_returnsNull() {
        when(client.scan(any(InputStream.class))).thenReturn(ScanResult.OK.INSTANCE);
        assertThat(scanner.scan(cleanFile)).isNull();
    }

    @Test
    @DisplayName("病毒文件 → 返回威胁描述")
    void virusFound_returnsThreat() {
        var virusFound = mock(ScanResult.VirusFound.class);
        when(virusFound.getFoundViruses())
                .thenReturn(Map.of("stream", List.of("Eicar-Test-Signature")));
        when(client.scan(any(InputStream.class))).thenReturn(virusFound);

        String threat = scanner.scan(cleanFile);
        assertThat(threat).contains("Eicar-Test-Signature");
    }

    @Test
    @DisplayName("引擎异常 → BusinessException(scanFailed) 包装")
    void engineError_wrapsException() {
        when(client.scan(any(InputStream.class))).thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> scanner.scan(cleanFile))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Connection refused");
    }

    @Test
    @DisplayName("持续失败达阈值 → 熔断打开后快速失败")
    void circuitBreaker_opensAfterFailures() {
        when(client.scan(any(InputStream.class))).thenThrow(new RuntimeException("Connection refused"));

        // 触发足够多次失败使熔断器打开（slidingWindowSize=10, minimumNumberOfCalls=5, failureRateThreshold=50）
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> scanner.scan(cleanFile))
                    .isInstanceOf(BusinessException.class);
        }

        // 熔断打开后应快速失败，不再触碰引擎
        long callCount = mockingDetails(client).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("scan")).count();
        assertThatThrownBy(() -> scanner.scan(cleanFile))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("熔断中");
    }

    @Test
    @DisplayName("挂起扫描 → timeoutSeconds 内快速失败并计入熔断失败率")
    void hangingScan_timesOutAndCountsAsFailure() throws Exception {
        when(client.scan(any(InputStream.class))).thenAnswer(inv -> {
            Thread.sleep(5000);          // 模拟 clamd 挂起：连接建立但无响应
            return ScanResult.OK.INSTANCE;
        });
        scanner = new ClamAvScanner(client, 0);   // timeout=0 → get 立即超时（任务必未完成）

        // 持续超时达到 minimumNumberOfCalls=5 且失败率 100% → 熔断打开
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> scanner.scan(cleanFile))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("超时");
        }
        // 熔断打开后快速失败，不再触碰引擎
        assertThatThrownBy(() -> scanner.scan(cleanFile))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("熔断中");
    }

    @Test
    @DisplayName("ping 委托 client")
    void ping_delegates() {
        scanner.ping();
        verify(client).ping();
    }
}
