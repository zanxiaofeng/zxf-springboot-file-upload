---
name: "Contract Testing"
description: "Spring Cloud Contract testing with Groovy DSL and RestAssuredMockMvc"
paths:
  - "**/*.groovy"
  - "**/contract/**/*.java"
---

# Contract Test Guide

> **职责边界：** 本文件定义契约测试的目录结构、Groovy DSL 模板、Base Test 类、Checklist。测试分层与包结构见 `test-conventions.md`，API 设计规范（URL/Method/状态码）见 `api-conventions.md`。

> **⚠️ 已知问题（上游未修复）：** Spring Cloud Contract 5.0.x 生成的 RestAssuredMockMvc 调用与 Spring Framework 7 不兼容——带 header 的契约会抛 `NoSuchMethodError`（rest-assured#1853 / spring-cloud-contract#2379，详见 `pom.xml` contract-tests profile 注释）。当前 3 个带 header 的契约（create/update/reject-invalid）暂时失败属预期，其余契约正常；上游发布兼容版本后恢复，或改用 `testMode = WEBTESTCLIENT` 规避。**在此期间 `run-contract-tests.sh` 无法全绿，Checklist 对应项按豁免处理。**

***

## Contract Test vs API Test

| 维度 | API Test | Contract Test |
|------|----------|---------------|
| 目的 | 验证**行为**（代码做了什么） | 验证**接口**（API 长什么样） |
| 运行方式 | WebTestClient + RANDOM_PORT | MockMvc + RestAssuredMockMvc |
| 数据 | @Sql 种子数据 + JSON fixtures | 内联或文件引用的固定数据 |
| 产出 | 测试结果 | 消费者端 Stub（供其他服务使用） |

Contract test 保证 API 契约不变，即 URL、HTTP 方法、请求/响应结构保持稳定。

***

## Directory

契约定义（groovy）位于 `src/test/resources/contracts/`：
```
src/test/resources/contracts/
└── {entity}/
    ├── shouldCreate{Entity}.groovy
    ├── shouldReturn{Entity}ById.groovy
    ├── shouldReturn404When{Entity}NotFound.groovy
    ├── shouldRejectInvalidCreate{Entity}.groovy
    ├── shouldUpdate{Entity}.groovy
    └── shouldDelete{Entity}.groovy
```

Contract 测试 Java 类放 `contract/` 包（验证契约 + 生成消费者端 Stub）：
```
src/test/java/{base-package}/contract/
├── ContractBaseTest.java           # 基类：@SpringBootTest + MockMvc setup
└── {Entity}ContractTest.java       # ⚠️ 构建期由 SCC maven plugin 生成（target/generated-test-sources/），非手写文件
```

***

## Groovy DSL Templates

所有响应体必须匹配 `ApiResponse<T>` 结构：`{ code, data: { ... }, timestamp }`。

> **成功响应 code 统一为 `"SUCCESS"`，错误响应 code 按模块编号（如 `"001001"`）。** ErrorCode 枚举定义见 `exception-handling.md` §3.2。

### Create (POST 201)
```groovy
Contract.make {
    description("should create a {entity} successfully")
    request {
        method POST()
        url "/api/v1/{resources}"
        headers { header("Content-Type", "application/json") }
        body(file("request-create.json"))
    }
    response {
        status 201
        headers {
            header("Content-Type", "application/json")
            header("Location", regex("/api/v1/{resources}/\\d+"))
        }
        body([
            code: "SUCCESS",
            data: [
                id       : $(regex(positiveInt())),
                name     : $(regex(nonEmpty())),
                status   : "ACTIVE",
                createdAt: $(regex(iso8601WithOffset()))
            ],
            timestamp: $(regex(iso8601WithOffset()))
        ])
    }
}
```

### Get by ID (GET 200)
```groovy
Contract.make {
    description("should return {entity} by id")
    request {
        method GET()
        url "/api/v1/{resources}/1"
    }
    response {
        status 200
        headers { header("Content-Type", "application/json") }
        body([
            code: "SUCCESS",
            data: [
                id       : 1,
                name     : "test-name",
                status   : "ACTIVE",
                createdAt: $(regex(iso8601WithOffset()))
            ],
            timestamp: $(regex(iso8601WithOffset()))
        ])
    }
}
```

### Not Found (GET 404)
```groovy
Contract.make {
    description("should return 404 when {entity} not found")
    request {
        method GET()
        url "/api/v1/{resources}/99999"
    }
    response {
        status 404
        headers { header("Content-Type", "application/json") }
        body([
            code     : "001001",
            message  : $(regex(nonEmpty())),
            timestamp: $(regex(iso8601WithOffset()))
        ])
    }
}
```

### Invalid Create (POST 400)
```groovy
Contract.make {
    description("should reject invalid {entity} creation")
    request {
        method POST()
        url "/api/v1/{resources}"
        headers { header("Content-Type", "application/json") }
        body([ name: "" ])
    }
    response {
        status 400
        headers { header("Content-Type", "application/json") }
        body([
            code     : "000002",
            message  : $(regex(nonEmpty())),
            timestamp: $(regex(iso8601WithOffset()))
        ])
    }
}
```

### Update (PUT 200)
```groovy
Contract.make {
    description("should update {entity} successfully")
    request {
        method PUT()
        url "/api/v1/{resources}/1"
        headers { header("Content-Type", "application/json") }
        body(file("request-update.json"))
    }
    response {
        status 200
        headers { header("Content-Type", "application/json") }
        body([
            code: "SUCCESS",
            data: [
                id       : 1,
                name     : $(regex(nonEmpty())),
                status   : "ACTIVE",
                createdAt: $(regex(iso8601WithOffset()))
            ],
            timestamp: $(regex(iso8601WithOffset()))
        ])
    }
}
```

### Delete (DELETE 204)

> **DELETE 返回 204 No Content，无响应体。** 与 `api-conventions.md` DELETE Convention 一致。

```groovy
Contract.make {
    description("should delete {entity} successfully")
    request {
        method DELETE()
        url "/api/v1/{resources}/1"
    }
    response {
        status 204
    }
}
```

***

## Base Test Class

```java
package {base-package}.contract;

@SpringBootTest(classes = {Project}Application.class, webEnvironment = MOCK)
@AutoConfigureMockMvc                   // SB4：@SpringBootTest 不再自动提供 MockMvc
@ActiveProfiles("test")
@Sql(scripts = {"/sql-data/cleanup/clean-up.sql", "/sql-data/init/data.sql"},
     executionPhase = BEFORE_TEST_METHOD)
public abstract class ContractBaseTest {
    @Autowired  // 测试基类允许字段注入(Spring Test 业内惯例);生产代码必须构造器注入
    private WebApplicationContext webApplicationContext;

    @BeforeEach
    void setup() {
        RestAssuredMockMvc.mockMvc(
            MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        );
    }
}
```

**注意：** Base test class 需要使用 `@Sql` 加载种子数据，确保 contract 验证时有数据可用。

***

## Checklist
- [ ] 所有响应体匹配 `ApiResponse<T>` 结构（`code`/`data`/`timestamp`）
- [ ] 成功响应 code 为 `"SUCCESS"`，错误响应 code 按模块编号（如 `"001001"`）
- [ ] DELETE 契约返回 204 No Content，无响应体
- [ ] Request/Response 字段匹配 `docs/design/api-spec-v1.md`
- [ ] 使用 regex 处理动态值（id、timestamp、email）
- [ ] 覆盖 success + error 场景（400、404）
- [ ] 覆盖 CRUD 全部操作（POST/GET/PUT/DELETE）
- [ ] `./scripts/run-contract-tests.sh` 通过（⚠️ RestAssured/Spring 7 兼容问题解决前，3 个带 header 契约豁免，见文首已知问题）
