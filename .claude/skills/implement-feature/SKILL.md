---
name: implement-feature
description: Implement a new feature following the TDD workflow — from requirement analysis through API tests, implementation, contract tests, and documentation update. Use when asked to implement a new feature or user story.
allowed-tools: Bash
---
# Implement Feature

!`echo "Current branch: $(git branch --show-current 2>/dev/null || echo 'N/A')"`

## Pre-conditions
- [ ] Requirement doc exists in `docs/requirements/`
- [ ] ADR recorded if new tech introduced

## Steps

0. **Validate pre-conditions** — check that `docs/requirements/{feature-name}.md` exists.
   If not found: output `Requirement doc not found: docs/requirements/{feature-name}.md. Create it first using the template in docs/templates/requirement-template.md.` and stop.

1. **Read requirement doc** — extract business rules and acceptance criteria
2. **Prepare test data** — add seed data to `src/test/resources/sql-data/init/data.sql`, create JSON fixtures under `src/test/resources/test-data/$feature-name/`
3. **Write failing API test (Red)** — WebTestClient + JSON fixtures + @Sql seed data + DatabaseVerifier
4. **Minimal implementation (Green)** — Controller -> ApplicationService -> Executor/Checker -> Repository in layers
5. **Refactor** — check against conventions in `.claude/rules/`, extract duplicates, optimize naming
6. **Write Contract Test** — Spring Cloud Contract Groovy DSL for each new endpoint
7. **Update documentation** — `docs/design/api-spec-v1.md`, `docs/design/domain-model.md`, and requirement status

## Downstream Integration Steps (if applicable)

1. Create MockFactory/Verifier in `support/mocks/` for WireMock stubs (alongside the failing API test)
2. Implement the concrete `{Service}Client` (a `@Component`, no interface) in `infrastructure/downstream/` using RestClient
3. Add `{Feature}Config` in `infrastructure/config/` if not present
4. Add downstream base URL to `application.yml` and `application-test.yml`
5. Wire the side effect per `service-conventions.md` §2: executor publishes a Domain Event, the listener in `application/event/` calls the client (no direct in-transaction downstream calls)

## Output
- Implementation code (rest -> application -> domain, technical components in infrastructure)
- API tests (`*ApiTests.java`)
- JSON fixtures under `test-data/$feature-name/`
- Contract tests (`*.groovy`)
- Updated documentation

## Validation
- `mvn test` passes
- `./scripts/run-contract-tests.sh` passes (if contract tests added; RestAssured/Spring 7 兼容问题解决前按 `contract-test.instructions.md` 已知问题豁免)
