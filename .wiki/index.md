# Wiki Index

内容目录：每页一行，按 section 分组；ingest 新页面时同步更新本文件。

## Concepts

- [上下文依赖方向](concepts/bounded-context-dependency-direction.md) — FileUpload 上游/FileScan 下游，层内单向、层间反向，硬禁令速查
- [扫描结论四态与处置映射](concepts/scan-verdict-and-disposition.md) — Passed/Infected/Rejected/Flagged、首个非 Passed 短路、Flagged=宏打标放行
- [fail-open 边界](concepts/fail-open-scope.md) — 只对引擎故障降级（SCAN_ENGINE_ERROR 守卫），检出绝不放行

## Modules

- [FileUpload 限界上下文](modules/fileupload-context.md) — 上游：文件模型/预检/受理写侧时序/staging/storage 修改注意
- [FileScan 限界上下文](modules/filescan-context.md) — 下游：管道骨架+四阶段+背压+异步轮询缓存，新增 Stage 流程

## Decisions

- [ADR-0001 架构总纲](decisions/adr-0001-hexagonal-pipeline-cqrs.md) — 六边形骨架+管道核心+受理边 CQRS，为什么不是纯 CQRS-lite
- [ADR-0002 管道骨架归属](decisions/adr-0002-pipeline-skeleton-in-domain.md) — 骨架入 domain、Stage 实现留 application 的判定
- [ADR-0003 异常体系模式 B](decisions/adr-0003-exception-pattern-b.md) — BusinessException+ErrorCode 单体系与消息分级
- [ADR-0004 务实偏离清单](decisions/adr-0004-declared-pragmatic-deviations.md) — 5 条已声明偏离，评审时勿当 bug 修
- [ADR-0005 纯轮询收敛](decisions/adr-0005-async-polling-not-sse.md) — 2026-09-24 删 SSE，202+轮询+后台执行器三件套

## Systems

## Playbooks
