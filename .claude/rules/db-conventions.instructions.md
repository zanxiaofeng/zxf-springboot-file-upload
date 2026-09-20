---
name: "Database Conventions"
description: "Database conventions for Flyway migrations, entity rules, and H2 compatibility"
paths:
  - "**/*.sql"
  - "**/domain/**/*.java"
  - "**/infrastructure/**/*.java"
  - "**/*.yml"
  - "**/*.yaml"
  - "**/*.properties"
---

# Database Conventions

> **职责边界：** 本文件定义 Entity 映射规则、`@Version` 乐观锁、H2 兼容性、索引策略、N+1 查询防护。迁移文件规范见 `db-migration.md`，Entity 领域方法与 Lombok 模板见 `architecture.md` §3.1。

***

## Migration Rules
- All DDL via Flyway migration
- Naming: `V{version}__{description}.sql`
- Dual-DB compatible: core migrations must run on both H2 (test, MODE=MySQL) and MySQL 8 — use syntax valid in both (e.g. `AUTO_INCREMENT`); no separate mysql/ directory (Flyway locations only load `classpath:db/migration`)
- 已知问题：`V1__create_users_table.sql` 使用 `IDENTITY` 语法（H2 可执行、MySQL 8 不支持）；支持生产 MySQL 前需用 corrective migration 处理，不得直接修改已合并迁移
- Test data via `@Sql` scripts in `src/test/resources/sql-data/` (cleanup + init + cases)
- **Never modify merged migrations** — add corrective migrations instead

> 完整迁移规范见 `db-migration.md`。

***

## Entity Rules

> 基于 **JPA / Hibernate 7**（Spring Boot 默认持久层）。**MyBatis Plus** 项目的对应方案见下方「MyBatis Plus 替代」小节。

### Entity 模板

```java
@Entity
@Table(name = "{table_name}")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(of = {"id", "{key_field}"})
public class {Entity} {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 必须字段：nullable = false + 业务约束
    @Column(nullable = false, unique = true, length = 50)
    private String name;

    // 枚举：必须 STRING 持久化，禁止 ORDINAL
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private {Entity}Status status = {Entity}Status.ACTIVE;

    // 乐观锁：所有可变实体必须
    @Version
    private Long version;

    // 时间戳：OffsetDateTime，不用 Date/LocalDateTime
    @Column(updatable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    // ── 生命周期回调 ──

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // ── 领域方法：封装业务规则 ──

    public void rename(String newName) {
        Assert.hasText(newName, "name must not be blank");
        this.name = newName;
    }

    // ── equals/hashCode：基于 id (JPA Identity Pattern) ──

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof {Entity} that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

### 关键规则

| 规则 | 说明 |
|------|------|
| Id | auto-generated (`IDENTITY` for MySQL, `SEQUENCE` for PostgreSQL) |
| Timestamps | 用 `@PrePersist` / `@PreUpdate` 生命周期回调（见 `architecture.md` §7.1），`OffsetDateTime` 类型；不依赖 `@Builder.Default`，纯时间戳场景不引入 JPA Auditing |
| Enums | use `@Enumerated(EnumType.STRING)`, **never ORDINAL** |
| Columns | never nullable for required fields, use `nullable = false` |
| **乐观锁** | **所有可变实体必须启用 `@Version`**（防止并发更新丢失数据） |
| 构造器保护 | `@NoArgsConstructor(access = PROTECTED)` + `@AllArgsConstructor(access = PRIVATE)`，强制通过 Builder 或工厂创建 |
| equals/hashCode | 基于 id（JPA Identity Pattern）；未持久化实体（id 为 null）用 `getClass().hashCode()` 避免冲突 |
| 领域方法 | 状态变更通过意图明确的方法（`activate()` / `rename()`），替代 setter |

### 约束三层对齐（DTO ↔ 实体 ↔ DDL）

应用层校验都可能被绕过（直接 SQL、迁移脚本），数据库 `NOT NULL`/唯一/外键约束是不依赖调用路径的最终保证。**DTO 注解、实体注解、DDL 三层必须表达同一约束集：**

| 代码层约束 | DDL 对应 | 不一致的典型后果 |
|-----------|---------|----------------|
| `@NotNull` / `@NotBlank` | `NOT NULL` | 校验放行的 null 在 INSERT 时报 500 |
| `@Size(max = 64)` | `VARCHAR(64)` | 校验放行 64 字符，列仅 32，截断或报错 |
| 业务唯一字段 | `UNIQUE` 约束 | 代码未查重，并发下重复数据落盘 |

**规则：**
- 对齐检查是**迁移审查固定环节**——Flyway 变更合入时 diff 列约束与实体/DTO 注解（对应 NC-013，见 `null-check-governance.md` §3）
- 反向不一致同样有害：DDL 更严会把入口 400 推迟为持久层 500，**修复方向是补 DTO 注解而非放松 DDL**
- 兜底是「兜」而非「堵」：`DataIntegrityViolationException` → 409 由 `exception-handling.md` §6.2 统一映射，友好报错由前置校验给出

### @Version (Optimistic Locking)

**所有可变实体必须添加 `@Version`：**

```sql
-- DDL
CREATE TABLE {table} (
    ...
    version BIGINT DEFAULT 0 NOT NULL,
    ...
);
```

```java
// Entity
@Version
private Long version;
```

JPA 自动处理：更新时检查 version，不匹配抛出 `OptimisticLockingFailureException`。

> Service 层乐观锁异常处理见 `service-conventions.md` §3。异常处理完整规范见 `exception-handling.md`。

### MyBatis Plus 替代(JPA → MyBatis Plus)

选用 MyBatis Plus 的工程,JPA 注解不生效,对应方案:

| 关注点 | JPA(Hibernate 7) | MyBatis Plus |
|--------|------|------|
| 乐观锁 | `jakarta.persistence.@Version` | `com.baomidou.mybatisplus.annotation.@Version` + 注册 `OptimisticLockerInnerInterceptor` |
| 审计时间戳 | `@CreatedDate` / `@LastModifiedDate` + `AuditingEntityListener` | `@TableField(fill = INSERT / INSERT_UPDATE)` + 自定义 `MetaObjectHandler` |
| ID 策略 | `@GeneratedValue(IDENTITY / SEQUENCE)` | `@TableId(IdType.AUTO / ASSIGN_ID)` |
| 分页 | Spring Data `Pageable` | `IPage` + `MybatisPlusInterceptor` + `PaginationInnerInterceptor` |
| 枚举持久化 | `@Enumerated(EnumType.STRING)` | `@EnumValue` 标记码值,或配置 `default-enum-type-handler` |
| 表/列映射 | `@Entity` / `@Table` / `@Column` | `@TableName` / `@TableField` |

***

## H2 Compatibility

| MySQL | H2 | Notes |
|-------|----|-------|
| AUTO_INCREMENT | GENERATED BY DEFAULT AS IDENTITY | ID generation |
| ENGINE=InnoDB | omit | Storage engine |
| CHARSET=utf8mb4 | omit | Character set |
| TIMESTAMP | TIMESTAMP WITH TIME ZONE | MySQL TIMESTAMP converts to UTC, H2 preserves offset |
| INT | INTEGER or BIGINT | Integer types |
| BOOLEAN / TINYINT(1) | BOOLEAN | Boolean mapping |
| TEXT | CLOB | Large text |
| JSON | VARCHAR(8192) or CLOB | H2 has no native JSON type |
| ON UPDATE CURRENT_TIMESTAMP | omit — use `@PreUpdate` instead | MySQL-specific auto-update |
| FULLTEXT INDEX | omit — use application-level search | H2 does not support FULLTEXT |

**OffsetDateTime 与 MySQL 的时区行为：** MySQL 的 `TIMESTAMP` 列存储时转换为 UTC，读取时转换为会话时区（不保留时区偏移）；`DATETIME` 不做转换。本项目测试库（H2 MODE=MySQL）与生产使用同构 DDL，两库行为以集成测试为准；时区偏移语义由应用层 `OffsetDateTime` 保证，不依赖列类型（不要在 H2 侧单方面改用 `TIMESTAMP WITH TIME ZONE`）。

***

## Index Strategy

**何时创建索引：**
- 外键列（`CONSTRAINT fk_{table}_{referenced} FOREIGN KEY ...`）
- 常查询的 WHERE 条件列
- 唯一约束（`UNIQUE INDEX`）
- 排序/分页查询的 ORDER BY 列

**复合索引规则：**
- 高选择性列在前
- 遵循最左前缀匹配

**命名约定：**
- 普通索引：`idx_{table}_{column}`
- 唯一索引：`uk_{table}_{column}`
- 外键：`fk_{table}_{referenced_table}`

***

## N+1 Query Prevention

```java
// 方式一：@EntityGraph 预加载关联
@EntityGraph(attributePaths = {"orders"})
List<User> findAll();

// 方式二：JOIN FETCH in @Query
@Query("SELECT u FROM User u JOIN FETCH u.orders WHERE u.id = :id")
Optional<User> findWithOrders(@Param("id") Long id);

// 方式三：@BatchSize 批量加载
@BatchSize(size = 50)
@OneToMany(mappedBy = "user")
private List<Order> orders;
```

***

## Spring Boot 4 / Hibernate 7 数据层变化

- **Hibernate 7**：`@SQLRestriction` 替代已废弃的 `@Where`（软删除过滤），行为不变
- **`@EntityScan` 迁包**：import 改为 `org.springframework.boot.persistence.autoconfigure.EntityScan`
- **Hibernate 注解处理器**：`hibernate-jpamodelgen` → `hibernate-processor`（生成 JPA 静态元模型）
- **异常翻译开关**：`spring.dao.exceptiontranslation.enabled` → `spring.persistence.exceptiontranslation.enabled`
- **Flyway 需专用 starter**：`spring-boot-starter-flyway`（不再仅靠第三方依赖）
