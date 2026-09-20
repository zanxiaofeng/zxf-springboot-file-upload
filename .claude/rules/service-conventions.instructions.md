---
name: "Service Conventions"
description: "Application layer transaction and orchestration details: facade transactions, domain events for downstream calls, optimistic locking, facade method naming"
paths:
  - "**/application/**/*.java"
---

# Application 层规范（事务与编排细则）

> **职责边界：** application 层的结构与骨架（`ApplicationService` 唯一门面、Command/Checker/Executor、Query/QueryExecutor）由 `architecture.instructions.md` §4 定义。本文件是以下主题的**唯一权威**：事务管理细则、Domain Event 下游解耦、乐观锁处理、门面方法命名。本层全部使用具体类（无接口 + 实现对，单实现禁止预抽接口）。

***

## 1. 事务边界（在门面方法上）

事务边界统一在 `ApplicationService` 的方法上；executor、checker、repository **不加事务注解**。

```java
@Service
@RequiredArgsConstructor
public class ApplicationService {

    @Transactional                        // 写方法：读写事务
    public {Entity} {domain}Create(Create{Entity}Command command) {
        return create{Entity}CommandExecutor.execute(command);
    }

    @Transactional(readOnly = true)       // 读方法：只读事务
    public Page<{Entity}> {domain}sAll({Entity}ListQuery query) {
        return {domain}ListQueryExecutor.execute(query);
    }
}
```

**规则：**
- 一个门面方法 = 一个事务边界；不在方法内再开嵌套事务编排多个 executor
- 只读优化的实际收益：Hibernate 跳过脏检查、FlushMode 收紧、部分驱动对只读连接优化
- 默认回滚规则：仅 `RuntimeException` 与 `Error` 回滚；需覆盖时 `@Transactional(rollbackFor = Exception.class)`（业务异常全部继承 `BusinessException`（RuntimeException），正常无需覆盖）

### 传播行为

| 传播类型 | 使用场景 |
|----------|----------|
| `REQUIRED`（默认） | 绝大多数业务方法 |
| `REQUIRES_NEW` | 审计日志等无论外层事务成败都必须落库的场景 |
| `NESTED` | 基于 savepoint 的嵌套（同一物理事务，非独立子事务）；内层异常仍向上传播，外层只有 `catch` 该异常才能保留更改。JTA 不支持；JDBC 与常见驱动（含 H2）多数可用，但与模式/版本相关——**使用前以目标库实测为准**。真正需独立事务的场景用 `REQUIRES_NEW` |

### 自引用代理陷阱

同 bean 内部方法调用**绕过 AOP 代理**，`@Transactional` 不生效。门面方法保持扁平转发即可天然规避；确需内部调用时，重构为独立 executor，而不是 `@Lazy` 自注入。

***

## 2. Domain Event：事务外下游调用

**规则：禁止在事务方法内直接调用下游 HTTP / 耗时服务**（详见 `architecture.instructions.md` 反模式 #4）。副作用通过 Domain Event 移到事务提交之后：

```java
// Executor：事务内只发布事件（不注入下游 Client）
private final ApplicationEventPublisher eventPublisher;

public User execute(Create{Entity}Command command) {
    checker.check(command);
    {Entity} saved = {domain}Service.save(toNew{Entity}(command));
    eventPublisher.publishEvent(
            new {Entity}CreatedEvent(saved.getId(), saved.getName(), saved.getEmail()));
    return saved;
}

// Listener：application/event/ 层，事务提交后执行；可注入 infrastructure 的具体 Client
@Slf4j
@Component
@RequiredArgsConstructor
public class {Entity}CreatedNotificationListener {
    private final {ServiceName}Client client;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated({Entity}CreatedEvent event) {
        client.sendNotification(event.id(), event.name());
    }
}
```

**好处：**
- executor 不依赖下游 Client，下游故障不拖垮主流程事务
- 下游调用在事务外执行，不持有数据库连接与锁
- 新增副作用只需新增 Listener，符合开闭原则

> 下游 Client 实现与错误分类见 `downstream-conventions.md`。

***

## 3. 乐观锁处理

使用 `@Version` 的实体在并发更新时可能抛出 `OptimisticLockingFailureException`。处理分两层：

**第一层（首选）——Checker 显式比对 version**，把绝大多数冲突提前为明确的业务异常：

```java
// checker 只读校验、只抛异常
public void check({Entity} existing, Update{Entity}Command command) {
    if (!existing.getVersion().equals(command.version())) {
        throw new ConflictException(ErrorCode.VERSION_CONFLICT, existing.getId());
    }
}
```

**第二层（兜底）——GlobalExceptionHandler 统一转换**：check 与 flush 之间仍存在并发窗口，`OptimisticLockingFailureException` 在事务提交时才可能抛出（JPA 脏检查 flush 发生在方法返回后的代理内），**在 executor / 门面方法内 try-catch 它通常捕获不到——禁止这种写法**。由 `GlobalExceptionHandler` 的 `@ExceptionHandler(OptimisticLockingFailureException.class)` 兜底返回 409。

**重试（可选）**：低冲突场景可引入 spring-retry（`@EnableRetry` + `@Retryable`），且必须确保 Retry advice 先于 Transaction advice（否则重试不携带新事务）。未引入 spring-retry 时只用上面两层。

> 异常体系与全局处理见 `exception-handling.md`。

***

## 4. 门面方法命名

门面已由 `{domain}` 限定实体上下文，方法名 = `{domain}{Action}`：

| 操作 | 命名 | 示例 |
|------|------|------|
| 创建 | `{domain}Create` | `userCreate(command)` |
| 更新 | `{domain}Update` | `userUpdate(command)` |
| 按 ID 删除 | `{domain}DeleteById` | `userDeleteById(id)` |
| 按 ID 查询 | `{domain}ById` | `userById(query)` |
| 列表查询 | `{domain}sAll` | `usersAll(query)` |
| 领域动作 | `{domain}{Verb}` | `uploadFile(command)` |

Domain Service（domain 层）的方法命名为另一套：`byId` / `all` / `save` / `deleteById` / `existsBy{Field}`，见 `architecture.instructions.md` §3.4。

***

## 5. 部分更新语义（Update Command）

Update Command 中字段为 `null` 表示**不更新**，而非清空；判空赋值在 executor 内完成：

```java
public User execute(Update{Entity}Command command) {
    {Entity} existing = {domain}Service.byId(command.id())
            .orElseThrow(() -> new NotFoundException(ErrorCode.{ENTITY}_NOT_FOUND, command.id()));
    checker.check(existing, command);
    if (command.name() != null) { existing.rename(command.name()); }
    return {domain}Service.save(existing);
}
```

> Command 的 record 定义与 Bean Validation 注解规范见 `validation.md`；HTTP 语义异常（`NotFoundException` / `ConflictException`）见 `exception-handling.md`。
