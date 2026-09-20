---
name: "Java Object Calisthenics"
description: "Object Calisthenics rules with production grading — 9 design constraints from Jeff Bay"
paths:
  - "**/*.java"
---

# Java 对象健身操（Object Calisthenics）

**版本：** 1.0
**生效日期：** 2026-07-17
**来源：** Jeff Bay 发表于《The ThoughtWorks Anthology》的 "Object Calisthenics" 练习（共 9 条规则）
**适用范围：** 所有基于 Java 21+ 的后端项目（含 Spring Boot 4.0+）

***

## 1. 定位：刻意练习 → 生产务实分级

对象健身操是 9 条**刻意严苛**的 OO 设计约束，本意是「健身操」式的刻意练习：在 kata 中 100% 遵守全部规则，用约束倒逼出高内聚、低耦合、封装良好的设计直觉。生产代码按下表分级应用，避免教条化。

### 分级说明

| 级别 | 含义 |
|------|------|
| **强制** | 生产代码必须遵守，Code Review 可拦截 |
| **推荐** | 默认遵守；有充分理由可偏离，偏离处建议注释说明 |
| **健身目标** | 练习中 100% 遵守；生产中作为设计信号（闻到坏味道时的重构方向），不作硬性拦截 |

### 9 条规则速查表

| # | 规则 | 核心意图 | 生产级别 |
|---|------|---------|---------|
| 1 | One Level of Indentation per Method（每方法一层缩进） | 方法只做一件事 | 推荐 |
| 2 | Don't Use the ELSE Keyword（不用 else） | 卫语句 / 多态替代分支堆叠 | **强制** |
| 3 | Wrap All Primitives and Strings（包装原始类型与字符串） | 领域概念类型化 | 分级（§2.3） |
| 4 | First Class Collections（集合一等公民） | 集合与其行为封装成类 | 推荐 |
| 5 | One Dot per Line（一行一个点） | 迪米特法则（LoD） | 推荐（例外见 §2.5） |
| 6 | Don't Abbreviate（不缩写） | 名实相符，拒绝歧义 | **强制** |
| 7 | Keep All Entities Small（保持实体小巧） | 单一职责 | 推荐 |
| 8 | No More Than Two Instance Variables（实例变量 ≤ 2） | 高内聚信号 | 健身目标 |
| 9 | No Getters/Setters/Properties（不用访问器） | Tell, Don't Ask | 分级（§2.9） |

***

## 2. 各规则详解与落地

### 2.1 每方法一层缩进（推荐）

**原文意图：** 方法体内只允许一层缩进。嵌套意味着方法在做多件事，迫使把内层逻辑抽取为命名良好的私有方法。

**生产落地：** 原文要求严格一层；生产以「方法体缩进 ≤ 2 层」为默认目标，练习中按严格一层执行。超限时的重构手法（按优先级）：

1. 卫语句提前返回（与 §2.2 配合）
2. 嵌套循环/条件 → 抽取意图命名的私有方法
3. 集合处理 → Stream API（filter / map / reduce 天然消除嵌套）

```java
// BAD: 三层嵌套，多个意图挤在一起
BigDecimal total = BigDecimal.ZERO;
for (Order order : orders) {
    if (order.isActive()) {
        for (OrderItem item : order.getItems()) {
            if (item.isDiscountable()) {
                total = total.add(item.getPrice().multiply(DISCOUNT_RATE));
            }
        }
    }
}

// GOOD: Stream 一层一个意图
BigDecimal total = orders.stream()
        .filter(Order::isActive)
        .flatMap(order -> order.getItems().stream())
        .filter(OrderItem::isDiscountable)
        .map(item -> item.getPrice().multiply(DISCOUNT_RATE))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
```

**例外：** 防御性资源处理中的 try/catch 嵌套（连接/会话借还、目录预创建、静默关闭）允许至 3 层；更深的嵌套应抽取为 `acquireQuietly()` / `releaseQuietly()` 类方法。

### 2.2 不用 else（强制）

**原文意图：** else 是分支堆叠的温床。消除 else 迫使使用卫语句、多态与表驱动，让主流程保持线性、易读。

**生产落地：** 业务代码禁止 `else` 关键字。替代手法（按场景选择）：

| 场景 | 手法 |
|------|------|
| 前置校验 / 边界条件 | 卫语句（`if (...) return/throw`，主流程保持顶格） |
| 类型 / 状态分支 | switch 表达式 + 模式匹配（JDK 21），或 sealed 类型穷尽匹配 |
| 空值分支 | `Optional.map / orElseGet / orElseThrow` |
| 重复出现的行为分支 | 多态 / 策略模式（分支下沉到类型体系） |
| 状态 → 动作映射 | Map 查表（`Map<Status, Handler>`） |

```java
// BAD
public BigDecimal price(Order order) {
    if (order.isVip()) {
        return order.total().multiply(VIP_RATE);
    } else {
        return order.total();
    }
}

// GOOD: 卫语句
public BigDecimal price(Order order) {
    if (order.isVip()) {
        return order.total().multiply(VIP_RATE);
    }
    return order.total();
}
```

**说明：** 卫语句的 `if (...) return` 不算违规；简单取值的二元选择可用三元表达式，但涉及业务分支时优先上表手法。

### 2.3 包装原始类型与字符串（分级）

**原文意图：** `int`、`String` 等原始类型没有领域语义，导致校验与行为散落在各处；包装为类型后规则内聚，误用在编译期暴露。

**生产落地（与 architecture.md §3.2 判断标准一致）：**

| 场景 | 级别 |
|------|------|
| 字段有格式校验 / 业务运算 / 多字段组合，或同一校验出现在 ≥2 个 DTO 或方法中 | **强制包装为 VO** |
| 简单字符串/数值、仅在单个 DTO 中使用 | 不强制 |
| kata 练习中 | 全部包装（健身目标） |

**Java 21 落地：** 非持久化 VO 首选 `record`（语言级不可变 + 模式匹配）；JPA 持久化 VO 用 `@Embeddable`（示例见 architecture.md §3.2 的 `Email`）。

```java
// record 作轻量 VO：校验内聚在 compact constructor
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Assert.isTrue(amount.signum() >= 0,
            () -> "amount must be non-negative, was: " + amount);
    }

    public Money add(Money other) {
        Assert.isTrue(currency.equals(other.currency), "currency mismatch");
        return new Money(amount.add(other.amount), currency);
    }
}
```

**收益：** `create(Money price)` 从类型上杜绝「元/分混淆」，校验规则不再跨 DTO 重复。

### 2.4 集合一等公民（推荐）

**原文意图：** 任何包含集合成员变量的类不应再有其他成员变量 —— 集合及其操作（过滤、聚合、不变式校验）应封装为专用类。

**生产落地：** 集合上存在 ≥1 条业务操作（过滤规则、聚合计算、不变式校验）时，必须封装为专用类，并提供防御性复制 / 不可变视图 + 意图明确的领域方法。

```java
// GOOD: 集合 + 行为封装为一个类
public class OrderItems {
    private final List<OrderItem> items;

    public OrderItems(List<OrderItem> items) {
        Assert.notEmpty(items, "order must contain at least one item");
        this.items = List.copyOf(items);          // 防御性复制
    }

    public Money totalPrice() {
        return items.stream()
                .map(OrderItem::subtotal)
                .reduce(Money.ZERO, Money::add);
    }

    public List<OrderItem> asList() {
        return items;                             // 已不可变，可直接返回
    }
}
```

**例外（JPA）：** 聚合根的 `@OneToMany` 关联由 JPA 管理，不要求单独包装；但必须通过领域方法操作集合（`order.addItem(item)`），禁止向外部暴露可变集合引用。

### 2.5 一行一个点（推荐）

**原文意图：** `a.getB().getC().doSomething()` 意味着调用方深入了对象的内部结构（违反迪米特法则 / Law of Demeter），任何中间结构变化都会波及所有调用方。功能分配视角（Feature Envy、分层不穿透）见 `java-solid-lod.md` §2.6。

**生产落地：** 禁止跨越领域对象的 getter 链；改为 Tell, Don't Ask —— 在对象上声明意图方法。

```java
// BAD: 调用方知道 Order → Customer → Address 的内部结构
String city = order.getCustomer().getAddress().getCity();

// GOOD: 结构封装在 Order 内
String city = order.shippingCity();
```

**明确例外（不算违规）：**

| 例外类型 | 示例 | 理由 |
|----------|------|------|
| Fluent DSL / 建造者链 | `stream().filter(...).map(...).toList()`、`builder().name(...).build()`、RestClient、AssertJ 断言链 | 同一抽象的连续变换，DSL 本义即链式 |
| 异常 / 错误元数据访问 | `ex.getErrorCode().getHttpStatus()`、`ex.getBindingResult().getFieldErrors()` | 框架契约的固定结构，非领域对象结构 |
| DTO / record 组件访问 | `request.address().city()` | DTO 是纯数据载体，组件即公开契约（见 §2.9） |

### 2.6 不缩写（强制）

**原文意图：** 缩写造成歧义与概念分裂（`usr` / `user` / `account` 是同一事物吗？）；名字要长到说明白为止。

**生产落地（呼应 java-coding-standard.md §2.1 命名规范）：** 类、方法、字段、变量命名禁止自造缩写；方法名应完整表达意图（`findActiveOrdersByCustomer` 优于 `findActOrdByCust`）。

**例外（约定俗成的通用缩写）：** `id`、`url`、`uri`、`api`、`http`、`db`、`dto`、`vo`、`dao`、`io`、`xml`、`json`。判断标准：**新成员能否不假思索地读出全称** —— 不能就不是通用缩写。

### 2.7 保持实体小巧（推荐）

**原文意图：** 类不超过 50 行、包不超过 10 个文件 —— 用硬性尺寸上限强制单一职责。

**生产落地（尺寸作为信号而非红线）：**

| 信号 | 阈值 | 动作 |
|------|------|------|
| 方法行数 | > 15 行（不含签名/空行/注释） | 抽取私有方法，每个方法一个抽象层级 |
| 类行数 | > ~200 行 | 审视职责，按变化原因拆分（SRP） |
| 公开方法数 | > ~10 个 | 审视是否承担多个角色，考虑拆分协作者 |
| 包结构 | 按业务聚合组织 | 遵循 architecture.md §2 的分层 + 聚合包结构 |

**健身目标：** kata 中严格执行「类 ≤ 50 行、包 ≤ 10 文件」，体会尺寸约束如何逼出职责拆分。

### 2.8 实例变量 ≤ 2（健身目标）

**原文意图：** 实例变量超过 2 个的类几乎必然内聚度不足 —— 一半字段只被一半方法使用。这是 9 条中最严苛的规则，用于极限训练类的分解。

**生产落地：** 不作硬性拦截，作为**设计信号**：

- 行为类（Service、Domain Service、helper）依赖超过 ~5 个 → 审视是否多个职责挤在一个类里，按用例拆分或聚合协作者（把总是同时出现的几个依赖提炼为一个领域服务）
- 出现「字段分组」现象（一半方法只用一半字段）→ 按分组拆类

**例外：** JPA Entity（字段即表列映射）、DTO/record（数据载体）、`@ConfigurationProperties`（配置绑定）天然多字段，不适用本规则。

### 2.9 不用 getter/setter（分级）

**原文意图：** Tell, Don't Ask —— 不要向对象索要数据再替它做决定，把行为放到数据所在的对象里。getter/setter 泛滥是贫血模型的根源。

**生产落地（与 architecture.md §3.1 一致）：**

| 场景 | 级别 |
|------|------|
| 领域 Entity / VO 暴露 public setter | **禁止** —— 状态变更必须走意图明确的领域方法（`activate()` / `rename()`） |
| 「取数据 → 判断 → 改数据」写在调用方 | **禁止** —— 把判断与修改搬进对象内部 |
| 为绕过封装新增 getter | 避免 —— 先问「调用方真正想完成什么」，在对象上声明该意图方法 |

**明确例外：**

| 例外 | 理由 |
|------|------|
| DTO / record 的访问器 | 纯数据载体，组件即序列化契约 |
| JPA Entity 的 getter | 框架反射与 Mapper 读取的务实需要；但只读够用，**不构成放开 setter 的理由** |
| Controller / Mapper 读取 DTO 字段 | 协议转换层的本职 |

```java
// BAD: 调用方替实体做决定
if (user.getLoginFailures() >= 3) {
    user.setStatus(UserStatus.LOCKED);
}

// GOOD: 行为放在数据所在处
user.recordLoginFailure();   // 内部达到阈值自行锁定

// User 内部
public void recordLoginFailure() {
    this.loginFailures++;
    if (this.loginFailures >= MAX_FAILURES) {
        this.status = UserStatus.LOCKED;
    }
}
```

***

## 3. 练习建议（kata）

对象健身操的价值在「练」不在「背」。推荐方式：

1. 选一个 200~500 行的小题目（Conway's Game of Life、银行转账、购物车计价）
2. **100% 遵守全部 9 条严格版**（包括「类 ≤ 50 行」「实例变量 ≤ 2」「零 getter」）
3. 每违反一条就停下来重构，直到全部满足
4. 复盘：哪些约束逼出了好设计（通常是 2/3/5/9），哪些只是痛苦（通常是 8）—— 这正是上文分级取舍的由来

***

## 4. 与既有规范的关系

本篇章不引入与既有规范冲突的规则，对齐关系如下：

| 健身操规则 | 既有规范 |
|-----------|---------|
| §2.3 包装原始类型 | architecture.md §3.2 Value Object（判断标准一致） |
| §2.9 不用 getter/setter | architecture.md §3.1「领域方法替代 setter」、§8 反模式 #1 贫血 Entity |
| §2.4 集合一等公民 | architecture.md §3.1 聚合根通过领域方法操作关联 |
| §2.6 不缩写 | java-coding-standard.md §2.1 命名规范 |
| §2.1 一层缩进 / §2.2 不用 else | java-coding-standard.md §1.1 可读性第一原则 |
| §2.5 一行一个点 / §2.9 Tell, Don't Ask | java-solid-lod.md §2.6 迪米特法则（功能分配视角） |
