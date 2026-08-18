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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@Component
public class YaraScanner {
    private final VirusScanProperties.Yara config;
    private final String rulesPath;
    /** 读 yara 输出的线程池（每任务一个虚拟线程），与调用线程解耦，防挂起时无限阻塞 */
    private final ExecutorService outputReader = Executors.newVirtualThreadPerTaskExecutor();
    /** 进程正常退出后排空 stdout 的宽限时长（秒） */
    private static final long OUTPUT_DRAIN_GRACE_SECONDS = 5;

    public YaraScanner(VirusScanProperties properties) {
        this.config = properties.getYara();
        // enabled=false 是完整逃生舱：不解析规则路径（规则文件缺失也能启动）
        this.rulesPath = config.isEnabled() ? resolveRulesPath(config.getRulesPath()) : null;
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
        Future<List<String>> outputFuture = null;
        try {
            Process started = new ProcessBuilder(command).redirectErrorStream(true).start();
            process = started;

            // 输出读取移入虚拟线程：若在调用线程同步读，yara 挂起且不关闭 stdout 时
            // readLine() 会永久阻塞，waitFor 的超时检查永远执行不到，最终耗尽扫描许可。
            // 进程被 destroyForcibly 后管道关闭，读线程的 readLine() 返回 null 自然退出。
            outputFuture = outputReader.submit(() -> readOutput(started));

            if (!process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS)) {
                throw new ScanFailedException("YARA 扫描超时: " + file.getFileName());
            }

            // 进程已退出，输出应立即排空；宽限超时说明 stdout 被子进程继承未关闭
            List<String> output = outputFuture.get(OUTPUT_DRAIN_GRACE_SECONDS, TimeUnit.SECONDS);

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
        } catch (ExecutionException e) {
            throw new ScanFailedException("YARA 输出读取失败: " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            if (outputFuture != null) {
                outputFuture.cancel(true);
            }
            throw new ScanFailedException("YARA 输出排空超时: " + file.getFileName(), e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private List<String> readOutput(Process process) throws IOException {
        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }
        return output;
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
