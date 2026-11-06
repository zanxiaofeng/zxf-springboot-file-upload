# POST /api/owners 调查报告

> 教学范例：Spring Petclinic REST，源码版本 `5180cc9bd0163393c4e628bedc1bc4c27cfaab1b`。坐标相对于 `../../../../src/main/java`；OpenAPI 契约位于 `src/main/resources/openapi.yml`。

## Executive Summary

- 调查范围：静态调查 `POST /api/owners`，覆盖本服务到数据库的直接一跳。
- 功能：校验并新增业主，经 JPA 写入 `owners`，返回 `201` 和回填主键。
- 外部依赖：O1（Database → `owners` 表；1 张交互卡、1 个数据库目标）。
- 注意：项目将部分约束冲突翻译为 `404`，不是通用的 `409`；该映射以当前版本源码为准。

## Inbound

### I1. 同步 HTTP → POST /api/owners

| 鉴权 | 主坐标 |
|---|---|
| Basic + OWNER_ADMIN | `rest/controller/v1/OwnerRestControllerV1.java#addOwner` |

> 新增业主：契约校验 → 鉴权 → O1 落库 → `201` 回执。

**关键事实**
- contract-first：路由与请求契约来自 `openapi.yml` 和生成的 `OwnersApi`，不能只搜索 Controller 方法注解。
- 错误由 `rest/advice/ExceptionControllerAdvice.java` 集中翻译。

<details><summary>Request、Response、处理逻辑与错误映射</summary>

**Request**

- Content-Type：`application/json`。
- Body 字段契约：`firstName`、`lastName`、`address`、`city`、`telephone`。
- Schema 校验：OpenAPI 契约经生成代码和 Bean Validation 执行；字段级约束以当前 `openapi.yml` 为准。

结构示意（合法 JSON，字符串为占位符）：

```json
{
  "firstName": "{firstName}",
  "lastName": "{lastName}",
  "address": "{address}",
  "city": "{city}",
  "telephone": "{telephone}"
}
```

**处理逻辑**

1. 接收并校验 Owner 输入。
2. 调用 O1 保存实体并回填 `id`。
3. 返回 `201`、`Location: /api/owners/{id}` 和完整 Owner 字段。

**Response — Success**

- Status：`201`。
- Body 字段契约：`id`、`firstName`、`lastName`、`address`、`city`、`telephone`；`id ← O1 回填主键`。

**错误映射**

| 场景 | 来源 | 对外响应 | 泄漏 |
|---|---|---|---|
| 未认证或角色不足 | 鉴权 | `401`／`403` | 否 |
| Bean Validation 失败 | 本地校验 | `400` ProblemDetail | 否 |
| 约束冲突（O1 传播） | 下游传播 | 当前项目映射为 `404` | 否 |
| 未捕获异常 | 未捕获 | `500` ProblemDetail | 否 |

</details>

## Operations

### O1. Database → owners 表（Spring Data JPA）

主坐标：`service/ClinicServiceImpl.java#saveOwner`

> 保存 Owner 实体并回填 IDENTITY 主键，供 I1 的响应和 Location 使用。

**关键事实**
- 无方法级 `@Transactional` 时，事务由当前 repository 调用链承担；不以「无注解」直接推断「无事务」。

<details><summary>派生 SQL、数据映射与失败表</summary>

**Statement（派生）**

依据当前实体映射和 `ownerRepository.save()` 推导，非运行时 SQL 抓取：

```sql
INSERT INTO owners (first_name, last_name, address, city, telephone)
VALUES (?, ?, ?, ?, ?)
```

**数据映射**

- 五个请求字段 ← I1 Request Body。
- 回填 `id` → I1 Response Body 与 `Location`。

**失败表**

| 症状 | 处置 |
|---|---|
| 约束冲突 | 致命-中止（映射见 I1） |

</details>
