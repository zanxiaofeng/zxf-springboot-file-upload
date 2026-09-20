---
name: "Integration Test Guide"
description: "Integration test conventions with WebTestClient, JSON fixtures, @Sql, DatabaseVerifier, and WireMock"
paths:
  - "**/integration/**/*.java"
  - "**/apitest/**/*.java"
  - "**/test/resources/**"
---


# API Test 编码规范

> **职责边界：** 本文件是 API Test 的**详细编码指南**——命名、Fixture、模板、Support 类、三层断言、WireMock 模式、SFTP Mock、配置。测试分层、包结构、Context 管理、独立性规则见 `test-conventions.md`。

***

## 1. 总览与设计原则

API Test 是端到端集成测试，启动完整的 Spring Boot 应用（RANDOM_PORT），通过 `WebTestClient` 发起真实 HTTP 请求，验证整个请求链路（Controller -> Service -> Repository -> Database + Downstream）。

| 原则 | 说明 |
|------|------|
| **真实 HTTP** | WebTestClient 发起真实 HTTP 请求，非 MockMvc |
| **H2 数据库** | 使用 H2（MySQL 兼容模式），Flyway 建表 |
| **@Sql 管理数据** | 预置种子数据，不通过 API 运行时创建 |
| **JSON Fixture** | 请求/响应使用 JSON 文件 + 模板变量，不硬编码 |
| **json-unit** | 使用 json-unit 断言，支持 `${json-unit.ignore}` 占位符、忽略额外字段和数组顺序 |
| **DB 直验** | 通过 DatabaseVerifier 直接查询 DB 验证状态 |
| **MockFactory/Verifier** | WireMock 下游 mock 通过 Factory 创建、Verifier 验证 |
| **Given/When/Then** | 每个测试方法严格遵循三段式结构 |

> **Spring Boot 4 提示：** 本规范 API Test 基于 `WebTestClient` + `RANDOM_PORT` 发起真实 HTTP，**不受** SB4「`@SpringBootTest` 不再自动注入 MockMvc」的影响。若个别测试改用 MockMvc，须显式加 `@AutoConfigureMockMvc`；Mock 注解统一用 `@MockitoBean` / `@MockitoSpyBean`（`@MockBean` 已移除）。
>
> **WebTestClient 注入（SB4.1 实测）：** SB4 模块化下 `@AutoConfigureWebTestClient`（旧包 `org.springframework.boot.test.autoconfigure.web.reactive`）已不存在/不可靠。`BaseApiTest` 改用 `@LocalServerPort` + `@BeforeEach` 手动构造：
> ```java
> @LocalServerPort protected int port;
> protected WebTestClient webTestClient;
> @BeforeEach void init() {
>     this.webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
> }
> ```
> 并需 test scope 引入 `spring-boot-starter-webflux`（提供 WebTestClient 客户端类，不启动 reactive server）。

> **测试包结构：** 完整目录结构见 `test-conventions.md` Test Package Structure。

***

## 2. 命名规范

### 类命名

| 类型 | 格式 | 示例 |
|------|------|------|
| 测试类 | `{Entity}ApiTests` | `UserApiTests`, `OrderApiTests` |
| Mock Factory | `{Service}MockFactory` | `PaymentMockFactory` |
| Mock Verifier | `{Service}MockVerifier` | `PaymentMockVerifier` |

### 方法命名

格式：`test{Action}{Entity}[{Condition}]`，如 `testCreateUser`、`testCreateUserWithValidationError`、`testGetUserByIdNotFound`。

### 文件命名

| 类型 | 路径格式 |
|------|---------|
| 请求 fixture | `test-data/{entity}/{operation}/request.json` |
| 成功响应 | `test-data/{entity}/{operation}/ok.json` 或 `created.json` |
| 错误响应 | `test-data/{entity}/{operation}/not-found.json` 或 `validation-error.json` |
| 种子 SQL | `sql-data/init/data.sql` |
| 用例 SQL | `sql-data/cases/{case-name}.sql` |
| CLOB 文件 | `sql-data/cases/{case-name}-details.txt` |

***

## 3. Support 类参考（读源码，不内联）

Support 类是测试基础设施，**不要在 fixture 中复制其代码**，直接引用即可：

| 类 | 路径 | 职责 |
|----|------|------|
| `BaseApiTest` | `integration/support/BaseApiTest.java` | 抽象基类：@SpringBootTest + WebTestClient + WireMock + @Sql 种子数据 + HTTP 辅助方法（`httpGetAndAssert`/`httpPostAndAssert`/`httpPutAndAssert`/`httpDeleteAndAssert`） |
| `FixtureFileLoader` | `integration/support/fixture/FixtureFileLoader.java` | 加载 classpath JSON 文件 + `${variable}` 模板变量替换 |
| `JsonAssert` | `support/json/JsonAssert.java` | json-unit 断言工具：`assertJsonEquals`（lenient，忽略额外字段和数组顺序）、`assertJsonEqualsStrict`（严格模式） |
| `DatabaseVerifier` | `support/sql/DatabaseVerifier.java` | JDBC 直接查询验证 DB 状态（count{Entities}, {entity}Exists, find{Entity}IdBy{Field} 等） |
| `MockFileLoader` | `support/mocks/MockFileLoader.java` | 加载 classpath `mock-data/` 目录下的 JSON 文件 + `${variable}` 模板变量替换，供 MockFactory 使用 |
| `{Service}MockFactory` | `support/mocks/` | WireMock stub 创建（`mock{Service}{Scenario}`），使用 MockFileLoader 加载 request/response 模板 |
| `{Service}MockVerifier` | `support/mocks/` | WireMock 调用验证（`verify{Service}{Action}`） |
| `@EnableSftpMock` | `support/sftp/` | 内嵌 SFTP 服务器注解（`@EnableSftpMock(sshPort = N)`），每个测试前启动 Apache Mina SSHD、后停止 |
| `SftpMockSupport` | `support/sftp/` | SFTP 文件验证工具：`verifyFileUploaded`、`verifyFileExists`、`verifyFileContent`、`verifyFileCount` 等 |

***

## 4. SQL 测试数据规则

| 规则 | 说明 |
|------|------|
| 三层结构 | Cleanup (`sql-data/cleanup/`) -> Init (`sql-data/init/`) -> Cases (`sql-data/cases/`) |
| Cleanup | `DELETE FROM` 按外键反序，不用 TRUNCATE |
| Init 种子 | 硬编码 ID（1-99），BCrypt 密码，显式时区时间戳 |
| Case 级别 | 特定场景额外数据，ID >= 100，用 `@Sql` 注解加载 |
| CLOB 技巧 | H2: `UTF8TOSTRING(FILE_READ('classpath:sql-data/cases/xxx.txt'))` |
| 执行顺序 | BaseApiTest @Sql -> method @Sql -> test body |

***

## 5. JSON Fixture 规则

| 规则 | 说明 |
|------|------|
| 目录 | `test-data/{entity}/{operation}/` |
| 请求模板 | 使用 `${variable}` 变量，运行时 `FixtureFileLoader.load(path, Map.of(...))` 替换 |
| 成功响应 | 可用模板变量，动态字段不写（由 `IGNORING_EXTRA_FIELDS` 自动忽略） |
| 错误响应 | 完全静态，不使用模板变量 |
| 动态字段占位符 | 可选：使用 `${json-unit.ignore}` 在 fixture 中显式标注忽略的字段 |
| 比较模式 | `assertJsonEquals` 使用 lenient 模式（忽略额外字段 + 数组顺序），fixture 可省略非关键字段 |

***

## 6. WireMock 命名模式

- **MockFactory 方法**: `mock{Service}{Scenario}` — 如 `mockPaymentAccepted()`、`mockPaymentRejected()`
- **MockVerifier 方法**: `verify{Service}{Action}` — 如 `verifyPaymentCalled(count)`、`verifyPaymentCalledWith(params...)`
- **静态文件**: `mock-data/mappings/{service}-{scenario}.json`，`__files/` 用 `.txt` 扩展名
- **请求匹配**: MockFactory 使用 `MockFileLoader` 加载 `mock-data/request/` 模板，通过 `equalToJson()` 匹配请求体结构
- **响应模板**: MockFactory 使用 `MockFileLoader` 加载 `mock-data/response/` 模板，支持 `${variable}` 变量替换

### MockFactory 模板加载模式

```java
@UtilityClass
public class {Service}MockFactory {

    /** 请求体模板 — 使用 ${json-unit.ignore} 通配匹配任意字段值 */
    private static final String REQUEST_BODY =
            MockFileLoader.load("request/{service}-{action}.json");

    public static void mock{Service}{Scenario}() {
        String responseBody = MockFileLoader.load("response/{service}-{scenario}.json");

        WireMock.stubFor(WireMock.post(urlEqualTo("/api/v1/{path}"))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(equalToJson(REQUEST_BODY))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(responseBody)));
    }

    /** 带变量替换的响应模板 */
    public static void mock{Service}{Scenario}(String param) {
        String responseBody = MockFileLoader.load("response/{service}-{scenario}.json",
                Map.of("key", param));
        // ... 同上 stub 注册
    }
}
```

### mock-data 模板文件规则

| 目录 | 用途 | 占位符 |
|------|------|--------|
| `mock-data/request/` | 请求体匹配模板 | `${json-unit.ignore}`（通配任意值）、`${variable}`（匹配特定值） |
| `mock-data/response/` | 响应体返回模板 | `${variable}`（运行时替换动态值） |
| `mock-data/mappings/` | WireMock 静态映射 | 无占位符 |
| `mock-data/__files/` | WireMock 静态响应体 | 无占位符 |

**请求模板示例** (`mock-data/request/notification-user-created.json`)：

```json
{
  "userId": "${json-unit.ignore}",
  "username": "${json-unit.ignore}",
  "email": "${json-unit.ignore}",
  "eventType": "${json-unit.ignore}"
}
```

> WireMock 的 `equalToJson()` 底层使用 json-unit，因此支持 `${json-unit.ignore}` 占位符。

> 下游 Mock 的完整规范见 `downstream-conventions.md` §9。

***

## 7. 三层断言体系

| 层级 | 工具 | 验证目标 | 使用场景 |
|------|------|---------|---------|
| HTTP Response | JsonAssert (json-unit) | 响应 JSON 结构和字段值 | **每个测试必须** |
| Database | DatabaseVerifier + AssertJ | 数据库状态 | 创建/更新/删除操作 |
| Downstream | MockVerifier | 下游服务调用 | 有下游集成的操作 |
| SFTP File | SftpMockSupport | SFTP 文件上传验证 | 有文件上传的操作 |

***

## 7.1 SFTP Mock 模式

使用内嵌 Apache Mina SSHD 服务器模拟 SFTP 环境，适用于测试文件上传功能。

```java
@EnableSftpMock(sshPort = 2222)              // 类级别：启动内嵌 SFTP 服务器
public class UploadApiTests extends BaseApiTest {

    @Test
    void testUploadFile(Path tempSftpDir) {  // 参数注入：临时 SFTP 目录
        // Given
        String requestBody = FixtureFileLoader.load("upload/post/request.json",
                Map.of("dst", "dir/file.txt", "content", "hello"));

        // When
        httpPostAndAssert("/api/v1/uploads", commonHeadersAndJson(),
                requestBody, String.class, HttpStatus.OK, null);

        // Then — 验证文件已上传且内容正确
        SftpMockSupport.verifyFileUploaded(tempSftpDir, "dir/file.txt", "hello");
    }
}
```

**SftpMockSupport 常用方法：**

| 方法 | 用途 |
|------|------|
| `verifyFileUploaded(dir, path, content)` | 验证文件存在且内容匹配 |
| `verifyFileExists(dir, path)` | 仅验证文件存在 |
| `verifyFileNotExists(dir, path)` | 验证文件不存在 |
| `verifyFileContent(dir, path, content)` | 验证已存在文件的内容 |
| `verifyFileCount(dir, dirPath, count)` | 验证目录下文件数量 |
| `prepareFile(dir, path, content)` | 预置文件（用于下载测试） |

***

## 8. 测试方法模板

### 标准模板（Given/When/Then）

```java
import {base-package}.support.json.JsonAssert;  // 显式调用(@UtilityClass 工具类不用 static import,见 §10 Support 类扩展)

@Test
void testCreate{Entity}() throws Exception {
    // Given
    String requestBody = FixtureFileLoader.load("{entity}/post/request.json",
            Map.of("{field1}", "value1", "{field2}", "value2"));
    {Service}MockFactory.mock{Service}{Scenario}();
    int initialCount = databaseVerifier.count{Entities}();

    // When
    ResponseEntity<String> response = httpPostAndAssert("/api/v1/{resources}",
            commonHeadersAndJson(), requestBody,
            String.class, HttpStatus.CREATED, MediaType.APPLICATION_JSON);

    // Then — 响应验证
    String expected = FixtureFileLoader.load("{entity}/post/created.json",
            Map.of("{field1}", "value1", "{field2}", "value2"));
    JsonAssert.assertJsonEquals(expected, response.getBody());

    // And — 数据库状态验证
    assertThat(databaseVerifier.count{Entities}()).isEqualTo(initialCount + 1);

    // And — 下游调用验证
    {Service}MockVerifier.verify{Service}CalledWith("value1", "value2");
}
```

### 错误场景模板

```java
@Test
void testCreate{Entity}WithValidationError() throws Exception {
    // Given — 不需要 MockFactory（验证失败不触发下游）
    String requestBody = "{\"{field1}\":\"\",\"{field2}\":\"invalid\"}";

    // When
    ResponseEntity<String> response = httpPostAndAssert("/api/v1/{resources}",
            commonHeadersAndJson(), requestBody,
            String.class, HttpStatus.BAD_REQUEST, MediaType.APPLICATION_JSON);

    // Then — 静态 fixture，无模板变量
    String expected = FixtureFileLoader.load("{entity}/post/validation-error.json");
    JsonAssert.assertJsonEquals(expected, response.getBody());

    // And — 下游未被调用
    {Service}MockVerifier.verify{Service}Called(0);
}
```

***

## 9. 配置规范（application-test.yml）

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: none
    show-sql: true
  flyway:
    enabled: true
    locations: classpath:db/migration

wiremock:
  server:
    port: 0

app:
  downstream:
    {service}:
      base-url: http://localhost:${wiremock.server.port}
```

***

## 10. Checklist

### 新增实体 API Test

- [ ] 测试类命名 `{Entity}ApiTests`，放在 `integration/` 包，继承 `BaseApiTest`
- [ ] 方法命名 `test{Action}{Entity}[{Condition}]`
- [ ] 每个方法包含 `// Given` / `// When` / `// Then` / `// And` 注释
- [ ] 请求体通过 `FixtureFileLoader.load()` + `Map.of()` 模板变量
- [ ] 响应断言使用 `JsonAssert.assertJsonEquals(expected, actual)`（**显式调用,非 static import** — @UtilityClass 兼容,见 §10）
- [ ] 写操作使用 `DatabaseVerifier` 验证 DB 状态
- [ ] 有下游调用时使用 `MockFactory` + `MockVerifier`
- [ ] MockFactory 使用 `MockFileLoader` 加载 request/response 模板（`mock-data/request/` 和 `mock-data/response/`）
- [ ] 请求模板使用 `equalToJson()` + `${json-unit.ignore}` 匹配请求体结构
- [ ] 所有 HTTP 调用通过 `httpXxxAndAssert()`，响应类型参数 `String.class`
- [ ] 种子数据已加入 `sql-data/init/data.sql`，清理 SQL 已更新 `sql-data/cleanup/clean-up.sql`
- [ ] JSON fixture 已创建在 `test-data/{entity}/` 下
- [ ] WireMock 请求/响应模板已创建在 `mock-data/request/` 和 `mock-data/response/` 下
- [ ] DatabaseVerifier 中添加了新实体的查询方法
- [ ] 有文件上传操作时使用 `@EnableSftpMock` + `SftpMockSupport`

### Support 类扩展

- [ ] 工具类用 `@UtilityClass`(统一规范见 `java-coding-standard.md` §5.2「工具类(@UtilityClass)」;main 与 test 一致),Spring Bean 用 `@Component` + `@RequiredArgsConstructor`
  - **调用必须用显式 `类名.方法`(如 `JsonAssert.assertJsonEquals(...)`、`FixtureFileLoader.load(...)`),禁止 `import static`** —— Lombok 生成的 static 方法与 javac static-import 不兼容(SB4 + Lombok 1.18.46 实测 test-compile 报 `cannot find symbol`)。规则 §8 模板已据此改用显式调用
- [ ] MockFactory: `mock{Service}{Scenario}`，使用 MockFileLoader 加载模板 | MockVerifier: `verify{Service}{Action}`
- [ ] DatabaseVerifier: `{verb}{Entity}{Field}` 或 `{verb}{Entity}By{Condition}`
