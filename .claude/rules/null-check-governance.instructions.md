---
name: "Null-Check Governance"
description: "判空坏味道识别与改造执行 — 分层校验职责模型、NC-001~NC-014 坏味道规则表、BAD/GOOD 对照、改造顺序与验收标准、Agent Prompt 模板"
paths:
  - "**/*.java"
---

# 判空治理规范（NC 规则与改造执行）

**适用范围：** JDK 21 + Spring Boot 4.1 + Jakarta Validation 3.1

> **职责边界：** 本文件是判空坏味道**识别与改造执行**的唯一权威——分层校验职责模型、NC-001~NC-014 坏味道规则表、BAD/GOOD 对照、改造顺序与验收标准、Agent Prompt 模板。具体机制规范分属各专题文件：声明式/命令式校验见 `validation.md`，校验失败的异常出口见 `exception-handling.md` §6，Optional/Null 安全/Lombok 见 `java-coding-standard.md` §3.3/§4.2/§5.2，DDL 约束见 `db-conventions.md`。冲突时机制细节以对应专题文件为准。

***

## 1. 分层校验职责模型

每层只校验职责内的约束，层间以契约为信任边界，**不重复设防**。

| 层级 | 校验职责 | 主要手段 | 规范出处 |
|------|---------|---------|---------|
| Controller 边界层 | 入参格式与非空 | Bean Validation 注解 + `@Valid` 级联 | `validation.md` §2 |
| 配置边界层 | 启动期配置校验 | `@ConfigurationProperties` + `@Validated` | `validation.md` §2.8 |
| Service 入口层 | 业务前置条件 | 方法级校验 / Spring `Assert`；语义校验抛类型化领域异常 | `validation.md` §2.7/§3、`exception-handling.md` §4.2 |
| Service 内部/领域层 | 不重复判空，依赖契约 | JSpecify `@NullMarked` + VO 紧凑构造器 | `java-coding-standard.md` §4.2 |
| 持久层 | 最终一致性兜底 | DB `NOT NULL`/唯一约束 + 实体注解 | `db-conventions.md` |

**落地原则：**

1. **边界校验注解化**——外部输入约束用 Bean Validation 注解声明，由框架统一执行；配置约束声明在绑定类上，启动期失败即拒绝启动
2. **内部校验断言化**——注解覆盖不到的内部判空用 `Assert`/`Objects.requireNonNull` 一行完成，失败即抛异常
3. **空值语义静态化**——JSpecify `@NullMarked`/`@Nullable` 把「可空/非空」变成可检查的契约（`java-coding-standard.md` §4.2）
4. **错误响应标准化**——校验失败经唯一 `GlobalExceptionHandler` 出口，统一 `ApiResponse` 信封（`exception-handling.md` §6；**不启用 ProblemDetail**，见 §6.4）

## 2. 判空坏味道类谱系（扫描地图）

判空失控散布于所有类类型。先按类名后缀、包结构与注解（`@Component`、`@KafkaListener` 等）识别类类型，再对照下表定位坏味道，最后按 §3 NC 规则机械匹配改造动作——把「哪里要改」从阅读全库的判断题转化为按类型过滤的匹配题。**未命中任何类类型的类，默认不引入新的判空分支。**

| 类类型 | 典型判空坏味道 | 归属治理 |
|--------|--------------|---------|
| Controller | 手写 if-null 校验 DTO，消息逐字拼接 | NC-001 → `validation.md` §2 |
| DTO↔Entity 转换器/Mapper/Assembler | 逐字段 if-null 拷贝 | 值对象（`architecture.md` §3.2）+ Mapper 纪律（`db-conventions.md`） |
| 工具类/Helper | 入口防御性判空后 `return null`，空语义含糊 | Optional 返回（`java-coding-standard.md` §3.3）/ 契约式设计 |
| 领域对象与实体 | getter 链深层判空、防御性副本 | NC-011、误区 #14 |
| 消息消费者/定时任务/事件监听 | payload 与上下文手工判空 | `Assert`（`validation.md` §3.1）+ 异常通道（`exception-handling.md` §7） |
| 远程调用客户端封装 | 响应体多层嵌套判空 | Optional 链 / 边界翻译（`downstream-conventions.md` §4） |
| 配置读取处 | 散落 `@Value` + 使用处判空 | NC-014 → `validation.md` §2.8 |
| 函数式管道 | map 链中 if-null 短路 | `java-coding-standard.md` §3.3/§3.4 |

## 3. NC 坏味道识别规则表（NC-001 ~ NC-014）

「检测模式」为正则级或 AST 级特征，可实现为扫描脚本（grep/semgrep/AST 插件）。与 `architecture.md` §8 反模式表的关系：**NC 表是判空专题的可扫描子集**，§8 覆盖架构级反模式，两者交叉引用不重复。

严重级：**高** = 语义错误或缺陷；**中** = 可维护性；**低** = 规范性。

| 编号 | 检测模式 / 代码特征 | 治理目标 | 严重级 |
|------|--------------------|---------|-------|
| NC-001 | AST：方法带 `@PostMapping`/`@PutMapping`/`@GetMapping`，方法体内出现对 `@RequestBody` 参数 getter 结果的 `== null`/`isEmpty` 判断后接 `throw` | 迁移到 DTO Bean Validation（`validation.md` §2.1） | 高 |
| NC-002 | 正则计数：同一方法体内连续 ≥3 条 `if\s*\([^)]*(== null\|isEmpty\|isBlank)[^)]*\)\s*\{?\s*throw` | `validation.md` §2（注解化）或 §3.1（Assert 链） | 高 |
| NC-003 | 正则：`== null \|\| ""\.equals\(`、`.length()\s*==\s*0`、`.trim()\.isEmpty()`、`.equals("")` | `StringUtils.hasText`/`isBlank`（`java-coding-standard.md` §5.1） | 中 |
| NC-004 | 正则：`== null \|\| \w+\.size()\s*==\s*0`、`== null \|\| \w+\.isEmpty()` 且左操作数为集合/Map | Spring `CollectionUtils.isEmpty`（`java-coding-standard.md` §5.3） | 中 |
| NC-005 | AST 跨类匹配：同一参数名在 Controller 方法与下游 Service 方法中均存在 null 检查 | 分层职责模型：保留 Controller 一处，删除 Service 重复判空（§1） | 中 |
| NC-006 | AST：`@NotNull` 标注的字段/参数类型为 `String`，且业务语义要求非空白（消息含「不能为空」） | 改 `@NotBlank`（`validation.md` §2.2） | 高（语义错误） |
| NC-007 | AST：字段或集合元素类型为自定义 DTO（`List<XxxDto>`/`XxxForm`），字段与 `@RequestBody` 形参上均无 `@Valid` | 补 `@Valid`（字段级 + `List<@Valid DTO>`，`validation.md` §2.4） | 高（缺陷） |
| NC-008 | 正则/AST：工程内无 `@RestControllerAdvice` 类；或 `catch` 块返回 `ResponseEntity.status(500)`、`printStackTrace()`、把 `e.getMessage()` 直返前端 | `exception-handling.md` §6（`GlobalExceptionHandler` + `ApiResponse` 信封） | 高 |
| NC-009 | AST：`Optional` 出现在实体/DTO 字段声明、方法形参类型、`@RequestParam` 之外的成员位置 | `java-coding-standard.md` §3.3：字段改普通类型 + `@Nullable`，参数拆方法或改 `@Nullable` | 中 |
| NC-010 | 正则计数：`throw new (IllegalArgumentException\|...Exception)\("` 的中文字面量在仓库内出现 ≥5 处且无 `ValidationMessages.properties` | 消息外化（`validation.md` §2.10） | 低 |
| NC-011 | 正则：同一表达式内 ≥3 级 getter 裸链 `\.get\w+\(\)\.get\w+\(\)\.get\w+\(\)` 且无 null 防护 | Optional 链（`java-coding-standard.md` §3.3）或值对象/Null Object（`architecture.md` §3.2） | 中 |
| NC-012 | AST：Service 的 `public` 方法参数无约束注解、类无 `@Validated`，且方法体首段无 `Assert`/`Preconditions` 调用 | 方法级校验（`validation.md` §2.7）或 Assert（§3.1） | 中 |
| NC-013 | AST+DDL 比对：实体列 `nullable = false` 而对应 DTO 字段无 `@NotNull`/`@NotBlank`；或 DDL `UNIQUE` 而代码无任何查重 | 三层对齐（`db-conventions.md`「约束三层对齐」） | 中 |
| NC-014 | AST：类标注 `@ConfigurationProperties` 而类上缺 `@Validated`，或类内字段无任何约束注解；或同一前缀的散落 `@Value("${...}")` ≥3 处且使用处存在手工判空；嵌套配置对象字段缺 `@Valid` 一并命中 | `validation.md` §2.8 | 高 |

> **使用注意：**
> - 正则级模式会产生误报，**扫描结果须经语义确认，禁止命中即批量替换**
> - NC-005 与 NC-013 为跨文件规则，需全仓库索引或人工评审
> - 严重级决定修复优先级而非改造顺序（排序见 §5）

## 4. 高频反模式 BAD/GOOD 对照

8 组代码对覆盖 NC 规则主项，可直接作替换模板。

### NC-001：Controller 手写判空 → DTO 注解校验

```java
// BAD — 校验逻辑混在 Controller 体内，消息魔法字符串散落
@PostMapping("/users")
public ResponseEntity<ApiResponse<UserRepresentation>> create(@RequestBody CreateUserCommand cmd) {
    if (cmd.getUsername() == null) {
        throw new IllegalArgumentException("用户名不能为空");
    }
    ...
}

// GOOD — 约束声明在 record 组件上，框架统一执行
public record CreateUserCommand(
        @NotBlank(message = "{user.username.notBlank}") String username,
        @Email String email) {}

@PostMapping("/users")
public ResponseEntity<ApiResponse<UserRepresentation>> create(@Valid @RequestBody CreateUserCommand cmd) { ... }
```

### NC-002：if-null-throw 校验链 → Assert 链（Service 入口）

```java
// BAD
if (orderId == null) throw new IllegalArgumentException("orderId 不能为空");
if (amount == null) throw new IllegalArgumentException("amount 不能为空");
if (amount.signum() <= 0) throw new IllegalArgumentException("amount 必须为正");

// GOOD
Assert.notNull(orderId, "orderId must not be null");
Assert.notNull(amount, "amount must not be null");
Assert.isTrue(amount.signum() > 0, "amount must be positive");
```

### NC-003：手工空串判断 → 语义化工具方法

```java
// BAD
if (name == null || "".equals(name.trim())) { ... }

// GOOD — Spring 原生，null/空串/纯空白统一判为「无文本」
if (!StringUtils.hasText(name)) { ... }
```

> 纪律：字符串「是否缺失」一律用 blank 语义（`hasText`/`isBlank`）；commons-lang3 的 `isEmpty` 不承认纯空白为「空」，`" "` 会穿透校验，仅用于允许空白占位符的场景。集合判空无此歧义。

### NC-006：String 字段误用 `@NotNull`

```java
// BAD — " " 空白串可通过校验
@NotNull(message = "用户名不能为空")
String username;

// GOOD
@NotBlank(message = "{user.username.notBlank}")
String username;
```

判定路径：先问类型，再问空白是否算有效值——**字符串默认 `@NotBlank`，集合/Map 用 `@NotEmpty`，其余引用类型用 `@NotNull`**（语义矩阵见 `validation.md` §2.2）。

### NC-007：嵌套 DTO 缺 `@Valid` 导致级联失效

```java
// BAD — AddressDto 上的约束静默失效，无任何报错
public record CreateOrderCommand(
        @NotNull Long userId,
        AddressDto address,
        List<OrderItemDto> items) {}

// GOOD — 三级缺一不可：参数 @Valid 管顶层、字段 @Valid 管对象级联、List<@Valid T> 管容器元素
public record CreateOrderCommand(
        @NotNull Long userId,
        @Valid @NotNull AddressDto address,
        @NotEmpty List<@Valid OrderItemDto> items) {}
```

### NC-009：Optional 用作实体字段

```java
// BAD — JPA 无法映射，Jackson 序列化语义混乱
private Optional<String> nickname;

// GOOD — 字段保持原类型，可空性用 JSpecify 显式标注
@Nullable
private String nickname;
```

完整边界（仅返回值、禁字段/参数）见 `java-coding-standard.md` §3.3。

### NC-011：深层裸调用链 → Optional 链

```java
// BAD — 任一级为 null 即 NPE
String city = order.getUser().getAddress().getCity();

// GOOD
String city = Optional.ofNullable(order.getUser())
        .map(User::getAddress)
        .map(Address::getCity)
        .orElse("未知");
```

### NC-012：Service 公共入口零校验 → 方法级校验

```java
// BAD — id 传 null 时在持久层才炸出含糊异常
@Service
public class OrderService {
    public Order detail(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }
}

// GOOD — 类级 @Validated 激活方法级校验，入口快速失败
@Service
@Validated
public class OrderService {
    public Order detail(@NotNull Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));   // 语义校验仍抛类型化领域异常
    }
}
```

未给出代码对：NC-004 同 NC-003 类推；NC-005/NC-013 是删除与对齐操作；NC-010 迁移目标见 `validation.md` §2.10；NC-014 示例见 `validation.md` §2.8。

## 5. 改造顺序与优先级

按依赖关系排序：**异常出口是所有校验改造的安全网，必须先就位**。

| 顺序 | 动作 | 对应规则 | 主要风险 | 回滚点 |
|------|------|---------|---------|-------|
| ① | 确认全局异常出口覆盖完整：`GlobalExceptionHandler` 覆盖全部校验异常类型（含 `BindException`/`MethodValidationException`，见 `exception-handling.md` §6.2）。注意：参考实现当前未单独声明 `BindException` handler（`@RequestBody` 场景由其子类 `MethodArgumentNotValidException` 覆盖），启用表单绑定的项目须先补 | NC-008 | 新异常类型落入兜底 500 | 单类单 PR |
| ② | DTO 注解化：补齐三大非空注解、`@Valid` 级联、`@NotBlank` 修正 | NC-001/006/007 | 原先裸字段透传的脏数据请求开始被 400 拦截，影响调用方 | 按 DTO 分 PR，逐字段可注解回退 |
| ③ | 配置属性注解化校验：散落 `@Value` 收敛为 `@ConfigurationProperties` + `@Validated`，补约束与嵌套 `@Valid` | NC-014 | 校验失败即启动 fail-fast：各环境配置不齐全时应用无法拉起，需灰度验证 | 按配置类分 PR；回滚恢复 `@Value`，保留配置类定义 |
| ④ | 删除 Controller 体内手工判空（**仅删除已被 ② 覆盖的部分**） | NC-001/005 | 误删未被注解覆盖的校验，造成校验空洞 | 逐方法 diff 核对「删除的每条判断都有对应注解」 |
| ⑤ | Service 入口断言收敛：方法级校验或 Assert，删除内部重复判空 | NC-002/012/005 | 自调用绕过 AOP 代理校验；异常类型由 NPE 变为 `MethodValidationException` | 按 Service 类分 PR |
| ⑥ | 渐进引入 NullAway：新模块先 `@NullMarked`，存量按包推进 | NC-009/011 辅助 | 存量静态报错量大，阻塞构建 | 先设 WARN 级，达标后再升 ERROR（`java-coding-standard.md` §4.2） |
| ⑦ | 设计层重构：值对象、Null Object、record 紧凑构造器 | NC-011 等 | 改动面大、语义风险高，不适合与 ①–⑥ 混排 | 单独排期、单独 PR |

排序逻辑：② 在 ④ 之前——注解先就位、再删手工判空，不留校验空洞窗口。③ 与 ② 可并行，但配置缺失缺陷暴露最晚（运行期才炸），尽早收敛收益最大。

## 6. 改造安全约束

以下约束任何步骤均不可违反：

1. **不改业务语义**——只改变校验表达方式，不改变拒绝集与放行集：改造前后用相同边界输入集（null、空串、空白串、空集合、超长字段）比对每个公开接口，行为必须一致
2. **异常类型变化是破坏性变更**——手工 `if-throw IllegalArgumentException` 改为注解校验后，异常变为 `MethodArgumentNotValidException`/`HandlerMethodValidationException`；改造前须全局检索 `catch`、`@ExceptionHandler`、`assertThrows` 及前端/第三方错误码契约，确认经 `GlobalExceptionHandler` 转换后对外 `ApiResponse` 结构不变
3. **批量改造分 PR**——每个 PR 只含一类规则或一个模块，禁止全仓库一次性重写
4. **每步跑测试**——每个 PR 合入前执行全量编译、相关模块单测与集成测试、契约测试；无测试覆盖的接口先补边界输入测试
5. **对外 API 错误响应结构保持兼容或显式版本升级**——`ApiResponse` 信封（`api-conventions.md`）对调用方是契约；无法兼容时按 API 版本演进处理，不得在既有版本上静默改变响应结构

## 7. 验收 Checklist

每个改造 PR 合入前逐项勾选：

- [ ] 编译通过，无新增 warning
- [ ] 单元测试与集成测试全部通过；删除的手工判空均有对应注解/断言的测试断言覆盖
- [ ] 已接入 NullAway 的模块静态分析零新增报错
- [ ] 手工探针——空白串：对 `@NotBlank` 字段提交 `" "`，确认返回 400 且 `ApiResponse.errors[]` 字段级错误正确
- [ ] 手工探针——嵌套 null：嵌套 DTO 字段提交 `null` 与非法值，确认 `@Valid` 级联生效
- [ ] 手工探针——空集合与缺元素：`items: []` 与 `items: [{}]` 分别触发 `@NotEmpty` 与元素级约束
- [ ] 手工探针——超长字段：超过 `@Size` 上限的输入返回 400 而非落库或 500
- [ ] 手工探针——基本类型 null：JSON 显式 `null` 传给基本类型字段，确认被反序列化层拦截（`HttpMessageNotReadableException` → 400）。前置条件：`spring.jackson.deserialization.fail-on-null-for-primitives=true`（**默认为 false，此时 null 静默绑定 0，探针必然失败**）；未开启该开关时，正确做法是改用包装类型 + `@NotNull`
- [ ] 手工探针——配置项：故意缺失或注入非法值，确认启动 fail-fast 且 FailureAnalyzer 输出指向具体属性（NC-014）
- [ ] 错误响应结构符合契约：`ApiResponse` 的 `code`/`message`/`errors[]` 与 `api-conventions.md` 一致
- [ ] 异常通道分离：类型化领域异常（业务规则）与参数校验异常（`VALIDATION_ERROR`）返回不同错误码，互不混用
- [ ] DB 约束抽查：改造涉及的字段在 DDL 中均有对应 `NOT NULL`/长度/唯一约束（NC-013）

## 8. Agent Prompt 模板

以下系统提示词可直接提供给 AI coding agent，方括号处按仓库实际填写：

```text
你是 Java/Spring 判空改造执行 agent。目标仓库：[仓库路径]，技术基线：Spring Boot 4.1 /
Spring Framework 7、jakarta.validation 命名空间（Jakarta Validation 3.1 +
Hibernate Validator，版本由 Boot BOM 托管）、JDK 21。禁止引入 javax.validation 任何引用。

【任务范围】按 NC 规则表（NC-001~NC-014，见 .claude/rules/null-check-governance.instructions.md §3）
扫描并改造判空坏味道。本次任务仅执行规则：[NC-XXX, NC-YYY, ...]；
每次只提交一个规则或一个模块的 PR。

【分层校验职责模型】改造必须服从以下职责划分：
- Controller 边界层：入参校验全部迁移到 DTO 的 Bean Validation 注解 + @Valid 级联，
  删除方法体内手写 if-null 判空（仅当每条判断都有对应注解时才允许删除）。
- 配置边界层：散落 @Value 注入收敛为 @ConfigurationProperties + @Validated，
  约束注解声明在绑定类上，嵌套配置对象字段必须加 @Valid；启动期校验失败即 fail-fast。
- Service 入口层：用方法级校验（类级 @Validated + 参数约束注解，异常
  MethodValidationException）或 Spring Assert（失败抛 IllegalArgumentException）。
- Service 内部/领域层：不新增防御性判空，依赖契约；可空点用 JSpecify @Nullable 标注。
- 持久层：DB NOT NULL/唯一约束兜底，发现 DDL 与 DTO 注解不一致时只做记录并报告，
  不擅自修改迁移脚本。

【异常与错误契约】业务错误一律通过 BusinessException + ErrorCode 表达（按 HTTP 处置类别的
语义子类 NotFoundException/ConflictException 位于 application/exception/，
见 exception-handling.md §3）；新增业务错误只新增 ErrorCode 枚举值，
禁止每实体新建异常类（异常类爆炸）。
错误响应对外统一为 ApiResponse 信封（code/message/errors[]，见 api-conventions.md），
禁止启用 ProblemDetail（exception-handling.md §6.4）。

【执行约束（违反即停止并报告）】
1. 不改业务语义：同一输入改造前后的「拒绝/放行」判定必须一致。
2. 异常类型变化（如 IllegalArgumentException → MethodArgumentNotValidException /
   HandlerMethodValidationException）前，先全局检索调用方 catch、@ExceptionHandler、
   测试中的 assertThrows 与前端错误码契约，列出影响清单等待确认。
3. 每个 PR 前必须运行：全量编译、相关模块单测与集成测试；测试不通过不得提交。
4. 对外 API 错误响应结构（ApiResponse 信封）保持兼容。
5. 无测试覆盖的公开接口：先补边界输入测试（null、空串、空白串、空集合、超长字段），
   再执行改造。
6. 正则/AST 扫描命中的每一处都必须经语义确认，禁止命中即替换。

【输出要求】每处改动输出：命中规则编号、文件与行号、BAD/GOOD 代码、
对应注解或断言的映射依据、测试结果摘要。发现规则表未覆盖的坏味道模式时，
记录特征并报告，不自行扩展规则。
```

## 9. 方案选型评估矩阵

判空治理各手段按**拦截时点**形成梯队（梯队是修复成本梯度而非优劣排名：每层启用对应手段，高层不替代低层）：

| 手段 | 拦截时点 | 覆盖场景 | 规范出处 |
|------|---------|---------|--------------|
| JSpecify + NullAway 静态分析 | 编译期 | 代码内部空安全契约 | `java-coding-standard.md` §4.2 |
| 配置属性绑定校验 | 启动期 | 配置边界层 | `validation.md` §2.8 |
| Bean Validation + 全局异常出口 | 请求边界运行期 | DTO 入参、方法/路径参数、级联 | `validation.md` §2 + `exception-handling.md` §6 |
| Assert 工具式判空 | 内部运行期 | 注解覆盖不到的内部前置条件 | `validation.md` §3.1 |
| 默认值策略（横切） | 绑定期 / 入口 / 内部 | 可选配置、可选参数、有合理回退的返回值 | `validation.md` §2.8（`@DefaultValue`）、`java-coding-standard.md` §5.1 |
| Optional 返回契约 | 内部运行期 | 查询返回值空语义 | `java-coding-standard.md` §3.3 |
| Lombok `@NonNull` | 内部运行期（硬失败） | 构造器/必填依赖注入 | `java-coding-standard.md` §5.2 |
| 值对象/record 紧凑构造器/Null Object | 设计预防 | 从源头减少判空需求 | `architecture.md` §3.2、`java-object-calisthenics.md` §2.3 |
| Jackson/DB 边界兜底 | 序列化/持久层 | 非法输入拦截、脏数据不落盘 | `exception-handling.md` §6.2、`db-conventions.md` |

> 静态分析与 Bean Validation 管辖不同信任边界：编译器看不见请求体/消息负载/配置文件（Bean Validation 运行时拦截）；内部把 null 传给非空参数是 NullAway 辖区。标准姿势是**入口校验一次、内部信任契约**。
>
> 默认值策略是横切手段而非新防御层：适用于「可选且有合理回退」的场景（可选配置、可选参数、第三方返回值回退）；**禁止用于关键数据缺失**——配置错误被默认值静默掩盖，比直接 fail-fast 更难排查（误区 #17）。

**扩展方向（按需引入的横切增强，非新机制）：**

| 扩展方向 | 建议引入形式 |
|---------|------------|
| OWASP 安全专项：服务端强制校验、allowlist 优先、全字段长度上限、失败即拒绝、`@Pattern` 警惕 ReDoS、枚举不匹配记入安全日志 | 独立安全 checklist，与 `validation.md` §2 配套 |
| 测试侧实践：`@WebMvcTest` 验证 400 契约、`@JsonTest` 验证 Jackson 边界、属性化测试验证校验不变量（合法不抛/非法必拒） | 并入 `test-conventions.md` / §7 验收 Checklist |
| 云原生配置边界：K8s 环境变量按 UPPER_SNAKE 映射 relaxed binding、启动打印脱敏生效配置、Actuator `configprops`/`env` 端点审计 | `validation.md` §2.8 的部署侧补充 |
| 工具判空与静态分析协同：手写判空收敛为语义校验 + 内部断言，空安全契约移交 JSpecify/NullAway | `validation.md` §3 与 `java-coding-standard.md` §4.2 联用时的职责划分 |

## 10. 误区速查表

按「误区 → 后果 → 正确做法」汇总，供评审速查；命中 NC 编号的条目可被工具自动扫描。完整机制回对应专题文件核对，本表只做索引。

| # | 误区 | 后果 | 正确做法 |
|---|------|------|---------|
| 1 | 所有字段一律加 `@NotNull`（过度校验） | 合法的可空字段被误拒，调用方被迫传占位垃圾值 | 按业务语义逐字段判定：可空字段留空或加 `@Nullable` |
| 2 | 用 `try-catch NullPointerException` 代替判空 | NPE 可能来自链上任意一级，吞掉真实缺陷；异常路径性能差 | 入口显式校验 + 契约式设计，NPE 只代表 bug，不应被捕获处理 |
| 3 | 认为 `@Valid` 与 `@Validated` 可互换 | 分组在 `@Valid` 上不生效；Service Bean 上用 `@Valid` 无效果 | `@Valid` 负责级联触发；`@Validated` 负责分组与 Service 类级激活（`validation.md` §2.3） |
| 4 | 分组滥用：Create/Update/Delete 多分组堆在一个 DTO 上 | 字段-分组矩阵难维护，新增字段漏配分组即校验失效 | 优先按场景拆 DTO（`architecture.md` §4.2），分组仅用于字段集高度重叠的少数场景 |
| 5 | String 字段用 `@NotNull` 期望「不能为空」 | `" "` 空白串通过校验，语义错误（NC-006） | String 一律默认 `@NotBlank` |
| 6 | 嵌套 DTO / 集合元素缺 `@Valid` | 嵌套约束静默失效，无任何报错（NC-007） | 字段加 `@Valid`，集合写 `List<@Valid DTO>` |
| 7 | `Optional.get()` 裸调 | 与直接解引用 null 等价，异常换成 `NoSuchElementException`，无错误码契约 | `orElseThrow(() -> new NotFoundException(ErrorCode.{ENTITY}_NOT_FOUND, id))` 或 `map`/`orElseGet` 链（`exception-handling.md` §4.2） |
| 8 | `isPresent()` + `get()` 组合 | 换皮的 if-null，未利用函数式 API | 改 `ifPresent`/`map`/`orElse*` 链式表达（`java-coding-standard.md` §3.3） |
| 9 | 项目内混用多套判空工具类 | `isEmpty`/`isBlank` 语义在不同库间漂移（NC-003/004） | 固定一套，优先级 Spring 自带 > commons-lang3 > Guava（`java-coding-standard.md` §5.1）；过渡期一次性替换后再删旧依赖 |
| 10 | 用领域异常通道返回参数格式错误 | 参数错误与业务规则违反混在一个错误码空间，前端无法区分「改输入重试」与「业务不满足」 | 参数校验走 Bean Validation → `VALIDATION_ERROR`；领域异常只表达业务规则（`exception-handling.md` §2） |
| 11 | 校验异常未捕获，裸抛 500 或泄漏堆栈 | 客户端收到无语义响应；堆栈泄漏内部结构（NC-008） | `GlobalExceptionHandler` 统一转换为 `ApiResponse`，字段级错误聚合返回 400（`exception-handling.md` §6） |
| 12 | DB 约束与代码注解不一致（DDL 宽、代码严，或反之） | 校验放行的数据落库报 500，或 DDL 比代码松导致脏数据经旁路写入（NC-013） | 迁移审查固定比对三层对齐（`db-conventions.md`）；DDL 更严时修复方向是补 DTO 注解而非放松 DDL |
| 13 | `Optional` 用作实体/DTO 字段或方法参数 | JPA 无法映射、Jackson 序列化语义混乱（NC-009） | 字段用普通类型 + `@Nullable`；Optional 只用于查询方法返回值（`java-coding-standard.md` §3.3） |
| 14 | 在 getter 中返回防御性副本或空对象来代替判空约定 | 掩盖空语义：调用方无法区分「无值」与「空值」 | 用 `@Nullable` 或 `Optional` 显式表达返回空语义；集合返回不可变空集合 `List.of()`（`java-coding-standard.md` §4.3） |
| 15 | 用散落 `@Value` 注入配置并在使用处手工判空 | 无校验通道、无配置元数据，key 拼错或缺失到运行期才暴露（NC-014） | 收敛为 `@ConfigurationProperties` + `@Validated`（`validation.md` §2.8），启动期 fail-fast；仅确需 SpEL 时保留 `@Value` |
| 16 | 认为嵌套配置属性不加 `@Valid` 也会被校验 | Boot 3.4+ 严格遵循规范，缺 `@Valid` 的嵌套属性被静默跳过，启动照常成功而校验未执行（NC-014） | 嵌套配置对象的字段/record 组件必须加 `@Valid`，与 DTO 级联规则一致（`validation.md` §2.8） |
| 17 | 给关键配置设默认值（`@DefaultValue`/字段初始化）掩盖缺失 | 配置漏配被默认值静默吞掉，系统以错误配置带病运行，问题推迟到更晚才暴露 | 关键配置缺失应 fail-fast（`@NotBlank` 无默认值）；默认值仅用于「有合理回退」的可选项（`validation.md` §2.8） |

> 第 2、9、10、17 条是「通道与边界」问题，扫描无法发现，只能依赖评审；第 5、6、11、12、13、15、16 条有对应 NC 规则可工具拦截。命中率最高的第 5、6、11 条可作重点宣讲项。
