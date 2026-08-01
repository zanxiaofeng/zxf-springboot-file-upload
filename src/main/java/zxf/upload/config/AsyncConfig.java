package zxf.upload.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * spring.threads.virtual.enabled=true 时，Boot 自动装配的
 * applicationTaskExecutor 即 SimpleAsyncTaskExecutor(virtual threads)，
 * @Async 默认使用它：每个扫描任务一个虚拟线程，无需手工声明执行器。
 * 并发上限由 VirusScanService 入口的 Semaphore 统一控制。
 * @EnableScheduling 供 AsyncScanProcessor 的 SSE 心跳使用。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
}
