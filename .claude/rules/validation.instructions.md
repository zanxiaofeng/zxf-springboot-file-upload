---
name: "Validation & Contract Programming"
description: "Declarative Bean Validation (preferred) and imperative contract programming — priority, annotations, groups, custom validators, @ConfigurationProperties, assertions, null-safety"
paths:
  - "**/*.java"
---

# 参数校验规范（声明式 + 命令式）

**适用范围：** JDK 21 + Spring Boot 4.1 + Jakarta Validation 3.1

> **示例约定：** 下文示例的校验消息为行文简洁使用字面量；正式代码必须使用消息键（如 `{user.username.notBlank}`）配合 `ValidationMessages.properties`（见 §2.10）。

> **职责边界：** 本文件定义参数校验的**完整规范**——声明式 Bean Validation（Controller / Service / ConfigurationProperties）和命令式断言（Domain Entity / VO / 内部不变式）。全局异常处理与业务异常表达模式选型（§2.1）见 `exception-handling.md`，Controller 层 `@PathVariable` / 分页参数规范见 `api-conventions.md`。判空坏味道识别（NC 规则表）与存量代码改造执行流程见 `null-check-governance.md`。

***

## 1. 校验策略总则

### 1.1 优先级：声明式 > 命令式

在 Spring Boot 项目中，校验手段分为两大类：

| 类别 | 机制 | 特点 |
|------|------|------|
| **声明式校验** | Bean Validation 注解（`@NotBlank`、`@Positive`、`@Size`…） | 框架自动触发，注解即文档，零样板代码 |
| **命令式校验** | `Assert` / `Preconditions` / `Objects.requireNonNull` | 手动编写校验逻辑，精确控制，适用于不受框架管理的对象 |

**核心原则：当约束可以通过 Bean Validation 注解声明式表达时，优先使用声明式校验，而非命令式断言。**

命令式断言（`Assert` / `Preconditions`）应保留给 Bean Validation 无法覆盖的场景：Domain 层 Entity / Value Object 构造器与领域方法（不受 Spring 管理）、内部不变式与后置条件。

**反模式 — 在 `@ConfigurationProperties` record 的 compact constructor 中用 `Assert` 逐条校验：**

```java
// BAD — 注解能做的事不应手动写
@ConfigurationProperties(prefix = "{feature}")
public record {Feature}Properties(
        String host,
        int port,
        int timeoutSeconds,
        int maxPoolSize,
        int maxConnections
) {
    public {Feature}Properties {
        Assert.hasText(host, "{feature}.host must not be blank");
        Assert.isTrue(port > 0, "{feature}.port must be positive");
        Assert.isTrue(timeoutSeconds > 0, "{feature}.timeout-seconds must be positive");
        Assert.isTrue(maxConnections <= maxPoolSize,
                "{feature}.max-connections must be <= {feature}.max-pool-size");
    }
}
```

```java
// GOOD — 声明式注解，Spring 绑定后自动校验
@Validated
@ConfigurationProperties(prefix = "{feature}")
public record {Feature}Properties(
        @NotBlank(message = "{feature}.host must not be blank")
        String host,

        @Positive(message = "{feature}.port must be positive")
        int port,

        @Positive(message = "{feature}.timeout-seconds must be positive")
        int timeoutSeconds,

        int maxPoolSize,  // 跨字段约束用 @AssertTrue 方法，见下方

        @Positive(message = "{feature}.max-connections must be positive")
        int maxConnections
) {
    @AssertTrue(message = "{feature}.max-connections must not exceed {feature}.max-pool-size")
    public boolean isMaxConnectionsValid() {
        return maxConnections <= maxPoolSize;
    }
}
```

### 1.2 分层验证职责

| 层 | 验证方式 | 验证内容 |
|----|---------|---------|
| Controller | `@Valid` / `@Validated` | 格式验证（非空、长度、格式、正则） |
| Service | `@Validated` + Bean Validation；依赖外部状态的语义校验用 `if + BusinessException` | 业务验证（存在性、状态、权限） |
| Entity | `@PrePersist` / `@PreUpdate` | 不变式（数据一致性约束） |
| Configuration | `@ConfigurationProperties` + `@Validated` | 配置属性约束（必填、范围、格式），启动时 Fail Fast |

### 1.3 适用场景判断

**判断标准：校验目标是否被 Spring 管理（是 Bean、受 `@Validated` / `@Valid` 触发）？**

| 场景 | 优先方式 | 原因 |
|------|---------|------|
| `@ConfigurationProperties` 类/record | `@Validated` + 字段注解 | Spring 绑定后自动校验，启动 Fail Fast |
| Command / Query / Representation record | 字段 Bean Validation 注解 | Controller `@Valid` 自动触发（请求侧 Command/Query） |
| Service 方法参数（Bean 类型） | `@Validated`（类级）+ `@Valid`（参数） | 框架 AOP 代理自动校验 |
| Service 用例中的语义校验（唯一性、存在性、状态前置条件 — 需查库或下游） | 用例代码 `if + BusinessException`（见 `exception-handling.md` §4.2） | 语义校验依赖外部状态，注解约束必须自包含、无副作用；业务拒绝抛 `BusinessException`（4xx），区别于编程契约违反（`IllegalArgumentException` → 500） |
| Domain Entity / Value Object | `Assert` / `Objects.requireNonNull` | 非 Spring 管理，Bean Validation 不触发 |
| 内部不变式 / 后置条件 | `assert` 或 `Assert.state` | 代码内部逻辑假设 |

### 1.4 核心原则

**契约即代码：** 方法的契约应当通过代码显式表达，而非仅依赖注释。通过"快失败"（Fail Fast）原则，尽早暴露错误，降低调试成本。

**区分输入校验与内部断言：**

| 场景 | 机制 | 用途 |
|------|------|------|
| 外部输入 | Bean Validation / `Preconditions` / `Objects.requireNonNull` | 参数、配置、外部系统返回的校验 |
| 内部假设 | `assert` | 验证代码逻辑假设、不变式、后置条件 |

**明确异常类型 —** 违反契约应抛出**非受检异常**：

| 异常类型 | 使用场景 |
|----------|----------|
| `IllegalArgumentException` | 参数值不合法 |
| `IllegalStateException` | 对象状态不正确 |
| `NullPointerException` | 参数或状态为 null（优先使用 `Objects.requireNonNull`） |
| `IndexOutOfBoundsException` | 索引越界 |

**信息充分原则 —** 异常信息必须包含：参数/状态名称、预期的约束条件、实际值（若安全且有助于排查）。

### 1.5 命令式校验工具选择优先级

当场景需要命令式校验（Domain 层、不受 Spring 管理的对象）时：

| 优先级 | 工具 | 适用场景 |
|--------|------|----------|
| 1 | `Objects.requireNonNull()` | JDK 原生，仅非空校验 |
| 2 | `org.springframework.util.Assert` | Spring Boot 项目首选，零额外依赖 |
| 3 | `Preconditions` (Guava) | 非 Spring 项目或复杂校验场景 |
| 4 | 自建工具类 | 以上均不可用时 |

***

## 2. 声明式校验 — Bean Validation

### 2.0 技术栈分层与职责

声明式校验由五层技术协作完成，每层各司其职：

| 技术 | 提供 | 不提供 |
|------|------|--------|
| **JDK** | 注解语法、反射、代理等基础能力 | Validation 规范 / 注解 / 引擎 |
| **Jakarta Validation**（规范 / API） | `@NotNull` / `@Valid`、`Validator` 接口、校验模型 | 真正执行校验的实现 |
| **Hibernate Validator**（实现 / 引擎） | Jakarta Validation 的实现（实际校验逻辑） | — |
| **Spring Framework** | 与 Web、AOP、数据绑定的集成；在合适时机调用 `Validator` 执行校验，并把错误转换成 Spring 异常 / 响应 | — |
| **Spring Boot** | 自动配置与默认依赖管理（starter）；「加依赖就能跑」，少写配置 | — |

> **理解要点：** 注解声明（Jakarta Validation）与执行引擎（Hibernate Validator）分离；Spring Framework 决定「何时校验、错误去哪」，Spring Boot 决定「零配置开箱即用」。

### 2.1 Controller 层参数验证

#### 请求体 `@RequestBody`

```java
@PostMapping
public ResponseEntity<ApiResponse<Long>> create(@RequestBody @Valid UserCreateDTO dto) {
    return ResponseEntity.ok(ApiResponse.success(userService.create(dto)));
}
```

#### 路径变量 `@PathVariable`

```java
@GetMapping("/{id}")
public ResponseEntity<ApiResponse<UserVO>> getById(
        @PathVariable @Positive(message = "ID必须为正数") Long id) { ... }
```

> `@PathVariable` 的 `@Positive` 校验规范详见 `api-conventions.md` @PathVariable Validation。

#### 查询参数 `@RequestParam`

```java
// 返回类型统一用 Spring Data Page(与 api-conventions / ApiResponse 一致);
// 分页查询应继承 PageableQuery 绑定 page/size(见 api-conventions.md Pagination),
// 此处用 @RequestParam 仅示例 @Min/@Max 约束写法
@GetMapping
public ResponseEntity<ApiResponse<Page<UserVO>>> list(
        @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
        @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) { ... }
```

#### 查询对象（GET 查询参数绑定）

分页查询继承 `infrastructure/application/PageableQuery`（getter/setter 供 GET 参数绑定，page/size 详见 `api-conventions.md` Pagination）；其余筛选字段用普通查询对象承载：

```java
public class UserListQuery extends PageableQuery {   // page/size 由基类提供并 clamp
    @Pattern(regexp = "^[a-zA-Z0-9_]*$") private String username;
    private LocalDate startDate;
    private LocalDate endDate;

    // 跨字段验证
    @AssertTrue(message = "{user.query.dateRange.invalid}")
    public boolean isDateRangeValid() {
        if (startDate == null || endDate == null) return true;
        return !endDate.isBefore(startDate);
    }
}
```

### 2.2 DTO 字段验证注解速查表

#### Jakarta Validation 标准（`jakarta.validation.constraints`）

| 类别 | 注解 | 用途 | 示例 |
|------|------|------|------|
| 空值 | `@Null` | 必须为 null（创建时禁止指定字段） | `@Null(groups = Create.class) Long id` |
| | `@NotNull` | 值不为 null | `@NotNull Integer age` |
| 非空 | `@NotBlank` | 字符串非空白（trim 后非空） | `@NotBlank String username` |
| | `@NotEmpty` | 集合/数组/Map/字符串非空（非 null 且 size > 0） | `@NotEmpty List<String> tags` |
| 长度 | `@Size` | 长度/大小范围（CharSequence / Collection / Map / Array） | `@Size(min = 3, max = 20)` |
| 数值 | `@Min` / `@Max` | 整数范围 | `@Min(0) @Max(150)` |
| | `@DecimalMin` / `@DecimalMax` | 小数范围（支持字符串表示，`inclusive` 控制边界） | `@DecimalMin("0.00")` |
| | `@Digits` | 数字精度（整数位 + 小数位） | `@Digits(integer = 9, fraction = 2)` |
| | `@Positive` / `@PositiveOrZero` | 正数 / 非负数 | `@Positive Long id` |
| | `@Negative` / `@NegativeOrZero` | 负数 / 非正数 | `@Negative BigDecimal balance` |
| | `@Range`(HV) | `@Min` + `@Max` 组合简写（见下表） | — |
| 格式 | `@Pattern` | 正则匹配 | `@Pattern(regexp = "^1[3-9]\\d{9}$")` |
| | `@Email` | 邮箱格式 | `@Email String email` |
| 时间 | `@Past` / `@PastOrPresent` | 必须在过去 / 过去或当前 | `@Past LocalDate birthday` |
| | `@Future` / `@FutureOrPresent` | 必须在未来 / 未来或当前 | `@Future LocalDate expiryDate` |
| 布尔 | `@AssertTrue` / `@AssertFalse` | 布尔断言（可用于跨字段校验方法） | `@AssertTrue Boolean agreed` |
| 级联 / 触发 | `@Valid` | 级联验证嵌套对象（`jakarta.validation.Valid`） | `@Valid AddressDTO address` |
| | `@Validated` | 触发方法级验证 / 指定验证组 / 触发 `@ConfigurationProperties` 验证（Spring，`org.springframework.validation.annotation.Validated`） | `@Validated(Create.class)` |

#### Hibernate Validator 附加（`org.hibernate.validator.constraints` / `...constraints.time`）

> 以下注解由 Hibernate Validator（`spring-boot-starter-validation` 默认包含的 Jakarta Validation 参考实现）提供，非 Jakarta 标准，但在 Spring Boot 项目中开箱即用。

| 类别 | 注解 | 用途 | 示例 |
|------|------|------|------|
| 字符串长度 | `@Length` | 字符串长度范围（`@Size` 的 String 专用版，历史更久） | `@Length(min = 3, max = 20) String name` |
| 数值范围 | `@Range` | `@Min` + `@Max` 组合简写 | `@Range(min = 0, max = 150) Integer age` |
| Duration | `@DurationMin` | `Duration` 最小值（`days/hours/minutes/seconds/millis/nanos` 求和，`inclusive` 控制边界） | `@DurationMin(seconds = 1) Duration timeout` |
| | `@DurationMax` | `Duration` 最大值（参数同上） | `@DurationMax(seconds = 60) Duration timeout` |
| 格式验证 | `@URL` | URL 格式验证（可指定 `protocol` / `host` / `port` / `regexp`） | `@URL(protocol = "https") String homepage` |
| | `@UUID` | UUID 格式验证（可限定 `version` / 允许 `nil` / 允许空） | `@UUID String uuid` |
| | `@CreditCardNumber` | 信用卡号（Luhn 算法，`ignoreNonDigitCharacters` 跳过非数字字符） | `@CreditCardNumber String cardNumber` |
| | `@ISBN` | ISBN 格式（`type = ISBN_10` / `ISBN_13`） | `@ISBN String isbn` |
| | `@EAN` | EAN 条形码（`type = EAN8` / `EAN13`） | `@EAN String ean` |
| 集合 | `@UniqueElements` | 集合元素唯一性 | `@UniqueElements List<String> codes` |
| Unicode | `@CodePointLength` | Unicode 代码点长度（`@Size` 按 `char` 计数，emoji 会误算） | `@CodePointLength(max = 10) String name` |
| | `@Normalized` | Unicode 规范化形式校验（`form = NFC` / `NFD` / `NFKC` / `NFKD`） | `@Normalized String text` |
| 货币 | `@Currency` | `MonetaryAmount` 的币种限制 | `@Currency("CNY") MonetaryAmount price` |
| 组合 | `@ConstraintComposition` | 多约束的组合逻辑（`AND`（默认）/ `OR`），用于自定义注解中 | `@ConstraintComposition(OR)` |
| Bean 级 | `@ScriptAssert` | 脚本表达式跨字段校验（`lang` + `script`，`alias` 默认 `_this`） | `@ScriptAssert(lang = "groovy", script = "...")` |

### 2.3 `@Valid` vs `@Validated`

| 维度 | `@Valid` | `@Validated` |
|------|----------|-------------|
| 来源 | Jakarta 标准（`jakarta.validation.Valid`） | Spring（`org.springframework.validation.annotation.Validated`） |
| 核心用途 | **级联验证**嵌套对象的字段约束 | **触发**方法级验证 / **指定验证组** / 触发 `@ConfigurationProperties` 验证 |
| 验证组 | 不支持 | `@Validated(Create.class)` |
| 可标注位置 | 字段、方法参数、方法返回值、容器元素（`List<@Valid User>`）、TYPE_USE | 类（TYPE）、方法（METHOD）、参数（PARAMETER）、ANNOTATION_TYPE |
| 嵌套级联 | `@Valid AddressDTO address` | 不触发嵌套级联 |
| 方法级验证 | 不支持 | 加在类上触发 `MethodValidationPostProcessor` AOP 代理，对方法参数 / 返回值约束生效 |
| `@ConfigurationProperties` | 不支持 | 类上加 `@Validated` 触发属性绑定后验证（见 §2.8） |

**使用场景速查：**

| 场景 | 使用 | 示例 |
|------|------|------|
| Controller `@RequestBody` | `@Valid` 或 `@Validated` | `create(@RequestBody @Valid UserDTO dto)` |
| Controller 分组验证 | `@Validated(Group.class)` | `create(@RequestBody @Validated(Create.class) UserDTO dto)` |
| 嵌套对象级联验证 | `@Valid` | `@Valid @NotNull AddressDTO address` |
| Service 方法参数验证 | `@Validated`（类级） | `@Validated public class OrderService { ... }` |
| `@ConfigurationProperties` 验证 | `@Validated`（类级） | `@Validated @ConfigurationProperties(...) class Props { ... }` |

> **关键区别：** `@Valid` 做的是「钻进去验证嵌套对象的字段」，`@Validated` 做的是「在这个类 / 参数上激活验证机制（含分组）」。两者常配合使用——`@Validated` 激活 + 指定分组，`@Valid` 级联到嵌套 DTO。

### 2.4 字段验证示例

```java
public record UserCreateDTO(
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度3-20")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "用户名必须字母开头")
    String username,

    @NotBlank @Email(message = "邮箱格式不正确") @Size(max = 100)
    String email,

    @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    String phone,

    @NotNull @Min(0) @Max(150)
    Integer age,

    @NotNull @DecimalMin("0.00") @Digits(integer = 9, fraction = 2)
    BigDecimal balance,

    @Valid @NotNull  // 级联验证嵌套对象
    AddressDTO address
) {}
```

#### 列表/集合元素验证

```java
@Data
public class BatchCreateRequest {
    @NotEmpty @Size(max = 100)
    @Valid  // 级联验证列表元素
    private List<@NotNull UserCreateDTO> users;
}
```

#### Map 验证

```java
@Data
public class ConfigUpdateRequest {
    @NotEmpty
    private Map<@NotBlank String, @NotNull String> configs;
}
```

### 2.5 验证组（分组验证）

```java
// 组定义
public interface ValidationGroups {
    interface Create {}
    interface Update {}
}

// DTO 使用分组
public record UserDTO(
    @Null(groups = Create.class, message = "创建时不能指定ID")
    @NotNull(groups = Update.class, message = "更新时ID不能为空")
    Long id,

    @NotBlank(groups = {Create.class, Update.class})
    @Size(min = 3, max = 20, groups = {Create.class, Update.class})
    String username,

    @NotBlank(groups = Create.class)  // 仅创建时必填
    String password
) {}

// Controller 使用分组
@PostMapping
public ResponseEntity<ApiResponse<Long>> create(
        @RequestBody @Validated(ValidationGroups.Create.class) UserDTO dto) { ... }

@PutMapping("/{id}")
public ApiResponse<Void> update(
        @RequestBody @Validated(ValidationGroups.Update.class) UserDTO dto) { ... }
```

### 2.6 自定义验证注解

#### 手机号验证（模板）

```java
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PhoneValidator.class)
@Documented
public @interface Phone {
    String message() default "手机号格式不正确";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class PhoneValidator implements ConstraintValidator<Phone, String> {
    private static final Pattern PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        return value == null || PATTERN.matcher(value).matches();
    }
}
```

#### 枚举值验证

```java
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = EnumValueValidator.class)
@Documented
public @interface EnumValue {
    String message() default "枚举值不合法";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    Class<? extends Enum<?>> enumClass();
}
```

#### 自定义验证规则

- 验证器中 `null` 值返回 `true`（让 `@NotNull`/`@NotBlank` 处理空值）
- 需要多条错误信息时，用 `context.disableDefaultConstraintViolation()` + `buildConstraintViolationWithTemplate()`

### 2.7 方法级验证

> **Spring Boot 4 已自动配置 `MethodValidationPostProcessor`**(`spring-boot-starter-validation` 提供),**无需手动声明该 Bean**。Service 类加 `@Validated` 即触发方法级参数校验。

如确需开启**返回值校验**,应**按需**配置(非 `setValidateReturnedValue(true)` 全局默认 —— 会"惊喜地"对所有 Bean 返回值校验),在具体场景显式启用。

> **重要：** 仅配置 `MethodValidationPostProcessor` 不够。Service **类**必须添加 `@Validated` 注解，方法级约束才会生效。未加 `@Validated` 的类，其 `@NotNull` 参数注解会被静默忽略。
>
> `setValidateReturnedValue(true)` 全局启用返回值校验，可能影响所有 Bean。如果只需参数校验，可省略此设置。

Service 使用：

```java
// 具体类（SF 6.1+ 方法级校验支持 class 代理，无需接口）
@Validated
public class OrderService {
    public @NotNull OrderVO getOrder(@NotNull Long orderId) { ... }
    public @NotEmpty List<@NotNull OrderVO> getUserOrders(@NotNull Long userId) { ... }
}
```

### 2.8 `@ConfigurationProperties` 验证

**核心机制：** `@ConfigurationProperties` 类加 `@Validated`，字段加 Bean Validation 注解 —— 配置非法时**应用启动即失败**（Fail Fast），而非运行时抛 NPE / 连接超时等难以定位的错误。

> **禁止在 compact constructor / 构造器中用 `Assert` 代替 Bean Validation 注解。** 这是本规范 §1.1 声明式优先原则的具体应用 —— `@ConfigurationProperties` 受 Spring 管理，`@Validated` + 注解是唯一正确的方式。跨字段约束用 `@AssertTrue` 方法或自定义类级约束。

#### 基本写法

```java
@Validated                        // ← 关键：触发 Bean Validation
@ConfigurationProperties("app.{feature}")
@Data
public class {Feature}Properties {

    @NotBlank(message = "{feature}.host must not be blank")
    private String host;

    @NotNull
    @Min(1) @Max(65535)
    private Integer port;

    @NotBlank
    private String username;

    @NotBlank                     // 密码也是必填配置，不能为空
    @ToString.Exclude             // 防止日志泄露（Lombok）
    private String password;

    @DurationMin(seconds = 1)     // Hibernate Validator 附加约束注解（见下表）
    private Duration timeout;

    @Valid                        // ← 嵌套配置类必须加 @Valid 才会级联验证
    @NotNull
    private Pool pool = new Pool();

    @Data
    public static class Pool {
        @Min(1)
        private Integer maxTotal = 8;

        @Min(0)
        private Integer maxIdle = 4;

        @Min(0)
        private Integer minIdle = 1;
    }
}
```

#### 关键规则

| 规则 | 说明 |
|------|------|
| **`@Validated` 必加** | 仅 `@ConfigurationProperties` 不会触发验证；必须加 Spring 的 `@Validated`（`org.springframework.validation.annotation.Validated`），由 `ConfigurationPropertiesBinder` 在绑定后执行校验 |
| **嵌套 `@Valid`** | 嵌套配置类（如 `Pool`）字段必须加 `@Valid`，否则嵌套属性不会被级联验证 |
| **启动失败 = Fail Fast** | 校验失败时抛出 `ConstraintViolationException`，应用上下文初始化失败、拒绝启动。这是期望行为 —— 宁可启动失败也不带病运行 |
| **敏感字段脱敏** | `password`、`secret`、`token` 等字段加 `@ToString.Exclude`，防止 `@Data` 生成的 `toString()` 泄露到日志 |
| **默认值与验证不冲突** | 有默认值的字段（如 `port = 22`）仍可加验证注解；验证针对**绑定后的最终值**，默认值也必须满足约束。record / 构造器绑定场景的默认值用 `@DefaultValue`，见下节 |

#### 构造器绑定的默认值：`@DefaultValue`

setter 绑定（JavaBean 风格）的默认值用字段初始化（如上例 `maxTotal = 8`）；**record / 构造器绑定没有字段初始化器的位置**，默认值必须通过 `@DefaultValue`（`org.springframework.boot.context.properties.bind.DefaultValue`）标注在**构造器参数**上：

```java
@Validated
@ConfigurationProperties("app.{feature}")
public record {Feature}Properties(

        @NotBlank
        String host,                            // 关键配置：缺失应启动失败，不给默认值

        @DefaultValue("30") @Positive
        int timeoutSeconds,                     // 有合理默认：缺省时绑定 30，可用基本类型

        @DefaultValue("http://localhost:3000")
        List<@NotBlank String> allowedOrigins,  // 集合默认值，value 为 String[]

        @Valid @DefaultValue
        Pool pool) {                            // 无值形式：嵌套对象始终绑定非空实例

    public record Pool(@Min(1) int maxTotal) { }
}
```

**使用规则与限制：**

| 规则 | 说明 |
|------|------|
| **仅构造器参数** | `@Target` 仅为 `PARAMETER`；setter 绑定场景改用字段初始化 |
| **仅属性键完全缺失时生效** | `Environment` 中存在该键——即使值为空（如 YAML 写 `timeout-seconds:` 后无内容）——绑定空值而非默认值。默认值是「缺省兜底」不是「空值兜底」（对比：`@RequestParam(defaultValue=...)` 对空串参数也生效） |
| **不支持占位符** | 值必须是常量，不能写 `${...}`；需占位符默认值时改用 `@Value("${x:default}")` 或 yml 占位符 |
| **默认值自身须过校验** | `@Validated` 校验的是默认值转换**之后**的结果（如 `"30"` 须满足 `@Positive`）；默认值转换失败同样在启动期 fail-fast |
| **与 `@Valid` 正交** | 无值形式保证嵌套对象非 null，`@Valid` 负责级联校验其内部约束，两者各司其职 |

**选型纪律：** 配置项「必须有值且有合理默认」→ `@DefaultValue` / 字段初始化；「可选、缺失有业务含义」→ 保持可空并在使用处处理；「关键配置」→ 不给默认值，缺失即 fail-fast。禁止用默认值掩盖关键配置缺失——那会把配置错误变成静默的错误行为。

#### Hibernate Validator Duration 约束注解

`@DurationMin` / `@DurationMax` 是 **Hibernate Validator**（Jakarta Validation 参考实现，`spring-boot-starter-validation` 默认包含）提供的附加约束，用于校验 `java.time.Duration` 类型的最小/最大值。包路径：`org.hibernate.validator.constraints.time`。

| 注解 | 参数 | 用途 | 示例 |
|------|------|------|------|
| `@DurationMin` | `days/hours/minutes/seconds/millis/nanos` + `inclusive` | Duration 最小值（各参数求和） | `@DurationMin(seconds = 1, millis = 500)` |
| `@DurationMax` | 同上 | Duration 最大值（各参数求和） | `@DurationMax(seconds = 30)` |

**参数说明：** `days`, `hours`, `minutes`, `seconds`, `millis`, `nanos` 均默认 `0`，最终阈值为所有参数之和。`inclusive`（默认 `true`）表示是否包含边界值。

```java
// 最小 1 秒（含），最大 30 秒（含）
@DurationMin(seconds = 1)
@DurationMax(seconds = 30)
private Duration connectTimeout;

// 最小 500 毫秒（不含边界）
@DurationMin(millis = 500, inclusive = false)
private Duration readTimeout;
```

> **注意：** `Duration` 字段推荐用 `@DurationMin` / `@DurationMax` 而非 Jakarta `@Min` / `@Max`，后者作用于 `Duration` 的内部值（纳秒），语义不直观且不可读。

**Spring Boot Duration 绑定：** `@ConfigurationProperties` 中 `Duration` 类型字段支持 `5s`、`500ms`、`1m` 等字符串自动绑定。配合 `@DurationUnit` 可设置无后缀时的默认单位：

```java
@DurationUnit(ChronoUnit.SECONDS)   // 无后缀值默认按秒解析（如 "5" → 5 秒）
private Duration timeout;
```

#### 配置方式（`@ConfigurationProperties` 注册）

```java
// 方式一：类上同时标注 @Configuration + @ConfigurationProperties（简单场景）
@Validated
@Configuration
@ConfigurationProperties("app.{feature}")
public class {Feature}Properties { ... }

// 方式二：@ConfigurationPropertiesScan 自动扫描（推荐，需在主类上标注）
@ConfigurationPropertiesScan
@SpringBootApplication
public class {Project}Application { ... }

// 方式三：@EnableConfigurationProperties 显式注册（不放在扫描包内时）
@EnableConfigurationProperties({Feature}Properties.class)
@Configuration
public class {Feature}Config { ... }
```

> 三种方式均可触发 `@Validated` 校验，区别仅在 Bean 注册方式。项目内保持一致即可。

#### `application.yml` 配置与验证的配合

```yaml
app:
  {feature}:
    host: ${FEATURE_HOST:localhost}     # 有默认值，@NotBlank 仍校验最终值
    port: ${FEATURE_PORT:22}            # @Min(1) @Max(65535) 校验
    timeout: 5s                         # Duration 类型，@DurationMin(seconds = 1) 校验
```

**关键原则：** 外部化配置（环境变量、命令行参数）的值不可预测，验证注解是唯一能在启动时拦截非法配置的手段。

#### 最佳实践

- **必填配置用 `@NotBlank` / `@NotNull`** — 防止忘记配置导致运行时 NPE
- **端口、超时等数值用 `@Min` / `@Max` / `@DurationMin`** — 防止不合理值（如负端口、零超时）
- **嵌套配置加 `@Valid`** — 每一层嵌套属性都应被验证
- **敏感字段加 `@ToString.Exclude`** — 与 `logging.md` 脱敏要求一致
- **验证粒度适中** — 只验证「值本身不合法会导致运行时错误」的约束；业务逻辑校验不在配置层做

#### 避免做法

- **忘记 `@Validated`** — 仅加字段注解不加 `@Validated`，校验被静默跳过
- **忘记嵌套 `@Valid`** — 嵌套 `Pool` 等内部类的约束注解不会自动触发。**Boot 3.4 起严格遵循 Bean Validation 规范**：缺 `@Valid` 的嵌套属性被**静默跳过**——启动照常成功而校验未执行，只能通过探针测试发现（NC-014，见 `null-check-governance.md` §3）；集合元素级联写在泛型实参位置 `List<@Valid Endpoint>`
- **用 `Assert` 代替 Bean Validation 注解** — `@ConfigurationProperties` 类/record 禁止在 compact constructor 或构造器中用 `Assert.hasText` / `Assert.isTrue` 做字段级校验；改用 `@Validated` + `@NotBlank` / `@Positive` / `@Min` / `@Max` 等声明式注解。跨字段约束用 `@AssertTrue` 方法或自定义类级约束
- **配置层做业务逻辑校验** — 配置只验证「值合法」，跨字段业务规则在 Service / Domain 层处理
- **用配置验证替代测试** — 配置验证是启动时防线，不替代测试中的配置绑定测试

> **元数据通道 ≠ 校验通道：** `spring-boot-configuration-processor`、`@NestedConfigurationProperty`、`@DeprecatedConfigurationProperty` 只在**编译期**生成元数据，服务 IDE 补全与弃用告警，「对实际绑定过程无影响」——`@NestedConfigurationProperty` **不能**触发嵌套校验（须 `@Valid`），处理器缺失也**不会**导致校验失效（只丢 IDE 体验）。

> **校验失败行为：** 校验发生在 bean 初始化期，违例包装为 `ConfigurationPropertiesBindException`，FailureAnalyzer 输出 `Property`（实际键名）+ `Origin`（来源文件与行列号）+ `Reason`——排错第一落点是 `Origin`。


### 2.9 声明式校验最佳实践

#### 验证注解放置优先级

验证注解应**优先加在 Bean 字段（含 record 组件）上**，方法参数为**最低优先级**。规则内聚在数据类型本身，无论从哪个入口使用该 Bean，约束都不会遗漏。

| 优先级 | 放置位置 | 适用场景 | 示例 |
|--------|---------|---------|------|
| 1（首选） | Bean 字段 / record 组件 | DTO、`@ConfigurationProperties`、嵌套对象、Value Object | `public record UserDTO(@NotBlank @Size(min = 3) String name) {}` |
| 2（按需） | 类级 `@AssertTrue` 方法 / `@ScriptAssert` | 跨字段校验，无法用单字段注解表达 | `@AssertTrue public boolean isDateRangeValid()` |
| 3（兜底） | 方法参数 | 仅限无法封装为 Bean 的简单类型（`@PathVariable`、`@RequestParam`、Service 基本类型参数） | `@PathVariable @Positive Long id` |

```java
// BAD — 验证规则散落在方法参数上，DTO 字段裸露无约束
public void createUser(@NotBlank @Size(min = 3) String username,
                       @Email String email) { ... }

// GOOD — 验证规则内聚在 record 中，方法只需 @Valid 触发
public record UserCreateDTO(
    @NotBlank @Size(min = 3, max = 20) String username,
    @Email String email
) {}

public void createUser(@Valid UserCreateDTO dto) { ... }
```

**规则：**
- 能封装为 Bean 的参数一律封装，约束加在字段上 —— 禁止在方法参数上为 Bean 字段补验证
- 方法参数注解仅用于无法封装的场景（路径变量、查询参数、简单类型）
- Bean 字段已有的约束，禁止在方法参数上重复声明（如 DTO 已有 `@NotBlank`，Controller 不再叠加）

#### 其他推荐做法

- **分层验证**：Controller 做格式验证，Service 做业务验证，Entity 做不变式验证，ConfigurationProperties 做配置约束验证（启动 Fail Fast）
- **错误消息清晰**：`@NotBlank(message = "用户名不能为空")`
- **使用验证组**区分创建/更新场景
- **Record + 验证**：`public record UserDTO(@NotBlank String username, @Email String email) {}`
- **组合注解**减少重复：将多个验证注解组合为一个自定义注解

#### 避免做法

- **过度验证**：`@NotNull @NotEmpty @NotBlank @Size(min=1)` 冗余，选一个即可
- **重复验证**：Controller 和 Service 重复相同格式验证
- **验证器不处理 null**：自定义 `isValid()` 必须处理 `value == null` 返回 `true`
- **忽略 `@Valid` 级联**：嵌套对象必须加 `@Valid` 才会触发验证

> **全局异常处理：** Bean Validation 校验失败后的异常处理（`MethodArgumentNotValidException`、`ConstraintViolationException` 等 → HTTP 响应映射）完整规范见 `exception-handling.md` §6.2。

### 2.10 错误消息外化管理

注解的 `message` 支持三类占位符：注解属性名（`{min}`/`{max}`）、`{validatedValue}`（被拒值）、`${...}` EL 表达式。硬编码消息会耦合文案与代码、无法国际化，**注解里只写消息键**：

```java
// GOOD — 消息键三段式：<域>.<字段>.<约束>
public record CreateUserCommand(
        @NotBlank(message = "{user.username.notBlank}")
        @Size(max = 32, message = "{user.username.size}")
        String username) {}
```

```properties
# src/main/resources/ValidationMessages.properties（规范默认 basename，按 locale 国际化）
user.username.notBlank=用户名不能为空
user.username.size=用户名长度不能超过 {max} 个字符
```

**规则：**
- 消息键用 `<域>.<字段>.<约束>` 三段式；对外 API 的错误定位依赖 `ApiResponse.errors[]` 的 `field` 与机器可读错误码（`api-conventions.md`），**不依赖消息文本**
- 改造存量代码时，散落的中文字面量消息（NC-010）统一收敛到资源文件

### 2.11 校验行为调优

默认收集全部约束冲突后一次性返回。两个调优点：

**快速失败（fail fast）**——第一个约束冲突即终止校验：

```properties
hibernate.validator.fail_fast=true
```

> **注意：** 该属性是 Hibernate Validator 专有扩展，不属规范，更换实现即失效；代价是客户端一次只能看到一个错误，应按端点语义权衡，**不作全局默认开启**。

**组序列（`@GroupSequence`）**——校验有成本顺序时（先廉价约束、再昂贵约束），控制执行顺序，前组未过则后续不执行：

```java
@GroupSequence({CheapChecks.class, ExpensiveChecks.class})
public interface OrderedChecks {}
```

组序列只编排执行顺序，**不解决创建/更新场景差异**——后者优先拆 DTO（`architecture.md` §4.2）。

***

## 3. 命令式校验 — 契约编程

> 当场景不受 Spring 管理（Domain Entity、Value Object、内部逻辑）时，使用命令式校验。以下规范适用于这些场景。

> **边界说明 — Service 层的语义校验：** Service 用例中还有一类显式代码校验——依赖外部状态的语义校验（唯一性、存在性、状态前置条件），它不适用上述「是否被 Spring 管理」的判断标准，产物是 `BusinessException`（业务拒绝 → 4xx），而非本节的契约异常（`IllegalArgumentException` → 500 兜底）。写法见 `exception-handling.md` §4.2：
>
> ```java
> // 语义校验：需查库才能判断，写在该业务用例的 Service 方法中
> if ({entity}Repository.existsByName(request.name())) {
>     throw new BusinessException(ErrorCode.{ENTITY}_ALREADY_EXISTS, request.name());
> }
> ```
>
> 原则：**注解管「语法」约束**（自包含、无副作用），**用例代码管「语义」约束**（需查库、需业务上下文）。不要为了免写 if 把数据库查询塞进自定义 ConstraintValidator。

### 3.1 参数校验（前置条件）

#### 强制校验规则

所有 `public` / `protected` 方法的所有参数，必须在方法入口处进行校验。

#### Spring Boot 项目示例（使用 `org.springframework.util.Assert`）

```java
import org.springframework.util.Assert;

public void updateUser(@NonNull String userId, int age, List<String> tags) {
    Assert.hasText(userId, "userId must not be blank");
    Assert.notNull(tags, "tags must not be null");
    Assert.isTrue(age >= 0 && age <= 150,
        () -> "age must be in range [0, 150], was: " + age);
    Assert.isTrue(!tags.isEmpty(), "tags must not be empty");
}
```

#### 非 Spring 项目示例（Guava Preconditions）

```java
import java.util.Objects;
import com.google.common.base.Preconditions;

public void updateUser(@Nonnull String userId, int age, List<String> tags) {
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(tags, "tags must not be null");
    Preconditions.checkArgument(!userId.isBlank(), "userId must not be blank");
    Preconditions.checkArgument(age >= 0 && age <= 150,
        "age must be in range [0, 150], was: %s", age);
    Preconditions.checkArgument(!tags.isEmpty(), "tags must not be empty");
}
```

### 3.2 内部断言（不变式与后置条件）

#### assert 的使用原则

`assert` 仅用于验证**代码内部逻辑假设**，不可用于外部输入校验。

#### 断言开启配置

| 环境 | JVM 参数 | 说明 |
|------|----------|------|
| 开发/测试 | `-ea` | 开启断言 |
| 生产 | `-da` | 关闭断言（默认） |

**重要：** 断言失败应视为编程错误，不应被 try-catch 捕获处理。

### 3.3 异常信息规范

信息格式：`[参数/状态名] must [约束条件], but was: [实际值]`

```java
// 数值范围
Preconditions.checkArgument(age >= 0 && age <= 150,
    "age must be in range [0, 150], but was: %s", age);

// 非空字符串
Preconditions.checkArgument(!name.isBlank(),
    "name must not be blank, but was: '%s'", name);

// 状态检查
if (!isInitialized()) {
    throw new IllegalStateException(
        "service must be initialized before use, current state: UNINITIALIZED");
}
```

### 3.4 类不变式（Class Invariants）

```java
public class BankAccount {
    private String accountId;
    private BigDecimal balance;

    public BankAccount(@Nonnull String accountId, @Nonnull BigDecimal initialBalance) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.balance = Objects.requireNonNull(initialBalance, "initialBalance must not be null");
        Preconditions.checkArgument(initialBalance.compareTo(BigDecimal.ZERO) >= 0,
            "initial balance must be non-negative, was: %s", initialBalance);
        assert invariant() : "class invariant violated after construction";
    }

    private boolean invariant() {
        return accountId != null && !accountId.isEmpty()
            && balance != null && balance.compareTo(BigDecimal.ZERO) >= 0;
    }
}
```

### 3.5 契约与继承

子类重写方法时：
- **不能放宽前置条件**（即允许更多非法输入）
- **不能削弱后置条件**（子类型可提供更强保证，但不能保证更少）
- **不能削弱类不变式**

***

## 4. Null 安全注解

| 注解 | 来源 | 用途 |
|------|------|------|
| `@Nonnull` / `@Nullable` | `jakarta.annotation` (Jakarta EE 11) | 应用代码可用（SB4 仍可用） |
| `@Nullable` / `@NullMarked` | `org.jspecify.annotations` (JSpecify) | **SB4 框架首选**，见下方 JSpecify 小节 |
| `@NonNull` | `lombok` | Lombok 项目使用（见 `java-coding-standard.md` §5.2） |

> **注意：** `@CheckForNull` 不属于 `jakarta.annotation`（Jakarta EE 11 仅含 `@Nonnull`/`@Nullable`），它来自 SpotBugs / JSR-305（`edu.umd.cs.findbugs.annotations`），禁止新引入。
>
> **注意：** Spring Boot 4 基于 Jakarta EE 11，`javax.*` 工件已完全移除（非 deprecated）。应用代码统一使用 `jakarta.annotation`，禁止使用旧版 `javax.annotation`。

#### JSpecify null-safety（SB4 推荐）

Spring Boot 4 / Spring Framework 7 全量采用 [JSpecify](https://jspecify.dev/) 注解（`org.jspecify.annotations`）表达 null 语义，框架自身 API 均已标注。应用代码可继续使用 `jakarta.annotation`，但新代码推荐向 JSpecify 演进：

```java
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked                    // 标记「null-safe zone」：包/类级，范围内类型默认非空
public class OrderService {

    public Order findById(Long id) {                  // 默认非空返回
        ...
    }

    public Order findByName(@Nullable String name) {   // 显式标注可空参数
        ...
    }
}
```

**关键规则：**
- `@NullMarked` 标注在包（`package-info.java`）或类上，范围内所有类型默认 non-null，仅需为可空处加 `@Nullable`
- 搭配 null checker（如 Checker Framework）或 Kotlin 可在编译期发现潜在 NPE
- **Actuator endpoint 参数禁止使用 `org.springframework.lang.Nullable`**，必须改用 `org.jspecify.annotations.Nullable`（SB4 已移除对前者的支持）

**强制要求：** 校验逻辑必须与注解声明的契约保持一致。

```java
// 正确：声明与校验一致
public void process(@Nonnull String input) {
    Objects.requireNonNull(input, "input must not be null");
}
```

> Null 安全的完整策略（三层防御、JSpecify 使用模式）见 `java-coding-standard.md` §4.2。

***

## 5. 测试

为每个公开方法编写负面测试，验证非法输入抛出预期异常：

```java
@Test
void updateUser_NullUserId_ShouldThrowException() {
    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> service.updateUser(null, 25)
    );
    assertEquals("userId must not be null", exception.getMessage());
}

@ParameterizedTest
@ValueSource(ints = {-1, 151, Integer.MIN_VALUE})
void updateUser_InvalidAge_ShouldThrowException(int invalidAge) {
    assertThrows(IllegalArgumentException.class,
        () -> service.updateUser("user1", invalidAge));
}
```

***

## 6. 代码审查清单

### 声明式校验

- [ ] DTO / `@ConfigurationProperties` 字段是否用 Bean Validation 注解（而非手动 `Assert`）？
- [ ] `@ConfigurationProperties` 类是否加了 `@Validated`？嵌套配置是否加了 `@Valid`？
- [ ] record / 构造器绑定配置类的默认值是否用 `@DefaultValue`（而非字段初始化）？关键配置是否未滥用默认值？
- [ ] Controller 是否用 `@Valid` / `@Validated` 触发验证，而非手动校验？
- [ ] 验证注解是否优先加在 Bean 字段上，而非方法参数上？
- [ ] 嵌套对象是否加了 `@Valid` 级联验证？
- [ ] 自定义验证器的 `isValid()` 是否处理了 `null` 返回 `true`？
- [ ] 未加 `@Validated` 的 Service 类，其方法参数注解不会被静默忽略？

### 命令式校验

- [ ] 所有 `public` / `protected` 方法是否都有参数校验？
- [ ] Spring Boot 项目是否使用了 `jakarta.annotation`（而非 `javax.annotation`）？
- [ ] Actuator endpoint / 框架集成点是否使用 JSpecify（`org.jspecify.annotations`）而非 `org.springframework.lang.Nullable`？
- [ ] 异常信息是否清晰，包含参数名、期望值和实际值？
- [ ] 是否将 `assert` 误用于外部输入校验？
- [ ] 注解声明（`@NonNull` / `@Nullable`）是否与校验逻辑一致？

### 通用

- [ ] 子类重写方法是否遵循 LSP 原则？
- [ ] 类不变式是否在关键方法后得到维护？
- [ ] 测试是否覆盖了校验的边界情况？

***

## 快速参考（索引）

模板类速查已并入正文对应章节，直接查阅：

| 主题 | 章节 |
|------|------|
| 声明式校验模板（Controller / Service / ConfigurationProperties） | §2.1 / §2.7 / §2.8 |
| 命令式断言模板（Entity / VO 不变式） | §3.1 |
| 断言 API 速查（`Assert.notNull` / `hasText` / `isTrue` …） | §3.2 |
| 校验消息键约定 | §2.10 |
