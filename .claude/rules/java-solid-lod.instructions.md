---
name: "Java SOLID and LoD"
description: "SOLID principles and Law of Demeter with production grading — class design and responsibility assignment"
paths:
  - "**/*.java"
---

# Java SOLID 与迪米特法则（LoD）

**版本：** 1.1
**生效日期：** 2026-08-19
**来源：** Robert C. Martin 整理的 SOLID 原则（SRP/OCP/LSP/ISP/DIP）；Ian Holland 提出的迪米特法则（Law of Demeter）
**适用范围：** 所有基于 Java 21+ 的后端项目（含 Spring Boot 4.0+）

***

## 1. 定位：类设计与功能分配的宏观原则

对象健身操（`java-object-calisthenics.md`）是**微观编码约束**（方法怎么写），SOLID 与 LoD 是**宏观设计原则**（类怎么分、职责怎么摆、依赖朝哪边）。两者配合：健身操逼出小类与封装，SOLID/LoD 回答「这些小类之间应该如何协作」。生产代码按同一套务实分级应用，避免教条化。

### 分级说明

| 级别 | 含义 |
|------|------|
| **强制** | 生产代码必须遵守，Code Review 可拦截 |
| **推荐** | 默认遵守；有充分理由可偏离，偏离处建议注释说明 |
| **设计信号** | 不作硬性拦截；闻到坏味道时的重构方向 |

### 原则速查表

| # | 原则 | 核心意图 | 生产级别 |
|---|------|---------|---------|
| 1 | SRP 单一职责 | 一个类只有一个变化原因 | 推荐（信号见 §2.1） |
| 2 | OCP 开闭原则 | 新增行为靠扩展，不靠修改 | 推荐 |
| 3 | LSP 里氏替换 | 子类必须可替换父类 | 推荐（契约部分**强制**，见 §2.3） |
| 4 | ISP 接口隔离 | 接口小而专，不强迫实现不需要的方法 | 推荐 |
| 5 | DIP 依赖倒置 | 依赖抽象，依赖方向指向领域 | **强制**（分层依赖方向） |
| 6 | LoD 迪米特法则 | 只与直接协作者交谈，不探知内部结构 | 推荐（例外同健身操 §2.5） |

***

## 2. 各原则详解与落地

### 2.1 SRP 单一职责（推荐）

**原文意图：** 一个类应该只有一个发生变化的原因（A class should have only one reason to change）。职责 = 变化原因；把「对谁负责」不同的代码分开，需求变更时改动互不波及。

**生产落地：**

- 分层职责边界已由 architecture.md **强制**保证（Controller 零业务逻辑、Service 只做编排、业务规则在 Domain）—— 类内部的 SRP 按本节的信号管理
- 判断问句：**「这个类要对几种不同来源的需求变更负责？」** 超过一个 → 候选拆分

**设计信号（出现即审视，按变化原因拆类）：**

| 信号 | 重构方向 |
|------|---------|
| 类名含糊：`{X}Manager` / `{X}Helper` / `{X}Util` 越滚越大 | 按用例拆为意图命名的类 |
| 「字段分组」：一半方法只用一半字段 | 按分组拆类（呼应健身操 §2.8） |
| 方法按调用方聚类：A 类调用者用方法 1/2，B 类调用者用方法 3/4 | 按调用方角色拆接口或拆类（呼应 §2.4 ISP） |
| 类行数 / 公开方法数超阈值 | 阈值与手法见健身操 §2.7 |

```java
// BAD: 一个类对「计价规则变化」和「通知渠道变化」两种原因负责
public class OrderService {
    public Money price(Order order) { /* 计价规则 */ }
    public void notifyCustomer(Order order) { /* 拼邮件、发短信 */ }
}

// GOOD: 计价归领域，通知走事件 + 监听器（呼应 architecture.md §7.3）
public class OrderPricing { public Money price(Order order) { ... } }
// OrderCreatedEvent → NotificationListener 负责通知，新增渠道只加 Listener
```

**例外：** Demo/种子数据类、测试夹具、跨切面配置类（`@Configuration`）天然承载多职责，不适用。

### 2.2 OCP 开闭原则（推荐）

**原文意图：** 对扩展开放、对修改关闭 —— 新增行为时增加新代码，而不是修改已通过测试的旧代码。

**Spring 落地手法（按变化频率选择）：**

| 变化类型 | 手法 |
|---------|------|
| 新增一种策略 / 算法（支付方式、计价策略） | 策略接口 + 多个 `@Component` 实现，注入 `Map<String, {Strategy}>` 或 `List<{Strategy}>` 按 key 路由，新增策略 = 新增一个 Bean |
| 新增一种副作用（创建后通知、审计、积分） | Domain Event + `@TransactionalEventListener`（architecture.md §7.3），新增副作用 = 新增 Listener |
| 类型 / 状态分支反复扩展 | sealed 类型 + 模式匹配穷尽分支（JDK 21），编译器强制处理新分支 |
| 状态 → 动作映射 | `Map<{Status}, {Handler}>` 查表（呼应健身操 §2.2） |

```java
// GOOD: 新增支付方式 = 新增一个 @Component，PaymentRouter 零修改
public interface PaymentStrategy {
    PaymentChannel channel();
    Receipt pay(Money amount);
}

@Component
@RequiredArgsConstructor
public class PaymentRouter {
    private final Map<String, PaymentStrategy> strategies;   // key = bean name

    public Receipt pay(String channel, Money amount) {
        PaymentStrategy strategy = Optional.ofNullable(strategies.get(channel))
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_CHANNEL_UNSUPPORTED));
        return strategy.pay(amount);
    }
}
```

**反教条约束：** 变化点尚未出现前保持简单（YAGNI）。第一次出现用 if/switch 直接写；**第二次出现同类分支**才引入上表扩展机制。为想象中的变化预先抽象，比修改旧代码危害更大。

### 2.3 LSP 里氏替换（推荐；契约部分强制）

**原文意图：** 任何使用父类型的地方，替换成子类型后行为仍然正确。子类可以扩展，但不得改变父类型的契约语义。

**Java 落地（优先级从高到低）：**

1. **组合优于继承** —— 默认不建实现继承层级；复用通过组合 + 委托，变化通过策略接口
2. **继承仅限两种合法场景**：框架扩展点（如测试基类 `BaseApiTest`、异常基类）与「天然 is-a 且行为完全兼容」的领域类型
3. **接口契约（强制部分）** —— 实现类必须满足接口声明的契约：
   - 不得强化前置条件（接口说接受任意正数，实现不能只接受偶数）
   - 不得弱化后置条件（接口承诺非空返回，实现不能返回 null / 空）
   - 不得抛出接口未声明的异常类型（业务错误统一 `BusinessException` + `ErrorCode`，见 exception-handling.md；两种表达模式的选型判据见该文件 §2.1）
   - 不得对「不需要的能力」抛出 `UnsupportedOperationException` —— 那是 ISP 问题（§2.4），应拆接口

```java
// BAD: 子类破坏了父类型「面积设置互不干涉」的直觉契约
class Square extends Rectangle {
    @Override void setWidth(int w)  { this.width = w; this.height = w; }
    @Override void setHeight(int h) { this.width = h; this.height = h; }
}

// GOOD: 不继承，各自实现 Shape 接口；可替换的是「接口」而非「父类」
sealed interface Shape permits RectangleShape, SquareShape { int area(); }
```

**与 TDD 的关系：** 单元测试是契约的最好载体 —— 针对接口写一组契约测试，所有实现类复用同一组测试（Contract Test 思想在类级的应用，见 tdd-workflow.md）。

### 2.4 ISP 接口隔离（推荐）

**原文意图：** 不应强迫任何客户端依赖它不使用的接口方法。胖接口导致实现类被迫空实现、调用方被迫看到无关方法。

**生产落地：**

| 场景 | 规则 |
|------|------|
| Repository | 按聚合各自定义（`{Entity}Repository` 具体类），禁止合并为一个巨型 Repository 强迫所有聚合挂上用不到的方法 |
| 下游 Client | 按下游服务能力拆分（`NotificationClient` / `PaymentClient` 具体类），禁止一个 `{Vendor}Client` 塞入全部调用 |
| 角色接口 | 一个类对多种调用方暴露不同能力时，按调用方角色定义窄接口（如 `Priceable` / `Cancelable`），而非一个大接口 |
| 抽接口的时机 | 默认具体 class；仅多实现 / 策略场景抽接口（呼应 service-conventions.md 与 architecture.md §1；本架构无 Port-Adapter 对） |

```java
// BAD: 胖接口 —— 只读的报表实现被迫空实现写方法
public interface UserPort { User save(User u); Optional<User> findById(Long id); void merge(User u); void purge(); }

// GOOD: 按角色拆分，调用方只依赖需要的窄接口
public interface UserQueryPort { Optional<User> findById(Long id); }
public interface UserCommandPort { User save(User u); }
```

**注意：** ISP 不是「接口越多越好」。本架构全部具体类，单实现 + 单调用方的接口是过度设计，出现第二实现前禁止预抽。

### 2.5 DIP 依赖倒置（强制）

**原文意图：** 高层模块不依赖低层模块，二者都依赖抽象；抽象不依赖细节，细节依赖抽象。

**本架构的落地（依赖规则与 architecture.md §1 一致）：**

| 规则 | 级别 |
|------|------|
| 依赖具体类而非抽象：application executor 注入 `{Entity}Service`（domain）与 `{Service}Client`（infrastructure 具体类） | **本架构约定** —— 无 Port 接口，依赖一律指向稳定的具体类 |
| domain 不依赖任何业务层 | **强制** —— 依赖方向恒指向 Domain |
| 依赖通过构造器注入（`@RequiredArgsConstructor`） | **强制** —— 禁止字段 `@Autowired`、禁止 `new` 具体实现 |
| 下游通知等副作用优先 Domain Event 解耦（executor 依赖事件而非 Client） | **推荐** —— 需要可替换/需测试隔离时的替代 Port 的手段 |
| 类内部依赖具体工具类（`ObjectMapper`、JDK 工具） | 不受限 |

```java
// BAD: Controller/Executor 跨层直连数据访问与 HTTP 细节，职责混杂
@Service
@RequiredArgsConstructor
public class CreateUserCommandExecutor {
    private final EntityManager entityManager;        // 数据访问细节
    private final RestClient notificationRestClient;  // HTTP 技术细节（应走 Client 或事件）
}

// GOOD: 依赖分层组件，技术细节收敛于各自的类
@Service
@RequiredArgsConstructor
public class CreateUserCommandExecutor {
    private final UserService userService;                   // domain 薄封装
    private final ApplicationEventPublisher eventPublisher;  // 通知走事件，连 Client 都不直接依赖
}
```

**收益即测试性：** 单元测试用 Mockito mock 具体类（test-conventions.md）、API 测试用 WireMock stub 下游 HTTP 边界（downstream-conventions.md），不依赖接口也可替换实现。

### 2.6 LoD 迪米特法则（推荐）

**原文意图：** 只与你的直接朋友交谈（Don't talk to strangers）—— 一个方法只能调用：自身、入参、成员变量、以及自己创建的对象；不得通过返回值链条深入协作者的内部结构。

**与对象健身操的关系：** 编码层面「一行一个点」的判定、例外表（Fluent DSL / 错误元数据 / DTO 组件）以健身操 §2.5 为准，本节从**功能分配**视角补充两条规则：

**规则一：Feature Envy 搬移 —— 方法羡慕谁的数据，就搬去谁那里**

```java
// BAD: 方法 80% 在用 Order 的数据 —— 功能分配错位
public class InvoiceService {
    public Money freight(Order order) {
        if (order.totalAmount().compareTo(FREE_FREIGHT_THRESHOLD) >= 0) {
            return Money.ZERO;
        }
        return order.isRemoteArea() ? REMOTE_FEE : NORMAL_FEE;
    }
}

// GOOD: 搬移到数据所在处，Service 只剩编排
// Order 内部
public Money freight() {
    if (totalAmount().compareTo(FREE_FREIGHT_THRESHOLD) >= 0) {
        return Money.ZERO;
    }
    return isRemoteArea() ? REMOTE_FEE : NORMAL_FEE;
}
```

**规则二：分层不穿透 —— 每层只与相邻层交谈**

| 穿透（禁止） | 正确路径 |
|-------------|---------|
| Controller 直接调用 Repository / Entity | Controller → Service → Repository |
| Controller 组装多个 Service 做编排 | 编排逻辑收进 Application Service |
| Mapper 调用 Repository 补数据 | Service 取齐数据后交给 Mapper 转换 |
| 测试直接改私有字段绕过领域方法 | 走公开 API 或包私有测试辅助 |

**收益：** 功能分配有唯一正确答案可寻 —— 「这个数据上的判断」总在数据所在的类里，「这个用例的步骤编排」总在 Application Service 里，新人无需猜测逻辑在哪。

***

## 3. 功能分配决策表

新增一段逻辑时，按此表自上而下找到第一个匹配的位置（与 architecture.md §3.4、§8 一致）：

| 逻辑特征 | 归属 | 依据原则 |
|---------|------|---------|
| 只涉及单个 Entity / VO 自身状态的判断或变更 | Entity / VO 的领域方法 | SRP + LoD（Feature Envy）+ 健身操 §2.9 |
| 跨多个聚合、或需查询外部数据的业务规则 | Domain Service | SRP |
| 用例步骤编排（取数 → 调领域 → 转换返回） | Application Service | SRP + 分层不穿透 |
| 副作用（通知、审计） | Domain Event + Listener | OCP + DIP |
| 可替换的算法 / 渠道 / 策略 | 策略接口 + Bean 路由 | OCP + DIP |
| HTTP 协议转换、DTO ↔ Entity 转换 | Controller / Mapper | 分层不穿透 |
| 技术实现细节（JPA、HTTP、配置） | Infrastructure 层 Adapter | DIP |

***

## 4. 与既有规范的关系

本篇章不引入与既有规范冲突的规则，对齐关系如下：

| 原则 | 既有规范 |
|------|---------|
| §2.1 SRP | 健身操 §2.7 保持实体小巧（尺寸信号）、§2.8 字段分组信号；architecture.md §8 反模式 #2 |
| §2.2 OCP | architecture.md §7.3 Domain Event；健身操 §2.2 不用 else（分支下沉手法） |
| §2.3 LSP | validation.md（契约编程：前置/后置条件）；exception-handling.md（异常统一） |
| §2.4 ISP | architecture.md §3.3 Repository 按聚合定义；service-conventions.md「全具体类」 |
| §2.5 DIP | architecture.md §1 分层依赖规则、§8 反模式 #9/#12 |
| §2.6 LoD | 健身操 §2.5 一行一个点（含例外表）、§2.9 Tell, Don't Ask；architecture.md §6 rest 层零业务逻辑 |
