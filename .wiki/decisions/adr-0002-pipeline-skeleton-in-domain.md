---
type: decision
status: accepted
owner: davis
stale_after: 2027-10-02
sources:
  - .claude/rules/project-architecture.instructions.md
  - src/main/java/zxf/upload/domain/filescan/ScanStage.java
  - src/main/java/zxf/upload/application/filescan/ScanPipelineConfig.java
---

# ADR-0002 管道骨架入 domain，Stage 实现留 application

`domain/filescan/` 持有管道**骨架**（`ScanStage` 接口 + `ScanVerdict` sealed 类型 + `ScanContext` + `ScanPipeline`）——零 infra 依赖的纯领域机制；**Stage 实现**（依赖 ClamAV/YARA 引擎客户端与 `@ConfigurationProperties`）留 `application/filescan/stage/`；`ScanPipelineConfig`（@Configuration，在 application）完成骨架与实现的装配。

## 背景与理由

- 早期论证"管道依赖 infra 引擎客户端故不能入 domain"**只对 Stage 实现成立**；骨架本身不触碰任何 infra 类型，属领域机制，与 `ScanResult` 等模型同域放置。
- application 实现 domain 接口（`ScanStage`），依赖方向合法——这是六边形"用例实现领域端口"的正统形态。
- **顺序即领域规则**：阶段顺序（类型校验 → ClamAV → YARA → 文档威胁）显式写在 `ScanPipelineConfig`，不靠隐式扫描顺序。

## 影响与关联

- 新增阶段 = 新 `ScanStage` 实现 + `ScanPipelineConfig` 装配加一行（OCP），不改骨架。
- 总纲见 [[adr-0001-hexagonal-pipeline-cqrs]]；管道所处上下文全貌见 [[filescan-context]]
- Stage 实现读 properties 属已声明偏离，见 [[adr-0004-declared-pragmatic-deviations]]
