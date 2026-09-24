# CLAUDE.md

Spring Boot 4.1 文件上传安全扫描服务（Java 21 / Maven / 虚拟线程）。上传文件经「类型校验 → 病毒扫描 → YARA 规则 → 文档威胁检测」四阶段管道，检出威胁自动隔离。详见 README.md。

## 架构（六边形骨架 + 管道核心 + 受理边 CQRS）

```
domain/fileupload/    UploadFile 值对象、UploadPolicy 预检规则（FileUpload = 上游：文件模型/受理/保管）
domain/filescan/      FileScan = 下游：消费 staged 文件，产出结论与处置指令。管道骨架（ScanStage 接口 + ScanVerdict（sealed）+ ScanContext + ScanPipeline）
                      + model/（ScanResult/ScanStatus/TypeCheck）+ FileDisposition（处置映射，消费 ScanStatus）
                      + documentthreat/（DocumentThreat、ThreatKind、DocumentThreatScanner）
application/          ApplicationService 唯一门面（{domain}{Action} 命名，纯转发）
application/fileupload/   FileUpload 用例：UploadFileCommand + UploadFileCommandChecker + {Sync,Async}UploadCommandExecutor
application/filescan/     FileScan 用例与组件：ScanPipelineConfig（顺序即规则）+ stage/{FileType,ClamAv,Yara,DocumentThreat}ScanStage
                          + FileScanService（背压+处置）+ AsyncScanProcessor（异步执行/结果缓存）+ PollScanResultExecutor（轮询查询）
infrastructure/       filescan/（ClamAV/YARA 引擎客户端）、fileupload/（Staging/FileStorage）、config/（FileScanProperties、FileUploadDomainConfig）、
                      domain/（异常体系）、rest/（全局处理）、health/、io/
rest/fileupload/      FileUploadController（FileUpload 受理端点：sync/async 两个 upload 端点）
rest/filescan/        ScanResultController（轮询查询端点）
rest/fileupload/representation/  UploadResponse（record，受理回执模型；fileupload 拥有，filescan 查询端点消费）
```

- **架构总纲**：六边形架构（隔离边界）+ 管道（核心域执行结构）+ 受理边 CQRS（薄用例读写分离）；完整叙事/组件映射/偏离清单见 `.claude/rules/project-architecture.instructions.md`——与通用规范冲突时以该篇为准
- 写侧时序：Controller 构造 Command → Checker（委托 UploadPolicy）→ Executor 内 `toUploadFile()` 转换 → staging 落盘 → 扫描管道/异步受理 → 结果翻译（在 Executor）
- 管道骨架（ScanStage/Verdict/Context/Pipeline）在 domain——零 infra 依赖的领域机制；Stage 实现（依赖引擎/配置）在 application/filescan/stage/；新增阶段 = 新实现 + `ScanPipelineConfig` 装配加一行（OCP）
- **上下文依赖规则（单向）**：domain 内 `filescan → fileupload`（ScanContext 携带 UploadFile）；application 内 `fileupload → filescan`（受理 Executor 调 FileScanService/AsyncScanProcessor）——层不同方向相反，各层内部单向
- 领域规则位置：上传预检在 `UploadPolicy`；宏可宽限判定在 `ThreatKind.canBeFlagged()`；处置映射在 `FileDisposition.of()`（filescan 域）
- `UploadPolicy` 由 `FileUploadDomainConfig` `@Bean` 从 properties 提取纯值装配（domain 不依赖 infra）
- application 按限界上下文对称组织：`fileupload/` ↔ `domain/fileupload/`、`filescan/` ↔ `domain/filescan/`

## 异常体系（exception-handling §2.1/§3 模式 B/C）

- 唯一出口：`BusinessException` + `ErrorCode` 枚举（`infrastructure/domain/`），无每条件异常类
- `BusinessException.rejected(msg)` / `virusDetected(threat)` / `scanFailed(detail[, cause])` 静态工厂
- 消息分级：`getClientMessage()` 对外安全文案（null 回退 ErrorCode 默认文案）；`getMessage()`（detail）只进日志不回显
- `GlobalExceptionHandler`：引擎故障 ERROR+堆栈，4xx WARN；404/405/方法级校验（400）有显式 handler

## 编码规范

`.claude/rules/`（19 个文件）按 `paths` glob 自动注入，面向 Java 21 + Spring Boot 4.1。

## 常用命令

- 编译：`mvn compile -q`
- 测试：`mvn clean test -q`（约 30s，58 个测试；**增量编译会被 IDE 的 ECJ 占位 class 污染，验证一律 clean**）

## Hooks（`.claude/settings.json`）

- `SessionStart`：输出 Java / Maven 版本（PATH 默认 JDK 可能是 22，项目要求 21）
- `PreToolUse`（`^Bash$`）：拦截 `rm -rf` 及变体，命中 exit 2 阻断
- `PostToolUse`（`^(Edit|Write)$`）：自动 `mvn compile -q`
- `Stop`：停止前自动 `mvn test -q`，失败以 `{"decision":"block"}` 阻止停止（连续 8 次后强制放行）

## 子代理 / 技能 / 命令

- `.claude/agents/`：`code-reviewer`、`security-auditor`、`tdd-guide`、`build-error-resolver`
- `.claude/skills/`：`add-endpoint`、`implement-feature`、`refactor-module`
- `.claude/commands/`：`/code-review`、`/security-audit`（`context: fork` 路由到对应子代理）
