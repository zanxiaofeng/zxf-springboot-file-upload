package zxf.upload.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.ClamavException;
import zxf.upload.model.exception.ScanFailedException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ClamAV 封装测试：结果透传、故障包装、熔断快速失败、健康检查委托。
 */
class ClamAvScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void scan_cleanFile_returnsNull() throws Exception {
        ClamavClient client = mock(ClamavClient.class);
        when(client.scan(any(InputStream.class)))
                .thenReturn(xyz.capybara.clamav.commands.scan.result.ScanResult.OK.INSTANCE);
        ClamAvScanner scanner = new ClamAvScanner(client);
        Path file = Files.writeString(tempDir.resolve("clean.txt"), "hello");

        assertThat(scanner.scan(file)).isNull();
    }

    @Test
    void scan_engineFailure_wrappedAsScanFailed() throws Exception {
        ClamavClient client = mock(ClamavClient.class);
        when(client.scan(any(InputStream.class)))
                .thenThrow(new ClamavException(new RuntimeException("connection refused")));
        ClamAvScanner scanner = new ClamAvScanner(client);
        Path file = Files.writeString(tempDir.resolve("a.txt"), "x");

        assertThatThrownBy(() -> scanner.scan(file))
                .isInstanceOf(ScanFailedException.class)
                .hasMessageContaining("ClamAV 扫描失败");
    }

    @Test
    void scan_persistentFailures_circuitOpensAndFailsFast() throws Exception {
        // capybara 无 socket 超时，引擎挂起时熔断器是防止级联耗尽的最后防线
        ClamavClient client = mock(ClamavClient.class);
        when(client.scan(any(InputStream.class)))
                .thenThrow(new ClamavException(new RuntimeException("down")));
        ClamAvScanner scanner = new ClamAvScanner(client);
        Path file = Files.writeString(tempDir.resolve("a.txt"), "x");

        // 前 5 次真实失败：达到 minCalls=5 且失败率 100% ≥ 50%，熔断随即打开
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> scanner.scan(file))
                    .isInstanceOf(ScanFailedException.class)
                    .hasMessageContaining("ClamAV 扫描失败");
        }

        // 第 6 次起熔断快速失败，不再触碰引擎
        assertThatThrownBy(() -> scanner.scan(file))
                .isInstanceOf(ScanFailedException.class)
                .hasMessageContaining("熔断中");
        verify(client, times(5)).scan(any(InputStream.class));
    }

    @Test
    void ping_delegatesToClient() {
        ClamavClient client = mock(ClamavClient.class);
        ClamAvScanner scanner = new ClamAvScanner(client);

        scanner.ping();

        verify(client).ping();
    }
}
