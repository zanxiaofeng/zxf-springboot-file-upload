---
name: repo-wiki
description: Initialize, update, query, and lint the project's .wiki/ knowledge base (repo-level LLM wiki for architecture decisions, domain concepts, and module guides).
when_to_use: Use when asked to init/ingest/query/lint the repo wiki, after changes that affect architecture decisions or domain concepts, or before answering architecture/domain questions.
arguments: [action]
allowed-tools:
  - Read
  - Write
  - Edit
  - Grep
  - Glob
  - Bash(python3 .wiki/bin/wiki_lint.py *)
disallowed-tools:
  - Bash(rm -rf *)
  - Bash(git push *)
---

# Repo Wiki

Maintain a persistent, interlinked markdown knowledge base under `.wiki/` — the compiled knowledge layer that agents read before reasoning about the domain, and update incrementally after code changes. Only knowledge **not derivable from code** belongs here (decision rationale, domain semantics, known traps).

## Pre-conditions
- [ ] `$ARGUMENTS` is one of: `init` / `ingest` / `query` / `lint`
- [ ] `.wiki/` exists (run `init` first if not)

## Wiki Layout

```
.wiki/
├── _template.md        # Page template — copy for every new page
├── index.md            # Content directory: one line per page, grouped by section
├── log.md              # Chronological log: one entry per ingest
├── concepts/           # Domain concepts (one concept per page)
├── modules/            # Module guides (one module per page)
├── decisions/          # Architecture decisions (ADR)
├── systems/            # Downstream dependencies & contracts
├── playbooks/          # Troubleshooting playbooks (flaky tests, env quirks)
└── bin/wiki_lint.py    # Mechanical lint script (copied here by init)
```

Start with `decisions/` + `concepts/` + `modules/`; add `systems/` and `playbooks/` when the need arises.

## Operations

### init
1. Create the layout above (empty section dirs, `index.md`, `log.md`, `_template.md` with the frontmatter below)
2. Copy the lint script bundled with this skill (`scripts/wiki_lint.py`, next to this SKILL.md) to `.wiki/bin/wiki_lint.py`
3. Confirm `.wiki/` is NOT matched by any gitignore rule — the wiki must be committed with code changes

### ingest (after a code change)
1. Read the change; identify affected pages via `index.md`
2. Update affected pages **incrementally** — never regenerate the whole wiki
3. Add new pages only for knowledge not derivable from code
4. Update `index.md` (one-line entry per new page) and append to `log.md`: `## [YYYY-MM-DD] <event>` + list of changed pages
5. Run lint; fix all errors before finishing

### query (before answering architecture/domain questions)
1. Read `index.md` first; navigate via `[[wikilinks]]`
2. Answer with page citations; treat the wiki as navigation, not authority — verify critical conclusions against source code
3. Archive genuinely valuable answers back as pages (then re-run ingest steps 4–5)

### lint
1. Run `python3 .wiki/bin/wiki_lint.py .wiki` from the repo root
2. Fix all ERRORs (broken wikilinks, missing/invalid frontmatter, naming violations)
3. Report WARNINGs (orphan pages, stale pages, unlisted pages) — never auto-delete

## Page Conventions

- File names: kebab-case ASCII (`order-refund.md`); page title = file name; one topic per page
- ADR: `decisions/adr-0001-{slug}.md`; sequence numbers are never reused
- Module pages mirror the code module name; rename pages in the same PR as the code
- Never encode status in file names (`-old`/`-v2`/`-deprecated` forbidden) — status lives in frontmatter
- Required frontmatter fields: `type`, `status`, `owner`, `stale_after`; `sources` lists raw sources (code paths or URLs)

```markdown
---
type: concept | module | decision | system | playbook
status: draft | accepted | deprecated
owner: {github-handle}
stale_after: {YYYY-MM-DD}
sources:
  - {code path or URL}
---
```

- Body: ≤5-line summary first (reusable by `index.md`), then details with `[[wikilinks]]`

## Validation Checklist
- [ ] Every page has valid frontmatter with required fields
- [ ] File names follow kebab-case; ADR numbering is sequential
- [ ] `index.md` lists every page; `log.md` has an entry for this change
- [ ] `python3 .wiki/bin/wiki_lint.py .wiki` exits 0
- [ ] Wiki changes ship in the same PR as the code change
