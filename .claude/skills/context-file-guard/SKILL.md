---
name: context-file-guard
description: Audit, prune, and normalize CLAUDE.md/AGENTS.md context files — structure rules, length cap, weak wording, dead references, wiki boundary, and cross-file drift.
when_to_use: Use when creating or reviewing CLAUDE.md/AGENTS.md, when rules seem to be ignored, before adding new rules, or during periodic (quarterly) context-file review.
arguments: [action]
allowed-tools:
  - Read
  - Write
  - Edit
  - Grep
  - Glob
  - Bash(python3 .claude/skills/context-file-guard/scripts/context_lint.py *)
disallowed-tools:
  - Bash(rm -rf *)
  - Bash(git push *)
paths:
  - "CLAUDE.md"
  - "AGENTS.md"
  - "**/CLAUDE.md"
  - "**/AGENTS.md"
---

# Context File Guard

Keep CLAUDE.md/AGENTS.md short, falsifiable, and single-sourced: these files carry **behavior rules and pointers**, while knowledge lives in `.wiki/` (maintained by the repo-wiki skill). A rule that lives only in CLAUDE.md is a wish — pair critical rules with hooks/CI, or label them advisory.

## Pre-conditions
- [ ] `$ARGUMENTS` is one of: `audit` / `prune` / `normalize`
- [ ] Target file exists (default: `CLAUDE.md` and/or `AGENTS.md` at repo root)

## Reference Structure

```
# {Project}

One-paragraph identity: what this project is, the agent's role, response language.

## Tech Stack
- Exact versions only ("Java 21, Spring Boot 4.1.x"), never bare names.

## Commands
- Precise build/test commands in fenced blocks (module- and filter-precise).

## Architecture
- One short paragraph on layers & dependency direction; details live in .wiki/ — link, don't inline.

## Conventions
- Only conventions that differ from defaults: branch naming, PR etiquette, commit style.

## Decisions
- One line per key decision WITH its reason ("X because Y"); full ADRs in .wiki/decisions/.

## Hard Rules
- Don't/Never list; every ban paired with the replacement ("No field @Autowired — use constructor injection").

## Principles
- 3–7 principles, each with a one-line rationale; they cover cases rules don't.
```

## Structure Rules

| Level | Section | Basis |
|---|---|---|
| Required | Tech Stack, Commands, Hard Rules | Anthropic Include table; AgentLint rules 1–3; JavaGuide 该写清单 |
| Recommended | Architecture, Conventions, Decisions, Principles | Anthropic Include table; AgentLint rules 4, 6–7 |
| Forbidden | Philosophy/history sections; file-by-file directory trees; inline reference docs; code-style details (linter's job); meta-rules about the file itself | Anthropic Exclude table; AgentLint anti-patterns; JavaGuide 不该写清单 |

## Content Rules

Every line must pass the **removal test**: *"Would removing this cause the agent to make mistakes?"* If not, cut it.

| Include | Exclude |
|---|---|
| Commands the agent can't guess | Anything derivable from reading code |
| Rules that differ from defaults | Standard language/framework conventions |
| Test instructions & preferred runners | Detailed API docs (link instead) |
| Repository etiquette (branch/PR) | Frequently-changing information |
| Project-specific decisions + reasons | Long explanations or tutorials |
| Env quirks, required variables, gotchas | File-by-file trees, "write clean code" |

- Weak wording ("尽量" / "建议" / "try to" / "consider") on rule lines → rewrite as MUST/禁止 or delete
- Knowledge-type content (domain concepts, module guides, troubleshooting) must NOT accumulate here — migrate via repo-wiki `ingest` and leave a link
- If CLAUDE.md and AGENTS.md coexist, designate one as the source of truth; the other imports/links to it

## Operations

### audit
1. Run the bundled script from the repo root: `python3 .claude/skills/context-file-guard/scripts/context_lint.py [files...]` (defaults to `CLAUDE.md` `AGENTS.md`)
2. Fix all ERRORs (dead references); review WARNINGs (length, missing sections, weak wording, drift)
3. Semantic pass (script can't do these): removal test per line; every Hard Rule paired with its alternative; Decisions carry reasons; Principles ≤ 7 with rationale; enforceable rules paired with hook/CI or marked advisory
4. Report each finding as **keep / cut / move-to-wiki**

### prune
1. Apply the removal test line by line; list cuts as a proposed diff — never apply without user confirmation
2. Move-to-wiki candidates are exported via repo-wiki `ingest` (then replaced by a link), not deleted

### normalize
1. Map the current file onto the Reference Structure; propose reorder/merge/split of sections
2. Apply only after user confirmation; keep the file under 200 lines (hard cap 250)

## Validation Checklist
- [ ] `context_lint.py` exits 0
- [ ] File ≤ 200 lines and passes the removal test
- [ ] Required sections present; no forbidden sections
- [ ] No knowledge-type content inlined (links to `.wiki/` instead)
- [ ] Critical rules paired with hook/CI, or labeled advisory
- [ ] Single source of truth between CLAUDE.md and AGENTS.md
