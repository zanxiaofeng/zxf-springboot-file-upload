# skills-archive

已退役技能的归档目录。此处内容**不参与** skill 触发扫描（Claude Code 只扫描 `.claude/skills/`），仅供追溯查阅，不再维护。

## investigate-endpoint（归档于 2026-11-06）

「API 执行链路调查报告」技能的第一代实现（密度控制学派：硬配额 + 机械自检 + 枚举失败表）。

**退役原因**：与重写版 `investigate-endpoint-new` 并存竞争触发。2026-11-06 新旧对照评测（本仓库异步上传用例，评分卡见仓库 `.claude/eval-runs/new-vs-old-2026-11-06/scorecard.md`）：旧版 96 分（表达 10/10）、新版 98 分（证据深度更深），token 成本持平。用户选定合并路线——新版为底，旧版三件排版利器（两列枚举失败表、折叠区规范、关键事实短句约束）已回灌至新版 `references/report-contract.md`，随后 heldout 验证 94 分（运行记录：新版 `evals/runs/run-2026-11-06-merge.md`）。

**未搬入新版的独特资产**（查阅本归档的主要原因）：

- `references/example.md` — 六类不合格反例（散文化 / 事实编造 / 空壳填表 / 证据泛滥 / 数据盲区 / 主线迷失），新版无对应内容
- `references/formats.md` — 12 类 Outbound 类型专属 schema 细节与「会猜错的无」速查表，比新版 `protocol-schemas.md` 更细
- `evals/evals.md` §五基线记录表 — 7 轮评审/回归的完整演化史与豁免归因实践
