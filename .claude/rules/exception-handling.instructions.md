---
name: "Exception Handling"
description: "Exception classification, throwing, catching, and global handling conventions for Spring MVC REST APIs"
paths:
  - "**/*.java"
---

# 异常处理规范

**适用范围：** JDK 21 + Spring Boot 4 + Spring MVC（Servlet 栈）REST API

> **职责边界：** 本文件是异常处理的**唯一权威**——异常分类、体系结构、抛出规范、捕获规范、全局处理、边界外异常、反模式。`architecture.md` §3.5 仅概述异常体系位置，`validation.md` 定义输入校验（校验失败如何变成异常），`downstream-conventions.md` §4 定义下游错误分类，`logging.md` 定义日志细节。冲突时以本文件为准。

***

## 1. 核心原则

| # | 原则 | 含义 |
|---|------|------|
| 1 | **早抛晚捕** | 在离错误源最近处抛出，在系统边界（`@RestControllerAdvice`）统一处理；中间层不拦截无能为力的异常 |
| 2 | **异常只用于异常情况** | 不用异常做正常流程控制；可预期的分支用返回值（`boolean` / `Optional`）表达 |
| 3 | **业务异常一律 unchecked** | `BusinessException extends RuntimeException`，与 Spring 事务「默认仅对 RuntimeException/Error 回滚」对齐 |
| 4 | **一次异常，一处日志** | 抛出点不打日志（异常携带上下文），处理点记录一次；禁止「log 完原样再抛」造成双重日志 |
| 5 | **消息分级** | 面向客户端的消息：安全、可懂、无内部细节；面向排查的细节：进日志（traceId + 业务上下文 + 堆栈） |

***

## 2. 异常分类与选型决策

| 场景 | 机制 | 最终 HTTP |
|------|------|-----------|
| 业务规则违反（已存在、状态非法、余额不足） | `BusinessException` + `ErrorCode` | 4xx（由 ErrorCode 决定） |
| 输入格式校验（非空、长度、格式） | Bean Validation 注解，不手写 if-throw（见 `validation.md`） | 400 |
| 资源不存在 | `BusinessException(ErrorCode.{ENTITY}_NOT_FOUND, id)`；**禁止裸 `Optional.get()`** | 404 |
| 编程错误 / 契约违反（参数为 null、非法内部状态） | `Assert` / `IllegalArgumentException` / `IllegalStateException`（见 `java-coding-standard.md` §4） | 500（兜底） |
| 下游调用失败 | 按 `downstream-conventions.md` §4 分类：降级返回 / 包装为 `BusinessException` 传播（保留 cause） | 由业务决策 |
| 基础设施故障 / 未预期异常（NPE、DB 连接断开） | **不捕获**，交全局兜底 | 500 |

**关键判断：** 「调用方能合理恢复吗？」——能恢复的不是异常（用返回值）；不能恢复但属于业务语义的是 `BusinessException`；属于编程失误的是 JDK 运行时异常，让它崩到兜底并修复代码。

### 2.1 业务异常表达策略（模式选型，项目级决策）

上表「业务规则违反」一行的异常形态，行业有两种成熟实践（另有常见折中形态 C，见下）：

| | 模式 A：类型化领域异常 | 模式 B：BusinessException + ErrorCode 枚举 |
|---|---|---|
| 形态 | 每个业务条件一个异常类（`{Entity}NotFoundException`），错误码常量随类声明 | 单一 `BusinessException`，全部错误收入 `ErrorCode` 枚举 |
| 行业代表 | Spring `DataAccessException` 层次、《Effective Java》异常翻译 | ABP 框架、阿里巴巴《Java 开发手册》「错误码集中管理」 |
| 优势 | 类型级 catch / 按类型路由（重试、补偿、降级分流）；异常类名即业务文档；业务术语进入类型系统 | 错误目录集中（枚举即错误清单）；统一单点 catch；新增错误零新增类 |
| 劣势 | 异常类随业务条件膨胀；错误目录分散在各异常类 | 无法按类型 catch（只能比对 code）；类型表达力弱，易演成错误码大杂烩 |

> **折中形态（模式 C，行业常见）：** 浅层次语义基类——按**处置类别**分 4~6 个（`NotFoundException` / `ConflictException` / `ValidationException` … extends `BusinessException`），组内用 `ErrorCode` 细化。catch 想粗就粗（按基类）、想细就细（比对 code），兼顾类型级分流与错误目录集中，适合中大型项目。

**本项目选型 = 模式 B：** `BusinessException` + `ErrorCode` 单体系（静态工厂 `rejected/virusDetected/scanFailed`，未引入模式 C 语义子类）；落点与消息分级见 `project-architecture.instructions.md` §4。

**纪律：** 选型在项目启动时确定并记入项目 agent 指南（`AGENTS.md`）；中途切换须全量迁移（业务代码 + 全局处理映射 + 测试断言）。**模式 A 与 B 禁止并存**；模式 C 是 B 的整体补充形态（同一基类层次内的粗/细两级 catch），采用后同样全项目统一。

**本 harness 参考实现采用模式 C（浅层语义基类）**：`BusinessException` + `ErrorCode` 枚举为主体，辅以按 HTTP 处置类别的语义子类 `NotFoundException`（404）/ `ConflictException`（409）（`application/exception/`）。体系结构见 §3。采用本 harness 的项目按上表判据选定模式并在各自 `AGENTS.md` 中记录。

***

## 3. 异常体系结构

### 3.1 单一基类 + 错误码枚举（禁止异常类爆炸）

全项目只有一个业务异常基类 `BusinessException` 和一个 `ErrorCode` 枚举（物理位置 `infrastructure/domain/`）。

**规则：**
- **新增业务错误 = 新增 `ErrorCode` 枚举值，而不是新增异常类**。禁止 `UserNotFoundException` / `OrderNotFoundException` × N 的类爆炸
- `ErrorCode` 三要素缺一不可：`code`（客户端可见的稳定标识）、`defaultMessage`（客户端安全的默认文案）、`httpStatus`（HTTP 映射）
- 错误码按模块编号分段：`{模块号}{错误序号}`（`000`=通用，`001`=用户，`002`=校验，`003`=文件上传……），模块号约定维护在枚举注释中
- 新增枚举值 checklist：code 未被占用 → message 客户端安全（无 SQL/堆栈/内部主机）→ httpStatus 符合 §6.2 映射语义

### 3.2 ErrorCode 枚举定义

```java
// infrastructure/domain/ErrorCode.java（示例值与参考实现一致；各项目以自己的 ErrorCode 为准）
@Getter @RequiredArgsConstructor
public enum ErrorCode {
    // 通用成功（固定 code，不参与模块分段）
    SUCCESS("SUCCESS", "Success", HttpStatus.OK),

    // 通用错误 (000xxx)
    INTERNAL_ERROR("000001", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    BAD_REQUEST("000002", "Bad request", HttpStatus.BAD_REQUEST),
    NOT_FOUND("000003", "Resource not found", HttpStatus.NOT_FOUND),
    DATA_CONFLICT("000004", "Data conflict — resource already exists or constraint violated", HttpStatus.CONFLICT),
    ACCESS_DENIED("000005", "Access denied", HttpStatus.FORBIDDEN),
    VALIDATION_ERROR("002001", "Validation failed", HttpStatus.BAD_REQUEST),

    // {模块}错误 ({模块号}xxx)
    USER_NOT_FOUND("001001", "User not found", HttpStatus.NOT_FOUND),
    USER_ALREADY_EXISTS("001002", "User already exists", HttpStatus.CONFLICT),
    VERSION_CONFLICT("001003", "Version conflict — resource was modified by another transaction", HttpStatus.CONFLICT),
    FILE_UPLOAD_FAILED("003001", "File upload failed", HttpStatus.INTERNAL_SERVER_ERROR),

    // 编码规则：{模块编号}{错误序号}
    // 000 = 通用, 001 = 用户, 002 = 校验, 003 = 文件上传, ...（模块号约定维护在枚举注释中）
    ;

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
```

### 3.3 BusinessException 规范

```java
// infrastructure/domain/BusinessException.java
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Object[] args;          // 业务上下文（id、字段值），供 handler 记日志

    public BusinessException(ErrorCode errorCode, Object... args) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.args = args;
    }

    // 包装底层异常时使用，必须保留 cause
    public BusinessException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
        this.args = args;
    }
}
```

**规则：**
- 必须携带 `ErrorCode`；禁止只传字符串消息（错误码是客户端的稳定契约，消息文案可随时改）
- `args` 携带排查所需业务上下文（实体 id、冲突字段值），**不得含密码、token 等敏感数据**
- 包装底层异常时必须用带 `cause` 的构造器（见 `java-coding-standard.md` §6.1 异常链）

**可选优化 —— 高频异常无堆栈构造：** 对于每秒可能抛出成千上万次、仅作结果信号的业务异常，可用 `super(msg, null, true, false)` 关闭堆栈填充（填堆栈是异常最昂贵的操作）。权衡：排查时无堆栈可依赖，只能靠 ErrorCode + args + 日志上下文定位。**仅对已被监控证实为热点的异常使用**，默认仍保留堆栈。

***

## 4. 抛出规范（Throwing）

### 4.1 在哪抛

| 层 | 规则 |
|----|------|
| Domain（实体方法 / Domain Service） | 业务不变式被违反时抛 `BusinessException` |
| Application（Service） | 编排中发现业务前置条件不满足时抛 `BusinessException`；用 `orElseThrow` 衔接 `Optional` |
| Infrastructure | 把技术异常（IOException、下游 4xx/5xx）**翻译**为 `BusinessException`（带 cause）或按契约降级返回；不把 `SQLException`、HTTP 客户端异常泄露到上层 |
| rest（Controller） | **禁止抛业务异常、禁止 try-catch 业务异常**；所有异常交给 `@RestControllerAdvice` |

### 4.2 怎么抛

```java
// GOOD: Optional 链式抛出，携带上下文
return repository.findById(id)
        .map(mapper::toResponse)
        .orElseThrow(() -> new BusinessException(ErrorCode.{ENTITY}_NOT_FOUND, id));

// GOOD: 业务规则校验失败
public void deactivate(String reason) {
    if (this.status == {Entity}Status.INACTIVE) {
        throw new BusinessException(ErrorCode.{ENTITY}_ALREADY_INACTIVE, this.id);
    }
    this.status = {Entity}Status.INACTIVE;
}

// GOOD: 翻译技术异常，保留 cause
} catch (IOException ex) {
    throw new BusinessException(ErrorCode.FILE_PROCESSING_FAILED, ex, fileName);
}

// BAD: 裸 Optional.get() —— NoSuchElementException 无语义
repository.findById(id).get();

// BAD: 裸 RuntimeException / Exception / Throwable —— 无错误码、无契约
throw new RuntimeException("user not found");

// BAD: return null / 返回 -1 表示失败 —— 把判空负担转嫁给每个调用方
return null;
```

### 4.3 禁止用异常做正常控制流

```java
// BAD: 用异常实现「是否存在」的常规查询
try {
    return load{Entity}(id);
} catch (BusinessException ex) {
    return default{Entity}();
}

// GOOD: 可预期分支用返回值表达
return repository.findById(id).map(mapper::toResponse).orElseGet(this::defaultResponse);
```

异常构造（填堆栈）比正常返回贵几个数量级，且让「正常流程」与「故障」在监控/日志中无法区分。

***

## 5. 捕获规范（Catching）

### 5.1 只捕获能处理的异常

catch 之后只有三种合法出路：**恢复**（降级返回默认值）、**转换重抛**（翻译为业务异常，带 cause）、**清理后重抛**（关资源/恢复状态后继续抛）。做不到其中任何一条，就不该 catch。

```java
// GOOD: 降级恢复（瞬态下游故障，服务可容忍）
} catch (ResourceAccessException ex) {
    log.warn("Downstream {service} unreachable, degrade, key: {}", event.key());
    return false;
}

// GOOD: 翻译重抛（基础设施异常 → 业务语义，保留 cause）
} catch (JsonProcessingException ex) {
    throw new BusinessException(ErrorCode.PAYLOAD_PARSE_FAILED, ex, source);
}

// BAD: 空 catch —— 绝对禁止
} catch (Exception ex) {
}

// BAD: 只打印不处理 —— 等价于吞异常
} catch (Exception ex) {
    log.error("error", ex);
}

// BAD: log 完原样再抛 —— 同一异常在日志出现两次，污染告警
} catch (IOException ex) {
    log.error("read failed", ex);
    throw ex;
}
```

### 5.2 捕获边界规则

- **catch 子句具体化**：`catch (IOException ex)`，禁止业务代码中 `catch (Exception | RuntimeException | Throwable)` 兜底（`@RestControllerAdvice` 除外）
- **禁止 catch `Error` / `Throwable`**：`OutOfMemoryError` 等 Error 不可恢复，捕获只会延迟死亡
- **确实要忽略异常时**：catch 块内注释说明原因 + `log.debug`，例：关闭资源时的次要异常
- **`InterruptedException`**：不向上抛时必须恢复中断标志 `Thread.currentThread().interrupt()`，禁止吞掉（线程池任务取消依赖中断信号）
- **multi-catch**：`catch (A | B ex)` 仅当 A、B 处理逻辑确实相同
- **`finally` 不 return、不抛异常**：会吞掉 try 块中的原异常；资源关闭优先用 try-with-resources

### 5.3 事务与异常（WebMVC 项目高频坑）

- `@Transactional` **默认仅对 RuntimeException/Error 回滚**；checked exception 需要回滚时显式 `@Transactional(rollbackFor = Exception.class)`（见 `service-conventions.md` §1）
- **rollback-only 陷阱**：`@Transactional` 方法内 catch 了 RuntimeException 但未重抛，事务已被标记 rollback-only，提交时抛 `UnexpectedRollbackException`：

```java
// BAD: catch 掉异常后正常返回 → 提交时 UnexpectedRollbackException
@Transactional
public void process(Long id) {
    try {
        innerService.save(id);   // 内部抛 RuntimeException
    } catch (BusinessException ex) {
        log.warn("skip: {}", id);   // 事务已 rollback-only，方法返回后提交即炸
    }
}

// GOOD 方式一: 让异常传播（外层不 catch）
// GOOD 方式二: 内层方法用 REQUIRES_NEW 独立事务，内层回滚不影响外层
```

- 事务方法内**不做下游 HTTP 调用**（`service-conventions.md` §2），也就无需在事务内处理下游异常——下游异常处理只发生在事件监听器（事务外）

***

## 6. 全局异常处理（WebMVC 边界）

### 6.1 单一 `@RestControllerAdvice`

- 全项目**唯一** `GlobalExceptionHandler`（`infrastructure/rest/`），所有 Controller 异常在此收敛
- 所有 handler 返回 `ResponseEntity<ApiResponse<Void>>`，响应头携带 `X-Trace-Id`（与 MDC 中 traceId 一致，见 `logging.md`）
- Controller 不写 try-catch，不返回裸 `ResponseEntity<String>` 错误体
- handler 方法自身必须**防御性**（不抛异常）：对 `rejectedValue`、`ex.getMessage()` 等可能为 null 的值先判空

### 6.2 异常 → 响应映射矩阵

| 异常 | HTTP | ErrorCode | 日志 | 说明 |
|------|------|-----------|------|------|
| `BusinessException` | 由 `ErrorCode.httpStatus` 决定 | `code` | WARN | 客户端消息用 ErrorCode 文案 |
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` | WARN | `@RequestBody @Valid` 失败；FieldError 的 `rejectedValue` 对敏感字段脱敏为 `***` |
| `HandlerMethodValidationException` | 400 | `VALIDATION_ERROR` | WARN | **SF 6.1+ 关键变化**：Controller 参数直接注解（类级无 `@Validated`）走内建方法校验，抛此异常而非 `ConstraintViolationException` |
| `ConstraintViolationException` | 400 | `VALIDATION_ERROR` | WARN | 手动调用 `Validator.validate()` 的编程式校验、旧 AOP 链路残留——出现即甄别是否应迁入内建链路，而非原样保留 |
| `MethodValidationException` | 400 | `VALIDATION_ERROR` | WARN | **SF 6.1+**：Service Bean 方法级校验（类级 `@Validated` + `MethodValidationPostProcessor` AOP 链路）失败；多为内部前置条件违例，**不逐字段对外暴露**（避免泄漏内部 API 形态），明细只记日志 |
| `BindException` | 400 | `VALIDATION_ERROR` | WARN | `@ModelAttribute` 表单绑定校验失败（区别于 `@RequestBody` 的 `MethodArgumentNotValidException`，须分别声明）。注意：参考实现当前未单独声明本 handler（`MethodArgumentNotValidException` 是其子类，`@RequestBody` 场景已覆盖）；启用表单绑定的项目须先补此 handler |
| `MissingServletRequestParameterException` | 400 | `BAD_REQUEST` | WARN | 缺少必填请求参数 |
| `MethodArgumentTypeMismatchException` | 400 | `BAD_REQUEST` | WARN | 路径/查询参数类型不匹配（如 id 传了非数字）；客户端消息含参数名即可，不回显原始值 |
| `HttpMessageNotReadableException` | 400 | `BAD_REQUEST` | WARN | JSON 语法错误、枚举值非法、body 缺失 |
| `HttpRequestMethodNotSupportedException` | 405 | `BAD_REQUEST` | WARN | 方法不支持（如 POST 打到只支持 GET 的路径） |
| `HttpMediaTypeNotSupportedException` | 415 | `BAD_REQUEST` | WARN | Content-Type 不支持 |
| `NoResourceFoundException` | 404 | `NOT_FOUND` | WARN | **SF 6.1+**：无匹配路由时的默认行为（取代旧的 `NoHandlerFoundException` 配置开关） |
| `MaxUploadSizeExceededException` | 413 | `BAD_REQUEST` | WARN | 上传超限 |
| `AccessDeniedException` | 403 | `ACCESS_DENIED` | WARN | **必须显式处理**，见 §6.3 陷阱 |
| `DataIntegrityViolationException` | 409 | 通用冲突码 | WARN | 唯一键冲突等；**禁止映射为实体特定错误码**（`architecture.md` §8 反模式 #6），实体语义在 Service 层先校验 |
| `OptimisticLockingFailureException` | 409 | `VERSION_CONFLICT` | WARN | Service 层已显式转换时的兜底（JPA flush 直接抛出的情况） |
| `Exception`（兜底） | 500 | `INTERNAL_ERROR` | **ERROR + 完整堆栈** | 固定通用文案，**绝不回显 `ex.getMessage()`** |

**规则：**
- 4xx 记 WARN（客户端问题，非系统故障，不附堆栈）；兜底 500 记 ERROR 且必须附完整异常对象（最后一个参数）
- 同一异常类型只定义一个 `@ExceptionHandler`（重复定义启动即报错）；具体异常与兜底 `Exception` 的优先级由 Spring 自动解析（最具体优先），无需手工排序
- Spring MVC 内置异常均实现 `ErrorResponse` 接口，可从其 `getStatusCode()` 取状态，但映射必须以本表为准保持全项目一致

### 6.3 Spring Security 异常陷阱（必须处理）

`@PreAuthorize` 等方法安全注解在 DispatcherServlet **内部**抛 `AccessDeniedException`——它会被 `@RestControllerAdvice` 捕获。如果只有兜底 `Exception` handler，权限拒绝会被错误地返回为 500。

```java
// 必须显式声明，否则 403 变 500
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
        AccessDeniedException ex, HttpServletRequest request) {
    String traceId = generateTraceId(request);
    log.warn("[{}] Access denied: {} {}", traceId, request.getMethod(), request.getRequestURI());
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .header(TRACE_ID_HEADER, traceId)
            .body(ApiResponse.error(ErrorCode.ACCESS_DENIED, "Access denied", traceId));
}
```

注意区分：Filter 链中的认证/授权失败（未登录、token 无效）**不经过** `@RestControllerAdvice`，由 Security 的 `AuthenticationEntryPoint`（401）/ `AccessDeniedHandler`（403）处理——见 §7.2。

### 6.4 ProblemDetail（RFC 9457）取舍

Spring Framework 6+ 原生支持 RFC 9457/7807 `ProblemDetail` 错误格式（`spring.mvc.problemdetails.enabled=true`，或继承 `ResponseEntityExceptionHandler`）。**项目统一响应信封二选一，禁止混用：**

- 已有 `ApiResponse<T>` 信封的项目 → 继续使用本规范的 `@RestControllerAdvice` 方案，不启用 ProblemDetail
- 全新项目且团队认可 RFC 9457 → 可整体采用 ProblemDetail，替换 `ApiResponse` 错误分支

混用会导致同一 API 的错误体有两种结构，客户端无法编写统一的错误处理逻辑。

### 6.5 生产环境 `server.error` 配置

`@RestControllerAdvice` 之外的异常（Filter 抛出、Servlet 容器层错误）会落到 Spring Boot 默认 `/error` 端点，生产环境必须关闭信息泄露：

```yaml
server:
  error:
    include-message: never            # 不回显异常消息
    include-stacktrace: never         # 不返回堆栈
    include-binding-errors: never     # 不回显绑定错误详情
```

***

## 7. WebMVC 边界之外的异常

`@RestControllerAdvice` 只覆盖 DispatcherServlet 内（Controller 及之后）抛出的异常。以下位置各有处置责任：

### 7.1 Filter / Interceptor

Filter 在 DispatcherServlet **之前**执行，其异常不被 advice 捕获。两种处理：

```java
// 方式一（推荐）：Filter 内自处理，自行写出 JSON 错误响应
} catch (Exception ex) {
    log.error("Filter error: {}", ex.getMessage(), ex);
    response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(jsonMapper.writeValueAsString(
            ApiResponse.error(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", traceId)));
}

// 方式二：委托给 HandlerExceptionResolver（复用 advice 逻辑）
// 构造器注入 @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver;
} catch (Exception ex) {
    resolver.resolveException(request, response, null, ex);
}
```

Interceptor 的 `preHandle` 抛出的异常**会**经过 DispatcherServlet 的异常解析（advice 可捕获），但 Filter 不会——不要混淆两者。

### 7.2 Spring Security 入口点

认证失败（401）与授权失败（403）发生在 Filter 链，通过配置入口点输出与 `ApiResponse` 一致的错误体：

```java
// SecurityConfig 中
.exceptionHandling(handling -> handling
        .authenticationEntryPoint((request, response, authException) ->
                writeErrorResponse(response, 401, ErrorCode.UNAUTHORIZED))     // 未认证
        .accessDeniedHandler((request, response, accessDeniedException) ->
                writeErrorResponse(response, 403, ErrorCode.ACCESS_DENIED)))   // 已认证但无权限
```

### 7.3 `@Async` / 定时任务 / 事件监听器

| 场景 | 陷阱 | 规则 |
|------|------|------|
| `@Async` void 方法 | 异常**静默丢失**（无调用方可接收） | 实现 `AsyncConfigurer#getAsyncUncaughtExceptionHandler`，在 handler 中 ERROR 记录；或有返回值的 `Future`/`CompletableFuture` 在 `get()`/`join()` 时检查异常 |
| `@Scheduled` | 异常中断本次执行，无客户端 | 方法体内 catch + ERROR 日志（+ 告警），**不外抛**（外抛只打印到容器日志，无结构化上下文） |
| `@TransactionalEventListener(AFTER_COMMIT)` | 异常不影响已提交事务，但会打 ERROR 噪音 | listener 内自行捕获，按业务决定重试/告警/丢弃 |

***

## 8. 日志与异常的配合（衔接 `logging.md`）

- **WARN**：可预期异常（业务异常、4xx 客户端错误）——消息含 traceId + 错误码 + 关键业务上下文，**不附异常对象**（堆栈对已知问题无信息量）
- **ERROR**：未预期异常（兜底 500、`@Async`/`@Scheduled` 未捕获）——必须附完整异常对象为最后一个参数（输出堆栈）
- 一处记录：抛出点不记，转换点不记（新异常带 cause），只有最终处理点记
- 脱敏：FieldError `rejectedValue`、日志参数中的敏感字段按 `logging.md` 的 `MaskUtils` 处理

***

## 9. 反模式（禁止）

| # | 反模式 | 后果 | 正确做法 |
|---|-------|------|---------|
| 1 | 空 catch / 只打印不重抛 | 故障静默，排查无门 | 恢复 / 转换重抛 / 不 catch |
| 2 | log 后原样重抛 | 双重日志、告警风暴 | 只在最终处理点记录一次 |
| 3 | 用异常做正常控制流 | 性能损耗、监控噪音 | 可预期分支用返回值 |
| 4 | 抛裸 `RuntimeException`/`Exception` | 无错误码契约，客户端无法区分错误 | `BusinessException` + `ErrorCode` |
| 5 | 每实体一个异常类 | 类爆炸，handler 无限膨胀 | 单一基类 + 枚举错误码 |
| 6 | Controller try-catch 业务异常自拼响应 | 错误格式不统一 | 交 `@RestControllerAdvice` |
| 7 | 兜底 handler 回显 `ex.getMessage()` | 泄露 SQL、内部主机、堆栈信息 | 固定通用文案 + 日志记详情 |
| 8 | `DataIntegrityViolation` → 实体特定错误码 | 新增实体后返回张冠李戴的错误码 | 通用冲突码；实体语义在 Service 先校验 |
| 9 | 事务内 catch 后正常返回 | `UnexpectedRollbackException` | 传播异常或内层 `REQUIRES_NEW` |
| 10 | 吞掉 `InterruptedException` | 线程池无法取消任务 | 恢复中断标志或向上抛 |
| 11 | `@Async` void 无 UncaughtExceptionHandler | 异步异常静默丢失 | 配置 handler + ERROR 日志 |
| 12 | 忘了 `AccessDeniedException` handler | 403 被兜底成 500 | 显式声明 403 handler |
| 13 | 包装异常丢失 cause | 根因链断裂 | 用带 cause 的构造器 |
| 14 | `finally` 中 return / 抛异常 | 吞掉 try 块原异常 | finally 只做清理；try-with-resources 优先 |
| 15 | 错误消息硬编码在抛出点各处 | 文案散落、无法统一维护 | 文案集中在 `ErrorCode` 枚举 |

***

## 10. Code Review Checklist

- [ ] 业务错误是否全部通过 `BusinessException` + `ErrorCode` 表达，无裸 `RuntimeException`？
- [ ] 新增错误是否只新增 `ErrorCode` 枚举值（而非新异常类）？code 是否按模块分段？
- [ ] 是否存在空 catch、只打印不处理、log 后原样重抛？
- [ ] 包装异常是否保留 cause？
- [ ] Controller 是否零 try-catch，异常全部交全局 advice？
- [ ] 全局 advice 是否覆盖 §6.2 矩阵（含 `HandlerMethodValidationException`、`NoResourceFoundException`、`AccessDeniedException`）？
- [ ] 兜底 500 是否固定文案、ERROR 级附完整堆栈、不回显异常消息？
- [ ] 事务方法内是否有 catch 后正常返回（rollback-only 风险）？checked exception 是否需要 `rollbackFor`？
- [ ] `@Async` / `@Scheduled` 是否有异常处置（UncaughtExceptionHandler / 方法内 catch）？
- [ ] Filter / Security 入口点异常是否输出与 `ApiResponse` 一致的错误体？
- [ ] 错误响应与日志中的敏感字段是否脱敏？
- [ ] 错误场景是否有对应 API 测试（400/404/409/422，见 `tdd-workflow.md` "Done" 标准）？
