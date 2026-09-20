---
name: "Java Coding Standard"
description: "Java coding standards for Java 21+ with Spring Boot 4.1+ — readability, conciseness, declarative style, modern Java features, null safety, tools"
paths:
  - "**/*.java"
---

# Java 编码规范

**版本：** 3.1
**生效日期：** 2026-08-05
**适用范围：** 所有基于 Java 21+ 的后端项目（含 Spring Boot 4.0+）

> **相关规范：** 校验与契约编程（声明式 Bean Validation / 命令式断言 / 不变式）见 `validation.md`；对象健身操（OO 设计约束）见 `java-object-calisthenics.md`；类设计原则（SOLID / 迪米特法则）见 `java-solid-lod.md`；异常处理（分类/§2.1 业务异常模式选型/抛出/捕获/全局处理）见 `exception-handling.md`；日志规范见 `logging.md`。

***

## 1. 核心原则

### 1.1 可读性是第一原则

> Code is read more than written. 优先选择意图清晰、易于维护的写法，避免过度抽象或炫技。

### 1.2 能用 1 行完成的代码绝不用 2 行

> Concise but not cryptic. 利用现代语法和工具库减少样板代码，但确保不牺牲可读性。

**注意**：这里的"简洁"指**代码意图的简洁**，而非字符数。工具方法（如 `StringUtils.defaultString()`）在表达"提供默认值"这一意图时，比三元表达式更清晰，应优先使用。

### 1.3 优先使用框架/库能力

> Don't reinvent the wheel. 按照以下优先级选择实现方式（详细对照表与替代方案见 §5）：

| 优先级 | 类型 | 说明 |
|--------|------|------|
| 1 | JDK 原生 + Spring 内置工具 | Spring 项目两者**同级优先**(均零额外依赖)。选更可读的,详见 §5.1 对照表 |
| 2 | Lombok | 减少 getter/setter、构造器、日志等样板代码 |
| 3 | Apache Commons | 仅当 JDK + Spring 均无法简洁实现时,引入**具体模块**,注释原因 |
| 4 | 其他第三方库 | Guava、Hutool 等,仅在以上均无法简洁实现时按需引入模块 |

### 1.4 优先使用声明式编程方式

> Declare intent, not steps. 描述"做什么"，让框架和语言特性处理"怎么做"。

声明式代码表达的是**数据转换管道**或**约束声明**，而非逐步的控制流命令。

| 场景 | 命令式（避免） | 声明式（推荐） |
|------|---------------|---------------|
| 集合处理 | `for` + `if` + 临时集合 | Stream API：`filter` / `map` / `reduce` / `collect` |
| 数据校验 | 手写 `if-throw` 校验逻辑 | Bean Validation 注解（见 `validation.md`） |
| 事务管理 | 手动 `begin` / `commit` / `rollback` | `@Transactional` 声明事务边界 |
| 查询定义 | 手拼 SQL 字符串 + 手动参数绑定 | Spring Data 方法名派生 / `@Query` |
| 空值处理 | 嵌套 `if (x != null)` 检查链 | `Optional.map` / `orElseThrow` 链式管道 |
| 对象构建 | 多参数构造器 / 手写 setter 链 | Builder 模式 / `record` 组件 |
| 样板代码 | 手写 getter / equals / hashCode | Lombok `@Data` / `@Value` 注解声明 |

**注意事项：**
- **可读性优先于"纯声明式"** — 嵌套超过 2 层的 Stream 或过度使用 `collect` 反而损害可读性
- **性能敏感的热路径** — 如果 Stream 产生可测量的性能退化，可回退命令式，但需注释说明原因
- **声明式 ≠ 无条件用 Stream** — 简单的单元素遍历无需强制 Stream 化

### 1.5 依赖注入

- **构造器注入唯一**（`@RequiredArgsConstructor`），禁止字段 `@Autowired`
- 同类型多 Bean 用 `@Qualifier` 消歧
- 避免循环依赖；确属不可避免时用 `@Lazy`
- Spring Bean（`@Component` / `@Service` / `@Repository`）不算工具类，不要用 `@UtilityClass`

***

## 2. 代码风格

### 2.1 命名规范

| 元素 | 规则 | 示例 |
|------|------|------|
| 类 / 接口 / Record | PascalCase，名词 | `UserService`, `OrderResponse` |
| 方法 | camelCase，动词开头 | `findByEmail`, `isActive`, `hasPermission` |
| 布尔方法 | `is` / `has` / `can` / `should` 前缀 | `isExpired`, `hasAccess` |
| 变量 / 字段 | camelCase | `userName`, `createdAt` |
| 常量（`static final`） | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT`, `DEFAULT_PAGE_SIZE` |
| 包 | 全小写，不缩写 | `com.example.{project}.domain.user` |
| Record 组件 | camelCase（与字段一致） | `record User(Long id, String name)` |
| 测试方法 | `test{Action}{Entity}[{Condition}]` | `testCreateUserWithValidationError` |

**禁止自造缩写**（对象健身操 §2.6）：`usr`、`cfg`、`amt` 等不允许。通用缩写例外清单以 `java-object-calisthenics.md` §2.6 为唯一权威源，此处不重复列举。

### 2.2 格式规范

> 参考 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) 关键规则，以下为**强制**要求。

#### 大括号

`if` / `else` / `for` / `do` / `while` 语句**必须使用大括号**，即使 body 为空或仅一行：

```java
// GOOD
if (user.isActive()) {
    process(user);
}

// BAD — 省略大括号
if (user.isActive()) process(user);
```

#### `@Override` 必须使用

重写父类方法或实现接口方法时，**必须标注 `@Override`**（编译器校验 + 意图文档）。

#### import 规则

- 禁止通配 import（`import java.util.*`），除非使用 Java 25 Module Import（`import module java.base`）
- import 顺序：`java.*` → `javax.*` / `jakarta.*` → `org.*` / `com.*` → 项目内部包 → `static import`
- 未使用的 import 必须清除（IDE 自动优化）

#### 缩进

- 统一 **4 空格**缩进，禁止 Tab
- 续行缩进 **8 空格**（二倍缩进区分续行）

### 2.3 Javadoc 标准

所有 `public` / `protected` 类和方法必须有 Javadoc：

```java
/**
 * Creates a new {Entity} with the given request data.
 *
 * @param request the creation request containing required fields
 * @return the created entity response with generated ID
 * @throws BusinessException if entity with same name already exists
 */
EntityResponse create(CreateRequest request);
```

**规则：**
- 方法 Javadoc：`@param`、`@return`、`@throws`
- 使用 `{@code ...}` 和 `{@link ...}` 标签
- record 组件的 Javadoc 写在组件声明上方
- 禁止无意义 Javadoc（如 `/** Gets the name. */`）

***

## 3. 现代 Java 特性

### 3.1 Record 与 Compact Constructor

**DTO 全部使用 record**（见 architecture.md）。Compact constructor 用于参数验证：

```java
public record CreateRequest(
    @NotBlank String name,
    @Email String email
) {
    public CreateRequest {
        // 可在此添加跨字段验证
        Objects.requireNonNull(name);
    }
}
```

#### Flexible Constructor Bodies（Java 25 正式特性，JEP 513）

> ⚠️ **本项目基线 Java 21，此特性不可用**（需 JDK 25+），仅作前瞻参考。

Java 25 起，构造器中允许在 `super()` / `this()` 调用**之前**执行代码——只要不读取未初始化的实例字段：

```java
// Java 25 — super() 前可直接验证、计算
public class Square extends Rectangle {
    public Square(Color color, int area) {
        if (area < 0) throw new IllegalArgumentException();
        double sideLength = Math.sqrt(area);
        super(color, sideLength, sideLength);   // ← 可以在 super 前做计算
    }
}
```

> **对 record 的影响：** record 的 compact constructor 本身就允许任意顺序代码，Flexible Constructor Bodies 主要影响继承体系中的传统构造器。record 继承自其他类的场景可直接受益。

**record 与 Lombok @Value 选择：**
- `record` 是 Java 21+ 首选（语言级别支持、pattern matching）
- `@Value` 仅在需要继承或 `@Builder` 时使用

### 3.2 Sealed 类型与 Pattern Matching

Sealed + record + pattern matching 构成 Java 21+ 的**代数数据类型**体系：

```java
// 1. Sealed 接口定义封闭类型集
public sealed interface DomainEvent
    permits EntityCreatedEvent, EntityUpdatedEvent, EntityDeletedEvent {
    Long entityId();
    OffsetDateTime occurredAt();
}

// 2. Record 实现各分支（自动 permits）
public record EntityCreatedEvent(Long entityId, String name, OffsetDateTime occurredAt)
    implements DomainEvent {}
public record EntityUpdatedEvent(Long entityId, String changes, OffsetDateTime occurredAt)
    implements DomainEvent {}
public record EntityDeletedEvent(Long entityId, String reason, OffsetDateTime occurredAt)
    implements DomainEvent {}
```

#### 穷尽 switch（编译器保证完整性）

```java
// 编译器强制覆盖所有 sealed 分支 —— 无需 default
String action = switch (event) {
    case EntityCreatedEvent e -> "created";
    case EntityUpdatedEvent e -> "updated";
    case EntityDeletedEvent e -> "deleted";
};
```

#### Record Pattern 解构（Java 21 正式特性）

```java
// 解构嵌套 record
if (event instanceof EntityCreatedEvent(Long id, String name, OffsetDateTime time)) {
    System.out.println("Created: " + name + " at " + time);
}
```

#### Guarded Pattern（`when` 子句）

```java
switch (event) {
    case EntityCreatedEvent e when e.name().startsWith("test") -> log.debug("Test entity");
    case EntityCreatedEvent e                                  -> log.info("Real entity");
    case EntityUpdatedEvent e                                  -> log.info("Update");
    case EntityDeletedEvent e                                  -> log.info("Delete");
}
```

#### Primitive Type Patterns（Java 25 第三次预览，JEP 507）

> ⚠️ **预览特性**，需 `--enable-preview --source 25`。以下为前瞻性参考。

```java
// 基本类型可在 instanceof / switch 中做模式匹配（检查无损转换）
int value = 42;
if (value instanceof byte b) {
    System.out.println("Fits in byte: " + b);
}

double d = 3.14;
switch (d) {
    case int i      -> System.out.println("Integer: " + i);
    case float f    -> System.out.println("Float: " + f);
    case double dd  -> System.out.println("Double: " + dd);
}
```

### 3.3 Optional 使用模式

**推荐：**
```java
// 链式操作
return repository.findById(id)
    .map(mapper::toResponse)
    .orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_NOT_FOUND, id));

// 条件执行
optional.ifPresent(this::process);

// 提供默认值（惰性求值）
String name = optional.orElseGet(() -> generateDefaultName());
```

**反模式：**
```java
// BAD: orElse() 总是急切求值
optional.orElse(expensiveCall());

// BAD: 直接 get() 不检查
optional.get();

// BAD: Optional 作为字段或参数类型
private Optional<String> name;              // 禁止
public void process(Optional<String> input); // 禁止

// BAD: Optional<Collection<T>> — 返回空集合即可
Optional<List<String>> getNames();           // 禁止，应返回 List<String>
```

**规则：** Optional 仅用于方法返回类型。禁止用于字段、构造器参数、方法参数。

### 3.4 Stream API 与 Gatherers

#### 基础最佳实践

```java
// toList() 替代 collect(Collectors.toList())
List<String> names = users.stream().map(User::getName).toList();

// 处理重复 key
Map<Long, User> byId = users.stream()
    .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

// flatMap 展平
List<Order> allOrders = customers.stream()
    .flatMap(c -> c.getOrders().stream())
    .toList();

// 短路操作
boolean exists = users.stream().anyMatch(User::isActive);
```

**反模式：**
```java
// BAD: peek() 用于非调试目的
stream.peek(System.out::println).toList();

// BAD: 存储 Stream 引用到字段
private Stream<String> names;

// BAD: parallelStream() 不考虑线程安全
list.parallelStream().collect(Collectors.toList());
```

#### Stream Gatherers（Java 22+，JEP 461）

Gatherer 是 Java 22 引入的 Stream 中间操作扩展机制，支持 `windowFixed`（固定窗口）、`windowSliding`（滑动窗口）、`fold`（折叠）等内置操作，以及自定义 Gatherer：

```java
// 固定窗口分组（每 3 个元素一组）
List<List<Integer>> chunks = numbers.stream()
    .gather(Gatherers.windowFixed(3))
    .toList();

// 滑动窗口（计算移动平均值）
List<Double> movingAverages = numbers.stream()
    .gather(Gatherers.windowSliding(3))
    .map(window -> window.stream().mapToInt(i -> i).average().orElse(0))
    .toList();

// 折叠（带状态的累积）
String concatenated = strings.stream()
    .gather(Gatherers.fold(() -> "", (acc, s) -> acc + s))
    .findFirst()
    .orElse("");
```

> **Gatherer vs Collector：** Collector 是终端操作（`collect`），Gatherer 是中间操作（可继续 `.map` / `.filter`），支持有状态转换，比 `Collectors.groupingBy` 等更灵活。

### 3.5 Text Blocks 与字符串格式化

```java
// 多行字符串（SQL、JSON、正则优先使用 text block）
String sql = """
    SELECT u.id, u.name, u.email
    FROM users u
    WHERE u.status = ?
    ORDER BY u.created_at DESC
    """;

// String.formatted() 替代 String.format()
String greeting = "Hello, %s!".formatted(name);

// 拼接少量字符串直接用 +（javac 自动优化为 StringBuilder）
String message = "User " + name + " created at " + createdAt;
```

### 3.6 Switch 表达式基础

> 模式匹配 switch（类型匹配、`case null`、guarded 分支）见 §3.2，此处只保留表达式基础。

```java
// yield 在块体中返回值
int result = switch (status) {
    case ACTIVE -> 1;
    case INACTIVE -> {
        log.debug("Inactive status");
        yield 0;
    }
};
```

***

## 4. 类型安全与 Null 安全

### 4.1 泛型类型安全

**PECS 原则：Producer extends, Consumer super**

```java
// Producer — 从集合读取，用 extends
void printAll(List<? extends Number> numbers);

// Consumer — 向集合写入，用 super
void addAll(List<? super Integer> target, List<Integer> source);

// 泛型方法签名
public static <T extends Comparable<T>> T max(T a, T b) {
    return a.compareTo(b) >= 0 ? a : b;
}
```

**规则：** 避免 `@SuppressWarnings("unchecked")` 除非类型安全已人工验证并注释原因。

### 4.2 Null 安全策略

Spring Boot 4 / Spring Framework 7 全量采用 [JSpecify](https://jspecify.dev/) null-safety 注解。项目采用**三层防御**策略：

| 层次 | 机制 | 用途 |
|------|------|------|
| **1. 类型声明** | JSpecify `@NullMarked` + `@Nullable` | 包/类级标注默认非空，仅可空处显式标注 |
| **2. 返回值** | `Optional<T>` | 方法返回可能"无值"的结果，链式处理 |
| **3. 参数校验** | `Objects.requireNonNull` / `Assert` | 运行时前置条件，快失败 |

```java
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked                        // ← 标记 null-safe zone：范围内所有类型默认非空
public class OrderService {

    // 默认非空返回 —— 调用方无需 null 检查
    public Order findById(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, id));
    }

    // Optional 返回 —— 调用方链式处理
    public Optional<Order> findByCode(String code) {
        return repository.findByCode(code);
    }

    // @Nullable 参数 —— 显式声明可空
    public Order update(Long id, @Nullable String note) {
        Order order = findById(id);
        if (note != null) {
            order.setNote(note);
        }
        return order;
    }
}
```

**规则：**
- `@NullMarked` 标注在包（`package-info.java`）或类上，范围内所有类型默认 non-null
- 新代码推荐 JSpecify（`org.jspecify.annotations`），`jakarta.annotation` 仍可用
- **禁止** `org.springframework.lang.Nullable`（SB4 已移除支持，Actuator endpoint 会报错）
- 应用代码统一使用 `jakarta.annotation` 或 JSpecify，**禁止**旧版 `javax.annotation`（SB4 已完全移除）

#### NullAway + Error Prone 编译期强制（可选增强）

JSpecify 注解本身不产生检查，需静态分析器消费。推荐 **NullAway**（Uber，作为 Error Prone 的空值规则运行）：`@NullMarked` 包内违规传参/解引用在 CI 直接编译失败——空值契约成为「不修复就无法合并」的门禁。

**渐进引入策略（禁止全局一次开启）：**

1. 新包从第一天起 `@NullMarked`（零成本区）
2. 存量包逐个加 `@NullMarked` 并修复报错，**按包提交 PR**
3. 未覆盖的包不加标注，按未标注语义跳过
4. NullAway 先设 `warn` 级，存量达标后再升 `ERROR`
5. 达标后把「新增代码必须在 `@NullMarked` 包内」写入评审清单防回潮

切忌反向操作——先全局启用再逐包豁免，豁免清单只增不减。Maven 集成需把 Error Prone 挂到 `maven-compiler-plugin`，且 JDK 16+ 强封装（JEP 396）要求配置 `--add-exports`/`--add-opens`（缺失时构建以 IllegalAccessError 失败）；关键开关：`NullAway:AnnotatedPackages` 限定检查范围、`NullAway:JSpecifyMode=true` 按 `@NullMarked` 语义推断非空默认。

> **与 Bean Validation 的分工：** 静态分析管代码内部契约（编译期），Bean Validation 管外部输入（运行期）——编译器看不见请求体/消息负载/配置文件。标准姿势是「入口校验一次、内部信任契约」，互补不替代。

### 4.3 不可变集合

```java
// List.of() — 结构不可变，不支持 null 元素
List<String> immutable = List.of("a", "b", "c");

// Collections.unmodifiableList() — 仅视图，底层集合仍可变
List<String> view = Collections.unmodifiableList(mutableList);

// List.copyOf() — 防御性复制为不可变
List<String> copy = List.copyOf(potentiallyMutableList);

// Stream.toList() — 不可修改但类型不同于 List.of()
List<String> fromStream = stream.toList();

// 收集为不可变列表
List<String> collected = stream.collect(Collectors.toUnmodifiableList());
```

***

## 5. 工具库与依赖管理

### 5.1 工具选择优先级与替代对照表

**原则：** 优先「简洁 + 意图清晰」。Spring 项目中，Spring 内置工具（`org.springframework.util.*`，零额外依赖）与 JDK 原生 API **同级优先**，选更可读的。

#### 常用替代对照表

| 需求 | 避免 | 推荐 |
|------|------|------|
| 字符串判空白 | `str == null \|\| str.isBlank()`（啰嗦） | Spring `StringUtils.hasText(str)` |
| 集合判空 | 手动 `list == null \|\| list.isEmpty()` | Spring `CollectionUtils.isEmpty(list)` |
| 集合创建 | Guava `Lists.newArrayList(...)` | `List.of(...)` (JDK 9+) |
| 文件读取 | Commons-io `FileUtils.readFileToString(...)` | `Files.readString(Path.of(...))` (JDK 11+) |
| HTTP 调用 | Hutool `HttpUtil`（第三方） | Spring `RestClient` / JDK `HttpClient` |
| 日期时间 | `Date / Calendar / DateUtils` | `java.time.*` (JDK 8+) |
| Base64 编码 | Commons-codec `Base64.encodeBase64String` | `java.util.Base64` (JDK 8+) |
| 数值范围限制 | `Math.min(Math.max(val, min), max)` | `Math.clamp(val, min, max)` (JDK 21) |
| 字符串默认值 | `str != null ? str : ""` 三元 | Spring `StringUtils.defaultString(str)` |
| 对象默认值 | `obj != null ? obj : default` 三元 | Spring `ObjectUtils.defaultIfNull(obj, default)` 或 JDK `Objects.requireNonNullElse(obj, default)`（惰性版 `requireNonNullElseGet`） |
| Map 取值默认值 | 手动 `containsKey` 判断 | `map.getOrDefault(key, default)` |
| 请求参数默认值 | `required = false` + 使用处判空 | `@RequestParam(defaultValue = "...")`（对空串参数同样生效） |
| 配置属性默认值 | 使用处手工判空兜底 | setter 绑定用字段初始化；record/构造器绑定用 `@DefaultValue`（仅构造器参数，详见 `validation.md` §2.8） |
| 类型判断分支 | `if (obj instanceof X) { X x = (X) obj; ... }` | `if (obj instanceof X x) { ... }` (JDK 16+) |
| 获取集合首/末元素 | 手动 `get(0)` / `get(size()-1)` | `sequencedCollection.getFirst()` / `getLast()` (JDK 21) |

#### JDK 21 新特性速查

```java
// 模式匹配 switch + Record Pattern + SequencedCollection + Math.clamp
// 详见 §3.2 / §3.6

// Virtual Threads — 见 §8.1（配置开启 / 手动 executor / pinning 与池化注意事项）
```

### 5.2 Lombok 使用规范

#### 推荐注解

| 注解 | 用途 | 注意事项 |
|------|------|---------|
| `@Data` | getter/setter/toString/equals/hashCode | **仅非 JPA 的 DTO/VO**。JPA Entity 用 `@Getter` + 手动 equals/hashCode |
| `@Builder` | 建造者模式 | — |
| `@Slf4j` | 自动创建 log 对象 | — |
| `@RequiredArgsConstructor` | 构造器注入 | — |
| `@Value` | 不可变类 | 仅在需要继承或 `@Builder` 时使用（否则用 record） |
| `@UtilityClass` | 工具类（全静态方法） | **所有工具类统一用此注解** |

#### 限制

- JPA 实体避免 `@Data`（循环依赖风险）；用 `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` + `@EqualsAndHashCode.Include` 指定业务主键
- `@ToString.Exclude` 排除敏感字段（密码、密钥等）
- Lombok 无层级限制（domain 层实际使用 `@Getter`/`@Builder`/`@RequiredArgsConstructor`，见 `tech-stack.md`）；唯一约束是 JPA 实体避免 `@Data`（上一条）

#### `@NonNull` 使用规则

`@NonNull` 是编译期代码生成：在标注位置插入 null 检查，失败抛 `NullPointerException`（带参数名消息）。

| 标注位置 | 生成行为 |
|---------|---------|
| 方法/构造器参数 | 方法体首行插入 null 检查 |
| 字段 | 在 setter、`@RequiredArgsConstructor` 生成的构造器中插入检查 |
| 方法返回值 | return 前插入检查，承诺不返回 null |

**规则：**
- **仅保留在构造器/必填依赖注入场景**——那里需要静态标注做不到的真实字节码检查；空值语义主表达用 JSpecify（§4.2），二者并存合法、存量无需返工
- **不用于输入校验**——抛 NPE 进不了校验异常通道，Controller 入参校验走 Bean Validation（`validation.md` §2）
- 检查只存在于 Lombok 生成的代码路径——手写 setter 会形成覆盖空洞
- `lombok.config` 可设置 `lombok.addNullAnnotations = jspecify`（配置键自 lombok 1.18.12 存在，`jspecify` 内置值自 1.18.38 支持、为官方推荐值之一）：让 Lombok 生成代码（getter、构造器、`@Builder` 等）自动附带 JSpecify 空值标注，避免 `@Nullable` 字段的生成 getter 被 NullAway 按包内默认视为非空返回。**条件启用**（NullAway 官方对 Lombok 仅 best-effort 兼容，已知坑：TYPE_USE 识别问题 NullAway #917、`@Builder` 支持有限 #321）：Lombok ≥1.18.38 且生成代码向消费方暴露 `@Nullable` 契约时启用；Lombok 暴露面小的项目不启用同样是合理保守选择；无论启用与否，变更后须重跑 NullAway 全量验证
- 构造器注入是无坑场景（检查路径与赋值路径完全重合）：

```java
@Service
@RequiredArgsConstructor
public class OrderService {
    @NonNull
    private final OrderRepository orderRepository;   // 生成构造器首行 null 检查
}
```

#### 工具类（@UtilityClass）

**所有工具类（仅含静态方法、无实例状态 —— main 代码与 test 代码统一）必须使用 `@UtilityClass`。**

`@UtilityClass` 自动：类标 `final` + 生成 `private` 构造器(throw) + 所有方法/字段 static 化。

**规则：**
- **不要再手写 `static` 关键字**（`@UtilityClass` 自动 static 化）；**不要同时写 `final`**
- **`import static` 谨慎使用**：旧版 javac 注解处理时序与 Lombok 生成的 static 方法曾有兼容问题，当前 JDK 21 + Lombok 组合通常可用——以项目构建实测为准，不确定时用显式 `类名.方法`
- 仅当项目禁用 Lombok 时，才手写 `final class X { private X() {} public static ... }`

### 5.3 Spring / Spring Boot 4 内置能力

#### 常用工具类

| 类 | 常用方法 | 说明 |
|----|----------|------|
| `org.springframework.util.StringUtils` | `hasText()`, `trimAllWhitespace()`, `commaDelimitedListToSet()` | 字符串工具 |
| `org.springframework.util.CollectionUtils` | `isEmpty()`, `mergeArrayIntoCollection()` | 集合工具 |
| `org.springframework.util.Assert` | `notNull()`, `hasLength()`, `state()`, `isTrue()` | 参数校验 |
| `org.springframework.util.FileCopyUtils` | `copy()`, `copyToByteArray()` | 文件/流复制 |
| `org.springframework.web.client.RestClient` | `get()`, `post()`, `delete()` | HTTP 客户端（SF 7，推荐替代 RestTemplate） |
| `org.springframework.beans.BeanUtils` | `copyProperties()`, `instantiateClass()` | Bean 操作 |

#### Spring Boot 4 关键特性

| 特性 | 配置/说明 |
|------|-----------|
| Virtual Threads | `spring.threads.virtual.enabled=true` |
| RestClient | 替代 RestTemplate 的现代 HTTP 客户端（新模块首选，需 `spring-boot-starter-restclient`） |
| JSpecify null-safety | 框架全量采用 `org.jspecify.annotations`（见 §4.2） |
| Jackson 3 | 默认 JSON 库，自动配置 `JsonMapper` |
| API Versioning | 内置 API 版本控制支持（`spring-boot-starter-webmvc`） |
| Module Import (Java 25) | 可用 `import module java.base` 简化 import（需 JDK 25+） |

#### RestClient 示例

```java
private final RestClient restClient;

// GET
String result = restClient.get()
    .uri("https://api.example.com/users/{id}", userId)
    .retrieve()
    .body(String.class);

// POST
User created = restClient.post()
    .uri("/users")
    .body(newUser)
    .retrieve()
    .body(User.class);
```

> **说明：** 已有项目使用 `RestTemplate` 可继续使用。新模块推荐 `RestClient`。

### 5.4 第三方库引入规范

1. **按需引入，避免全量依赖** — Commons 只引入需要的模块；Hutool 优先引入模块而非 hutool-all
2. **版本统一** — Spring Boot 项目利用 BOM 管理版本
3. **显式注释** — 引入非 JDK/Spring 依赖时，必须在构建文件中添加注释说明原因
4. **冲突检查** — 引入新依赖前，运行 `mvn dependency:tree` 检查版本冲突

#### Apache Commons 场景

| 模块 | 典型场景 |
|------|----------|
| commons-lang3 | `join()` 复杂分隔符、`abbreviate()` 等高级操作 |
| commons-io | 递归删除目录、复制大文件 |
| commons-collections4 | 集合交集、差集或双向 Map |
| commons-codec | Hex 编码（若 JDK 未覆盖） |
| commons-pool2 | 连接池、资源池 |

#### 其他第三方库场景

| 场景 | 推荐库 |
|------|--------|
| 本地缓存 | Caffeine（优先）/ Guava `CacheBuilder` |
| 限流 | Guava `RateLimiter` |
| 不可变集合增强 | Guava `ImmutableXXX` |
| Excel 简单读写 | Hutool `ExcelUtil` |
| 验证码生成 | Hutool `CaptchaUtil` |

### 5.5 冲突与规避

- **HTTP 客户端：** SB4 新模块优先 `RestClient`，`RestTemplate` 维护模式
- **文件上传：** 使用 MultipartFile，禁止额外引入 commons-fileupload
- **日志门面：** 使用 SLF4J，避免引入 commons-logging
- **Bean 属性复制：** 优先 Spring `BeanUtils.copyProperties()`，避免 Apache Commons BeanUtils
- **集合工具：** Spring 项目统一用 Spring `CollectionUtils`，避免混用多库集合工具
- **异步编程：** 优先 JDK `CompletableFuture`

### 5.6 常用工具库版本

| 库 | 推荐版本 | 说明 |
|----|----------|------|
| commons-lang3 | 3.18.0 | 与 Spring Boot 4.x 兼容 |
| commons-io | 2.19.0 | 稳定版本 |
| commons-collections4 | 4.5.0 | 避免使用旧版 commons-collections |
| guava | 33.4.0-jre | 选择 -jre 变体 |
| hutool | 5.8.34 | 按需引入模块 |

> **注意：** Spring Boot 4.x 通过 BOM 管理大部分依赖版本。使用 `spring-boot-dependencies` BOM 时，无需手动指定 Commons 库版本。

***

## 6. 异常与资源管理

### 6.1 异常链

**规则：** 始终保留根本原因（cause）。

```java
// GOOD: 传递 cause
catch (IOException ex) {
    throw new BusinessException(ErrorCode.INTERNAL_ERROR, ex);
}

// BAD: 吞掉原因
catch (IOException ex) {
    throw new BusinessException(ErrorCode.INTERNAL_ERROR);  // 丢失 ex！
}

// BAD: 空 catch 块
catch (Exception ex) {
    // 静默忽略 — 绝对禁止
}
```

### 6.2 try-with-resources

```java
try (var stream = Files.newInputStream(path)) {
    // 使用 stream
} // 自动关闭，异常通过 getSuppressed() 获取
```

### 6.3 安全编码原则

> 参考 [Oracle Secure Coding Guidelines for Java SE](https://www.oracle.com/java/technologies/javase/seccodeguide.html)。

| 原则 | 规则 |
|------|------|
| **输入净化** | 所有外部输入（HTTP 参数、配置值、下游返回）必须校验后才使用；禁止信任未经验证的输入 |
| **敏感数据** | 密码、密钥、Token 等不得出现在日志、异常消息、`toString()` 中（用 `@ToString.Exclude` / `MaskUtils` 脱敏） |
| **反序列化防护** | 禁止 `ObjectInputStream` 直接反序列化不可信数据；优先使用 JSON 等安全格式 |
| **SQL 注入** | 禁止字符串拼接 SQL；必须用参数化查询（`@Query` + `:param` / JDBC `PreparedStatement`） |
| **异常信息脱敏** | 面向客户端的错误消息不含 SQL/堆栈/内部主机名；详情进日志（见 `exception-handling.md`） |
| **权限检查** | 敏感操作必须先做权限校验（`@PreAuthorize` / 显式检查），不依赖客户端"不知道 URL" |

> **完整异常处理规范（异常分类、抛出、捕获、全局处理）见 `exception-handling.md`。安全编码原则与异常处理的交叉内容以 `exception-handling.md` 为准。**

***

## 7. 日期时间

- 使用 `OffsetDateTime`，禁止 `Date` / `Calendar` / `LocalDateTime`
- 时区处理：存储用 UTC（`OffsetDateTime.now(ZoneOffset.UTC)`），展示按用户时区转换
- 时间计算：`Duration`（精确时间） / `Period`（日期）
- 格式化：`DateTimeFormatter.ISO_OFFSET_DATE_TIME`

***

## 8. 并发与虚拟线程

### 8.1 虚拟线程（Java 21 正式特性）

**方式一：Spring Boot 配置（推荐）**
```yaml
# application.yml
spring.threads.virtual.enabled: true
```
→ Tomcat 请求处理、`@Async`、`ScheduledTask` 自动使用虚拟线程。

**方式二：手动创建（高级场景）**
```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var future = executor.submit(() -> doSomething());
    future.get(); // 必须检查异常
}
```

### 8.2 Scoped Values（Java 25 正式特性，JEP 506）

> ⚠️ **本项目基线 Java 21，此特性不可用**（Java 21 中为 preview API，需 `--enable-preview`；正式使用需 JDK 25+）。

Scoped Values 是 ThreadLocal 的**现代替代**：不可变、范围受限、与虚拟线程完美配合。

```java
public class RequestContext {
    // 定义 ScopedValue
    public static final ScopedValue<User> LOGGED_IN_USER = ScopedValue.newInstance();

    public void serve(Request request) {
        User user = authenticate(request);
        // 绑定值并执行 —— 作用域内所有方法可通过 LOGGED_IN_USER.get() 访问
        ScopedValue.where(LOGGED_IN_USER, user)
            .run(() -> controller.handle(request));
        // run() 返回后自动解绑 —— 无需手动清理
    }
}

// 任意深层方法中直接访问
public void processOrder(Long orderId) {
    User currentUser = RequestContext.LOGGED_IN_USER.get(); // 无需参数透传
    // ...
}
```

### 8.3 ThreadLocal → Scoped Values 迁移指南

| 维度 | ThreadLocal | Scoped Values |
|------|------------|---------------|
| 可变性 | 可变（`set()` / `remove()`） | **不可变**（绑定后不可修改） |
| 生命周期 | 手动管理（必须 `remove()` 防泄漏） | **自动管理**（`run()` / `call()` 结束自动解绑） |
| 线程安全 | 可变导致竞态条件 | 不可变 → 无竞态 |
| 继承 | `InheritableThreadLocal` 复制到子线程（开销大） | 不需复制（不可变共享） |
| 虚拟线程 | 可用但 `InheritableThreadLocal` 开销大 | **推荐**（为虚拟线程设计） |
| 内存 | ThreadLocal 持有引用，线程池中泄漏风险 | 作用域结束即释放 |

**何时迁移：**
- ✅ 请求级上下文（登录用户、Trace ID、租户 ID）→ **优先用 Scoped Values**
- ✅ 新项目 → 直接用 Scoped Values
- ⚠️ 已有 ThreadLocal 代码 → 可逐步迁移；库/框架的 ThreadLocal 集成暂不急
- ❌ 需要可变上下文（极少见）→ 仍用 ThreadLocal

### 8.4 注意事项

- 避免 `synchronized` → 改用 `ReentrantLock`（防止 carrier thread pinning）
- 不要池化虚拟线程（`VirtualThreadPerTaskExecutor` 不是池）
- `CompletableFuture` 用于异步组合
- 不可变集合作为线程安全默认

***

## Code Review Checklist

- [ ] 命名是否清晰、无自造缩写？（§2.1）
- [ ] 大括号是否始终使用？`@Override` 是否标注？（§2.2）
- [ ] `public` / `protected` 方法是否有 Javadoc？（§2.3）
- [ ] DTO 是否用 record？compact constructor 是否做了跨字段验证？（§3.1）
- [ ] sealed + record + pattern matching 是否穷尽覆盖？（§3.2）
- [ ] Optional 是否仅用于返回值（非字段/参数）？（§3.3）
- [ ] Stream 是否避免 `peek()` 滥用、`parallelStream()` 盲用？（§3.4）
- [ ] Null 安全是否采用三层防御（JSpecify + Optional + requireNonNull）？（§4.2）
- [ ] 不可变集合是否正确选择（`List.of` vs `unmodifiableList` vs `copyOf`）？（§4.3）
- [ ] 工具选择是否遵循优先级（JDK/Spring → Lombok → Commons）？（§5.1）
- [ ] Lombok `@Data` 是否避开 JPA Entity？（§5.2）
- [ ] 异常链是否保留 cause？空 catch 是否存在？（§6.1）
- [ ] 敏感数据是否脱敏？SQL 是否参数化？（§6.3）
- [ ] 日期是否用 `OffsetDateTime`？（§7）
- [ ] 请求级上下文是否考虑用 Scoped Values？（§8.2）

***

## 决策流程

```
功能需求 → JDK 原生可简洁实现?
  是 → 使用 JDK 原生 API
  否 → 项目使用 Spring?
    是 → Spring 内置工具可满足?
      是 → 使用 Spring 工具类
      否 → Commons 库可显著简化?
    否 → Commons 库可显著简化?
      是 → 引入对应 Commons 模块并注释原因
      否 → 其他库提供必需功能?
        是 → 按需引入 Guava/Hutool 等并注释原因
        否 → 手工实现，保持可读性
```
