package zxf.upload.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * spring.threads.virtual.enabled=true 时，Boot 自动装配的
 * applicationTaskExecutor 即 SimpleAsyncTaskExecutor(virtual threads)，
 *
 * @Async 默认使用它：每个扫描任务一个虚拟线程，无需手工声明执行器。
 * 并发上限由 AsyncScanProcessor 中的 Semaphore 控制。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean
    public AsyncTaskExecutor virusScanExecutor() {
        return new SimpleAsyncTaskExecutor(
                Thread.ofVirtual().name("virus-scan-", 0).factory());
    }
}