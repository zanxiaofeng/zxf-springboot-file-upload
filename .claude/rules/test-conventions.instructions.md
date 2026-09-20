---
name: "Test Conventions"
description: "Testing conventions for API tests and contract tests with H2 and WireMock"
paths:
  - "**/test/**/*.java"
  - "**/*ApiTests.java"
  - "**/*ContractTest.java"
---

# Testing Conventions

> **职责边界：** 本文件是测试规范**总则**——测试分层、包结构、单元测试模式、Spring Test Context 管理、测试独立性。API Test 详细编码规范（命名、Fixture、模板、Support 类、三层断言）见 `integration-test-guide.md`，契约测试见 `contract-test.md`，TDD 工作流见 `tdd-workflow.md`。

***

## Three-Layer Test Pyramid

| Layer | Package | Scope | Tools | DB | Downstream | Naming Convention |
|-------|---------|-------|-------|-----|------------|-------------------|
| **Unit Test** | `unit/` | Single class in isolation | JUnit 5 + AssertJ + Mockito | None | None | `{ClassUnderTest}Test` |
| **Integration (API)** | `integration/` | Controller → ApplicationService → Executor → Repository | WebTestClient + @Sql + JSON fixtures + json-unit + DatabaseVerifier | H2 | WireMock (MockFactory/Verifier) | `{Entity}ApiTests` |
| **Contract Test** | `contract/` | API contract verification | Spring Cloud Contract + MockMvc + RestAssuredMockMvc | H2 | RestAssuredMockMvc | `{ClassUnderTest}ContractTest` |
| **Repository Slice** | `support/` | JPA repository (@DataJpaTest) | @DataJpaTest + H2 | H2 | None | `{Entity}RepositoryTest`（按需添加，参考实现暂无实例） |

**Layer dependency rule:** A failing unit test points to a bug in a single class. A failing API test points to an integration issue. A failing contract test points to a broken API agreement. Always fix from the bottom up.

***

## Test Package Structure

三类测试分别放在独立的 package 下,共享工具放 `support/`:

```
src/test/java/{base-package}/
├── support/                          # 共享测试工具 + Repository 切片测试
│   ├── json/JsonAssert.java
│   ├── mocks/{MockFileLoader, {Service}MockFactory, {Service}MockVerifier}.java
│   ├── sftp/{extension/SftpMockSpringJunitExtension, EnableSftpMock, SftpMockSupport}.java
│   ├── sql/DatabaseVerifier.java
│   ├── utils/TestResourcePaths.java
│   └── {Entity}RepositoryTest.java   # @DataJpaTest 切片测试(归 support, 按需添加)
├── unit/                             # Unit tests(Mockito / 纯 JUnit,无 Spring context)
│   ├── {Entity}ServiceTest.java
│   └── {Entity}MapperTest.java
├── integration/                      # API / 端到端集成测试(原 apitest/)
│   ├── {Entity}ApiTests.java
│   └── support/                      # integration 专属基础设施
│       ├── BaseApiTest.java
│       └── fixture/FixtureFileLoader.java
└── contract/                         # Contract tests
    ├── ContractBaseTest.java
    └── {Entity}ContractTest.java
```

**包规则:**
- `unit/` — 纯 Mockito / JUnit,不启动 Spring context
- `integration/` — 启动完整 Spring Boot 应用(WebTestClient + RANDOM_PORT + H2 + WireMock),即原 `apitest/`
- `contract/` — Spring Cloud Contract(MockMvc + RestAssuredMockMvc),验证 API 契约
- `support/` — 跨测试类型共享的工具类(`JsonAssert`、`MockFileLoader`、`DatabaseVerifier` 等)+ `{Entity}RepositoryTest`(@DataJpaTest 切片)

***

## Unit Test Patterns

> Unit tests 放在 `unit/` 包下。例外:`{Entity}RepositoryTest`(@DataJpaTest)归 `support/`(见下文 Repository Tests)。

### Service Tests — Mockito

Use `@ExtendWith(MockitoExtension.class)` for service layer tests. No Spring context needed.

```java
@ExtendWith(MockitoExtension.class)
class {Entity}ServiceTest {

    @Mock
    private {Entity}Repository repository;

    @Mock
    private {Entity}Mapper mapper;

    @InjectMocks
    private {Entity}Service service;

    @Test
    void findById_existingId_returnsResponse() {
        // given
        {Entity} entity = {Entity}.builder().id(1L).name("test").build();
        {Entity}Response expected = new {Entity}Response(1L, "test", Status.ACTIVE, null);
        given(repository.findById(1L)).willReturn(Optional.of(entity));
        given(mapper.toResponse(entity)).willReturn(expected);

        // when
        {Entity}Response actual = service.findById(1L);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void findById_nonExistingId_throwsBusinessException() {
        given(repository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.{ENTITY}_NOT_FOUND);
    }
}
```

### Repository Tests — @DataJpaTest

Use `@DataJpaTest` for testing repositories. This loads only the persistence layer.

> 归 `support/` 包(非 `unit/`):作为 Repository 的支撑性切片测试,与公共工具同包。

```java
@DataJpaTest
@Import({Entity}Repository.class)
class {Entity}RepositoryTest {

    @Autowired  // 测试切片允许字段注入(Spring Test 业内惯例);生产代码必须构造器注入(见 java-coding-standard.md §1.5)
    private {Entity}Repository repository; // concrete @Repository class

    @Test
    void save_andFindById_roundTrip() {
        {Entity} entity = {Entity}.builder().name("test").build();
        {Entity} saved = repository.save(entity);

        Optional<{Entity}> found = repository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("test");
    }
}
```

### Mapper / Utility Tests — Plain JUnit 5

Mappers and utilities are pure functions. No framework needed.

```java
class {Entity}MapperTest {

    private {Entity}Mapper mapper = new {Entity}Mapper();

    @Test
    void toEntity_mapsAllFields() {
        Create{Entity}Request request = new Create{Entity}Request("test", Type.DEFAULT);

        {Entity} entity = mapper.toEntity(request);

        assertThat(entity.getName()).isEqualTo("test");
        assertThat(entity.getId()).isNull(); // not persisted yet
    }

    @Test
    void toResponse_mapsAllFields() {
        {Entity} entity = {Entity}.builder().id(1L).name("test").build();

        {Entity}Response response = mapper.toResponse(entity);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("test");
    }
}
```

***

## Spring Test Context Management

### Test-Specific Beans with @TestConfiguration

Use `@TestConfiguration` + `@Import` to provide test-specific beans without modifying production code.

```java
@TestConfiguration
class TestConfig {
    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }
}
```

### Context Caching Rules

Spring Test caches the `ApplicationContext` by configuration. Breaking the cache causes full reloads (slow).

**Spring Boot 4 测试 Mock 变化（重要）：**
- `@MockBean` / `@SpyBean` 已移除 → 改用 `@MockitoBean` / `@MockitoSpyBean`
- `@MockitoBean` 可用于测试类字段（含超类层级），但**不能用于 `@Configuration` 类**；共享 mock 改用 `@MockitoBean(types = {...})` 或自定义复合注解
- `MockitoTestExecutionListener` 已移除 → 用 Mockito 原生 `@ExtendWith(MockitoExtension.class)`
- **`@SpringBootTest` 不再自动注入 MockMvc** → 需 `@AutoConfigureMockMvc`
- **`@SpringBootTest` 不再自动注入 `WebClient` / `TestRestTemplate`** → 需 `@AutoConfigureTestRestTemplate`（并依赖 `spring-boot-resttestclient`）；新代码推荐 `RestTestClient` + `@AutoConfigureRestTestClient`
- For service unit tests, prefer `@ExtendWith(MockitoExtension.class)` over `@MockitoBean`
- Reserve `@MockitoBean` for integration tests where you must replace a bean inside the running context

**Avoid `@DirtiesContext` unless truly necessary:**
- `@DirtiesContext` destroys the cached context after the test class
- Acceptable: tests that modify shared singleton state (e.g., `@CacheManager`, `JsonMapper` config)
- Not acceptable: just to reset data — use `@Sql` cleanup scripts instead

**Best practice for fast test suites:**
1. Group tests with the same `@MockitoBean` configuration into the same test class
2. Use `@Sql` for data cleanup instead of `@DirtiesContext`
3. Keep API test classes focused on a single controller to share the same context

***

## Test Independence Rules

### Mandatory Rules

1. **No `@DependsOn` or test ordering dependencies** — every test must pass when run alone or in any order
2. **No shared mutable state between tests** — use local variables or `@Sql` for setup
3. **Deterministic test data** — use fixed values, not `System.currentTimeMillis()` or `UUID.randomUUID()`
4. **No hidden test coupling** — a test must not rely on side effects from a previous test

### Data Isolation with @Sql

每个测试自清理 + 自初始化：`@Sql(scripts = {"/sql-data/cleanup/clean-up.sql", "/sql-data/init/data.sql"}, executionPhase = BEFORE_TEST_METHOD)`。三层脚本结构（cleanup/init/cases）、CLOB `FILE_READ` 技巧与数据隔离规则的**单一来源**见 `integration-test-guide.md` §4。

### Time Determinism

For tests involving time, inject a `Clock` bean:

```java
// Production
@Bean
public Clock systemClock() {
    return Clock.systemDefaultZone();
}

// Test
@TestConfiguration
static class TestClockConfig {
    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-15T10:30:00Z"), ZoneOffset.UTC);
    }
}
```

Never use `OffsetDateTime.now()` in test assertions — always use the fixed clock value.

***

## Comprehensive API Test Reference

For API test conventions including naming, fixtures, templates, support class reference, WireMock patterns, assertion system, and checklists, see `integration-test-guide.md`.
