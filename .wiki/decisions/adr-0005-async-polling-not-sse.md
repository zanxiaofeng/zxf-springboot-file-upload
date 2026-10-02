---
type: decision
status: accepted
owner: davis
stale_after: 2027-10-02
sources:
  - src/main/java/zxf/upload/rest/filescan/ScanResultController.java
  - src/main/java/zxf/upload/application/filescan/AsyncScanProcessor.java
  - src/main/java/zxf/upload/application/filescan/PollScanResultExecutor.java
---

# ADR-0005 异步结果查询收敛为纯轮询（删除 SSE）

2026-09-24 决策：删除 SSE 推送通道，异步扫描结果查询收敛为**纯轮询**模型（决策发生于当日规范重构会话，git log 无细粒度记录）。现行机制为受理边三件套：**202 受理回执 + GET 轮询 + 后台执行器**（选型指南 §9.1 形态）。

## 现行机制

- 异步受理：Controller 收 multipart → `AsyncUploadCommandExecutor` 提交 `AsyncScanProcessor`（@Async 虚拟线程）→ 立即回 202 + scanId。
- 轮询：`ScanResultController` → 门面 `fileScanStatus` → `PollScanResultExecutor.execute(scanId)` 读取 `AsyncScanProcessor` 的结果缓存。
- 统一响应模型：轮询 body 与受理回执共用 `UploadResponse`（见 [[adr-0004-declared-pragmatic-deviations]] 偏离 1/2）。
- 读侧单参数用例不包 Query record；无事务注解（项目无数据源，管道非事务性）。

## 影响与关联

- 客户端契约只有一个 GET 轮询端点；新增推送类需求须先推翻本决策。
- 执行与缓存细节见 [[filescan-context]]；总纲见 [[adr-0001-hexagonal-pipeline-cqrs]]
