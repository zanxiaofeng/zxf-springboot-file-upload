# 本项目架构落地形态（zxf-springboot-file-upload）

> **定位：** 本篇是本项目架构的**唯一权威叙事**。整体架构 = **六边形架构（Ports & Adapters）为骨架 + 扫描管道（Pipe-Filter）为核心域模式 + 受理边 CQRS（用例读写分离）**。通用规范 `architecture.instructions.md`（CQRS-lite）仅适用于受理边的用例组织；核心域由管道模式承担。冲突时以本篇为准。

***

## 1. 架构总纲：三段各管一层

| 模式 | 管什么 | 落点 |
|------|--------|------|
| **六边形架构** | 隔离边界：核心（领域+用例）与外部技术（HTTP/引擎/存储）通过端口对接 | 层间依赖方向 + Controller=主适配器 + 引擎客户端=次适配器 |
| **Pipe-Filter** | 核心域执行结构：扫描流水线的阶段顺序与短路 | 骨架在 `domain/filescan/`，装配与实现在 `application/filescan/`（§3） |
| **受理边 CQRS** | 入口用例的读写分离：写（受理上传）/ 读（轮询结果） | `application/fileupload`（写）+ `application/filescan`（读）（§4） |

**为什么不是纯 CQRS-lite 项目**：CQRS 的 Command/Checker/Executor 形态面向 CRUD 用例（每用例一段业务编排）；本项目的核心域是"一条扫描流水线"——阶段顺序、短路、上下文传递是领域规则，不是任何单个用例的编排。因此受理边保留 CQRS 用例分离（它确实是"类 CRUD 的薄层"），核心域交给管道模式。

### 上下文关系与依赖规则（单向）

**FileUpload = 上游**（定义文件模型、提供受理暂存与文件保管）；**FileScan = 下游**（消费 staged 文件，产出扫描结论与处置指令）。

| 层 | 依赖方向 | 体现 |
|----|---------|------|
| domain | `filescan → fileupload` | `ScanContext` 携带 `UploadFile`；管道消费上游产物 |
| application | `fileupload → filescan` | 受理 Executor（上传流程主导）调用 `FileScanService`/`AsyncScanProcessor` |
| infrastructure | `filescan → fileupload` | `FileScanService` 处置时委托 `FileStorageService`（文件保管属 upload 设施） |

层不同则方向相反是正常形态（领域模型依赖 ≠ 应用流程依赖）；**每层内部严格单向**，禁止出现 `fileupload → filescan` 的 domain import 或 `filescan → fileupload` 的 application import。

### 六边形组件映射

| 六边形组件 | 本项目对应 |
|-----------|-----------|
| 核心·领域模型 | `domain/`（两个限界上下文：**fileupload** / **filescan**） |
| 核心·领域规则 | `UploadPolicy`、`ThreatKind.canBeFlagged()`、`FileDisposition`、管道阶段顺序 |
| 核心·用例 | 受理 Executor ×2、`PollScanResultExecutor`、`FileScanService`（背压+处置） |
| 端口 | `ApplicationService` 门面方法（应用服务端口）、`ScanStage` 接口（多态端口） |
| 主适配器（驱动侧） | `rest/fileupload/`（受理端点）+ `rest/filescan/`（查询端点）Controller |
| 次适配器（被驱动侧） | `infrastructure/filescan/`（ClamAV/YARA 引擎客户端）、`fileupload/`（Staging/Storage）、`config/` |

## 2. 分层与包结构（实际形态）

```
zxf.upload
├── domain/                     # 核心：两个限界上下文
│   ├── fileupload/                 # FileUpload 域：UploadFile / UploadPolicy
│   └── filescan/                   # FileScan 域：管道骨架（ScanStage/ScanVerdict/ScanContext/ScanPipeline）
│       ├── model/                      # ScanResult / ScanStatus / TypeCheck
│       ├── FileDisposition.java        # 处置映射（ScanStatus → 文件去向）
│       └── documentthreat/             # 子域：DocumentThreat / ThreatKind / DocumentThreatScanner
├── application/
│   ├── ApplicationService.java     # 应用服务端口（六边形正统组件）：纯转发
│   ├── fileupload/                 # FileUpload 写用例（平铺，无 command/checker/executor 机制目录）：
│   │                               #   UploadFileCommand + UploadFileCommandChecker + {Sync,Async}UploadCommandExecutor
│   └── filescan/                   # FileScan 用例与组件：ScanPipelineConfig（装配，顺序即规则）
│                                   #   + stage/×4 + FileScanService + AsyncScanProcessor + PollScanResultExecutor
├── infrastructure/                 # 次适配器：filescan/（ClamAV/YARA 引擎客户端）fileupload/（Staging/Storage）
│                                   #   config/（Properties + 领域组件 @Bean 装配）domain/（异常体系）rest/（全局处理）health/ io/
└── rest/
    ├── fileupload/                 # 主适配器：受理端点 Controller（Sync/Async）
    │   └── representation/             # UploadResponse（受理回执模型，受理回执与轮询 body 共用）
    └── filescan/                   # 主适配器：查询端点 Controller（轮询，消费 fileupload 的 UploadResponse）
```

## 3. 扫描管道（核心域模式）

| 类型 | 职责 |
|------|------|
| `ScanStage` | 过滤器接口（4 个实现的真实多态，符合 ISP） |
| `ScanVerdict` | sealed 代数数据类型：Passed / Infected / Rejected / Flagged（宏打标放行） |
| `ScanContext` | 管道上下文（staged 文件 + 阶段间传递的 detectedMime） |
| `ScanPipeline` | 按序执行，首个非 Passed 结论短路；总开关 false 直通 |
| `stage/*` | 阶段 1 类型校验+ZIP 防护、阶段 2 ClamAV、阶段 3 YARA、阶段 4 文档威胁+宏策略分级 |
| `ScanPipelineConfig` | 阶段顺序显式装配——顺序即领域规则，新增阶段 = 新实现 + 装配加一行（OCP） |
| `FileScanService` | 背压闸门（Semaphore）+ 调管道 + 按 `FileDisposition` 执行文件生命周期 |
| `AsyncScanProcessor` | 异步扫描执行器（@Async 虚拟线程，selection-guide §9.4）+ 结果缓存，供轮询读取（支撑组件，非 Stage） |

**归属判定记录**（骨架入 domain、实现留 application）：早期论证"管道依赖 infra 引擎客户端故不能入 domain"只对 **Stage 实现**成立；**骨架**（ScanStage/ScanVerdict/ScanContext/ScanPipeline）是零 infra 依赖的纯领域机制，故定义于 `domain/filescan/`（与 ScanResult 等模型同域）。Stage 实现依赖引擎/properties，留 `application/filescan/stage/`（application 实现 domain 接口，依赖方向合法）；`ScanPipelineConfig`（@Configuration）在 application 完成骨架与实现的装配。新增阶段 = 新 Stage 实现 + 装配加一行。

## 4. 受理边 CQRS 落地要点

- 门面：`fileSyncUpload` / `fileAsyncUpload` / `fileScanStatus`，纯转发无逻辑（异步 = 提交 202 + 轮询查询 + 后台执行器三件套，selection-guide §9.1）
- 写侧时序：Controller `commandOf()` 构造 Command → Checker（委托 domain `UploadPolicy`，规则唯一来源）→ Executor 内 `toUploadFile()` 转换 → staging 落盘 → 管道/受理 → 结果翻译（在 Executor）
- 读侧：`PollScanResultExecutor.execute(scanId)`（轮询兜底；单参数用例不再包 Query record）
- 无事务注解：本项目无数据源，扫描管道非事务性

## 5. 异常选型（exception-handling §2.1）

本项目选 **模式 B**：`BusinessException` + `ErrorCode` 单体系，静态工厂 `rejected/virusDetected/scanFailed`（最后者携带仅进日志的 detail）；未引入模式 C 语义子类。消息分级：`getClientMessage()` 对外安全文案（null 回退 ErrorCode 默认文案），`getMessage()`（detail）只进日志。

## 6. 已声明的务实偏离（评审时勿当 bug 修）

1. `PollScanResultExecutor` 与门面 `fileScanStatus` 返回 rest 层 `UploadResponse`——异步受理回执与轮询 body 的统一响应模型
2. `AsyncScanProcessor` 依赖 rest 类型（偏离 1 的连锁：缓存 UploadResponse）
3. `FileTypeScanStage` 在 application 层读 `@ConfigurationProperties`——domain 禁依赖 infra，故类型校验不入 domain
4. `UploadFileCommand` 携带 `InputStream content`——multipart 无 @RequestBody，流是写操作的必要输入；领域对象 `UploadFile` 不含流
5. `DocumentThreatScanner`（domain）依赖 POI（重量级第三方库）、Lombok `@Slf4j` 与 `infrastructure.domain.BusinessException`——POI 按指南严格口径应抽端口+适配器，但项目反模式 #12 禁预抽接口，两规范冲突时声明为务实偏离；BusinessException 是 `infrastructure/domain` 跨层技术支撑包的全项目异常唯一出口（architecture §3.5「供全项目跨层使用」），属 §1 依赖规则表 Domain 行的已声明豁免而非技术实现依赖（2026-09-24 决策：补声明，不迁移异常包、不加翻译层）。domain 保持**零 Spring import**（2026-09-23 已达成：UploadFile 改 JDK 原生、本类去 `@Component` 改 `FileScanDomainConfig` @Bean 装配），仅第三方领域库、编译期工具与跨层异常体系豁免

## 7. 领域规则位置速查

| 规则 | 位置 |
|------|------|
| 空文件/大小/扩展名预检、同步路由阈值 | `domain/fileupload/UploadPolicy` |
| 处置映射（CLEAN→入库、INFECTED→隔离、其余→清理） | `domain/filescan/FileDisposition.of()` |
| 宏可宽限判定 | `domain/filescan/documentthreat/ThreatKind.canBeFlagged()` |
| 文档格式判定 | `domain/filescan/documentthreat/DocumentThreatScanner.isDocumentFormat()` |
| 阶段顺序 / 短路 / 总开关 | `application/filescan/ScanPipeline`（顺序在 `ScanPipelineConfig`） |
| fail-open 只对引擎故障降级 | `application/filescan/FileScanService.handleEngineFailure`（SCAN_ENGINE_ERROR 守卫） |
