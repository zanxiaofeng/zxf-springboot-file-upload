---
name: "Logging Conventions"
description: "Logging conventions using @Slf4j with SLF4J placeholders and log level guidelines"
paths:
  - "**/*.java"
---


# Logging Conventions

Use `@Slf4j` (Lombok). Never declare a manual `Logger` field.

## SLF4J Rules

1. **Use placeholder format** — never `String.format` or string concatenation
2. **Exception logs must include the exception object** as the last argument (outputs stack trace)
3. **Placeholder count must match parameter count** — the exception object does not count as a placeholder

```java
// Correct
log.error("Failed to process, key: {}", key, ex);
log.info("User login, userId: {}, username: {}", userId, MaskUtils.mask(username));
log.warn("Retry attempt {} for {}", attempt, operation, ex);

// Wrong — missing exception object, no stack trace
log.error("Failed to process, key: {}, exception: {}", key, ex.getMessage());
// Wrong — string concatenation
log.debug("User login, userId: " + userId);
```

## Log Levels

| Level | When to use | Production |
|-------|-------------|------------|
| **ERROR** | Unrecoverable failure (DB connection lost, config error) | Enabled |
| **WARN** | Recoverable issue (degraded service, retry, deprecated API) | Enabled |
| **INFO** | Business events & lifecycle (login, order, startup) | Enabled |
| **DEBUG** | Diagnostics (params, flow, HTTP req/res) | Disabled |

## What to Log

- Application lifecycle: startup, shutdown, config loaded
- Business operations: `log.info("Order created, orderId: {}, amount: {}", orderId, amount)`
- All exceptions with context: `log.error("Payment failed, orderId: {}", orderId, ex)`
- External calls: `log.debug("Calling API, url: {}, method: {}", url, method)`

## What NOT to Log

- Passwords, tokens, PII — always mask sensitive data
- 在 DEBUG 日志中传入**昂贵构造的参数**(如 `log.debug("x: {}", buildExpensive())`)时,需 `if (log.isDebugEnabled())` guard 避免无谓构造;SLF4J 2.x 占位符日志(`log.debug("x: {}", x)`)已先做级别检查,普通参数无需 guard

## Standard Templates

```java
// Normal operation
log.info("Operation completed, result: {}", result);
log.debug("Processing request, param: {}", param);

// Exception — include context + exception object
log.error("Failed to process, key: {}", key, ex);

// Performance
log.info("Query completed, duration: {}ms, rows: {}", duration, rows);
```

## MDC / Trace ID

Inject a `traceId` into the Mapped Diagnostic Context (MDC) on every request via a Servlet Filter. This enables log correlation across services.

### Log Pattern

```
%d{ISO8601} [%thread] %-5level %logger{36} [traceId:%X{traceId}] - %msg%n
```

Add this pattern to `logback-spring.xml`:

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{ISO8601} [%thread] %-5level %logger{36} [traceId:%X{traceId}] - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

### Trace Filter

```java
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // 上游 traceId 必须白名单校验(防日志注入 / 响应头分裂 CRLF),不合法则丢弃重新生成
        String traceId = Optional.ofNullable(((HttpServletRequest) request).getHeader("X-Trace-Id"))
                .filter(id -> id != null && id.matches("[A-Za-z0-9_-]{8,128}"))
                .orElse(UUID.randomUUID().toString());
        MDC.put("traceId", traceId);

        // Propagate trace ID in response header
        if (response instanceof HttpServletResponse httpServletResponse) {
            httpServletResponse.setHeader("X-Trace-Id", traceId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // Clean up MDC to prevent thread-local leaks in thread pool
            MDC.remove("traceId");
        }
    }
}
```

**Rules:**
- Always clean up MDC in `finally` — servlet containers reuse threads, and stale MDC values leak across requests
- Accept `X-Trace-Id` from upstream services; generate a new UUID if absent
- Return the same `X-Trace-Id` in the response header for client-side correlation

## Structured Logging

### Spring Boot 4.0 Built-in JSON Logging

Spring Boot 4.0+ supports structured logging natively via configuration:

```yaml
# application.yml — SB4.1 结构化日志:合法格式 id 仅 ecs / gelf / logstash(json 非合法 id 会被静默忽略)
logging:
  structured:
    format:
      console: logstash   # 或 ecs / gelf
      file: logstash
```

This outputs log events as JSON without additional dependencies.

### Logstash Encoder (ELK / Datadog / CloudWatch)

For environments using the ELK stack, Datadog, or CloudWatch Logs, use `logstash-logback-encoder`:

```xml
<!-- pom.xml -->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.0</version>
</dependency>
```

```xml
<!-- logback-spring.xml -->
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdc>true</includeMdc>
        <customFields>{"app_name":"{project}"}</customFields>
    </encoder>
</appender>
```

### When to Use Structured vs Unstructured

| Environment | Format | Reason |
|-------------|--------|--------|
| Local development | Unstructured (plain text) | Human-readable in console |
| CI/CD pipelines | Unstructured | Readable in build logs |
| Staging / Production | Structured (JSON) | Machine-parseable, searchable in log aggregation |

## Spring Boot 4 日志相关默认行为

- **Logback 默认 charset 为 UTF-8**（与 Log4j2 行为对齐，无需显式配置）
- **Liveness / Readiness 探针**：检测到 Kubernetes 平台时自动启用；其他平台需显式 `management.endpoint.health.probes.enabled=true`（关闭用 `=false`）
- **DevTools Live Reload 默认禁用**：如需启用设 `spring.devtools.livereload.enabled=true`

## Sensitive Data Masking

Never log sensitive fields in plain text. Always mask before logging.

### Fields That Must Always Be Masked

| Category | Fields |
|----------|--------|
| Authentication | password, secret, token, apiKey, accessToken, refreshToken |
| Financial | creditCard, cardNumber, cvv, bankAccount |
| PII | ssn, idNumber, passportNumber |
| Health | medicalRecord, diagnosis |

### Inline Masking Pattern

> **优先用下方 `MaskUtils`(已处理 null / 越界)。** 仅当一次性场景用内联 `substring` 时,**必须先判空 + 长度**,避免 NPE / `StringIndexOutOfBoundsException`:

```java
// Phone number: show first 3 digits only(先判空 + 长度)
if (phone != null && phone.length() >= 3) {
    log.info("User login, phone: {}****", phone.substring(0, 3));
}

// ID number: show first 4 and last 4
if (idNumber != null && idNumber.length() >= 8) {
    log.info("ID verification, number: {}****{}", idNumber.substring(0, 4), idNumber.substring(idNumber.length() - 4));
}

// Email: show first character and domain(校验含 @)
int at = email == null ? -1 : email.indexOf('@');
if (at > 0) {
    log.info("Email changed, email: {}***@{}", email.charAt(0), email.substring(at + 1));
}
```

### Dedicated Masking Utility

For repeated masking logic, create a utility method:

```java
@UtilityClass               // lombok.experimental.UtilityClass;工具类规范见 java-coding-standard.md §5.2
public class MaskUtils {

    /**
     * Masks a string, showing the first {@code visiblePrefix} characters.
     * "13812345678" with visiblePrefix=3 → "138****"
     */
    public String mask(String value, int visiblePrefix) {  // @UtilityClass 自动 static
        if (value == null || value.length() <= visiblePrefix) {
            return "***";
        }
        return value.substring(0, visiblePrefix) + "****";
    }

    /**
     * Masks a string, showing first and last N characters.
     * "6222021234567890" with 4/4 → "6222****7890"
     */
    public String maskMiddle(String value, int visiblePrefix, int visibleSuffix) {
        if (value == null || value.length() <= visiblePrefix + visibleSuffix) {
            return "***";
        }
        return value.substring(0, visiblePrefix) + "****" + value.substring(value.length() - visibleSuffix);
    }

    /**
     * Full mask for tokens, passwords, secrets.
     */
    public String maskAll(String value) {
        return "***";  // token 类脱敏不暴露原值,null 与非 null 均返回 ***
    }
}
```

Usage:

```java
log.info("User login, phone: {}", MaskUtils.mask(phone, 3));
log.info("Card payment, card: {}", MaskUtils.maskMiddle(cardNumber, 4, 4));
log.info("Auth attempt, token: {}", MaskUtils.maskAll(token));
```
