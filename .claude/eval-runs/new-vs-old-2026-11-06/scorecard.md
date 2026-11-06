# 新旧 skill 对照评测评分卡（new-vs-old-2026-11-06）

## 实验条件

| 项 | 值 |
|---|---|
| 用例 | smoke-async-upload（POST /api/files/async/upload + 独立轮询） |
| 源码 | 本仓库 HEAD 181c393（`cc0917c..HEAD` 对 src/、pom.xml、README.md 零 diff，等效基线固定版本） |
| 固定 prompt | 「请调查 POST /api/files/async/upload 异步上传及独立轮询的完整执行链路并产出调查报告」（两组一字不差） |
| A 组 | 旧版 skill（.claude/skills/investigate-endpoint/），全新上下文子代理 |
| B 组 | 新版 skill（.claude/skills/investigate-endpoint-new/），全新上下文子代理 |
| 防泄漏 | 两组对等禁读：A 禁 example.md（案例 C 与用例同源）、B 禁 example-async.md（同源）；双方均禁对方目录与全部 evals/（含 baseline） |
| 评分者 | 主会话（对照 baseline.md + 源码逐项核验） |

## 效能

| 指标 | A（旧版） | B（新版） | 差异 |
|---|---|---|---|
| subagent tokens | 156,141 | 156,830 | +0.4%（噪音内） |
| 工具调用 | 58 | 61 | +5% |
| 耗时 | 437s | 466s | +7% |
| 读取项目源码文件 | 48 | 47 | 持平 |
| 规范文件加载 | SKILL + formats + guide（3） | SKILL + guide + report-contract + protocol-schemas + example-distributed（5）+ 校验器运行 ×2 | — |
| 结构校验方式 | 模型自检清单（无工具） | validate_report.py PASS ×2 | — |

## 质量评分（新版 rubric：硬门禁 + 百分制）

| 维度 | A | B | 判读 |
|---|---:|---:|---|
| 硬门禁 6 项 | 全过 | 全过 | 平 |
| 事实准确 /25 | 24 | 25 | B 深度点更多且全部实证：熔断 minimumNumberOfCalls=5、OLE2 10 token 逐词、MIME 14 / 扩展名 15 双集合、max-request-size 110MB 双值、ERROR 文案来自 catch 分支考据。A 无错误，仅深度略浅 |
| 最低覆盖 /25 | 24 | 25 | B 全中 baseline 且多项超出（202 无 Location、sanitizeForLog、隔离双失败仍报 INFECTED 显式点破、getInputStream IO 归「业务处理」）。A 全中，缺 110MB 细节 |
| 数据契约与映射 /15 | 14 | 15 | B 的终态↔message 映射逐条考据（含文案真实来源）；A 的 threat/reason 来源标注同样清晰 |
| 失败与错误传播 /15 | 14 | 15 | B 错误表多两行显式（引擎故障「不在本响应出现」、后台异常收口）；A 枚举失败表更规整 |
| 证据可验证性 /10 | 10 | 10 | 平：主坐标 + 风险坐标均充分可验 |
| 表达与边界 /10 | 10 | 8 | **A 明显胜**：折叠/表格/短行/枚举词，主路径可扫读；B 长句嵌套密度高、Summary 注意 6 条偏重、O 卡处置分支无结构化失败表 |
| **总分** | **96** | **98** | 均远超 85 通过线 |

## 事实抽查记录（评分者对照源码）

- A 组：multipart 100MB/110MB、Semaphore(16)、TTL 30min、ZIP 四限（10000/100MB/1GB/100:1）、ClamAV/YARA 配置记法——全部与 application.yml / FileScanService 吻合。
- B 组：MIME_TO_PRIMARY_EXT 恰 14 项、CircuitBreaker COUNT_BASED(10)+minimumNumberOfCalls(5)+50%、OLE2 token 清单逐词一致（DocumentThreatScanner.java:38-39）、PDF /launch 与 /javascript+/openaction 并存逻辑、pom 依赖清单——全部吻合。
- 两组均无编造，坐标均有效。

## 结论

1. 成本持平：token 差 0.4%，质量差异不是用 token 买来的——新版「评测协议重 → 运行贵」的担忧对**执行侧**不成立（评测侧 with/without 对照仍然贵）。
2. 质量同档微差：B +2（赢在证据深度与覆盖细节），A 的失分均非错误而是深度略浅。
3. 表达是分水岭：A 的排版密度控制（折叠、枚举失败表、≤10 行主路径）扫读体验明显更好；B 的长句/无失败表是 report-contract「不为枚举整齐牺牲语义」的副作用——属可在新版骨架内回灌修复的排版层，不涉及其证据/门禁核心。
4. 结构性差异维持初判：B 有校验脚本与完整评测协议；A 有 7 轮回归实践与成熟密度规则。
