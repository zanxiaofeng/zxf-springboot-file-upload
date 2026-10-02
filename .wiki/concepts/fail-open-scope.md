---
type: concept
status: accepted
owner: davis
stale_after: 2027-01-02
sources:
  - src/main/java/zxf/upload/application/filescan/FileScanService.java
  - src/main/java/zxf/upload/infrastructure/domain/ErrorCode.java
---

# fail-open 边界：只对引擎故障降级，检出结果绝不放行

降级（fail-open）语义只适用于**引擎基础设施故障**（ClamAV/YARA 不可达、超时等），由 `FileScanService.handleEngineFailure` 中的 `SCAN_ENGINE_ERROR` 守卫限定；任何**实际检出**（Infected/Rejected/Flagged）走正常结论流，不存在"检出后降级放行"路径。

## 陷阱

- 修改 `handleEngineFailure` 时不得把守卫条件放宽到其他 `ErrorCode`——放宽等于"引擎报毒也放行"，直接击穿本服务存在的意义。
- fail-open 放行的是"扫不了"（引擎故障），不是"扫出问题"；两者在日志与响应上的表现不同：前者走 `scanFailed(detail)`，detail 只进日志（见 [[adr-0003-exception-pattern-b]] 消息分级）。
- 背压闸门（Semaphore）与 fail-open 无关，勿混在同一开关上调整。

## 影响与关联

- 结论四态与处置见 [[scan-verdict-and-disposition]]；所处上下文见 [[filescan-context]]
