---
name: add-endpoint
description: Add a new REST API endpoint following the contract-first approach with API tests and Spring Cloud Contract tests. Use when asked to add a new endpoint to an existing resource.
allowed-tools: Bash
---
# Add Endpoint

## Pre-conditions
- [ ] API spec updated in `docs/design/api-spec-v1.md`
- [ ] Command/Query/Representation defined as `record`
- [ ] `ApplicationService` has the method

## Steps

0. **Validate pre-conditions** — verify:
   - `docs/design/api-spec-v1.md` contains the endpoint definition
   - `ApplicationService` has the required method
   If any condition fails: output the specific missing item and stop.

1. **Implement the use case** — Command/Query + Checker/Executor, wired through `ApplicationService`; business rules in Checker/Domain layer
2. **Create/update Controller endpoint** — return `ApiResponse<T>`, URL follows `/api/v1/{resource}`
3. **Write API test** — WebTestClient + JSON fixtures + @Sql seed data
4. **Write Contract Test** — Spring Cloud Contract Groovy DSL
5. **Update OpenAPI spec** if applicable

## Validation Checklist
- [ ] URL follows `/api/v1/{resource}` pattern
- [ ] HTTP Method matches action semantics
- [ ] Response uses `ApiResponse<T>` wrapper
- [ ] Contract Test covers success scenario
- [ ] Contract Test covers error scenarios (400, 404, 500)
- [ ] Downstream side effects documented in API spec (if applicable)
