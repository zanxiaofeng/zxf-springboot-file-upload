---
type: concept
status: accepted
owner: davis
stale_after: 2027-01-02
sources:
  - src/main/java/zxf/upload/domain/filescan/ScanVerdict.java
  - src/main/java/zxf/upload/domain/filescan/FileDisposition.java
  - src/main/java/zxf/upload/domain/filescan/documentthreat/ThreatKind.java
---

# 扫描结论四态与处置映射

`ScanVerdict`（sealed 代数数据类型）四态：**Passed / Infected / Rejected / Flagged**。`ScanPipeline` 按序执行 Stage，**首个非 Passed 结论短路**整条管道。`FileDisposition.of()` 把 `ScanStatus` 映射为文件生命周期：**CLEAN→入库、INFECTED→隔离、其余→清理**。

## 领域语义（代码之外的部分）

- **Flagged = 宏打标放行**：文档含宏但不属必须拒绝的威胁类别时，打标放行而非隔离/清理——宏是否可宽限由 `ThreatKind.canBeFlagged()` 判定（领域规则唯一来源）。这是"检出 ≠ 拒绝"的安全分级：宽限宏、拒绝恶意载荷。
- **短路即规则**：类型校验（阶段 1）不过就不消耗 ClamAV/YARA 引擎算力；顺序与短路属领域规则，显式装配于 `ScanPipelineConfig`（见 [[adr-0002-pipeline-skeleton-in-domain]]）。
- 处置映射落在 filescan 域（结论的所有者决定文件去向），但实际搬运文件委托 `FileStorageService`（upload 设施，方向见 [[bounded-context-dependency-direction]]）。

## 影响与关联

- 引擎故障时的降级边界（fail-open 范围）见 [[fail-open-scope]]；总纲见 [[adr-0001-hexagonal-pipeline-cqrs]]
