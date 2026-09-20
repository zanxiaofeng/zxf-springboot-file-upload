---
name: "Downstream Integration"
description: "Downstream service integration with RestTemplate, error handling, and WireMock testing"
paths:
  - "**/infrastructure/**/*.java"
  - "**/application/command/**/*.java"
  - "**/integration/**/*.java"
  - "**/*.yml"
  - "**/*.yaml"
  - "**/*.properties"
---

# Downstream Integration Conventions

> **职责边界：** 本文件是下游集成的**唯一权威**——设计原则、HTTP 客户端实现（RestClient/RestTemplate）、错误分类、弹性模式、连接池、接口设计、日志、测试配置。`architecture.md` §5.1 仅概述 Client 位置与依赖方向，`service-conventions.md` §1/§2 定义事务边界与 Domain Event 下游解耦。

***

## 1. Design Principle

- 下游客户端是 **infrastructure 层的具体 `@Component` 类** (`infrastructure/downstream/{Service}Client.java`)，无接口 + 实现对（见 `architecture.md` §1 依赖规则）
- 使用 `RestClient`（首选，Spring Framework 7，需 `spring-boot-starter-restclient`）或 `RestTemplate` 做 HTTP 调用
- 禁止 Controller 直接调用下游；下游 Client 具体类由 `application/event/` 的事件监听器注入（executor 发布事件、不直接调下游，事务内禁止耗时调用，见 `service-conventions.md` §2）
- 方法参数使用 Event DTO 或 record，**禁止超过 3 个原始参数**

***

## 2. RestClient 实现（首选）

```java
// Config — infrastructure/config/{Feature}Config.java
@Configuration
public class DownstreamConfig {
    @Bean
    public RestClient {service}RestClient(RestClient.Builder builder,
            @Value("${app.downstream.{service}.base-url}") String baseUrl) {
        return builder
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}

// Implementation — infrastructure/downstream/{Service}Client.java
@Slf4j
@Component
@RequiredArgsConstructor
public class {Service}Client {
    private final RestClient {service}RestClient;

    public boolean sendNotification({Event}Event event) {
        try {
            {service}RestClient.post()
                .uri("/api/v1/notifications")
                .body(event)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            // 4xx/5xx：RestClient 默认抛出，不吞状态码；记录后按 §4 分类决策
            log.error("Downstream {service} returned {}: key={}", ex.getStatusCode(), event.key());
            return false;
        } catch (ResourceAccessException ex) {
            log.warn("Downstream {service} unreachable: {}", ex.getMessage());
            return false;
        }
    }
}
```

***

## 3. RestTemplate 实现（已有项目）

```java
@Bean
public RestTemplate downstreamRestTemplate(RestTemplateBuilder builder) {
    return builder
            .connectTimeout(Duration.ofSeconds(3))   // Boot 3.4+：setConnectTimeout/setReadTimeout 已废弃
            .readTimeout(Duration.ofSeconds(5))
            .build();
}
```

> 已有项目使用 `RestTemplate` 可继续使用。新模块推荐 `RestClient`。

***

## 4. 错误分类处理

下游 HTTP 错误分为三类，处理策略不同：

| 错误类型 | 异常类 | 处理策略 |
|----------|--------|----------|
| 连接失败/超时 | `ResourceAccessException` | 记录 WARN + 返回 false（瞬态错误，可重试） |
| 客户端错误 (4xx) | `HttpClientErrorException` | 记录 ERROR + 业务决策（参数错误？认证过期？） |
| 服务端错误 (5xx) | `HttpServerErrorException` | 记录 ERROR + 降级处理（可重试/熔断） |

**RestClient `onStatus` 精细化处理：**

RestClient 默认对 4xx/5xx 抛 `HttpClientErrorException` / `HttpServerErrorException`（均为 `RestClientResponseException` 子类）。`onStatus` 仅用于自定义映射——**handler 内必须抛出异常**，只 log 不抛会替换默认错误处理，错误响应被当作正常返回：

```java
.retrieve()
.onStatus(status -> status.is4xxClientError(), (req, res) -> {
    // 4xx: 业务错误 —— 必须抛出，再由上层 catch 分类
    throw new HttpClientErrorException(res.getStatusCode(), "Downstream client error");
})
.onStatus(status -> status.is5xxServerError(), (req, res) -> {
    // 5xx: 服务端问题 —— 抛出后可重试/熔断
    throw new HttpServerErrorException(res.getStatusCode(), "Downstream server error");
})
```

> 下游异常的完整捕获/处理规范见 `exception-handling.md` §5。

***

## 5. 弹性模式（生产推荐）

使用 Resilience4j 增强下游调用可靠性：

```java
// pom.xml（resilience4j artifact 随 Spring Boot 版本变化：Boot 3.x 为 resilience4j-spring-boot3，
// 更新版本以官方文档为准 —— 引入前必须核对当前 Boot 版本对应的 artifact，勿照抄旧坐标）
// <dependency>
//     <groupId>io.github.resilience4j</groupId>
//     <artifactId>resilience4j-spring-boot3</artifactId>
// </dependency>

@Slf4j
@Component
@RequiredArgsConstructor
public class {Service}Client {
    private final RestClient {service}RestClient;

    @CircuitBreaker(name = "{service}", fallbackMethod = "sendNotificationFallback")
    @Retry(name = "{service}")
    public boolean sendNotification({Event}Event event) {
        // ... RestClient 调用
    }

    private boolean sendNotificationFallback({Event}Event event, Exception ex) {
        log.warn("Circuit breaker/fallback for {service}: {}", ex.getMessage());
        return false;
    }
}
```

```yaml
# application.yml
resilience4j:
  circuitbreaker:
    instances:
      {service}:
        failure-rate-threshold: 50
        slow-call-duration-threshold: 3s
        slow-call-rate-threshold: 80
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
  retry:
    instances:
      {service}:
        max-attempts: 3
        wait-duration: 500ms
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException
```

***

## 6. 连接池配置（生产环境）

默认 `RestTemplate` / `RestClient` 每个请求打开新 TCP 连接。生产环境推荐连接池：

```java
@Bean
public RestTemplate downstreamRestTemplate(RestTemplateBuilder builder) {
    var httpClient = HttpClients.custom()
        .setMaxConnTotal(50)
        .setMaxConnPerRoute(10)
        .build();

    var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setConnectionRequestTimeout(Duration.ofSeconds(2));

    return builder.requestFactory(() -> factory).build();
}
```

***

## 7. 下游接口设计

```java
// GOOD: 使用 Event DTO/record，具体 @Component 类（本架构无接口 + 实现对）
public class {Service}Client {
    public boolean sendNotification({Event}CreatedEvent event) { ... }
}

// BAD: 超过 3 个原始参数
public class {Service}Client {
    public boolean sendNotification(Long userId, String username, String email) { ... } // 违反规则
}
```

***

## 8. 下游调用日志

- **DEBUG 级别**：请求 URL、HTTP 方法、响应状态码
- **ERROR 级别**：调用失败，包含下游服务名称和关键参数（脱敏后）
- **禁止**：记录完整请求体/响应体（可能包含敏感数据）

> 日志规范详见 `logging.md`。

***

## 9. 测试配置

Production: `app.downstream.{service}.base-url` in `application.yml`
Test: `app.downstream.{service}.base-url` pointing to `http://localhost:${wiremock.server.port}` in `application-test.yml`

### WireMock 测试模式

三个支撑类位于 `support/mocks/`：`MockFileLoader`（加载 mock-data/ 模板 + `${variable}` 替换）、`{Service}MockFactory`（`equalToJson` + `${json-unit.ignore}` 通配匹配请求体，stub success/failure 两种响应）、`{Service}MockVerifier`（验证调用次数与请求内容）。

**mock-data 模板文件**：

| 文件 | 用途 | 占位符 |
|------|------|--------|
| `mock-data/request/{service}-{action}.json` | 请求体匹配模板 | `${json-unit.ignore}`（通配）、`${variable}`（特定值） |
| `mock-data/response/{service}-{scenario}.json` | 响应体返回模板 | `${variable}`（运行时替换动态值） |

**请求模板示例** — 所有字段使用 `${json-unit.ignore}` 通配匹配：

```json
{
  "userId": "${json-unit.ignore}",
  "username": "${json-unit.ignore}",
  "email": "${json-unit.ignore}",
  "eventType": "${json-unit.ignore}"
}
```

> WireMock 的 `equalToJson()` 底层使用 json-unit，支持 `${json-unit.ignore}` 占位符进行结构匹配。

> WireMock 测试模式与 MockFactory/MockVerifier 完整规范见 `integration-test-guide.md` §6。
