---
type: decision
status: accepted
owner: davis
stale_after: 2027-10-02
sources:
  - .claude/rules/project-architecture.instructions.md
  - docs/springboot-architecture-selection-guide.md
  - src/main/java/zxf/upload/application/ApplicationService.java
---

# ADR-0001 架构总纲：六边形骨架 + 管道核心 + 受理边 CQRS

整体架构由三段模式各管一层：**六边形架构**管隔离边界（核心↔外部技术通过端口对接）、**Pipe-Filter 管道**管核心域执行结构（扫描流水线的阶段顺序与短路）、**受理边 CQRS** 管入口用例读写分离。2026-09-23 按架构选型指南推演得出 C+管道+受理边 CQRS 组合，与现状一致。规范正文以 project-architecture.instructions.md 为唯一权威叙事。

## 背景与理由

- **为什么不是纯 CQRS-lite**：CQRS 的 Command/Checker/Executor 形态面向 CRUD 用例（每用例一段业务编排）；本项目核心域是"一条扫描流水线"——阶段顺序、短路、上下文传递是**领域规则**，不属于任何单个用例的编排。故受理边保留 CQRS 用例分离（它确实是"类 CRUD 的薄层"），核心域交给管道模式。
- **六边形组件映射**：领域模型/规则 = `domain/`；用例 = 受理 Executor ×2 + `PollScanResultExecutor` + `FileScanService`；端口 = `ApplicationService` 门面方法 + `ScanStage` 多态接口；主适配器 = rest Controller；次适配器 = `infrastructure/`（引擎客户端、Staging/Storage、config）。

## 影响与关联

- 管道骨架的层归属判定见 [[adr-0002-pipeline-skeleton-in-domain]]
- 上下文间依赖方向规则（层内单向、层间方向相反）见 [[bounded-context-dependency-direction]]
- 管道四态结论的领域语义见 [[scan-verdict-and-disposition]]
