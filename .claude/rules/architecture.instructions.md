---
name: "Architecture"
description: "Layered architecture with CQRS-lite (ApplicationService facade + Command/Query + Executor/Checker)"
paths:
  - "**/domain/**/*.java"
  - "**/application/**/*.java"
  - "**/infrastructure/**/*.java"
  - "**/rest/**/*.java"
---

# 分层架构规范（CQRS-lite）

基于分层架构与 CQRS-lite（Command/Query 分离 + ApplicationService 门面）的四层架构最佳实践。适用于 Spring Boot 4.x + JPA（Hibernate 7）项目。

> **本项目适用范围：** 本篇的 CQRS-lite 受理结构（门面 + Command/Checker/Executor + Query）在本项目**仅适用于受理边**（上传受理与轮询查询这两个薄用例）。本项目整体采用**六边形架构（Ports & Adapters）骨架 + 扫描管道（Pipe-Filter）核心域**——核心域的阶段顺序/短路/宏策略等规则由管道模式组织，不适用本篇的用例编排形态。完整叙事、组件映射与已声明偏离见 `project-architecture.instructions.md`（冲突时以该篇为准）。

> **职责边界：** 本文件定义**分层规则、包结构、各层职责概述、跨领域关注点、反模式**。各层的详细编码规范见对应专题文件（见文末导航表）。

***

## 1. 分层与依赖规则

```
                    ┌───────────────────────────────────┐
                    │  rest  (HTTP 入口)                  │
                    │  Controller, Representation,       │
                    │  Mapper                            │
                    └─────────────┬─────────────────────┘
                                  │ 只依赖 ApplicationService
                    ┌─────────────▼─────────────────────┐
                    │  application  (用例编排)             │
                    │  ApplicationService,               │
                    │  Command/Query + Checker/Executor  │
                    └─────────────┬─────────────────────┘
                                  │ 依赖
                    ┌─────────────▼─────────────────────┐
                    │  domain  (核心业务)                  │
                    │  model, Service, Repository        │
                    └───────────────────────────────────┘
                          （最底层支撑层，无向上依赖）
                    ┌───────────────────────────────────┐
                    │  infrastructure  (底层支撑)          │
                    │  下游 Client, SFTP, Config,        │
                    │  ApiResponse/GlobalExceptionHandler│
                    │  BusinessException/ErrorCode,      │
                    │  CQRS 骨架基类                      │
                    └───────────────────────────────────┘
```

**依赖规则（必须严格遵守）：**

| 层 | 允许依赖 | 禁止依赖 |
|----|---------|---------|
| Domain | JDK, Jakarta Persistence, Spring Data types (Page/Pageable), Lombok, spring-core 工具类（`Assert` 等） | application, infrastructure, rest |
| Application | domain, infrastructure（下游 Client / SFTP 等具体技术组件、`infrastructure.application` 查询基建、`infrastructure.domain` 异常体系） | rest |
| Infrastructure | 仅自身子包（`infrastructure.rest` → `infrastructure.domain`） | application, rest, domain |
| rest | application（`ApplicationService` 门面及 Command/Query 签名类型）, domain（仅 model 类型，供 Representation 转换）, `infrastructure.rest`（ApiResponse/GlobalExceptionHandler）, `infrastructure.domain`（ErrorCode/BusinessException） | infrastructure 其他子包（sftp/config/downstream 等）, domain 的 Service/Repository |

> **核心原则：** 全部使用具体类，不建接口 + 实现对（无多实现需求时禁止预抽接口）。Application 是用例编排层，写操作走 Command + Checker + Executor，读操作走 Query + QueryExecutor，`ApplicationService` 是唯一门面。Domain 不依赖任何业务层；Application 直接依赖 infrastructure 的下游 Client 是放弃依赖倒置后的务实选择。Infrastructure 是最底层支撑层：`infrastructure/{layer}` 存放对应层共享的技术支撑代码（`infrastructure.rest` 响应壳与全局异常处理、`infrastructure.domain` 异常/错误码体系、`infrastructure.application` CQRS 骨架基类），各层按依赖规则使用对应下沉包。

***

## 2. 包结构

```
com.example.{project}
├── {Project}Application.java
├── domain/
│   └── {entity}/
│       ├── model/
│       │   ├── {Entity}.java               # 聚合根 / JPA Entity
│       │   └── {Entity}Status.java         # 状态枚举（与聚合根同在 model/）
│       ├── {Entity}Service.java            # Domain Service（@Service 具体类，薄封装 Repository）
│       └── {Entity}Repository.java         # @Repository 具体类（无 Port 接口）
├── application/
│   ├── ApplicationService.java             # 唯一门面，Controller 只注入它
│   ├── command/
│   │   └── {domain}/
│   │       ├── {Action}{Entity}Command.java        # 写命令（record + Bean Validation）
│   │       ├── checker/{Action}{Entity}CommandChecker.java  # 业务规则校验（只读校验，不写）
│   │       └── executor/{Action}{Entity}CommandExecutor.java # 用例执行（check → 转换 → 持久化）
│   ├── query/
│   │   ├── {Action}{Entity}Query.java              # 读查询（record 或 PageableQuery 子类）
│   │   └── executor/{Action}{Entity}QueryExecutor.java
│   ├── exception/
│   │   ├── NotFoundException.java          # HTTP 语义异常（404，继承 BusinessException）
│   │   └── ConflictException.java          # HTTP 语义异常（409，继承 BusinessException）
│   └── event/
│       └── {Entity}CreatedEvent.java + Listener   # Domain Event + AFTER_COMMIT 监听（见 service-conventions §2）
├── infrastructure/
│   ├── application/                        # 跨层技术支撑：CQRS 骨架基类
│   │   └── PageableQuery.java              # 分页查询基类（page/size + getPageRequest()）
│   ├── domain/                             # 跨层技术支撑：异常/错误码体系
│   │   ├── BusinessException.java
│   │   └── ErrorCode.java
│   ├── rest/                               # 跨层技术支撑：响应壳与全局异常处理
│   │   ├── ApiResponse.java                # 统一响应壳（本项目 API 约定，见 api-conventions.md）
│   │   └── GlobalExceptionHandler.java
│   ├── config/                             # SecurityConfig, RestClientConfig 等
│   ├── downstream/
│   │   └── {ServiceName}Client.java        # 下游 HTTP 客户端（@Component 具体类）
│   └── sftp/                               # 文件传输技术组件（连接池、Properties 等）
└── rest/
    └── {domain}/
        ├── {Entity}Controller.java
        ├── mapper/{Entity}RepresentationMapper.java
        └── representation/{Entity}Representation.java   # 响应模型（record）
```

> **防误解：** `infrastructure/{layer}` 是横切技术基建的落点（响应壳、异常体系、CQRS 骨架基类），与 `infrastructure/application/PageableQuery` 同一模式 — 不代表领域模型或接口层移入 infrastructure。

**命名约定：**

| 类型 | 命名模式 | 示例 |
|------|---------|------|
| Entity | `{名词}`（放 `model/`） | `User`, `Order` |
| Repository | `{Entity}Repository` | `UserRepository`（@Repository 具体类） |
| Domain Service | `{Entity}Service` | `UserService`（@Service 具体类，薄封装） |
| 写命令 | `{Action}{Entity}Command` | `CreateUserCommand`（record） |
| 命令校验器 | `{Action}{Entity}CommandChecker` | `UserCreateCommandChecker` |
| 命令执行器 | `{Action}{Entity}CommandExecutor` | `CreateUserCommandExecutor` |
| 读查询 | `{Action}{Entity}Query` | `GetUserQuery`, `UserListQuery` |
| 查询执行器 | `{Action}{Entity}QueryExecutor` | `GetUserQueryExecutor` |
| 门面 | `ApplicationService` | `ApplicationService`（全项目唯一） |
| 响应模型 | `{Entity}Representation` | `UserRepresentation`（record，放 `representation/`） |
| 响应映射 | `{Entity}RepresentationMapper` | `UserRepresentationMapper`（rest 层，只做读侧转换） |
| Controller | `{Entity}Controller` | `UserController` |
| 下游 Client | `{Service}Client` | `NotificationClient`（具体类，无 Impl 后缀） |
| Value Object | 业务名词 | `Email`, `Money`, `Address` |
| ErrorCode | `{模块编号}{错误编号}` | `001001` (用户模块 001, 错误 001) |

***

## 3. Domain 层（架构核心）

Domain 层是架构核心。所有业务规则、业务术语都定义在此层（业务异常基类物理位置在 `infrastructure/domain/`，见 §3.5）。不依赖 application / infrastructure / rest 层（JPA 注解与 Spring Data 类型是为简化持久化的务实妥协）。

### 3.1 Entity（聚合根，`domain/{entity}/model/`）

Entity 是具有唯一标识的业务对象，应包含业务行为（方法），而非贫血数据袋。

**关键规则：**

| 规则 | 说明 |
|------|------|
| **`@Version` 必须** | 所有可变实体必须添加 `@Version`，防止并发更新丢失 |
| **时间戳用 `@PrePersist` / `@PreUpdate`** | 不依赖 `@Builder.Default`（见 §7.1） |
| **equals/hashCode 基于 id** | 用 `Objects.hashCode(id)`；未持久化实体（id 为 null）hashCode 退化为常量 0 —— 实体不作为 hash 容器 key 即可 |
| **领域方法替代 setter** | 状态变更通过 `activate()` / `deactivate()` 等意图明确的方法 |
| **构造器保护** | `@NoArgsConstructor(access = PROTECTED)` + `@AllArgsConstructor(access = PRIVATE)`，强制通过 Builder 或工厂创建 |
| **枚举禁止 ORDINAL** | 必须 `@Enumerated(EnumType.STRING)`（数据库可读性 + 枚举重排序安全性） |

> Entity 完整模板与 Lombok 注解规范见 `db-conventions.md`。

### 3.2 Value Object

当字段有内在校验规则时，封装为 Value Object。判断标准：**如果一段校验逻辑出现在两个以上的 Command/Representation 或方法中，就应该提取为 VO。**

- **使用 VO 的场景：** 有格式校验（Email、Phone）、有业务运算（Money、Percentage）、有多字段组合（Address）、在多个模型间重复相同校验
- **不使用 VO 的场景：** 简单字符串/数值、仅在单个模型中使用
- **Java 21 落地：** 非持久化 VO 首选 `record`；JPA 持久化 VO 用 `@Embeddable`

> VO 完整示例与 OO 设计约束见 `java-object-calisthenics.md` §2.3。

### 3.3 Repository（具体类）

`@Repository` 具体类，无 Port 接口、无 Adapter。定义领域视角的数据访问方法。

```java
@Repository
@RequiredArgsConstructor
public class {Entity}Repository {

    private final {Entity}JpaSupport jpa;   // 包私有 Spring Data 接口，内部实现细节

    public {Entity} save({Entity} entity) { return jpa.save(entity); }
    public Optional<{Entity}> findById(Long id) { return jpa.findById(id); }
    public void deleteById(Long id) { jpa.deleteById(id); }
    public Optional<{Entity}> findByName(String name) { return jpa.findByName(name); }
    public boolean existsByName(String name) { return jpa.existsByName(name); }
    public Page<{Entity}> findAll(Pageable pageable) { return jpa.findAll(pageable); }
}
```

**命名约定：**
- 查询方法：`findBy{Field}` / `existsBy{Field}`
- 返回单个：`Optional<T>`，禁止返回 null
- 返回集合：`List<T>` 或 `Page<T>`
- 分页/排序语义直接委托 Spring Data（包私有 `{Entity}JpaSupport extends JpaRepository` 仅作为实现细节，包外不可见）

### 3.4 Domain Service（薄封装）

`@Service` 具体类，薄封装同包的 Repository。是 executor 与数据访问之间的固定中间层。

```java
@Service
@RequiredArgsConstructor
public class {Entity}Service {

    private final {Entity}Repository repository;

    public Optional<{Entity}> byId(Long id) { return repository.findById(id); }
    public Page<{Entity}> all(Pageable pageable) { return repository.findAll(pageable); }
    public {Entity} save({Entity} entity) { return repository.save(entity); }
    public void deleteById(Long id) { repository.deleteById(id); }
    public boolean existsByName(String name) { return repository.existsByName(name); }
}
```

**规则：**
- 方法命名用紧凑风格：`byId` / `all` / `save` / `deleteById` / `existsBy{Field}`
- `byId` 返回 `Optional`，"不存在"的业务判断（抛 `NotFoundException`）由 application 层 executor 做
- 不加事务注解 — 事务边界统一在 `ApplicationService`
- 无状态，不持有请求上下文

**判断标准：** 只涉及单个聚合内部状态 → Entity 方法；聚合外查询（唯一性检查）、跨聚合一致性 → Domain Service / executor。

### 3.5 异常体系

- **业务异常基类**：`BusinessException` + `ErrorCode` 枚举（物理位置 `infrastructure/domain/`，供全项目跨层使用）
- **HTTP 语义子类**：`NotFoundException`（404）/ `ConflictException`（409）（`application/exception/`），继承 BusinessException、默认通用 ErrorCode、可传模块 ErrorCode 定制消息。executor / checker 抛语义子类

> 异常体系完整定义、抛出/捕获规范、全局处理见 `exception-handling.md`。

***

## 4. Application 层（CQRS-lite）

Application 层负责用例编排：写操作 `Command → Checker → Executor`，读操作 `Query → QueryExecutor`。**业务规则在 Domain 层与 Checker，编排在 Executor。**

> Service 写法、事务管理、乐观锁处理、方法命名等完整规范见 `service-conventions.md`。

### 4.1 ApplicationService（唯一门面）

全项目唯一的 `ApplicationService`（`@Service` 具体类）。**Controller 只注入它**，不注入 executor / repository / client。

```java
@Service
@RequiredArgsConstructor
public class ApplicationService {

    private final Create{Entity}CommandExecutor create{Entity}CommandExecutor;
    // ... 其余 executor 注入

    @Transactional
    public {Entity} {domain}Create(Create{Entity}Command command) { return create{Entity}CommandExecutor.execute(command); }

    @Transactional(readOnly = true)
    public Page<{Entity}> {domain}sAll({Entity}ListQuery query) { return {domain}ListQueryExecutor.execute(query); }
}
```

**规则：**
- 方法命名 `{domain}{Action}`：`userCreate` / `orderDeleteById` / `usersAll` / `uploadFile`
- **事务边界在方法上**：写方法 `@Transactional`，读方法 `@Transactional(readOnly = true)`
- 返回 Domain 对象（`{Entity}` / `Page<{Entity}>`），Representation 转换留给 rest 层
- 门面无业务逻辑，只做转发

### 4.2 Command / Checker / Executor（写侧）

```
application/command/{domain}/
├── Create{Entity}Command.java              # record + Bean Validation（格式校验注解）
├── checker/Create{Entity}CommandChecker.java
└── executor/Create{Entity}CommandExecutor.java
```

- **Command**：`record`，携带写操作的全部输入；格式校验（`@NotBlank` / `@Email` / `@Pattern` 等）由 Controller 的 `@Valid` 触发；id 来自路径变量时通过 `withId(id)` 复制绑定，不放请求体
- **Checker**：聚合外部业务规则（唯一性、乐观锁版本比对等）。只读校验、只抛异常（`ConflictException` 等），**绝不写数据**。无业务规则时省略 Checker
- **Executor**：`@Component`，`execute()` 顺序为 *check → 转换（如密码哈希）→ 持久化 → 副作用（经 Domain Event，禁止事务内直接调下游，见 §7.3）*。**不加事务注解**（边界在门面）、**不注入 Validator**（格式校验已在 Controller 完成）、不互相调用
- 删除等以 id 为唯一输入的操作可不建 Command，executor 直接收 `Long id`

### 4.3 Query / QueryExecutor（读侧）

- 简单查询用 `record`（如 `GetUserQuery(Long id)`）
- 分页查询继承 `infrastructure/application/PageableQuery`（getter/setter 供 GET 参数绑定，`getPageRequest()` 转 Spring Data Pageable）
- `QueryExecutor` 返回 Domain 对象或 `Page<{Entity}>`

### 4.4 核心要点

- Command / Query / Representation 全部使用 `record`（分页 Query 除外）
- 禁止 Command 携带领域对象或嵌套 DTO — Command 只携带原始输入
- Mapper 不在 application 层 — 读侧 Representation 转换在 `rest/{domain}/mapper/`，写侧 Command→Entity 转换在 executor 内

***

## 5. Infrastructure 层

Infrastructure 层是最底层支撑层：跨层技术支撑包（`infrastructure/{layer}`）、下游 HTTP 客户端、文件传输、安全与配置。全部具体类。

### 5.1 下游服务客户端

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class {ServiceName}Client {          // 具体类，无 {Service}Client 接口
    // RestClient 调用下游；业务层直接注入此具体类
}
```

> 下游 HTTP 客户端实现、错误分类、弹性模式、连接池配置见 `downstream-conventions.md`。

### 5.2 跨层技术支撑包（`infrastructure/{layer}`）

`infrastructure/{layer}` 存放对应层共享的技术支撑代码：`infrastructure/application/`（CQRS 骨架基类，如 `PageableQuery`）、`infrastructure/domain/`（`BusinessException` + `ErrorCode` 异常/错误码体系）、`infrastructure/rest/`（`ApiResponse` 响应壳 + `GlobalExceptionHandler`）。只放有真实行为、被多个模块复用的代码 — 空标记基类、纯泛型壳、仅单处使用的工具一律不建。

### 5.3 Config

> RestClient/RestTemplate Bean 配置、超时设置见 `downstream-conventions.md`。

***

## 6. rest 层

rest 层只做 HTTP 协议转换：HTTP Request → ApplicationService 调用 → HTTP Response。**零业务逻辑。**

> URL 模式、HTTP 方法语义、状态码映射、分页约定、`@PathVariable` 校验等完整规范见 `api-conventions.md`。

**核心要点：**
- Controller 只注入 `ApplicationService` 与本域的 `{Entity}RepresentationMapper`
- 所有端点返回 `ResponseEntity<ApiResponse<T>>`（DELETE 除外：返回 **204 No Content**，无响应体）；`ApiResponse` 是本项目 API 约定，细则见 `api-conventions.md`
- 请求体直接用 Command（`@Valid @RequestBody Create{Entity}Command`），格式校验在此触发
- 读侧转换 `Domain → Representation` 在 `{Entity}RepresentationMapper`（`@Component` + 手动映射）
- 不做任何业务判断（if/else、业务异常抛出、数据转换）；全局异常统一在 `GlobalExceptionHandler`（`infrastructure/rest/`，`@RestControllerAdvice`，无参全局扫描），Controller 不写 try-catch

> 异常处理完整规范见 `exception-handling.md`。

***

## 7. 跨领域关注点

### 7.1 审计（时间戳）

标准做法：`@PrePersist` / `@PreUpdate` 生命周期回调，不依赖 `@Builder.Default`、无需额外配置：

```java
@PrePersist
protected void onCreate() { createdAt = OffsetDateTime.now(); }

@PreUpdate
protected void onUpdate() { updatedAt = OffsetDateTime.now(); }
```

> 仅当需要记录「谁创建/谁修改」（auditor）时才引入 Spring Data JPA Auditing（`@CreatedDate` / `@LastModifiedDate` + `@CreatedBy` / `@LastModifiedBy` + `@EnableJpaAuditing`）；纯时间戳场景不必引入。

### 7.2 乐观锁（必须）

**所有可变实体必须添加 `@Version`：**

```java
@Version
private Long version;
```

JPA 自动处理：更新时检查 version，不匹配抛出 `OptimisticLockingFailureException`；显式比对在 Update Checker 中完成（回传 version 不匹配 → `ConflictException`）。

> 乐观锁异常处理见 `service-conventions.md` §3。

### 7.3 Domain Event（推荐）

用事件解耦副作用，避免在 Executor 中直接调用下游：Executor 事务内发布事件，`application/event/` 监听器在 `@TransactionalEventListener(AFTER_COMMIT)` 中调用下游 Client。**好处：** Executor 不依赖下游 Client，下游调用在事务外执行，新增副作用只需新增 Listener（开闭原则）。

> 完整代码模式与依赖规则说明见 `service-conventions.md` §2（单一来源，此处不重复）。

### 7.4 软删除（按需）

```java
@Column(name = "deleted_at")
private OffsetDateTime deletedAt;

// Hibernate 7 使用 @SQLRestriction 替代已废弃的 @Where
@SQLRestriction("deleted_at IS NULL")
@Entity
public class {Entity} { ... }

public void softDelete() { this.deletedAt = OffsetDateTime.now(); }
```

### 7.5 分页安全

分页上限在本项目的手动绑定模式下**不能**依赖 `spring.data.web.pageable.max-page-size` — 该配置只作用于 Spring Data 自带的 `PageableArgumentResolver`，对继承 `PageableQuery` 的 page/size 属性绑定**不生效**。上限 clamp 统一在 `infrastructure/application/PageableQuery#getPageRequest()` 内实现（`size` 限制在 1..100，`page ≥ 0`），子类无需自行处理；rest 层不得绕过 `PageableQuery` 直接绑定 Spring Data `Pageable`（分页约定见 `api-conventions.md`）。

> 分页 API 规范详见 `api-conventions.md` Pagination Conventions。

***

## 8. 反模式（禁止）

| # | 反模式 | 为什么有问题 | 正确做法 |
|---|-------|------------|---------|
| 1 | **贫血 Entity**：只有 getter/setter 无行为 | 业务逻辑散落在 executor，Entity 退化为数据结构 | 用领域方法封装状态变更 |
| 2 | **Controller 包含业务逻辑** | 违反单一职责，难以测试 | Controller 只做 HTTP↔Java 转换 |
| 3 | **Controller 注入 Executor/Repository/Client** | 绕过门面，用例入口失控、事务边界失守 | Controller 只注入 `ApplicationService` |
| 4 | **事务内做耗时下游调用** | 持有数据库连接和事务锁，影响性能和一致性 | 用 Domain Event + `@TransactionalEventListener(AFTER_COMMIT)` |
| 5 | **ErrorCode 单体枚举无限膨胀** | 所有模块的错误码混在一起，难以维护 | 按模块编号分段：`{模块号}{序号}` |
| 6 | **GlobalExceptionHandler 硬编码实体错误码** | 新增实体后，`DataIntegrityViolation` 处理会返回错误实体的错误码 | 通用错误用通用 ErrorCode |
| 7 | **skip `@Version`** | 并发更新丢失数据 | 所有可变实体必须 `@Version` |
| 8 | **枚举用 ORDINAL 持久化** | 数据库值无意义，枚举重排序导致数据错乱 | 必须用 `@Enumerated(STRING)` |
| 9 | **字段注入 `@Autowired`** | 隐藏依赖、难以测试 | 构造器注入 `@RequiredArgsConstructor` |
| 10 | **下游 Client 方法超过 3 个原始参数** | 可读性差，参数顺序易出错 | 封装为事件对象或 DTO |
| 11 | **密码/加密等策略分散在多处** | 策略不一致，改一处漏一处 | 统一在 executor 或 Domain Service 中处理 |
| 12 | **无多实现需求预抽接口** | 接口 + 实现对是无效抽象，双倍维护成本 | 全部具体类，出现第二实现时再抽 |
| 13 | **Executor 之间互相调用** | 用例边界模糊，事务与重试语义混乱 | 共享逻辑下沉 Domain 层，或合并用例 |
| 14 | **Command 携带领域对象/嵌套 DTO** | Command 应是扁平不可变输入，携带对象导致校验与映射失控 | 只携带原始字段，record 定义 |
| 15 | **Checker 内做写操作** | 校验器产生副作用，重复执行会破坏数据 | Checker 只读校验、只抛异常 |
| 16 | **Representation 泄入 application/domain 层** | HTTP 模型反向污染内层，层次依赖倒置 | Representation 只存在于 rest 层 |
| 17 | **用 `@Builder.Default` 设置时间戳** | 只在使用 Builder 时生效，其他创建方式会丢失默认值 | 用 `@PrePersist` / JPA Auditing |

> 判空专题的坏味道编号（NC-001~NC-014，可工具扫描）与改造执行流程见 `null-check-governance.instructions.md`。

***

## 9. 测试架构

> 测试分层、包结构、命名约定、数据隔离等完整规范见 `test-conventions.md` 和 `integration-test-guide.md`。

```
┌─────────────────────────────────────────────────────────────────┐
│  API Test (Integration) — {Entity}ApiTests extends BaseApiTest   │
│  Spring Boot RANDOM_PORT + WebTestClient + H2 + WireMock        │
├─────────────────────────────────────────────────────────────────┤
│  Contract Test — {Entity}ContractTest                            │
│  Spring Cloud Contract + RestAssuredMockMvc                     │
│  (⚠️ RestAssured 与 SF7 兼容问题待上游修复，见 contract-test.md)  │
├─────────────────────────────────────────────────────────────────┤
│  Unit Test — {ClassUnderTest}Test                                │
│  JUnit 5 + Mockito (no Spring Context)                          │
└─────────────────────────────────────────────────────────────────┘
```

***

## 10. 新增业务模块 Checklist

以 `{Entity} = Order` 为例：

**Phase 1 — Domain（先定核心）**
- [ ] `domain/order/model/Order.java` — Entity + `@Version` + 领域方法
- [ ] `domain/order/model/OrderStatus.java` — 状态枚举
- [ ] `domain/order/OrderRepository.java` — @Repository 具体类（+ 包私有 JpaSupport）
- [ ] `domain/order/OrderService.java` — Domain Service 薄封装
- [ ] `infrastructure/domain/ErrorCode.java` — 追加 Order 错误码

**Phase 2 — Application（用例编排）**
- [ ] `application/command/order/CreateOrderCommand.java` + `checker/` + `executor/`（按需）
- [ ] `application/query/GetOrderQuery.java` / `OrderListQuery.java` + `executor/`
- [ ] `ApplicationService` 追加 `orderCreate` / `orderById` / `ordersAll` 等门面方法
- [ ] `application/exception/` 按需追加 HTTP 语义异常

**Phase 3 — Infrastructure（技术组件）**
- [ ] `infrastructure/downstream/` 按需新增下游 Client
- [ ] `db/migration/V{N}__create_orders_table.sql` — Flyway

**Phase 4 — rest（暴露 HTTP）**
- [ ] `rest/order/OrderController.java` — 只注入 ApplicationService + Mapper
- [ ] `rest/order/representation/OrderRepresentation.java` + `mapper/OrderRepresentationMapper.java`

**Phase 5 — Test**
- [ ] `unit/OrderTest.java` — 实体单元测试
- [ ] `integration/OrderApiTests.java` + JSON fixtures + @Sql seed data
- [ ] `contracts/orders/` — Spring Cloud Contract

***

## 专题文件导航

| 主题 | 文件 |
|------|------|
| 技术栈与版本 | `tech-stack.md` |
| Service 层完整规范 | `service-conventions.md` |
| API 设计规范 | `api-conventions.md` |
| 异常处理完整规范 | `exception-handling.md` |
| 参数校验规范 | `validation.md` |
| 判空治理（NC 规则与改造执行） | `null-check-governance.md` |
| Java 编码规范 | `java-coding-standard.md` |
| 对象健身操 | `java-object-calisthenics.md` |
| SOLID 与迪米特法则 | `java-solid-lod.md` |
| 日志规范 | `logging.md` |
| 数据库规范 | `db-conventions.md` |
| 数据库迁移 | `db-migration.md` |
| 下游集成规范 | `downstream-conventions.md` |
| 测试规范总则 | `test-conventions.md` |
| API Test 指南 | `integration-test-guide.md` |
| 契约测试 | `contract-test.md` |
| TDD 工作流 | `tdd-workflow.md` |
| Code Review | `code-review.md` |
