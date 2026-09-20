---
name: "DB Migration"
description: "Flyway database migration guide with directory structure and destructive change process"
paths:
  - "**/*.sql"
  - "**/infrastructure/**/*.java"
  - "**/*.yml"
  - "**/*.yaml"
  - "**/*.properties"
---

# DB Migration Guide

> **职责边界：** 本文件定义 Flyway 迁移文件的目录结构、版本编号、回滚策略、数据迁移模式。Entity 映射规则与 H2 兼容性见 `db-conventions.md`。

***

## Directory Structure
```
src/main/resources/db/
└── migration/              -- Core DDL (environment-agnostic; 语法须 H2 与 MySQL 8 双库
                                            可执行, 见 db-conventions.md — V1 的 IDENTITY 语法
                                            待 corrective migration)
    ├── V1__create_users_table.sql
    ├── V2__create_orders_table.sql
    └── V3__add_user_bio_column.sql

src/test/resources/sql-data/ -- Test data (@Sql scripts, NOT managed by Flyway)
├── cleanup/                -- Truncate/delete before each test
│   └── clean-up.sql
├── init/                   -- Seed data for all tests
│   └── data.sql
└── cases/                  -- Case-level overrides (CLOB via FILE_READ)
    └── user-bio-test.sql
```

***

## Flyway Version Numbering

- 格式：`V{version}__{description}.sql`（**双下划线**分隔版本号和描述）
- 版本号：整数递增（`V1`、`V2`、`V3`），**间隙允许但不推荐**
- 分支冲突解决：协调版本号或使用 `spring.flyway.out-of-order=true`
- 文件名描述：kebab-case，简洁描述变更内容

***

## Repeatable Migrations (R__)

`R__{description}.sql` — 每次校验和变化时重新执行：
- 用途：视图（VIEW）、存储过程、函数
- 不使用版本号，按描述排序执行
- 示例：`R__create_user_stats_view.sql`

***

## Rollback Strategy

Flyway 社区版**不支持 UNDO 迁移**。采用补偿式正向迁移：

```
破坏性变更流程:
1. 创建新 Migration（如 V4__add_user_phone.sql）添加新列/表
2. 保留旧列，双写新旧数据
3. 在下一个版本 Migration 中清理旧列
4. 记录决策到 docs/design/adr/
```

***

## Data Migration Patterns

**新增 NOT NULL 列（安全三步法）：**
```sql
-- Step 1: 添加列，允许 NULL
ALTER TABLE users ADD COLUMN phone VARCHAR(20);

-- Step 2: 数据填充（单独 Migration）
UPDATE users SET phone = '' WHERE phone IS NULL;

-- Step 3: 添加 NOT NULL 约束（单独 Migration）
ALTER TABLE users ALTER COLUMN phone SET NOT NULL;
```

**拆分列：**
```sql
-- Step 1: 创建新列
ALTER TABLE users ADD COLUMN first_name VARCHAR(50);
ALTER TABLE users ADD COLUMN last_name VARCHAR(50);

-- Step 2: 迁移数据
UPDATE users SET first_name = SUBSTRING_INDEX(name, ' ', 1),
                last_name = SUBSTRING(name, LOCATE(' ', name) + 1);

-- Step 3: 后续版本中删除旧列
```

***

## Flyway Configuration

> **Spring Boot 4：** Flyway 现需专用 starter `spring-boot-starter-flyway`（不再仅引入第三方 Flyway 依赖）。

```yaml
# application.yml
spring:
  flyway:
    locations: classpath:db/migration
    baseline-on-migrate: true    # 已有数据库首次启用 Flyway
    validate-on-migrate: true    # 启动时校验已应用的 Migration
    out-of-order: false          # 禁止乱序（生产环境推荐 false）
```

***

## H2 Compatibility

For H2 compatibility syntax mapping, see `db-conventions.md` H2 Compatibility 小节。

***

## Destructive Change Process
1. Create new Migration file (e.g., V4__add_user_phone.sql)
2. Keep old column, add new column, dual-write in application
3. Clean old column in next version Migration
4. Record decision in docs/design/adr/
