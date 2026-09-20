---
name: "API Conventions"
description: "REST API design conventions for URL patterns, HTTP methods, and response format"
paths:
  - "**/rest/**/*.java"
  - "**/docs/design/**/*.md"
---

# API Design Conventions

## URL Pattern
- Base path: `/api/v{version}/{resource}`
- Plural nouns: `/users`, `/orders`
- No verbs in URL (use HTTP Method)

## HTTP Method Semantics
| Method | Purpose | Success Code |
|--------|---------|-------------|
| GET    | Query   | 200 |
| POST   | Create  | 201 |
| PUT    | Full update | 200 |
| PATCH  | Partial update | 200 |
| DELETE | Delete  | **204** |

## Response Body
成功响应的 `code` 固定为 `SUCCESS`；`message` 等为 null 的字段不输出（`ApiResponse` 标注 `@JsonInclude(NON_NULL)`）。
```json
{
  "code": "SUCCESS",
  "data": { },
  "timestamp": "2026-04-27T12:00:00+08:00",
  "traceId": "abc123"
}
```

## Error Response
```json
{
  "code": "002001",
  "data": null,
  "message": "Request validation failed",
  "timestamp": "2026-04-27T12:00:00+08:00",
  "traceId": "abc123",
  "errors": [
    { "field": "email", "message": "must be a valid email", "rejectedValue": "invalid" }
  ]
}
```

## Downstream Side Effects
When an endpoint triggers a downstream call, document it in the API spec:
- Endpoint URL, payload format, failure mode
- Example: `POST /api/v1/{resource}` sends `POST /api/v1/{downstream-service}/{event-name}`

To add a new endpoint, use the `/add-endpoint` skill or follow the step-by-step process defined there.

## API Versioning Strategy
- **URL-based versioning**: `/api/v1/...`, `/api/v2/...`
- **When to bump version**: breaking changes (removing fields, changing types, renaming endpoints)
- **Non-breaking changes** (adding optional fields, new endpoints) do NOT require version bump
- **Version coexistence**: both versions run simultaneously, old version deprecated with sunset header
- **Controller organization**: `{Entity}V1Controller`, `{Entity}V2Controller` — separate classes, same or different packages
- **Deprecation**: `@Deprecated` annotation + `Sunset` response header, minimum 6 months overlap before removal

## Pagination Conventions

所有 list 端点：查询对象继承 `infrastructure/application/PageableQuery`，返回 `ApiResponse<Page<...>>`。

### Request Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page`    | int  | 0       | Zero-based page index（负值在 `getPageRequest()` 内收敛为 0） |
| `size`    | int  | 10      | Page size（上限 clamp 见下） |

### Controller Example

```java
@GetMapping
public ResponseEntity<ApiResponse<Page<{Entity}Representation>>> list({Entity}ListQuery query) {
    return ResponseEntity.ok(ApiResponse.success(
            {domain}RepresentationMapper.toPage(applicationService.{domain}sAll(query))));
}
```

**Rules:**
- 分页参数由 `PageableQuery` 的 getter/setter 绑定 GET 查询参数，`getPageRequest()` 转 Spring Data `Pageable`
- 页面大小上限 clamp 统一在 `PageableQuery` 内实现（1..100）——**不要**依赖 `spring.data.web.pageable.max-page-size`，该配置只作用于 Spring Data 自带的 `PageableArgumentResolver`，对这种手动绑定模式不生效（见 architecture §7.5）
- 不要直接绑定 Spring Data `Pageable`（`@PageableDefault`）——本项目分页只有 `PageableQuery` 一种模式
- 门面返回 `Page<{Entity}>`，`{Entity}` → Representation 的分页转换在 rest 层 mapper（见 architecture §4.4）

### Response Format

```json
{
  "code": "SUCCESS",
  "data": {
    "content": [
      { "id": 1, "name": "example" }
    ],
    "totalElements": 150,
    "totalPages": 8,
    "number": 0,
    "size": 20
  },
  "timestamp": "2026-04-27T12:00:00+08:00",
  "traceId": "abc123"
}
```

## HTTP Status Codes

### Success Codes

| Code | Meaning | When to Use |
|------|---------|-------------|
| **200 OK** | Successful retrieval or update | GET, PUT, PATCH responses |
| **201 Created** | Resource created successfully | POST responses; include `Location` header |
| **204 No Content** | Successful with no response body | DELETE responses |
| **202 Accepted** | Request accepted for async processing | Long-running operations, async tasks |

DELETE 端点返回 **204 No Content**（无响应体，与上表及 HTTP Method Semantics 一致）：

```java
@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable @Positive Long id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
}
```

### Error Codes

| Code | Meaning | When to Use |
|------|---------|-------------|
| **400 Bad Request** | Malformed request or validation failure | Invalid JSON, missing required fields |
| **404 Not Found** | Resource does not exist | `findById` returns empty |
| **409 Conflict** | State conflict | Duplicate resource, optimistic lock failure |
| **422 Unprocessable Entity** | Semantically invalid request | Business rule violation, invalid state transition |

## @PathVariable Validation

All path variable IDs must use `@Positive` validation to reject zero and negative values.

```java
@GetMapping("/{id}")
public ResponseEntity<ApiResponse<{Entity}Response>> getById(
        @PathVariable @Positive Long id) {
    return ResponseEntity.ok(ApiResponse.success(service.findById(id)));
}
```

**Rules:**
- Every `@PathVariable` representing an entity ID must be annotated with `@Positive`
- This applies to all HTTP methods: GET, PUT, PATCH, DELETE
- `@Positive` rejects `0` and negative values(按 Jakarta Validation 规范,**null 视为 valid**;若需非空请叠加 `@NotNull`。`@PathVariable` 缺失时 Spring 已先返回 400,不会进入方法为 null)
- For composite keys or non-ID path variables, use the most appropriate constraint (`@NotBlank`, `@Pattern`, etc.)

> 参数校验的完整规范（声明式 Bean Validation、命令式断言、`@Valid` vs `@Validated` 等）见 `validation.md`。
