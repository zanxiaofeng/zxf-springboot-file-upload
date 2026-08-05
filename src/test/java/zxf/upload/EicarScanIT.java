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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EICAR 端到端集成测试（Testcontainers + failsafe）。
 * *IT 命名由 maven-failsafe-plugin 在 verify 阶段执行，mvn test 不跑。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EicarScanIT {

    private static final String EICAR_STRING =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> clamav = new GenericContainer<>("clamav/clamav:1.4.5")
            .withExposedPorts(3310)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(10));   // 首次下载病毒库较慢

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zxf.virus-scan.clamav.host", clamav::getHost);
        registry.add("zxf.virus-scan.clamav.port", () -> clamav.getMappedPort(3310));
        registry.add("zxf.virus-scan.yara.enabled", () -> "false");
        try {
            Path tmp = Files.createTempDirectory("eicar-it-storage");
            registry.add("zxf.virus-scan.storage.base-path", () -> tmp.resolve("storage").toString());
            registry.add("zxf.virus-scan.storage.quarantine-path", () -> tmp.resolve("quarantine").toString());
            registry.add("zxf.virus-scan.storage.staging-path", () -> tmp.resolve("staging").toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void eicarFile_isRejected_asInfected() throws Exception {
        Path eicar = Files.createTempFile("eicar", ".txt");
        Files.writeString(eicar, EICAR_STRING);

        HttpResponse<String> response = uploadFile(eicar, "eicar.txt");
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("Eicar-Test-Signature");
    }

    @Test
    void cleanFile_isAccepted() throws Exception {
        Path clean = Files.createTempFile("clean", ".txt");
        Files.writeString(clean, "This is a clean file for testing.");

        HttpResponse<String> response = uploadFile(clean, "clean.txt");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("CLEAN");
    }

    private HttpResponse<String> uploadFile(Path file, String filename) throws Exception {
        String boundary = "----TestBoundary" + System.currentTimeMillis();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + Files.readString(file, StandardCharsets.ISO_8859_1) + "\r\n"
                + "--" + boundary + "--\r\n";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/files/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.ISO_8859_1))
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
