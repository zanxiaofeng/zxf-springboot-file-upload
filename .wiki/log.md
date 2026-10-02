# Wiki Log

时间线日志：每次 ingest 追加一节 `## [YYYY-MM-DD] <event>` 并列出变更页面。

## [2026-10-02] init

- 初始化 `.wiki/` 结构：`index.md` / `log.md` / `_template.md` + 空 section 目录（concepts / modules / decisions / systems / playbooks）
- 复制 lint 脚本至 `bin/wiki_lint.py`
- 确认 `.wiki/` 不被任何 gitignore 规则匹配，可随代码提交

## [2026-10-02] 首批 ingest（bootstrap）

来源：project-architecture.instructions.md 决策叙事 + docs/ 选型指南 + 2026-09 规范重构决策存档。新增 10 页：

- decisions/adr-0001-hexagonal-pipeline-cqrs.md
- decisions/adr-0002-pipeline-skeleton-in-domain.md
- decisions/adr-0003-exception-pattern-b.md
- decisions/adr-0004-declared-pragmatic-deviations.md
- decisions/adr-0005-async-polling-not-sse.md
- concepts/bounded-context-dependency-direction.md
- concepts/scan-verdict-and-disposition.md
- concepts/fail-open-scope.md
- modules/fileupload-context.md
- modules/filescan-context.md

index.md 同步登记全部页面。
