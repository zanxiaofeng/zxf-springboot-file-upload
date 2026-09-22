# zxf-springboot-file-upload

基于 Spring Boot 4 的文件上传安全扫描服务。文件上传前经过「类型校验 → 病毒扫描 → 规则匹配 → 文档威胁检测」四阶段扫描管道，检出威胁自动隔离，全程基于虚拟线程实现高并发低开销。

## 功能特性

- **四阶段扫描管道**：Tika 类型校验、ClamAV 病毒扫描、YARA 规则匹配、文档威胁检测（VBA 宏 / ActiveX / PDF 危险动作）
- **同步 / 异步双端点**：`POST /api/files/sync/upload` 同步等待扫描结果（默认 >20MB 拒绝）；`POST /api/files/async/upload` 异步受理，支持 SSE 实时推送 + 轮询兜底
- **ZIP 炸弹防护**：条目数、单条目解压大小、累计解压总量、压缩比四重流式校验（OWASP 解压炸弹防护）
- **背压控制**：信号量统一闸门（默认 16 并发），同步与异步共用，防止并发上传打爆扫描引擎
- **故障熔断**：Resilience4j 熔断 ClamAV 故障，防止引擎挂起级联拖垮服务；支持 fail-closed（默认，拒绝上传）/ fail-open（放行打标）两种策略
- **文件生命周期治理**：暂存（staging）→ 入库 / 隔离 / 删除，各路径临时文件无泄漏
- **健康检查**：`/actuator/health` 集成 ClamAV PING 探测

## 技术栈

| 类别 | 组件 |
|------|------|
| 框架 | Spring Boot 4.1.0（Web / Validation / Actuator）、Java 21、虚拟线程 |
| 类型检测 | Apache Tika 3.3.2 |
| 病毒扫描 | ClamAV 1.4.5（Docker）+ clamav-client 2.1.2（INSTREAM 协议） |
| 规则匹配 | YARA（外部二进制）+ 自定义规则 `rules/malware.yar` |
| 文档解析 | Apache POI 5.5.1（OLE2 宏检测） |
| ZIP 处理 | commons-compress 1.28.0 |
| 并发 / 弹性 | Caffeine（结果缓存 TTL 淘汰）、Resilience4j CircuitBreaker |
| 测试 | spring-boot-starter-test、Testcontainers 2.0.4（真实 ClamAV 端到端） |
| 其他 | Lombok |

## 扫描管道

```
上传文件
  │  StagingService 同步落盘（staging 目录）
  ▼
┌─ 扫描管道（Semaphore 背压，默认 16 并发）──────────────────┐
│ ① 类型校验   Tika MIME 探测 → 白名单 + 扩展名一致性校验      │
│              （zip 附加：条目数 / 解压大小 / 压缩比防炸弹）    │
│ ② ClamAV     INSTREAM 病毒扫描（超时快速失败 + 熔断）        │
│ ③ YARA       自定义恶意软件规则匹配                          │
│ ④ 文档威胁   按检测到的 MIME 路由：VBA 宏 / ActiveX /        │
│              PDF 危险动作（宏策略支持 BLOCK / FLAG 分级）      │
└────────────────────────────────────────────────────┘
  ├─ CLEAN            → FileStorageService 入库，删除 staging 文件
  ├─ INFECTED         → 移入隔离区（quarantine）
  └─ REJECTED / ERROR → 删除 staging 文件
```

## 快速开始

### 前置条件

- JDK 21
- Maven 3.9+
- Docker（运行 ClamAV、执行集成测试）
- `yara` 命令行工具（YARA 扫描依赖；缺失时扫描报引擎故障，按 fail-strategy 处理，默认拒绝上传）

### 1. 启动 ClamAV

```bash
docker compose -f docker/docker-compose.yml up -d
```

首次启动会下载病毒库，`start_period` 为 120s，可通过 `docker compose -f docker/docker-compose.yml ps` 确认 healthy。

### 2. 构建与测试

```bash
mvn clean test      # 单元测试（不依赖 Docker）
mvn clean verify    # 含 EICAR 端到端集成测试（Testcontainers 启动真实 ClamAV，需 Docker）
```

### 3. 启动服务

```bash
mvn spring-boot:run
```

服务默认监听 `http://localhost:8080`。

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/files/sync/upload` | 同步上传，等待扫描完成后返回最终结果 |
| POST | `/api/files/async/upload` | 异步上传，受理后返回 scanId |
| GET | `/api/files/async/scan/{scanId}` | 轮询扫描结果 |
| GET | `/api/files/async/scan/{scanId}/events` | SSE 事件流 |

### 同步上传

```bash
curl -F "file=@report.pdf" http://localhost:8080/api/files/sync/upload
```

```json
{ "scanId": null, "status": "CLEAN", "message": "文件安全", "filePath": "a1b2c3-report.pdf" }
```

文件超过同步阈值（默认 20MB）时返回 400，提示改用异步上传接口。

### 异步上传

```bash
curl -F "file=@big.zip" http://localhost:8080/api/files/async/upload
# → 202 Accepted
{ "scanId": "6f0e...", "status": "SCANNING", "message": "扫描进行中", "filePath": null }
```

随后通过 SSE 实时接收进度，或轮询获取结果：

```bash
# SSE 事件流（扫描完成即推送结果）
curl -N http://localhost:8080/api/files/async/scan/{scanId}/events

# 轮询兜底（任何时刻可查询）
curl http://localhost:8080/api/files/async/scan/{scanId}
```

### 健康检查

```bash
curl http://localhost:8080/actuator/health
```

`health` 中包含 ClamAV PING 探测结果（UP / DOWN）。

### 错误码

| 错误码 | HTTP 状态 | 场景 |
|--------|-----------|------|
| `FILE_REJECTED` | 400 | 类型不在白名单 / 扩展名不符 / ZIP 炸弹 / 超同步阈值 |
| `VIRUS_DETECTED` | 422 | 检出病毒或文档威胁 |
| `SCAN_ENGINE_ERROR` | 502 | 扫描引擎故障（fail-closed 策略下拒绝上传） |
| `FILE_TOO_LARGE` | 413 | 超过 multipart 大小上限（100MB） |
| `INTERNAL_ERROR` | 500 | 服务器内部错误 |

## 配置

关键配置见 `src/main/resources/application.yml`（前缀 `zxf.virus-scan`）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `enabled` | `true` | 扫描总开关，关闭时直接入库 |
| `fail-strategy` | `CLOSED` | 引擎故障策略：`CLOSED` 拒绝上传；`OPEN` 放行并打标 `scan-engine-degraded` |
| `macro-policy` | `BLOCK` | 含 VBA 宏文档处置：`BLOCK` 隔离；`FLAG` 放行并打标（ActiveX / PDF 危险动作始终拦截） |
| `max-file-size` | 100MB | 单文件大小上限，与 multipart 对齐 |
| `sync-max-file-size` | 20MB | 超过则要求异步上传，0 表示不限制 |
| `max-concurrent-scans` | 16 | 扫描并发闸门（Semaphore） |
| `result-retention-minutes` | 30 | 异步扫描结果缓存 TTL |
| `zip.*` | 见 yml | ZIP 炸弹防护阈值（条目数 / 单条目 / 累计 / 压缩比） |
| `clamav.host` / `clamav.port` | `localhost:3310` | ClamAV 地址 |
| `yara.rules-path` | `classpath:rules/malware.yar` | YARA 规则文件 |

支持的环境变量：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `CLAMAV_HOST` / `CLAMAV_PORT` | `localhost` / `3310` | ClamAV 连接地址 |
| `CLAMAV_TIMEOUT_SECONDS` | `60` | 单次扫描超时 |
| `YARA_BINARY` | `yara` | YARA 可执行文件路径 |
| `UPLOAD_STORAGE` | `./data/upload-storage` | 正式文件存储目录 |
| `UPLOAD_QUARANTINE` | `./data/upload-quarantine` | 隔离区目录 |
| `UPLOAD_STAGING` | `./data/upload-staging` | 暂存目录 |

### 允许的文件类型

默认白名单（MIME 由 Tika 实际探测，同时校验扩展名一致性）：

`pdf` `docx` `xlsx` `pptx` `doc` `xls` `ppt` `jpg` `png` `gif` `bmp` `txt` `csv` `zip`

## 项目结构

```
src/main/java/zxf/upload/                 # 六边形骨架 + 管道核心 + 受理边 CQRS（两个限界上下文：FileUpload / FileScan）
├── FileUploadApplication.java      # 启动类
├── domain/                         # 领域层（application 按同名列对称）
│   ├── fileupload/                     # FileUpload 域（上游：文件模型 / 受理 / 保管）
│   │   ├── UploadFile.java             # 值对象：原始名 + 大小 + 暂存路径（不可变）
│   │   └── UploadPolicy.java           # 预检规则：空文件 / 大小 / 扩展名白名单 / 同步路由阈值
│   └── filescan/                       # FileScan 域（下游：消费 staged 文件，产出结论与处置指令）
│       ├── FileDisposition.java        # 处置映射：CLEAN→入库、INFECTED→隔离、其余→清理（消费 ScanStatus）
│       ├── ScanStage.java              # 过滤器接口（零 infra 依赖的领域机制）
│       ├── ScanVerdict.java            # sealed 结论：Passed / Infected / Rejected / Flagged
│       ├── ScanContext.java            # 管道上下文（detectedMime 阶段间传递）
│       ├── ScanPipeline.java           # 按序执行 + 短路
│       ├── model/                      # ScanResult（不可变+工厂）/ ScanStatus / TypeCheck
│       └── documentthreat/             # DocumentThreat / ThreatKind（canBeFlagged）/ DocumentThreatScanner
├── application/                    # 应用层：限界上下文的用例与组件
│   ├── ApplicationService.java         # 唯一门面：fileSyncUpload / fileAsyncUpload / fileScanStatus
│   ├── fileupload/                     # FileUpload 用例
│   │   ├── UploadFileCommand.java          # 写命令（record，携带上传输入与内容流）
│   │   ├── UploadFileCommandChecker.java   # 只读校验（委托 UploadPolicy）
│   │   ├── SyncUploadCommandExecutor.java  # 落盘 → 全管道扫描 → 结果翻译
│   │   └── AsyncUploadCommandExecutor.java # 落盘 → scanId 受理 → 异步分发
│   └── filescan/                       # FileScan 组件
│       ├── ScanPipelineConfig.java         # 阶段顺序显式装配（顺序即领域规则）
│       ├── stage/                          # FileType（Tika+ZIP 防护）/ ClamAv / Yara / DocumentThreat 实现
│       ├── FileScanService.java            # Semaphore 背压 + 处置执行 + fail 策略收口
│       ├── AsyncScanProcessor.java         # 异步任务分发 + 结果缓存 + SSE 推送
│       └── PollScanResultExecutor.java     # 轮询兜底（直收 scanId）
├── infrastructure/                 # 基础设施层：技术组件（次适配器）
│   ├── domain/                         # BusinessException + ErrorCode 异常体系
│   ├── rest/GlobalExceptionHandler.java # 统一错误响应（单一出口）
│   ├── filescan/                       # ClamAvScanner / YaraScanner（外部引擎客户端）
│   ├── fileupload/                     # StagingService（纯落盘 IO）/ FileStorageService（入库/隔离）
│   ├── config/                         # FileScanProperties / AsyncConfig / FileUploadDomainConfig（领域策略装配）
│   ├── health/ClamAvHealthIndicator.java # ClamAV 健康检查
│   └── io/FileUtils.java
└── rest/                           # 接入层：HTTP ↔ Command 协议转换（零业务逻辑，主适配器）
    ├── fileupload/                     # 受理端点（FileUpload 域）
    │   ├── FileSyncUploadController.java   # POST /api/files/sync/upload
    │   └── FileAsyncUploadController.java  # POST /api/files/async/upload
    ├── filescan/                       # 查询端点（FileScan 域）
    │   └── ScanResultController.java       # GET /api/files/async/scan/{scanId}[/events]
    └── file/representation/UploadResponse.java  # 响应模型（record，受理与查询共用）
```

## 文档

`docs/` 目录收录了本项目涉及技术栈的学习笔记：

- [病毒扫描技术栈总览](docs/病毒扫描技术栈总览.md)
- [Tika文件类型检测详解](docs/Tika文件类型检测详解.md)
- [ClamAV病毒扫描引擎详解](docs/ClamAV病毒扫描引擎详解.md)
- [YARA恶意软件规则匹配详解](docs/YARA恶意软件规则匹配详解.md)
- [文档威胁检测详解](docs/文档威胁检测详解.md)
- [HTTP传输文本与二进制及数据读取方式详解](docs/HTTP传输文本与二进制及数据读取方式详解.md)
- [SpringResource资源抽象与释放处置详解](docs/SpringResource资源抽象与释放处置详解.md)
- [VSCode进行Java软件开发全景指南](docs/VSCode进行Java软件开发全景指南.md)
- [病毒扫描方案设计 v3](docs/virus-scan-solution-v3.md)