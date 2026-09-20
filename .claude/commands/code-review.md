---
description: "Systematic code review for quality, architecture compliance, test coverage, and security in Java/Spring Boot projects"
agent: code-reviewer
context: fork
argument-hint: "file-or-directory path to review"
---

Review the target below by following the **code-reviewer agent** instructions in full — the agent definition is the single source for required rules files, anti-pattern grep patterns, review checklists, and the output format.

**Review target:** $ARGUMENTS (if omitted, review the current change set: `git diff --name-only` + untracked files)

Additional emphasis for this pass:

- Architecture compliance against `architecture.instructions.md` §1 — infrastructure is the bottom support layer; `infrastructure/{rest,domain,application}` are cross-layer support packages
- Layered validation responsibilities per `validation.instructions.md` (Controller annotations → Service assertions → DB constraints)
- Logging standards per `logging.instructions.md` (no PII, placeholder syntax, exception object as last argument)

Produce the structured report exactly as specified in the agent's Output Format (verdict, metrics, critical/high/suggestions/questions/praise).
