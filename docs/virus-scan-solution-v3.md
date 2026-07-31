# 文件上传病毒扫描服务 — 实现方案

**模块:** `zxf-springboot-file-upload`

**Goal:** 实现多层文件上传病毒扫描服务（Tika + ClamAV + YARA + 文档威胁检测），支持同步/异步扫描模式，异步结果通过 SSE 推送并提供轮询兜底。

**Architecture:** `上传 → 预检（大小/扩展名，未落盘）→ 暂存落盘 → 扫描管道（Tika 类型校验 → ClamAV → YARA → 文档威胁检测）→ 入库 / 隔离 / 清理`。暂存文件生命周期完全由扫描管道内部管理，调用方不接触临时文件。

**Tech Stack:** Spring Boot 4.1, Java 21（Virtual Threads）, ClamAV (xyz.capybara:clamav-client 2.1.3), YARA CLI (ProcessBuilder), Apache Tika 2.9.x, commons-compress 1.27+, Apache POI 5.3+, Lombok

---

## 并发模型：Virtual Threads

扫描是 IO 密集型负载（文件读写、INSTREAM socket、YARA 子进程等待），非常适合虚拟线程：

- 开启 `spring.threads.virtual.enabled=true`，Spring Boot 4.1 自动将 Web 容器与 `@Async` 默认执行器切换为虚拟线程（`VirtualThreadTaskExecutor`/`SimpleAsyncTaskExecutor`），每个扫描任务一个虚拟线程，天然支持高并发上传；
- 虚拟线程**无池化、无队列**，因此背压不能依赖线程池拒绝策略，改用 **`Semaphore` 信号量**限制并发扫描数（默认 16），许可耗尽时调用方阻塞等待（同步模式在 Web 虚拟线程上等待，成本极低）；
- 注意避免 `synchronized` 钉住（pinning）载体线程：管道内全部使用 `java.util.concurrent` 与 NIO 文件 API，无 synchronized 块。

---

## File Structure

```
zxf-springboot-file-upload/
├── pom.xml
├── docker/
│   └── docker-compose.yml                           # ClamAV 服务
├── src/main/java/zxf/upload/
│   ├── FileUploadApplication.java                   # @SpringBootApplication + @ConfigurationPropertiesScan
│   ├── config/
│   │   ├── VirusScanProperties.java                 # 扫描配置（含 failStrategy、并发信号量）
│   │   └── AsyncConfig.java                         # @EnableAsync（虚拟线程执行器）
│   ├── control/
│   │   └── FileUploadController.java                # REST：同步/异步上传、SSE、轮询
│   ├── model/
│   │   ├── ScanStatus.java
│   │   ├── ScanResult.java
│   │   ├── UploadResponse.java
│   │   └── exception/
│   │       ├── FileRejectedException.java           # 400
│   │       ├── VirusDetectedException.java          # 422
│   │       └── ScanFailedException.java             # 502
│   ├── service/
│   │   ├── VirusScanService.java                    # 核心扫描管道（管理暂存文件生命周期）
│   │   ├── StagingService.java                      # 上传落盘 + 预检
│   │   ├── FileStorageService.java                  # 正式存储 + 隔离区
│   │   ├── ClamAvScanner.java                       # ClamAV 封装
│   │   ├── YaraScanner.java                         # YARA CLI 封装
│   │   ├── FileTypeValidator.java                   # Tika 探测 + ZIP 炸弹流式检查
│   │   ├── DocumentThreatScanner.java               # OOXML/OLE2/PDF 威胁检测（分块低内存）
│   │   └── AsyncScanProcessor.java                  # 异步扫描 + SSE + 结果缓存 + 信号量背压
│   └── support/rest/
│       └── GlobalExceptionHandler.java
├── src/main/resources/
│   ├── application.yml
│   └── rules/malware.yar
└── src/test/java/zxf/upload/
    ├── service/VirusScanServiceTest.java
    ├── service/FileTypeValidatorTest.java
    ├── service/DocumentThreatScannerTest.java
    └── control/FileUploadControllerTest.java
```

---

## Task 1: Maven 依赖

`zxf-springboot-file-upload/pom.xml`：

```xml
<properties>
    <java.version>21</java.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- ClamAV 客户端（INSTREAM 协议） -->
    <dependency>
        <groupId>xyz.capybara</groupId>
        <artifactId>clamav-client</artifactId>
        <version>2.1.3</version>
    </dependency>

    <!-- Apache Tika 文件类型检测 -->
    <dependency>
        <groupId>org.apache.tika</groupId>
        <artifactId>tika-core</artifactId>
        <version>2.9.2</version>
    </dependency>

    <!-- ZIP 炸弹防护（流式） -->
    <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-compress</artifactId>
        <version>1.27.1</version>
    </dependency>

    <!-- OLE2 宏检测（POIFS + VBAMacroReader） -->
    <dependency>
        <groupId>org.apache.poi</groupId>
        <artifactId>poi</artifactId>
        <version>5.3.0</version>
    </dependency>

    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

验证：`mvn compile -pl zxf-springboot-file-upload -q` → BUILD SUCCESS

---

## Task 2: 启动类

```java
package zxf.upload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FileUploadApplication {
    public static void main(String[] args) {
        SpringApplication.run(FileUploadApplication.class, args);
    }
}
```

---

## Task 3: Model 层

### ScanStatus.java

```java
package zxf.upload.model;

public enum ScanStatus {
    SCANNING,
    CLEAN,
    INFECTED,   // 确认威胁（病毒/YARA 命中/恶意宏）
    REJECTED,   // 策略拒绝（超大/类型不符/伪造扩展名）
    ERROR       // 扫描服务自身故障
}
```

### ScanResult.java

```java
package zxf.upload.model;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;

/**
 * 扫描结果，不可变。
 * stagingPath：管道内暂存文件路径，所有状态均携带，供管道统一清理/隔离。
 * detectedMime：Tika 探测结果，随管道传递避免重复探测。
 * CLEAN 状态下 details 携带正式存储路径。
 */
@Value
@Builder
public class ScanResult {
    ScanStatus status;
    String threat;
    String details;
    Path stagingPath;
    String detectedMime;

    public boolean isClean() {
        return status == ScanStatus.CLEAN;
    }

    public static ScanResult clean(Path stagingPath, String detectedMime) {
        return ScanResult.builder()
                .status(ScanStatus.CLEAN)
                .stagingPath(stagingPath)
                .detectedMime(detectedMime)
                .build();
    }

    public static ScanResult infected(Path stagingPath, String threat) {
        return ScanResult.builder()
                .status(ScanStatus.INFECTED)
                .stagingPath(stagingPath)
                .threat(threat)
                .build();
    }

    public static ScanResult rejected(Path stagingPath, String reason) {
        return ScanResult.builder()
                .status(ScanStatus.REJECTED)
                .stagingPath(stagingPath)
                .threat(reason)
                .build();
    }

    public static ScanResult error(Path stagingPath, String message) {
        return ScanResult.builder()
                .status(ScanStatus.ERROR)
                .stagingPath(stagingPath)
                .details(message)
                .build();
    }
}
```

### UploadResponse.java

```java
package zxf.upload.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UploadResponse {
    private String scanId;
    private ScanStatus status;
    private String message;
    private String filePath;

    public static UploadResponse scanning(String scanId) {
        return new UploadResponse(scanId, ScanStatus.SCANNING, "扫描进行中", null);
    }

    public static UploadResponse of(String scanId, ScanResult result, String storedPath) {
        String message = switch (result.getStatus()) {
            case CLEAN -> "文件安全";
            case INFECTED -> "检测到威胁: " + result.getThreat();
            case REJECTED -> "文件被拒绝: " + result.getThreat();
            case ERROR -> "扫描失败: " + result.getDetails();
            case SCANNING -> "扫描进行中";
        };
        return new UploadResponse(scanId, result.getStatus(), message, storedPath);
    }
}
```

### 异常类

```java
package zxf.upload.model.exception;

/** 策略拒绝（400）：文件过大、类型不符、ZIP 炸弹 */
public class FileRejectedException extends RuntimeException {
    public FileRejectedException(String message) { super(message); }
}
```

```java
package zxf.upload.model.exception;

import lombok.Getter;
import zxf.upload.model.ScanResult;

/** 检测到威胁（422） */
@Getter
public class VirusDetectedException extends RuntimeException {
    private final ScanResult scanResult;

    public VirusDetectedException(ScanResult scanResult) {
        super(scanResult.getThreat());
        this.scanResult = scanResult;
    }
}
```

```java
package zxf.upload.model.exception;

/** 扫描基础设施故障（502），fail-close 策略下抛出 */
public class ScanFailedException extends RuntimeException {
    public ScanFailedException(String message) { super(message); }
    public ScanFailedException(String message, Throwable cause) { super(message, cause); }
}
```

---

## Task 4: 配置属性 + AsyncConfig（Virtual Threads）

### VirusScanProperties.java

```java
package zxf.upload.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Data
@Validated
@ConfigurationProperties(prefix = "zxf.virus-scan")
public class VirusScanProperties {

    /** 总开关。false 时管道直接入库（仍执行大小/扩展名预检）。 */
    private boolean enabled = true;

    /** 扫描引擎故障策略：CLOSED=拒绝上传（默认，安全优先）；OPEN=放行并告警 */
    private FailStrategy failStrategy = FailStrategy.CLOSED;

    @Min(1024)
    private long maxFileSize = 100 * 1024 * 1024; // 100MB

    /** 并发扫描许可数（虚拟线程 + 信号量背压） */
    @Min(1)
    private int maxConcurrentScans = 16;

    @Valid
    private ZipGuard zip = new ZipGuard();

    private List<String> allowedExtensions = List.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "jpg", "jpeg", "png", "gif", "bmp",
            "txt", "csv", "zip");

    private List<String> allowedMimeTypes = List.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "image/jpeg", "image/png", "image/gif", "image/bmp",
            "text/plain", "text/csv",
            "application/zip");

    @Valid
    private ClamAv clamav = new ClamAv();
    @Valid
    private Yara yara = new Yara();
    @Valid
    private Storage storage = new Storage();

    public enum FailStrategy { OPEN, CLOSED }

    @Data
    public static class ZipGuard {
        /** 单文件压缩比上限 */
        private long maxCompressionRatio = 100L;
        /** 累计解压字节上限（默认 1GB） */
        private long maxTotalUncompressed = 1L << 30;
        /** 嵌套压缩包最大深度 */
        private int maxNestingDepth = 2;
    }

    @Data
    public static class ClamAv {
        @NotBlank
        private String host = "localhost";
        private int port = 3310;
        /** socket 超时 ms */
        private int timeout = 30000;
    }

    @Data
    public static class Yara {
        private boolean enabled = true;
        private String binaryPath = "yara";
        private String rulesPath = "classpath:rules/malware.yar";
        /** 单文件扫描超时（秒） */
        private long timeoutSeconds = 60;
    }

    @Data
    public static class Storage {
        private String basePath = "./data/upload-storage";
        private String quarantinePath = "./data/upload-quarantine";
        /** 上传暂存区（扫描前落盘位置） */
        private String stagingPath = "./data/upload-staging";
    }
}
```

### AsyncConfig.java

```java
package zxf.upload.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * spring.threads.virtual.enabled=true 时，Boot 自动装配的
 * applicationTaskExecutor 即 SimpleAsyncTaskExecutor(virtual threads)，
 * @Async 默认使用它：每个扫描任务一个虚拟线程，无需手工声明执行器。
 * 并发上限由 AsyncScanProcessor 中的 Semaphore 控制。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
```

> 如需显式声明（便于在日志/监控中识别），也可注册：
> ```java
> @Bean
> public AsyncTaskExecutor virusScanExecutor() {
>     return new SimpleAsyncTaskExecutor(
>             Thread.ofVirtual().name("virus-scan-", 0).factory());
> }
> ```

---

## Task 5: StagingService（上传落盘 + 预检）

```java
package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.exception.FileRejectedException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 上传暂存：在请求线程内把 MultipartFile 同步落盘到 staging 目录。
 * 之后的同步/异步扫描只传递 Path，与 multipart 请求级临时文件生命周期解耦。
 */
@Slf4j
@Service
public class StagingService {
    private final Path stagingDir;
    private final VirusScanProperties properties;

    public StagingService(VirusScanProperties properties) {
        this.properties = properties;
        this.stagingDir = Paths.get(properties.getStorage().getStagingPath());
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建暂存目录: " + stagingDir, e);
        }
    }

    /**
     * 预检（未落盘，低成本）+ 落盘。
     * @return 暂存文件路径
     */
    public Path stage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileRejectedException("上传文件不能为空");
        }
        // 大小预检：落盘前拦截，防止超大文件打爆磁盘（容器级限制见 application.yml）
        if (file.getSize() > properties.getMaxFileSize()) {
            throw new FileRejectedException("文件过大，最大允许 "
                    + properties.getMaxFileSize() / 1024 / 1024 + "MB");
        }
        String original = file.getOriginalFilename();
        String ext = (original != null && original.contains("."))
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase()
                : "";
        // 扩展名预检（快路径，内容防伪由 Tika 负责）
        if (!ext.isEmpty() && !properties.getAllowedExtensions().contains(ext)) {
            throw new FileRejectedException("不支持的文件扩展名: " + ext);
        }

        Path stagingFile = stagingDir.resolve(UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext));
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, stagingFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new FileRejectedException("文件暂存失败: " + e.getMessage());
        }
        log.debug("File staged: {} -> {}", original, stagingFile);
        return stagingFile;
    }
}
```

---

## Task 6: ClamAvScanner

```java
package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import xyz.capybara.clamav.ClamavClient;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.exception.ScanFailedException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * ClamAV 封装。第三方 SDK 类型（xyz.capybara.*）不出本类，
 * 调用方只接收 String threat / null，避免类型耦合。
 * 引擎故障抛 ScanFailedException，由管道按 fail-strategy 处理。
 */
@Slf4j
@Component
public class ClamAvScanner {
    private final ClamavClient client;

    public ClamAvScanner(VirusScanProperties properties) {
        VirusScanProperties.ClamAv cfg = properties.getClamav();
        this.client = new ClamavClient(cfg.getHost(), cfg.getPort(), cfg.getTimeout());
    }

    /**
     * @return null = 干净；非 null = 病毒描述
     * @throws ScanFailedException ClamAV 服务不可达/协议错误
     */
    public String scan(Path file) {
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
```

> 备注：`xyz.capybara:clamav-client` 的 INSTREAM 分块受 ClamAV `StreamMaxLength` 限制（默认 100MB，与 maxFileSize 对齐即可）。如后续遇兼容性问题，可直接实现 INSTREAM 协议替换，接口签名不变。

---

## Task 7: YaraScanner

```java
package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.exception.ScanFailedException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class YaraScanner {
    private final VirusScanProperties.Yara config;
    private final String rulesPath;

    public YaraScanner(VirusScanProperties properties) {
        this.config = properties.getYara();
        this.rulesPath = resolveRulesPath(config.getRulesPath());
    }

    /**
     * @return null = 干净/未启用；非 null = 命中签名描述
     * @throws ScanFailedException yara 进程执行错误（由管道按 fail 策略处理）
     */
    public String scan(Path file) {
        if (!config.isEnabled()) {
            return null;
        }

        List<String> command = List.of(
                config.getBinaryPath(),
                "-s",                              // 打印命中串
                "--fail-on-warnings",
                rulesPath,
                file.toAbsolutePath().toString());

        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();

            List<String> output = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }

            if (!process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ScanFailedException("YARA 扫描超时: " + file.getFileName());
            }

            // yara CLI：退出码 0 = 执行成功（无论是否命中）；非 0 = 执行错误。
            // 命中与否以输出是否为空判断。
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new ScanFailedException(
                        "YARA 执行错误, exit=" + exitCode + ", output=" + String.join(" | ", output));
            }
            if (output.isEmpty()) {
                return null;
            }

            // -s 输出格式：非缩进行首列为 "规则名 文件路径"，缩进行为命中串
            String signatures = output.stream()
                    .filter(l -> !l.startsWith(" ") && !l.startsWith("\t") && !l.startsWith("0x"))
                    .map(l -> l.split(" ")[0])
                    .distinct()
                    .collect(Collectors.joining(", "));
            log.warn("YARA rules matched in {}: {}", file.getFileName(), signatures);
            return "YARA: " + signatures;
        } catch (IOException e) {
            throw new ScanFailedException("YARA 进程启动失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScanFailedException("YARA 扫描被中断", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String resolveRulesPath(String rulesPath) {
        if (!rulesPath.startsWith("classpath:")) {
            return rulesPath;
        }
        String resourcePath = rulesPath.substring("classpath:".length());
        try (var is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("YARA 规则文件不存在: " + rulesPath);
            }
            Path tempFile = Files.createTempFile("yara-rules-", ".yar");
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            tempFile.toFile().deleteOnExit();
            return tempFile.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("解析 YARA 规则路径失败: " + rulesPath, e);
        }
    }
}
```

> 虚拟线程适配说明：`process.waitFor(timeout, ...)` 与流读取均为阻塞 IO，在虚拟线程上运行时载体线程被正常卸载（unmount），不会占用平台线程；无需额外改造。

---

## Task 8: FileTypeValidator（Tika 单次探测 + 流式 ZIP 检查）

```java
package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.exception.ScanFailedException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * 文件类型校验：Tika 探测一次，detectedMime 随返回值传递到后续管道阶段。
 */
@Slf4j
@Component
public class FileTypeValidator {
    private static final Map<String, String> MIME_TO_PRIMARY_EXT = Map.ofEntries(
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/vnd.ms-powerpoint", "ppt"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/bmp", "bmp"),
            Map.entry("text/plain", "txt"),
            Map.entry("application/zip", "zip"));

    private final VirusScanProperties properties;
    private final Tika tika = new Tika();

    public FileTypeValidator(VirusScanProperties properties) {
        this.properties = properties;
    }

    /** 校验结果。detectedMime 供管道后续阶段复用。 */
    public record TypeCheck(boolean passed, String detectedMime, String rejectReason) {}

    public TypeCheck validate(Path file, String originalFilename) {
        final String detectedMime;
        try {
            detectedMime = tika.detect(file);
        } catch (IOException e) {
            throw new ScanFailedException("文件类型探测失败: " + e.getMessage(), e);
        }

        String extension = extractExtension(originalFilename);
        log.debug("Type check: filename={}, ext={}, detected={}", originalFilename, extension, detectedMime);

        if (properties.getAllowedMimeTypes().stream().noneMatch(detectedMime::equalsIgnoreCase)) {
            return new TypeCheck(false, detectedMime, "不支持的文件类型: " + detectedMime);
        }
        if (!isExtensionConsistent(detectedMime, extension)) {
            return new TypeCheck(false, detectedMime, "文件类型与扩展名不符，检测到: " + detectedMime);
        }
        if ("application/zip".equals(detectedMime)) {
            String zipProblem = inspectZip(file, 1);
            if (zipProblem != null) {
                return new TypeCheck(false, detectedMime, zipProblem);
            }
        }
        return new TypeCheck(true, detectedMime, null);
    }

    public boolean isDocumentFormat(String mimeType) {
        return mimeType != null && (mimeType.contains("officedocument")
                || mimeType.contains("msword") || mimeType.contains("ms-excel")
                || mimeType.contains("ms-powerpoint") || mimeType.equals("application/pdf"));
    }

    private boolean isExtensionConsistent(String mimeType, String extension) {
        // jpg/jpeg 互认
        if ("image/jpeg".equals(mimeType)) {
            return "jpg".equals(extension) || "jpeg".equals(extension);
        }
        // CSV 常被 Tika 探测为 text/plain，双向互认
        if ("text/plain".equals(mimeType)) {
            return "txt".equals(extension) || "csv".equals(extension);
        }
        String expected = MIME_TO_PRIMARY_EXT.get(mimeType);
        if (expected != null) {
            return expected.equals(extension);
        }
        return properties.getAllowedExtensions().contains(extension);
    }

    /**
     * 流式 ZIP 检查：
     * - getSize() 返回 -1 时按实际读取字节计数；
     * - 累计解压总量绝对上限；
     * - 嵌套压缩包深度限制（递归炸弹）。
     *
     * @return null = 通过；非 null = 拒绝原因
     */
    private String inspectZip(Path file, int depth) {
        VirusScanProperties.ZipGuard guard = properties.getZip();
        if (depth > guard.getMaxNestingDepth()) {
            return "疑似嵌套 ZIP 炸弹，压缩包嵌套深度超过 " + guard.getMaxNestingDepth();
        }
        long compressedSize;
        try {
            compressedSize = Files.size(file);
        } catch (IOException e) {
            throw new ScanFailedException("读取文件大小失败", e);
        }

        long totalUncompressed = 0;
        try (InputStream in = Files.newInputStream(file);
             ZipArchiveInputStream archive = new ZipArchiveInputStream(in)) {
            ZipArchiveEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = archive.getNextZipEntry()) != null) {
                long entrySize = entry.getSize();
                if (entrySize >= 0) {
                    totalUncompressed += entrySize;
                } else {
                    // 未知大小：实际读取计数
                    int n;
                    while ((n = archive.read(buffer)) != -1) {
                        totalUncompressed += n;
                    }
                }
                if (totalUncompressed > guard.getMaxTotalUncompressed()) {
                    return "疑似 ZIP 炸弹，累计解压大小超过 " + (guard.getMaxTotalUncompressed() >> 20) + "MB";
                }
                if (compressedSize > 0 && totalUncompressed > compressedSize * guard.getMaxCompressionRatio()) {
                    return "疑似 ZIP 炸弹，压缩比超过 " + guard.getMaxCompressionRatio() + ":1";
                }
                if (!entry.isDirectory()
                        && entry.getName().toLowerCase(Locale.ROOT).endsWith(".zip")
                        && depth + 1 > guard.getMaxNestingDepth()) {
                    return "疑似嵌套 ZIP 炸弹，压缩包嵌套深度超过 " + guard.getMaxNestingDepth();
                }
            }
        } catch (IOException e) {
            // 损坏的 zip 交由 ClamAV/YARA 判定；此处异常按引擎故障上抛
            throw new ScanFailedException("ZIP 检查失败: " + e.getMessage(), e);
        }
        return null;
    }

    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
```

---

## Task 9: DocumentThreatScanner（分块低内存 + POI 真实宏提取）

```java
package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.poifs.macros.VBAMacroReader;
import org.springframework.stereotype.Component;
import zxf.upload.model.exception.ScanFailedException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 文档威胁检测（浅检测层，位于 ClamAV/YARA 之后）：
 * - OOXML (docx/xlsx/pptx)：检查 vbaProject.bin、ActiveX 前缀（遍历条目名，不依赖可选的目录条目）
 * - OLE2 (doc/xls/ppt)：POI VBAMacroReader 真实提取宏模块，存在可疑 API 才判威胁
 * - PDF：分块 + 重叠窗口扫描 /JavaScript+/OpenAction、/Launch；
 *   对 FlateDecode 压缩对象流无效属已知限制，深度检测由 YARA 规则与 ClamAV 兜底
 *
 * 全程流式/分块读取，不整文件入内存。
 */
@Slf4j
@Component
public class DocumentThreatScanner {
    /** 分块窗口 1MB，重叠 64B 防止关键字跨块漏检 */
    private static final int CHUNK = 1 << 20;
    private static final int OVERLAP = 64;

    private static final String[] SUSPICIOUS_MACRO_TOKENS = {
            "autoopen", "autoexec", "document_open", "workbook_open",
            "shell", "wscript.shell", "cmd.exe", "powershell", "urldownloadtofile", "createobject"
    };

    /**
     * @return null = 干净/非文档；非 null = 威胁描述
     */
    public String scan(Path file, String detectedMime) {
        String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (filename.endsWith(".docx") || filename.endsWith(".xlsx") || filename.endsWith(".pptx")) {
                return scanOoxml(file);
            }
            if (filename.endsWith(".doc") || filename.endsWith(".xls") || filename.endsWith(".ppt")) {
                return scanOle2(file);
            }
            if (filename.endsWith(".pdf")) {
                return scanPdf(file);
            }
            return null;
        } catch (ScanFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ScanFailedException("文档威胁检测失败: " + e.getMessage(), e);
        }
    }

    private String scanOoxml(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            boolean hasVba = false;
            boolean hasActiveX = false;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith("vbaproject.bin")) {
                    hasVba = true;
                }
                if (name.contains("/activex/") || name.startsWith("activex/")) {
                    hasActiveX = true;
                }
            }
            if (hasVba) {
                log.warn("OOXML contains VBA project: {}", file.getFileName());
                return "Office 文档包含 VBA 宏";
            }
            if (hasActiveX) {
                log.warn("OOXML contains ActiveX controls: {}", file.getFileName());
                return "Office 文档包含 ActiveX 控件";
            }
            return null;
        }
    }

    private String scanOle2(Path file) throws IOException {
        try (VBAMacroReader reader = new VBAMacroReader(file.toFile())) {
            Map<String, String> macros = reader.readMacros();
            if (macros.isEmpty()) {
                return null;
            }
            StringBuilder all = new StringBuilder();
            macros.values().forEach(code -> all.append(code.toLowerCase(Locale.ROOT)).append('\n'));
            String code = all.toString();
            for (String token : SUSPICIOUS_MACRO_TOKENS) {
                if (code.contains(token)) {
                    log.warn("OLE2 contains suspicious macro token '{}' in {}", token, file.getFileName());
                    return "OLE2 文档包含可疑 VBA 宏（命中: " + token + "）";
                }
            }
            log.info("OLE2 contains benign macros: {}", file.getFileName());
            return null;
        } catch (IllegalArgumentException e) {
            // 非 OLE2 文件（伪装场景已被 Tika 拦截，此处为双保险）
            return null;
        }
    }

    private String scanPdf(Path file) throws IOException {
        boolean hasJavaScript = false;
        boolean hasOpenAction = false;
        byte[] tail = new byte[0];
        try (InputStream in = Files.newInputStream(file)) {
            byte[] chunk = new byte[CHUNK];
            int n;
            while ((n = in.read(chunk, OVERLAP, CHUNK - OVERLAP)) != -1) {
                System.arraycopy(tail, 0, chunk, 0, tail.length);
                int len = tail.length + n;
                String text = new String(chunk, 0, len, StandardCharsets.ISO_8859_1)
                        .toLowerCase(Locale.ROOT);
                if (text.contains("/javascript")) hasJavaScript = true;
                if (text.contains("/openaction")) hasOpenAction = true;
                if (text.contains("/launch")) {
                    log.warn("PDF contains /Launch action: {}", file.getFileName());
                    return "PDF 包含 Launch 动作（可能执行外部程序）";
                }
                if (hasJavaScript && hasOpenAction) {
                    log.warn("PDF contains JavaScript with OpenAction: {}", file.getFileName());
                    return "PDF 包含自动执行的 JavaScript";
                }
                tail = new byte[OVERLAP];
                System.arraycopy(chunk, len - OVERLAP, tail, 0, OVERLAP);
            }
        }
        return null;
    }
}
```

---

## Task 10: VirusScanService（核心管道）

```java
package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.ScanResult;
import zxf.upload.model.ScanStatus;
import zxf.upload.model.exception.ScanFailedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 扫描管道。输入为 staging 文件 Path，拥有该文件的完整生命周期：
 * - CLEAN：移交 FileStorageService 入库，删除 staging 文件；
 * - INFECTED：移入隔离区；
 * - REJECTED/ERROR：删除 staging 文件。
 * 调用方只拿结果，不接触临时文件。
 */
@Slf4j
@Service
public class VirusScanService {
    private final VirusScanProperties properties;
    private final FileTypeValidator fileTypeValidator;
    private final ClamAvScanner clamAvScanner;
    private final YaraScanner yaraScanner;
    private final DocumentThreatScanner documentThreatScanner;
    private final FileStorageService storageService;

    public VirusScanService(VirusScanProperties properties,
                            FileTypeValidator fileTypeValidator,
                            ClamAvScanner clamAvScanner,
                            YaraScanner yaraScanner,
                            DocumentThreatScanner documentThreatScanner,
                            FileStorageService storageService) {
        this.properties = properties;
        this.fileTypeValidator = fileTypeValidator;
        this.clamAvScanner = clamAvScanner;
        this.yaraScanner = yaraScanner;
        this.documentThreatScanner = documentThreatScanner;
        this.storageService = storageService;
    }

    /**
     * 执行完整扫描管道。
     * @return CLEAN 时 details 为正式存储路径；其余状态 staging 文件已被妥善处理
     */
    public ScanResult scanFile(Path stagingFile, String originalFilename) {
        try {
            ScanResult result = doScan(stagingFile, originalFilename);
            switch (result.getStatus()) {
                case CLEAN -> {
                    String stored = storageService.store(stagingFile, originalFilename);
                    deleteQuietly(stagingFile);
                    log.info("Scan passed: {} -> {}", originalFilename, stored);
                    return ScanResult.builder()
                            .status(ScanStatus.CLEAN)
                            .detectedMime(result.getDetectedMime())
                            .details(stored)
                            .build();
                }
                case INFECTED -> {
                    storageService.moveToQuarantine(stagingFile);
                    log.warn("Infected file quarantined: {}", originalFilename);
                    return result;
                }
                default -> {
                    deleteQuietly(stagingFile);
                    return result;
                }
            }
        } catch (ScanFailedException e) {
            deleteQuietly(stagingFile);
            // fail 策略统一收口：CLOSED 上抛（转 502）；OPEN 降级放行并打标
            if (properties.getFailStrategy() == VirusScanProperties.FailStrategy.OPEN) {
                log.error("Scan engine failed, fail-open policy applied: {}", e.getMessage(), e);
                try {
                    String stored = storageService.store(stagingFile, originalFilename);
                    return ScanResult.builder()
                            .status(ScanStatus.CLEAN)
                            .details(stored)
                            .threat("scan-engine-degraded")
                            .build();
                } catch (IOException ioe) {
                    throw new ScanFailedException("fail-open 降级存储失败", ioe);
                }
            }
            throw e;
        } catch (IOException e) {
            deleteQuietly(stagingFile);
            throw new ScanFailedException("扫描管道 IO 异常: " + e.getMessage(), e);
        }
    }

    private ScanResult doScan(Path stagingFile, String originalFilename) {
        if (!properties.isEnabled()) {
            return ScanResult.clean(stagingFile, null);
        }

        // 阶段 1：类型校验（Tika 探测一次，detectedMime 向后传递）
        FileTypeValidator.TypeCheck typeCheck = fileTypeValidator.validate(stagingFile, originalFilename);
        if (!typeCheck.passed()) {
            return ScanResult.rejected(stagingFile, typeCheck.rejectReason());
        }
        String mime = typeCheck.detectedMime();

        // 阶段 2：ClamAV
        String clamThreat = clamAvScanner.scan(stagingFile);
        if (clamThreat != null) {
            return ScanResult.infected(stagingFile, clamThreat);
        }

        // 阶段 3：YARA
        String yaraThreat = yaraScanner.scan(stagingFile);
        if (yaraThreat != null) {
            return ScanResult.infected(stagingFile, yaraThreat);
        }

        // 阶段 4：文档威胁（复用 mime，不重复探测）
        if (fileTypeValidator.isDocumentFormat(mime)) {
            String docThreat = documentThreatScanner.scan(stagingFile, mime);
            if (docThreat != null) {
                return ScanResult.infected(stagingFile, docThreat);
            }
        }

        return ScanResult.clean(stagingFile, mime);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.error("清理暂存文件失败: {}", path, e);
        }
    }
}
```

---

## Task 11: FileStorageService

```java
package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zxf.upload.config.VirusScanProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
public class FileStorageService {
    private final Path storagePath;
    private final Path quarantinePath;

    public FileStorageService(VirusScanProperties properties) {
        this.storagePath = Paths.get(properties.getStorage().getBasePath());
        this.quarantinePath = Paths.get(properties.getStorage().getQuarantinePath());
        try {
            Files.createDirectories(storagePath);
            Files.createDirectories(quarantinePath);
            // 隔离区权限收敛（POSIX 系统生效）
            if (quarantinePath.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(quarantinePath,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException | UnsupportedOperationException e) {
            throw new IllegalStateException("存储目录初始化失败", e);
        }
    }

    public String store(Path sourceFile, String originalFilename) throws IOException {
        String ext = extractExtension(originalFilename);
        Path target = storagePath.resolve(UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext));
        Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("File stored: {} -> {}", originalFilename, target);
        return target.toAbsolutePath().toString();
    }

    public void moveToQuarantine(Path file) {
        try {
            // 隔离文件重命名为 UUID，防止原名冲突与路径信息泄漏
            Path target = quarantinePath.resolve(UUID.randomUUID() + ".quarantined");
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("File quarantined: {} -> {}", file.getFileName(), target.getFileName());
        } catch (IOException e) {
            log.error("移入隔离区失败: {}", file, e);
        }
    }

    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
```

> 生产补充建议（后续迭代）：存储/隔离区 TTL 定时清理（`@Scheduled`）、磁盘水位监控、对接对象存储。

---

## Task 12: AsyncScanProcessor（虚拟线程 + 信号量背压 + SSE/轮询）

```java
package zxf.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.ScanResult;
import zxf.upload.model.ScanStatus;
import zxf.upload.model.UploadResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 异步扫描 + 结果分发。
 *
 * 并发模型：@Async 在虚拟线程上执行（每任务一个虚拟线程）。
 * 虚拟线程无池化，背压由 Semaphore(maxConcurrentScans) 提供：
 * 许可耗尽时提交方（Web 虚拟线程）阻塞等待，成本极低。
 *
 * 时序安全：
 * 1. 结果先入 completedScans 缓存；
 * 2. registerEmitter 时若结果已存在，立即回放并结束 —— 客户端何时连接都能拿到结果；
 * 3. 轮询端点 getResult 任何时刻可拿当前状态。
 */
@Slf4j
@Service
public class AsyncScanProcessor {
    private final VirusScanService scanService;
    private final Semaphore scanPermits;

    private final Map<String, SseEmitter> pendingEmitters = new ConcurrentHashMap<>();
    private final Map<String, UploadResponse> completedScans = new ConcurrentHashMap<>();

    public AsyncScanProcessor(VirusScanService scanService, VirusScanProperties properties) {
        this.scanService = scanService;
        this.scanPermits = new Semaphore(properties.getMaxConcurrentScans());
    }

    /** 轮询兜底端点使用 */
    public UploadResponse getResult(String scanId) {
        UploadResponse done = completedScans.get(scanId);
        return done != null ? done : UploadResponse.scanning(scanId);
    }

    public void registerEmitter(String scanId, SseEmitter emitter) {
        // 先查结果缓存：扫描可能已先于 SSE 连接完成
        UploadResponse done = completedScans.get(scanId);
        if (done != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName(done.getStatus())).data(done));
                emitter.complete();
            } catch (IOException e) {
                log.warn("SSE 回放失败: {}", scanId, e);
            }
            return;
        }
        pendingEmitters.put(scanId, emitter);
        emitter.onCompletion(() -> pendingEmitters.remove(scanId));
        emitter.onTimeout(() -> {
            pendingEmitters.remove(scanId);
            log.warn("SSE emitter 超时: {}", scanId);
        });
    }

    @Async   // 使用 Boot 装配的虚拟线程执行器（spring.threads.virtual.enabled=true）
    public void processScan(String scanId, Path stagingFile, String filename) {
        UploadResponse response;
        try {
            scanPermits.acquire();          // 背压：超过 maxConcurrentScans 时虚拟线程挂起等待
            try {
                ScanResult result = scanService.scanFile(stagingFile, filename);
                String storedPath = result.isClean() ? result.getDetails() : null;
                response = UploadResponse.of(scanId, result, storedPath);
            } finally {
                scanPermits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response = new UploadResponse(scanId, ScanStatus.ERROR, "扫描被中断", null);
        } catch (Exception e) {
            log.error("异步扫描失败: scanId={}", scanId, e);
            response = new UploadResponse(scanId, ScanStatus.ERROR, "扫描失败: " + e.getMessage(), null);
        }

        completedScans.put(scanId, response);
        SseEmitter emitter = pendingEmitters.remove(scanId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName(response.getStatus())).data(response));
                emitter.complete();
            } catch (IOException e) {
                log.warn("SSE 推送失败（结果已缓存，客户端可轮询）: {}", scanId, e);
            }
        }
    }

    private String eventName(ScanStatus status) {
        return switch (status) {
            case CLEAN -> "complete";
            case INFECTED, REJECTED -> "threat";
            default -> "error";
        };
    }
}
```

---

## Task 13: FileUploadController + GlobalExceptionHandler

### FileUploadController.java

```java
package zxf.upload.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zxf.upload.model.ScanResult;
import zxf.upload.model.UploadResponse;
import zxf.upload.model.exception.FileRejectedException;
import zxf.upload.model.exception.ScanFailedException;
import zxf.upload.model.exception.VirusDetectedException;
import zxf.upload.service.AsyncScanProcessor;
import zxf.upload.service.StagingService;
import zxf.upload.service.VirusScanService;

import java.nio.file.Path;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileUploadController {
    private final StagingService stagingService;
    private final VirusScanService scanService;
    private final AsyncScanProcessor asyncProcessor;

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Scan-Async", defaultValue = "false") boolean async) {

        String filename = StringUtils.cleanPath(
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown");

        // 请求线程内同步落盘，同步/异步扫描都只消费 Path
        Path stagingFile = stagingService.stage(file);

        if (async) {
            String scanId = UUID.randomUUID().toString();
            asyncProcessor.processScan(scanId, stagingFile, filename);
            return ResponseEntity.accepted().body(UploadResponse.scanning(scanId));
        }

        ScanResult result = scanService.scanFile(stagingFile, filename);
        return switch (result.getStatus()) {
            case CLEAN -> ResponseEntity.ok(UploadResponse.of(null, result, result.getDetails()));
            case REJECTED -> throw new FileRejectedException(result.getThreat());
            case INFECTED -> throw new VirusDetectedException(result);
            default -> throw new ScanFailedException(result.getDetails());
        };
    }

    /** SSE 推送（增强通道） */
    @GetMapping(value = "/scan/{scanId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scanEvents(@PathVariable String scanId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        asyncProcessor.registerEmitter(scanId, emitter);
        return emitter;
    }

    /** 轮询兜底：任何时刻都能拿到当前状态 */
    @GetMapping("/scan/{scanId}")
    public UploadResponse scanStatus(@PathVariable String scanId) {
        return asyncProcessor.getResult(scanId);
    }
}
```

### GlobalExceptionHandler

```java
package zxf.upload.support.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import zxf.upload.model.exception.FileRejectedException;
import zxf.upload.model.exception.ScanFailedException;
import zxf.upload.model.exception.VirusDetectedException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorResponse(String code, String message) {}

    @ExceptionHandler(FileRejectedException.class)
    public ResponseEntity<ErrorResponse> handleFileRejected(FileRejectedException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("FILE_REJECTED", ex.getMessage()));
    }

    @ExceptionHandler(VirusDetectedException.class)
    public ResponseEntity<ErrorResponse> handleVirusDetected(VirusDetectedException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorResponse("VIRUS_DETECTED", ex.getScanResult().getThreat()));
    }

    @ExceptionHandler(ScanFailedException.class)
    public ResponseEntity<ErrorResponse> handleScanFailed(ScanFailedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("SCAN_ENGINE_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("FILE_TOO_LARGE", "文件超过大小限制"));
    }
}
```

---

## Task 14: YARA 规则

`src/main/resources/rules/malware.yar`：

```yar
rule Ransomware_Note_Indicators {
    meta:
        description = "Ransomware note common phrases"
    strings:
        $msg1 = "your files have been encrypted" nocase
        $msg2 = "bitcoin" nocase
        $msg3 = "decrypt your files" nocase
        $msg4 = "pay the ransom" nocase
    condition:
        2 of them
}

rule Suspicious_Office_Macro {
    meta:
        description = "Office document with auto-execute macro and shell invocation"
    strings:
        $auto_open = "AutoOpen" nocase
        $auto_exec = "AutoExec" nocase
        $doc_open = "Document_Open" nocase
        $shell = "Shell(" nocase
        $cmd = "cmd.exe" nocase
        $powershell = "powershell" nocase
        $wscript = "WScript.Shell" nocase
    condition:
        // OLE2 头 D0 CF -> uint16 小端 0xCFD0；ZIP/OOXML 头 PK -> 0x4B50
        (uint16(0) == 0xCFD0 or uint16(0) == 0x4B50) and
        1 of ($auto_open, $auto_exec, $doc_open) and
        1 of ($shell, $cmd, $powershell, $wscript)
}

rule PDF_Embedded_JS_AutoExec {
    meta:
        description = "PDF with JavaScript and OpenAction"
    strings:
        $pdf_header = "%PDF"
        $js = "/JavaScript"
        $open_action = "/OpenAction"
    condition:
        $pdf_header at 0 and $js and $open_action
}
```

> 生产环境建议挂载外部规则目录（`rules-path: /etc/yara/rules/`）并建立规则更新流程，classpath 内置规则仅作基线。

---

## Task 15: application.yml + docker-compose

### application.yml

```yaml
spring:
  threads:
    virtual:
      enabled: true            # Web 请求与 @Async 均运行在虚拟线程上
  servlet:
    multipart:
      max-file-size: 100MB     # 容器级第一道闸，超限直接 413
      max-request-size: 110MB

zxf:
  virus-scan:
    enabled: true
    fail-strategy: CLOSED      # 扫描引擎故障时拒绝上传（安全优先）
    max-file-size: 104857600   # 100MB，与 multipart 对齐
    max-concurrent-scans: 16   # 虚拟线程 + 信号量背压
    zip:
      max-compression-ratio: 100
      max-total-uncompressed: 1073741824   # 1GB
      max-nesting-depth: 2
    clamav:
      host: ${CLAMAV_HOST:localhost}
      port: ${CLAMAV_PORT:3310}
      timeout: 30000
    yara:
      enabled: true
      binary-path: ${YARA_BINARY:yara}
      rules-path: "classpath:rules/malware.yar"
      timeout-seconds: 60
    storage:
      base-path: ${UPLOAD_STORAGE:./data/upload-storage}
      quarantine-path: ${UPLOAD_QUARANTINE:./data/upload-quarantine}
      staging-path: ${UPLOAD_STAGING:./data/upload-staging}
```

### docker/docker-compose.yml

```yaml
services:
  clamav:
    image: clamav/clamav:1.4-stable
    container_name: clamav
    environment:
      CLAMD_STARTUP_TIMEOUT: "600"
    ports:
      - "3310:3310"
    volumes:
      - clamav-db:/var/lib/clamav
    healthcheck:
      # 官方镜像自带健康脚本，真实探测 clamd 就绪（PING/PONG）
      test: ["CMD", "/health.sh"]
      interval: 30s
      timeout: 10s
      retries: 10
      start_period: 120s   # 首次启动下载病毒库较慢

volumes:
  clamav-db:
```

> YARA CLI 需安装到应用运行环境（Dockerfile 中 `apt-get install -y yara` 或宿主包管理器）。

---

## Task 16: 测试计划

### VirusScanServiceTest（单元，Mock 各扫描器）

- 各阶段短路顺序：类型拒绝后不再调 ClamAV；
- CLEAN → staging 文件被删除且调用 store；
- INFECTED → 调用 moveToQuarantine；
- REJECTED/ERROR → staging 被删除（`Files.exists` 断言无泄漏）；
- ScanFailedException + failStrategy=CLOSED → 异常上抛；OPEN → 降级入库并打标 `scan-engine-degraded`。

### FileTypeValidatorTest

- 伪造扩展名：exe 内容改名 `.pdf` → REJECTED；
- csv 被 Tika 探测为 text/plain → 通过；
- EICAR 串写入 `.txt` → 类型层通过（留给 ClamAV 层）；
- ZIP 炸弹样例（高压缩比构造）→ REJECTED；
- 未知大小 entry（streaming zip）→ 按实际读取计数，不误判。

### DocumentThreatScannerTest

- 含 vbaProject.bin 的 docx → 威胁；
- POI 生成带 `AutoOpen + Shell` 宏的 xls → 威胁；良性宏 → 放行；
- 含 `/JavaScript + /OpenAction` 的 PDF → 威胁；
- 100MB 大 PDF → 扫描内存占用平稳（分块验证）。

### FileUploadControllerTest（@WebMvcTest）

- 同步 CLEAN → 200；INFECTED → 422 VIRUS_DETECTED；REJECTED → 400 FILE_REJECTED；
- 异步时序用例：先 POST 拿到 scanId，扫描完成后**再**连 SSE → 仍能收到 complete 事件（回放）；`GET /scan/{scanId}` 轮询返回终态；
- 并发 50 个异步上传，断言同时在扫的数量不超过 `max-concurrent-scans`（信号量背压生效）；
- 空文件 → 400；超大文件 → 413。

### 集成测试（可选，Testcontainers）

- 容器启动 ClamAV，上传 EICAR 文件断言 422 —— 端到端验证管道与 INSTREAM 兼容性。

> EICAR 测试串（无害，ClamAV 必报 `Eicar-Test-Signature`）：
> `X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*`

---

## Task 17: 构建与验证

```bash
mvn compile -pl zxf-springboot-file-upload
mvn test -pl zxf-springboot-file-upload
docker compose -f docker/docker-compose.yml up -d clamav
mvn spring-boot:run -pl zxf-springboot-file-upload

# 手工冒烟
curl -F "file=@clean.pdf" http://localhost:8080/api/files/upload
echo 'X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*' > eicar.txt
curl -F "file=@eicar.txt" http://localhost:8080/api/files/upload        # 期望 422
curl -F "file=@big.pdf" -H "X-Scan-Async: true" http://localhost:8080/api/files/upload
curl -N http://localhost:8080/api/files/scan/{scanId}/events            # SSE
curl http://localhost:8080/api/files/scan/{scanId}                      # 轮询兜底
```

---

## Self-Review Checklist

- [x] 项目名 `zxf-springboot-file-upload`，包名 `zxf.upload`，全文一致
- [x] 并发模型为 Virtual Threads：`spring.threads.virtual.enabled=true` + `@Async` 默认虚拟线程执行器 + Semaphore 背压
- [x] 无 synchronized 块，无 pinning 风险点；阻塞 IO（process.waitFor / 文件读写）均对虚拟线程友好
- [x] 暂存文件生命周期由管道收口，INFECTED 必进隔离区，无泄漏路径
- [x] SSE 结果缓存 + 回放 + 轮询兜底，无时序窗口
- [x] 异常语义清晰：400 FILE_REJECTED / 413 FILE_TOO_LARGE / 422 VIRUS_DETECTED / 502 SCAN_ENGINE_ERROR
- [x] fail-strategy 可配，默认 CLOSED；enabled=false 行为明确
- [x] YARA 命中以输出判定；规则魔数小端值正确（0xCFD0 / 0x4B50）
- [x] Spring Boot 4.1：Jakarta 命名空间、@ConfigurationPropertiesScan、starter-validation
