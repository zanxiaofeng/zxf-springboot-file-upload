---
name: investigate-endpoint
description: Investigate an inbound operation from source code and produce an evidence-backed execution-path report covering contracts, direct interactions, data mapping, control flow, and error propagation. Use for HTTP/gRPC endpoints, MQ consumers, scheduled jobs, CLI commands, or event handlers when asked to trace or investigate the full flow（调查/梳理接口、调用链、请求处理流程）. Not for isolated function explanations or general code reading that needs no flow tracing.
---

# Investigate Endpoint

## 目标

从源码静态调查一个入站操作，产出「结论优先、证据可验、数据流清晰」的报告。默认覆盖本服务直接发出的一跳，不展开下游内部；进程内重要操作单独标注，不混入外部依赖。

## 不可妥协的质量红线

- 不编造路径、字段、SQL、配置、默认值、错误映射或源码坐标。
- 不泄露凭证、敏感配置值或真实个人数据；动态值使用占位符。
- 完成最低覆盖后再成稿：入口与前置限制、主成功路径、直接交互、关键数据映射、失败处置与错误翻译。
- 关键协议事实和高风险结论必须有直接证据；派生结论与未解析项必须标明。
- 静态调查不得伪称运行时验证；源码不足时缩小结论范围，而不是补全想象。
- 摘要独立可读：无需展开细节即可理解功能、主链路、外部依赖和主要风险。

## 工作流程

### 1. 预检任务

确认源码根、目标入口、调查目的和可用版本。记录仓库 commit 或等价版本；存在多个匹配入口时先消歧。缺少关键源码且无法可靠推断时，询问用户或明确限制范围。

### 2. 建立证据台账

阅读 `references/investigation-guide.md`，定位入口并维护结构化台账：入口、调用关系、交互候选、协议证据、数据映射、错误翻译、未解析项。调查期间可以写台账，但不要提前撰写最终报告正文。

### 3. 追踪主链路和直接交互

从 handler 沿直接引用链到进程边界，识别条件、循环、并行、异步、短路、超时、重试、熔断和背压。横切机制不是默认深潜方向，但鉴权、入站限制、异常翻译及已证实影响本链路的行为属于最低覆盖，不得跳过。

### 4. 实证契约与数据流

按实际交互类型读取 `references/protocol-schemas.md` 的相关小节。提取真实 Request／Response／Message／Statement／Command；追踪关键字段的来源、转换和消费去向。配置默认值与依赖行为按证据等级记录。

### 5. 做覆盖门禁

逐项确认：每个相关 I 卡有入口与响应；每个直接交互有归属；关键字段能追踪；失败能追到翻译层；并行或条件分支没有被迫编成单一顺序；所有未解析项已标注或已通过补充调查解决。

### 6. 成稿并验证

阅读 `references/report-contract.md`，按骨架生成报告。只参考一个最相似的范例，不要通读全部范例。环境允许时运行：

```bash
python scripts/validate_report.py report.md
```

自动检查通过后，再人工复核事实、覆盖和不确定性；校验器不能替代源码实证。

## 调查模式

默认模式平衡主链路、契约和错误行为。用户明示安全或依赖审查时，将鉴权、隐式出站、敏感数据、凭证来源、信任边界和依赖版本提升为主线，但仍保持本服务直接一跳的报告边界。

## 参考资源

- `references/investigation-guide.md`：预检、入口定位、证据台账、证据等级、覆盖门禁和安全边界。
- `references/report-contract.md`：报告骨架、卡片语义、数据与错误映射、证据和不确定性规则。
- `references/protocol-schemas.md`：各 Inbound／Outbound 类型的按需调查菜单。
- `references/example-crud.md`：单体 CRUD 简例。
- `references/example-distributed.md`：分布式、多调用与控制流范例。
- `references/example-async.md`：异步受理、独立轮询入口和进程内操作范例。
- `scripts/validate_report.py`：报告结构与编号静态校验器。
- `evals/evals.md`：修改本 Skill 后的触发、质量、效能和回归评测协议。
