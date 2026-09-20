---
name: refactor-module
description: Safely refactor a module with characterization tests, incremental changes, and continuous verification. Use when asked to refactor, clean up, or restructure existing code.
allowed-tools: Bash
---

# Refactor Module

## Pre-conditions
- [ ] All tests pass (`mvn test`)
- [ ] Code coverage > 80%

## Steps

1. **Identify code smells** — duplication, long methods (>50 lines), unclear naming, tight coupling, deep nesting (>4 levels)
2. **Write characterization tests** if coverage is low — capture current behavior before changing anything
3. **Apply refactoring incrementally** — one change at a time, with a clear commit after each
4. **Run tests after each change** — `mvn test` or `./scripts/fast-test.sh`
5. **Update documentation** if public API changed

## Safety Rules
- Never refactor without passing tests
- One refactoring at a time
- Prefer small commits
- If tests break, revert and reassess:
  - `git checkout -- <file>` to discard uncommitted changes
  - `git stash` to save current work and switch approach
  - Reassess by re-identifying the code smell and considering an alternative refactoring

## Reassessment Criteria
When tests break after a refactoring step:
1. Check if the refactoring introduced a bug (fix the refactoring)
2. Check if the test was coupled to implementation details (fix the test, not the code)
3. If neither, consider a different refactoring approach or break the change into smaller steps

## Common Refactorings
- Extract Method — long methods
- Extract Class — large classes with multiple responsibilities
- Move Method — methods in wrong layer
- Rename — unclear naming
- Introduce Parameter Object — long parameter lists
- Replace Conditional with Polymorphism — complex switch/if chains

## Validation
- `mvn test` passes after each change
- `mvn compile` succeeds
