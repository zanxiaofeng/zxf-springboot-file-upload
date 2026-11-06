# POST /api/files/async/upload 与轮询 GET /api/files/async/scan/{scanId} 调查报告

## Executive Summary

- **调查范围**：静态源码阅读；源码根 `/home/davis/Work/zxf-springboot-file-upload`，commit `181c393`。入口为异步上传 `POST /api/files/async/upload`（I1）及其伴随轮询 `GET /api/files/async/scan/{scanId}`（I2），覆盖本服务直接发出的一跳。坐标前缀 `src/main/java/zxf/upload/` 下文省略；其余路径（配置/测试）相对仓库根完整给出。
- **功能**：multipart 文件上传的异步安全扫描受理。I1 在请求线程内完成预检（空文件/大小/扩展名）→ staging 落盘 → 生成 scanId → 立即返回 202；扫描在虚拟线程后台执行四阶段管道（类型校验+ZIP 防护 → ClamAV → YARA → 文档威胁检测，首个非 Passed 结论短路），按处置映射入库（CLEAN）/隔离（INFECTED）/清理（REJECTED/ERROR），终态写入内存缓存；I2 按 scanId 轮询读取当前状态（SCANNING 占位或终态）。
- **外部依赖**：O1（文件系统 → staging 暂存区写入）、O4（自定义 TCP → ClamAV 守护进程 INSTREAM）、O5（子进程 → yara CLI）、O7（文件系统 → 正式存储入库与隔离区处置）；外部交互共 4 项（2 文件系统 + 1 自定义 TCP + 1 子进程）。O2（异步执行与结果缓存）、O3（类型校验与 ZIP 炸弹防护）、O6（文档威胁检测）为进程内重要操作，无外部系统，不计入外部依赖。
- **注意**：① 轮询无「未受理/结果已淘汰」与「进行中」的区分，也不返回 404——任意合法 UUID 一律 200 SCANNING；结果缓存为单机内存（TTL 30 分钟、上限 100,000 条），进程重启全部丢失，客户端可能无限轮询。② 无鉴权、无幂等；202 响应无 Location 头，轮询 URL 需客户端自行拼接。③ 引擎故障按 fail-strategy=CLOSED（当前配置）收敛为轮询终态 ERROR + 固定通用文案（异步路径不出现 5xx）；后台任何意外异常同样统一翻译为该文案。④ 隔离处置双重 IO 失败时威胁文件静默滞留 staging（仅 ERROR 日志），轮询仍报 INFECTED。⑤ ClamAV 有熔断+60s 超时；YARA 无熔断，仅 60s 超时 + 强杀进程。⑥ 大小限制双闸：容器 multipart 100MB/110MB（413）先于领域预检 100MB（400）。

## Inbound

### I1. HTTP Server → POST /api/files/async/upload（multipart 异步受理）

| 鉴权 | 主坐标 |
|---|---|
| 无（未引入 Spring Security，pom.xml 无 security 依赖） | `rest/fileupload/FileUploadController.java#asyncUpload` |

> 同步完成预检与暂存后立即返回 202 + scanId，扫描转虚拟线程后台执行，本请求不感知扫描结果。

**关键事实**
- 请求体上限：`spring.servlet.multipart.max-file-size=100MB`、`max-request-size=110MB`（`src/main/resources/application.yml:6-8`），超限由容器抛 `MaxUploadSizeExceededException` → 413。
- multipart part 名必须为 `file`；缺失、非 multipart 请求 → 400（`infrastructure/rest/GlobalExceptionHandler.java#handleMissingFilePart`）。
- 全链路无鉴权、无幂等键：同一文件重复上传即重复受理与扫描。
- 202 响应不携带 Location 头，轮询 URL 不在响应中给出。
- 本入口为纯 Servlet MVC 栈（`spring-boot-starter-web`），虚拟线程开启（`spring.threads.virtual.enabled=true`，`src/main/resources/application.yml:2-4`）。

<details><summary>Request / Response 契约、处理逻辑与错误映射</summary>

**Request**

`multipart/form-data`，唯一业务 part：`file`（filename = 客户端原始文件名，body = 文件字节流）。无其他参数、无 Header 约定。

**处理逻辑**

1. `commandOf(file)`：`MultipartFile` → `UploadFileCommand(originalFilename ← getOriginalFilename()，size ← getSize()，content ← getInputStream())`（`application/fileupload/UploadFileCommand.java#of`）。`条件`：`getInputStream()` 抛 IOException → `BusinessException.rejected("文件暂存失败，请稍后重试")` → 400。
2. `UploadFileCommandChecker.check`：委托 `UploadPolicy.rejectionReason`（`domain/fileupload/UploadPolicy.java:22-34`）。`条件`（任一命中即 400 FILE_REJECTED）：`size <= 0`（"上传文件不能为空"）；`size > 104857600`（"文件过大，最大允许 100MB"）；扩展名非空且不在白名单（默认 15 项，含 jpeg 别名，"不支持的文件扩展名: {ext}"）。空扩展名放行（类型一致性由管道阶段 1 兜底）。异步入口不执行 `checkSyncLimit`（同步 20MB 阈值仅约束 `/sync/upload`）。
3. O1：staging 落盘（`StagingService.stage`），获得 stagingPath。`条件`：落盘 IO 失败 → 400 FILE_REJECTED（清理残留后拒绝）。
4. `scanId = UUID.randomUUID().toString()`（`application/fileupload/AsyncUploadCommandExecutor.java:28`）。
5. O2：`asyncScanProcessor.processScan(scanId, staged)` 提交后台扫描。`异步`：`@Async` void，受理线程不等待结果、不感知后台异常。
6. 返回 `202 Accepted` + `UploadResponse.scanning(scanId)`。

**Response — 202（结构示意，动态值为占位符）**

```json
{"scanId": "{uuid}", "status": "SCANNING", "message": "扫描进行中"}
```

序列化：Jackson（SB4 默认 Jackson 3），`@JsonInclude(NON_NULL)`（`rest/fileupload/representation/UploadResponse.java:11`），null 的 `filePath` 不输出。

**错误映射（实际翻译层：`infrastructure/rest/GlobalExceptionHandler.java`）**

| 场景 | 来源 | 对外响应 | 泄漏 |
|---|---|---|---|
| 缺 file part / 非 multipart / part 名错误 | 平台或框架 | 400 `{"code":"FILE_REJECTED","message":"缺少上传文件或请求格式非法"}` | 否（仅 debug 日志） |
| multipart 超限（>100MB / >110MB） | 平台或框架 | 413 `{"code":"FILE_TOO_LARGE","message":"文件超过大小限制"}` | 否 |
| getInputStream IO 失败 | 业务处理（Controller 协议转换） | 400 FILE_REJECTED「文件暂存失败，请稍后重试」 | 否 |
| 空文件 / 超 100MB / 扩展名非白名单 | 业务处理（UploadPolicy） | 400 FILE_REJECTED + 具体原因文案 | 回显客户端自提供的扩展名（低敏） |
| staging 落盘 IO 失败 | 业务处理（StagingService） | 400 FILE_REJECTED「文件暂存失败，请稍后重试」；内部路径只进日志 | 否 |
| 扫描引擎故障 | 下游传播（后台） | **不在本响应出现**——异步路径收敛为轮询终态 ERROR（见 O2） | — |
| 其他未预期异常 | 未捕获 | 500 INTERNAL_ERROR 固定文案 | 否（不回显 getMessage） |

错误体统一为 `ErrorResponse(code, message)` record，非 ApiResponse 信封（本项目自有约定）。

</details>

### I2. HTTP Server → GET /api/files/async/scan/{scanId}（轮询查询，独立入口）

| 鉴权 | 主坐标 |
|---|---|
| 无 | `rest/filescan/ScanResultController.java#scanStatus` |

> 读取 I1 受理的 scanId 的扫描当前状态；进行中返回 SCANNING 占位，完成后返回终态（CLEAN/INFECTED/REJECTED/ERROR）。

**关键事实**
- 与 I1 的关系：I1 受理时生成 scanId 并由 O2 写入结果缓存；I2 仅读该缓存（`application/filescan/PollScanResultExecutor.java#execute` → `AsyncScanProcessor#getResult`），不触发任何扫描或文件操作。两入口分属不同域 Controller（`rest/fileupload/` 与 `rest/filescan/`），URL 前缀同为 `/api/files/async`（对外契约保留）。
- scanId 校验：`@Pattern` UUID 正则（`rest/filescan/ScanResultController.java:22`），方法参数级校验失败 → `HandlerMethodValidationException` → 400 VALIDATION_ERROR（测试 `src/test/java/zxf/upload/rest/filescan/ScanResultControllerTest.java:55-61` 实证，不进 handler 方法）。
- **未知 scanId（从未受理）、扫描进行中、结果已按 TTL 淘汰三者不可区分**，一律 200 SCANNING（`AsyncScanProcessor#getResult` 缓存 miss 回退占位；测试 `src/test/java/zxf/upload/application/filescan/AsyncScanProcessorTest.java:62-67` 实证）。无 404、无结果删除端点。
- 检出威胁（INFECTED/REJECTED）以 200 + 终态字段表达，不是 4xx/422——异步语义下威胁不是请求错误。

<details><summary>Response 契约与状态集合</summary>

**Response — 200（结构示意，动态值为占位符）**

进行中占位 / 未知或已淘汰：

```json
{"scanId": "{uuid}", "status": "SCANNING", "message": "扫描进行中"}
```

终态示例（CLEAN）：

```json
{"scanId": "{uuid}", "status": "CLEAN", "message": "文件安全", "filePath": "{uuid}.txt"}
```

终态集合与 message 映射（`UploadResponse.of`，`rest/fileupload/representation/UploadResponse.java:18-28`）：

| status | message | filePath |
|---|---|---|
| SCANNING | "扫描进行中" | 省略 |
| CLEAN（无 threat） | "文件安全" | 存储文件名（`{uuid}.ext`，不含路径） |
| CLEAN（带打标） | "文件安全（{threat}）"（macro-flagged / scan-engine-degraded，见 O6/O7） | 存储文件名 |
| INFECTED | "检测到威胁: {threat}" | 省略 |
| REJECTED | "文件被拒绝: {threat}" | 省略 |
| ERROR | "扫描引擎暂时不可用，请稍后重试"（来自 O2 catch 分支的 ErrorCode 默认文案，非 `of` 的防御性 ERROR 分支） | 省略 |

**错误映射**

| 场景 | 来源 | 对外响应 |
|---|---|---|
| scanId 非 UUID 格式 | 本地校验（@Pattern） | 400 `{"code":"VALIDATION_ERROR","message":"请求参数非法"}`（不逐字段暴露） |
| 其他未预期异常 | 未捕获 | 500 INTERNAL_ERROR 固定文案 |

</details>

## Operations（O2 为后台异步单元；O3→O6 为管道阶段顺序，首个非 Passed 短路；O7 为终态处置）

### O1. 文件系统 → staging 暂存区写入（受理线程内）

主坐标：`infrastructure/fileupload/StagingService.java#stage`

> 把上传字节流同步落盘到暂存目录并返回路径；失败清理残留并以 400 拒绝。

**关键事实**
- 路径模板：`{staging-path}/{uuid}.{ext}`，staging-path 默认 `./data/upload-staging`（可环境变量 `UPLOAD_STAGING` 覆盖，`src/main/resources/application.yml:36`）；ext 取自原始文件名（basename 清洗后），无扩展名则无后缀。
- 写入方式：`Files.copy(content, target, REPLACE_EXISTING)`；UUID 命名天然不冲突，REPLACE_EXISTING 仅为防御。
- 目录在 Bean 构造期 `createDirectories`，失败抛 `IllegalStateException` → 应用启动失败（fail-fast，非请求期行为）。
- 落盘 IO 失败：`deleteQuietly` 清理 → log.error（含完整路径，仅日志）→ `BusinessException.rejected`（400）。

### O2. 进程内，无外部系统 → 异步执行、背压与结果缓存

主坐标：`application/filescan/AsyncScanProcessor.java#processScan`

> 在虚拟线程上执行完整扫描（O3→O6→O7）并把终态写入 Caffeine 缓存供 I2 轮询；任何异常收敛为 ERROR 终态。

**关键事实**
- 异步模型：`@Async` void + `@EnableAsync`（`infrastructure/config/AsyncConfig.java`）；`spring.threads.virtual.enabled=true` 时 Boot 自动装配的 applicationTaskExecutor 即虚拟线程执行器，每任务一个虚拟线程，无手工线程池。
- 背压：`FileScanService.scanFile` 入口 `Semaphore(16)`（`application/filescan/FileScanService.java:46,56`；`zxf.virus-scan.max-concurrent-scans=16`），许可耗尽时虚拟线程挂起等待；同步/异步两入口共用同一闸门。排队被中断 → `scanFailed`（→ ERROR 终态）。
- 结果缓存：Caffeine `expireAfterWrite=30 分钟`（`zxf.virus-scan.result-retention-minutes`）、`maximumSize=100,000`（`application/filescan/AsyncScanProcessor.java:32,41-44`）；到期淘汰防内存泄漏。单机内存态，重启即失。
- 失败收口：catch Exception → log.error（引擎地址/路径/堆栈只进日志）→ 终态 `ERROR` + `ErrorCode.SCAN_ENGINE_ERROR` 默认文案"扫描引擎暂时不可用，请稍后重试"（与同步路径 GlobalExceptionHandler 对该错误码的收口一致）。`@Async` void 的异常在方法内已收口，不会静默丢失。
- 已知限制：受理 202 返回后、后台完成前进程崩溃，则任务丢失且无补偿，该 scanId 轮询永远 SCANNING。

### O3. 进程内，无外部系统 → 类型校验与 ZIP 炸弹流式防护（管道阶段 1）

主坐标：`application/filescan/stage/FileTypeScanStage.java#scan`

> Tika 内容探测 MIME + 白名单/扩展名一致性校验 + ZIP 四限防护；拒绝短路管道为 REJECTED。

**关键事实**
- Tika（tika-core 3.3.2）`tika.detect(file)` 探测一次，结果写入 `ScanContext.detectedMime` 供 O6 复用（避免重复探测）。探测 IO 失败 → `scanFailed`（502 语义，异步路径转 ERROR 终态）。
- MIME 白名单默认 14 项（`infrastructure/config/FileScanProperties.java:25-39`：pdf/docx/xlsx/pptx/doc/xls/ppt/jpg/png/gif/bmp/txt/csv/zip），配置未覆盖。
- 扩展名一致性特判：`image/jpeg` 接受 jpg/jpeg；`text/plain` 接受 txt/csv；其余按 MIME_TO_PRIMARY_EXT 映射，无映射时退回扩展名白名单判断。
- ZIP 防护（仅 `application/zip`，`inspectZip`，commons-compress 1.28.0）：条目数 >10,000、单 entry 解压 >100MB、累计解压 >1GB、压缩比 >100:1，任一命中即拒绝；所有 entry 实际解压逐字节计数，不信任 header 声明；不递归解压嵌套 zip（由 ClamAV/YARA 对原始字节兜底）。损坏 zip / 声明与实际不符 → `scanFailed`（fail-closed）。

### O4. 自定义 TCP → ClamAV 守护进程（clamd INSTREAM 扫描，管道阶段 2）

主坐标：`infrastructure/filescan/ClamAvScanner.java#scan`

> 经 clamav-client 向 ClamAV 发送 INSTREAM 流扫描；返回威胁描述或 null，故障抛 SCAN_ENGINE_ERROR 由 fail 策略处置。

**关键事实**
- 客户端：clamav-client 2.1.2（`xyz.capybara.ClamavClient`），`new ClamavClient(host, port, Platform.JVM_PLATFORM)`，`client.scan(InputStream)` 即 clamd INSTREAM 协议；第三方 SDK 类型不出本类。
- 目标地址：`zxf.virus-scan.clamav.host=${CLAMAV_HOST:localhost}`、`port=${CLAMAV_PORT:3310}`（`src/main/resources/application.yml:25-26`）。
- 超时：SDK 2.1.2 无 socket 超时配置，扫描提交到独立虚拟线程限时等待 `timeoutSeconds=60`（`${CLAMAV_TIMEOUT_SECONDS:60}`）；超时抛 `scanFailed("ClamAV 扫描超时(60s): {fileName}")` 并计入熔断失败率；被放弃的虚拟线程阻塞在 socket read 上待 TCP 自然回收。
- 熔断：resilience4j-circuitbreaker 2.4.0，`CircuitBreaker "clamav"`，COUNT_BASED 滑窗 10、最小调用 5、失败率阈值 50%、OPEN 等待 30s、半开放行 3 次（`ClamAvScanner.java:62-69`）；熔断打开时 `CallNotPermittedException` → `scanFailed("ClamAV 熔断中，扫描暂时不可用")`。
- 病毒命中返回 `"ClamAV: {病毒名: 文件名; …}"`，无命中返回 null（视为 Passed，不产生网络副作用之外的交互）。

### O5. 子进程 → yara CLI（自定义规则匹配，管道阶段 3）

主坐标：`infrastructure/filescan/YaraScanner.java#scan`

> 以子进程运行 yara 二进制对文件做规则匹配，解析 stdout 命中规则名；进程级超时与强杀保护。

**关键事实**
- 完整命令（字面值）：`{binary-path} -s --fail-on-warnings {rulesPath} {file 绝对路径}`；binary-path 默认 `yara`（env `YARA_BINARY`），rulesPath 默认 `classpath:rules/malware.yar`（`src/main/resources/application.yml:28-32`）。
- classpath 规则在 Bean 构造期解压到临时文件 `yara-rules-*.yar`（deleteOnExit）；`yara.enabled=false` 是完整逃生舱（不解析规则路径也能启动，scan 直接返回 null）。
- 超时与清理：`waitFor(60s)` 超时 → `scanFailed`；stdout 读取移入独立虚拟线程（防读阻塞绕过超时检查），进程退出后 5s 排空宽限；finally 中 `destroyForcibly` 残余进程。
- 退出码语义：0 = 执行成功（无论命中与否），非 0 → `scanFailed`（输出附入 detail 仅进日志）；命中判定以 stdout 非空为准，解析非缩进行首列为规则名，返回 `"YARA: {rule1, rule2}"`。
- 无熔断器（与 O4 不同），仅超时 + 强杀兜底。

### O6. 进程内，无外部系统 → 文档威胁检测（POI / ZIP / PDF 浅检测，管道阶段 4）

主坐标：`domain/filescan/documentthreat/DocumentThreatScanner.java#scan`

> 按 O3 产出的 detectedMime 路由三分支检测文档威胁；威胁按 ThreatKind 分级，宏在 FLAG 策略下放行打标，ActiveX/PDF 危险动作始终拦截。

**关键事实**
- 路由输入为 `ScanContext.detectedMime`（内容探测结果，不信任客户端扩展名）；非文档格式直接 Passed（`isDocumentFormat`：officedocument/msword/ms-excel/ms-powerpoint/pdf）。
- OOXML（docx/xlsx/pptx）：`java.util.zip.ZipFile` 仅遍历条目名——`activex/` 前缀 → ACTIVE_X（先判，防 FLAG 策略被 VBA 先命中绕过）；`vbaproject.bin` 后缀 → MACRO。
- OLE2（doc/xls/ppt）：POI 5.5.1 `VBAMacroReader.readMacros()` 真实提取宏模块，逐模块小写化匹配 10 个可疑 token（autoopen/autoexec/document_open/workbook_open/shell/wscript.shell/cmd.exe/powershell/urldownloadtofile/createobject，命中之一即 MACRO）；良性宏返回 null。
- PDF：1MB 分块 + 64B 重叠窗口流式扫描（ISO_8859_1 小写）——`/launch` → PDF_ACTION；`/javascript` 且 `/openaction` 并存 → PDF_ACTION。已知限制：对 FlateDecode 压缩对象流无效，深度检测由 O4/O5 兜底。
- 分级处置（`application/filescan/stage/DocumentThreatScanStage.java:37-44`）：`ThreatKind.canBeFlagged()`（仅 MACRO）且 `macro-policy=FLAG` → `Flagged("macro-flagged: {描述}")`（管道转 CLEAN + threat 打标）；否则 Infected（管道转 INFECTED）。当前配置 `macro-policy=BLOCK`（`src/main/resources/application.yml:14`）→ 宏文档一律 INFECTED。
- 解析抛出的意外异常 → `scanFailed`（fail-closed 走引擎故障通道）。

### O7. 文件系统 → 终态处置（入库 / 隔离 / 清理）

主坐标：`application/filescan/FileScanService.java#handleResult`

> 按领域处置映射执行 staging 文件生命周期：CLEAN 入库、INFECTED 隔离、REJECTED/ERROR 清理；引擎故障按 fail 策略收口。

**关键事实**
- 内部顺序（`FileDisposition.of(status)` 查表，`domain/filescan/FileDisposition.java:12-18`）：
  - `条件` STORE（CLEAN）：copy 至 `{base-path}/{uuid}.{ext}`（默认 `./data/upload-storage`，env `UPLOAD_STORAGE`）→ 删除 staging → 结果 details 替换为存储文件名（不含路径，防内部路径外泄）→ O2 写缓存（filePath 即该文件名）。
  - `条件` QUARANTINE（INFECTED）：move 至 `{quarantine-path}/{uuid}.quarantined`（默认 `./data/upload-quarantine`，env `UPLOAD_QUARANTINE`）；跨文件系统 move 失败降级 copy+delete。降级再失败仅 log.error，**威胁文件滞留 staging**，轮询仍报 INFECTED（处置失败不改变结论）。隔离区目录构造期设 POSIX 权限 `rwx------`（`infrastructure/fileupload/FileStorageService.java:28-31`）。
  - `条件` DISCARD（REJECTED/ERROR）：`deleteQuietly` 删除 staging（失败仅 WARN 日志）。
- 引擎故障收口（`handleEngineFailure`，仅 `SCAN_ENGINE_ERROR` 可降级）：当前 `fail-strategy=CLOSED`（`src/main/resources/application.yml:13`）→ 删除 staging 后原样上抛 → O2 catch 转轮询终态 ERROR；`条件` OPEN（未启用）→ 先入库再删 staging，终态 CLEAN + threat=`scan-engine-degraded`。业务拒绝类 BusinessException 不会被 fail-open 放行（安全守卫）。
- 管道 IO 异常：删除 staging → `scanFailed` → 同样转 ERROR 终态。

## 补充说明

- 控制流总览（I1 视角）：I1 步骤 1-4 在请求线程同步执行（O1 为唯一 IO）；步骤 5 提交 O2 后立即 202。后台虚拟线程内：Semaphore 排队 → O3 →（Passed 才继续）O4 → O5 → O6 → O7 → 缓存 put。管道（`domain/filescan/ScanPipeline.java#execute`）首个非 Passed 结论短路：Rejected→REJECTED、Infected→INFECTED、Flagged→CLEAN+打标；`zxf.virus-scan.enabled=false` 时管道直通 CLEAN（仍走 O7 入库）。
- 关键数据流向：`scanId` ← UUID（I1 受理）→ I2 路径参数 → 缓存 key；`originalFilename` → `cleanedName()`（basename，剥目录防路径混淆）→ O1/O7 命名扩展名与日志（`sanitizeForLog` 防 log forging）；`detectedMime` ← O3 → ScanContext → O6 路由 → CLEAN 结果透传；`filePath` ← O7 存储文件名（仅 CLEAN）。
- `UploadResponse`（`rest/fileupload/representation/`）同时是 I1 受理回执与 I2 轮询 body 的统一响应模型，由 fileupload 域拥有、filescan 查询端点消费；`ApplicationService`/`PollScanResultExecutor` 返回 rest 类型是项目架构文档已声明的务实偏离。
- 本服务无统一 ApiResponse 信封：成功体为 `UploadResponse`，错误体为 `ErrorResponse(code, message)`（`GlobalExceptionHandler` 内嵌 record）。
- 同步端点 `POST /api/files/sync/upload` 与本链路共用同一管道、信号量闸门与处置逻辑（差异仅：请求线程内同步执行 + 20MB 阈值 + 引擎故障直接 502），超出本报告范围。

## 运行元数据（非报告内容）

实际读取的项目源码文件（含配置与测试，去重，共 47 个）：

1. /home/davis/Work/zxf-springboot-file-upload/pom.xml
2. /home/davis/Work/zxf-springboot-file-upload/src/main/resources/application.yml
3. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/FileUploadApplication.java
4. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/ApplicationService.java
5. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/AsyncScanProcessor.java
6. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/FileScanService.java
7. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/PollScanResultExecutor.java
8. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/ScanPipelineConfig.java
9. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/stage/ClamAvScanStage.java
10. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/stage/DocumentThreatScanStage.java
11. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/stage/FileTypeScanStage.java
12. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/stage/YaraScanStage.java
13. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/fileupload/AsyncUploadCommandExecutor.java
14. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/fileupload/UploadFileCommand.java
15. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/fileupload/UploadFileCommandChecker.java
16. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/FileDisposition.java
17. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/ScanContext.java
18. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/ScanPipeline.java
19. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/ScanStage.java
20. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/ScanVerdict.java
21. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/documentthreat/DocumentThreat.java
22. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/documentthreat/DocumentThreatScanner.java
23. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/documentthreat/ThreatKind.java
24. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/model/ScanResult.java
25. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/model/ScanStatus.java
26. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/model/TypeCheck.java
27. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/fileupload/UploadFile.java
28. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/fileupload/UploadPolicy.java
29. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/config/AsyncConfig.java
30. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/config/FileScanDomainConfig.java
31. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/config/FileScanProperties.java
32. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/config/FileUploadDomainConfig.java
33. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/domain/BusinessException.java
34. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/domain/ErrorCode.java
35. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/filescan/ClamAvScanner.java
36. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/filescan/YaraScanner.java
37. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/fileupload/FileStorageService.java
38. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/fileupload/StagingService.java
39. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/io/FileUtils.java
40. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/rest/GlobalExceptionHandler.java
41. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/rest/filescan/ScanResultController.java
42. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/rest/fileupload/FileUploadController.java
43. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/rest/fileupload/representation/UploadResponse.java
44. /home/davis/Work/zxf-springboot-file-upload/src/test/java/zxf/upload/application/filescan/AsyncScanProcessorTest.java
45. /home/davis/Work/zxf-springboot-file-upload/src/test/java/zxf/upload/application/fileupload/AsyncUploadCommandExecutorTest.java
46. /home/davis/Work/zxf-springboot-file-upload/src/test/java/zxf/upload/rest/filescan/ScanResultControllerTest.java
47. /home/davis/Work/zxf-springboot-file-upload/src/test/java/zxf/upload/rest/fileupload/FileUploadControllerTest.java
