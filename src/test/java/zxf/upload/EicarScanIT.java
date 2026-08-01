package zxf.upload;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EICAR 端到端集成测试：Testcontainers 启动真实 ClamAV，验证完整管道
 * （multipart 解析 → Tika → ClamAV INSTREAM → 隔离）与 INSTREAM 协议兼容性。
 * EICAR 串无害，但所有杀毒引擎必报 Eicar-Test-Signature。
 *
 * 无 Docker 环境时自动跳过；mvn test 不执行（*IT 由 failsafe 在 verify 阶段运行）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EicarScanIT {

    /** EICAR 测试串（无害，ClamAV 必报 Eicar-Test-Signature） */
    private static final String EICAR =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    @Container
    static final GenericContainer<?> CLAMAV = new GenericContainer<>(
            DockerImageName.parse("clamav/clamav:1.4"))
            .withExposedPorts(3310)
            // clamd 加载完病毒库后才监听端口，端口可连即就绪；首次启动下载库较慢
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(10));

    @DynamicPropertySource
    static void scanProperties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("zxf.virus-scan.clamav.host", CLAMAV::getHost);
        registry.add("zxf.virus-scan.clamav.port", () -> CLAMAV.getMappedPort(3310));
        registry.add("zxf.virus-scan.yara.enabled", () -> "false");   // 测试环境无 yara CLI
        Path dirs = Files.createTempDirectory("eicar-it-");
        registry.add("zxf.virus-scan.storage.base-path", () -> dirs.resolve("storage").toString());
        registry.add("zxf.virus-scan.storage.quarantine-path", () -> dirs.resolve("quarantine").toString());
        registry.add("zxf.virus-scan.storage.staging-path", () -> dirs.resolve("staging").toString());
    }

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void uploadEicarFile_returns422VirusDetected() throws Exception {
        HttpResponse<String> response = upload("eicar.txt", EICAR.getBytes(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body())
                .contains("VIRUS_DETECTED")
                .contains("Eicar-Test-Signature");
    }

    @Test
    void uploadCleanFile_returns200Clean() throws Exception {
        HttpResponse<String> response = upload("hello.txt", "hello world".getBytes(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"CLEAN\"");
    }

    /** 构造 multipart/form-data 请求并 POST 到上传端点 */
    private HttpResponse<String> upload(String filename, byte[] content) throws Exception {
        String boundary = "boundary-" + UUID.randomUUID();
        byte[] body = buildMultipartBody(boundary, filename, content);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/files/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofMinutes(2))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static byte[] buildMultipartBody(String boundary, String filename, byte[] content) {
        String delimiter = "--" + boundary + "\r\n";
        String header = delimiter
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";

        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[headerBytes.length + content.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(content, 0, result, headerBytes.length, content.length);
        System.arraycopy(footerBytes, 0, result, headerBytes.length + content.length, footerBytes.length);
        return result;
    }
}
