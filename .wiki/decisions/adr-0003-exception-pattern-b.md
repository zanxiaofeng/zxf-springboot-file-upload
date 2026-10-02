---
type: decision
status: accepted
owner: davis
stale_after: 2027-10-02
sources:
  - .claude/rules/project-architecture.instructions.md
  - src/main/java/zxf/upload/infrastructure/domain/BusinessException.java
  - src/main/java/zxf/upload/infrastructure/domain/ErrorCode.java
---

# ADR-0003 异常体系选型：模式 B（BusinessException + ErrorCode 单体系）

全项目异常唯一出口为 `BusinessException` + `ErrorCode` 枚举（`infrastructure/domain/` 跨层技术支撑包），**无每条件异常类**，未引入模式 C 语义子类。静态工厂：`rejected(msg)` / `virusDetected(threat)` / `scanFailed(detail[, cause])`（最后者携带仅进日志的 detail）。

## 背景与理由

- 单体系避免异常类爆炸；语义差异用 `ErrorCode` 枚举 + 静态工厂表达，比模式 C（每语义一个子类）在本项目体量下成本更低。
- **消息分级**是安全要求：`getClientMessage()` 返回对外安全文案（null 回退 `ErrorCode` 默认文案），可回显给客户端；`getMessage()`（detail）只进日志不回显——防引擎内部信息（路径、规则名、堆栈）外泄。
- `GlobalExceptionHandler` 分级记日志：引擎故障 ERROR+堆栈，4xx WARN；404/405/方法级校验（400）有显式 handler。

## 影响与关联

- 新增异常场景 = 加 `ErrorCode` 枚举值 + 必要时加静态工厂，不建异常子类。
- `DocumentThreatScanner`（domain）依赖此异常包属跨层豁免，见 [[adr-0004-declared-pragmatic-deviations]]
