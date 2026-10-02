---
type: module
status: accepted
owner: davis
stale_after: 2027-01-02
sources:
  - src/main/java/zxf/upload/domain/filescan/
  - src/main/java/zxf/upload/application/filescan/
  - src/main/java/zxf/upload/infrastructure/filescan/
---

# FileScan 限界上下文（下游）

**职责**：消费 [[fileupload-context]] 的 staged 文件，经四阶段管道（类型校验+ZIP 防护 → ClamAV → YARA → 文档威胁+宏策略分级）产出结论与处置指令。**角色 = 下游**。

## 组成

- domain：管道骨架（`ScanStage`/`ScanVerdict`/`ScanContext`/`ScanPipeline`，归属论证见 [[adr-0002-pipeline-skeleton-in-domain]]）+ `model/`（ScanResult/ScanStatus/TypeCheck）+ `FileDisposition` + `documentthreat/` 子域（`ThreatKind.canBeFlagged()` 宏宽限判定）。
- application：`stage/` ×4 + `ScanPipelineConfig`（顺序即规则）+ `FileScanService`（背压 Semaphore + 调管道 + 执行处置）+ `AsyncScanProcessor`（@Async 虚拟线程执行 + 结果缓存，供轮询）+ `PollScanResultExecutor`（读侧）。
- infrastructure：`ClamAvScanner` / `YaraScanner` 引擎客户端 + `ClamAvHealthIndicator`。

## 修改注意

- **新增扫描阶段**：新 `ScanStage` 实现 + `ScanPipelineConfig` 装配加一行；不改骨架、不加 if。
- 结论语义与处置见 [[scan-verdict-and-disposition]]；引擎故障降级边界见 [[fail-open-scope]]；异步轮询契约见 [[adr-0005-async-polling-not-sse]]。
- application 层此上下文**不得 import fileupload**（方向禁令见 [[bounded-context-dependency-direction]]）。
