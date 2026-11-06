# POST /api/files/sync/upload 调查报告

## Executive Summary

- **调查范围**：`/home/davis/Work/zxf-springboot-file-upload`（commit 181c393；已核对工作区 src/ 与 pom.xml 同该提交）；纯静态源码阅读，未启动服务；入口 `POST /api/files/sync/upload`。坐标前缀 `src/main/java/zxf/upload/`，测试与资源文件使用仓库相对路径。
- **功能**：接收 multipart 文件上传，在请求线程内同步完成「预检（同步阈值/空文件/大小/扩展名）→ 暂存落盘（O1）→ 四阶段扫描管道（O2 类型校验与 ZIP 防护 → O3 ClamAV → O4 YARA → O5 文档威胁，首个非 Passed 结论短路）→ 处置（O6 入库/隔离/清理）」，HTTP 200 直接返回终态结论；策略拒绝、威胁检出、引擎故障分别翻译为 400 / 422 / 502。
- **外部依赖**：共 6 张 O 卡——O1 文件系统（staging 写入）、O3 自定义 TCP（ClamAV 守护进程）、O4 子进程（yara CLI）、O6 文件系统（入库/隔离/清理）为本机或外部交互；O2、O5 为进程内重要操作（标注「进程内，无外部系统」，不计入外部依赖）。
- **注意**：① 端点无鉴权、无幂等键（仓库无 Spring Security 依赖与配置，已 grep 实证）；重复上传会生成多个存储文件。② 引擎故障默认 fail-closed（502 拒绝上传）；`zxf.virus-scan.fail-strategy=OPEN` 时降级放行入库并打标 `scan-engine-degraded`，`macro-policy=FLAG` 时含宏文档放行打标——两种放行仅靠响应 `message` 中的 threat 文案区分。③ 引擎故障细节（host:port、内部路径）只进日志，对外固定文案。④ 超过 20MB 返回 400 FILE_REJECTED 并在文案中引导改走异步端点（不是 413）；超过 multipart 限制 100MB 才是 413。⑤ 并发闸门 Semaphore(16) 为同步/异步共用，许可耗尽时请求线程（虚拟线程）无限期挂起排队，无排队超时。⑥ 成功体为 `UploadResponse`、错误体为 `ErrorResponse{code,message}`，无项目规范中的 `ApiResponse` 统一信封（本项目实际形态）。

## Inbound

### I1. HTTP Server → POST /api/files/sync/upload

| 鉴权 | 主坐标 |
|---|---|
| 无 | `rest/fileupload/FileUploadController.java#syncUpload` |

> 同步受理 multipart 上传（part 名 `file`），在请求线程内跑完预检、暂存与完整扫描管道，直接返回终态扫描结论；区别于 `POST /api/files/async/upload` 的 202 + 轮询形态（该入口独立于本报告，未展开）。

**关键事实**
- 无鉴权、无授权、无幂等键：任何可达客户端可直接上传。
- 请求体上限来自平台配置：`spring.servlet.multipart.max-file-size=100MB`、`max-request-size=110MB`（`src/main/resources/application.yml:6-8`）。
- 同步路由阈值 20MB（`sync-max-file-size: 20971520`）：超限在预检即拒绝（400），不进入扫描。
- 预检扩展名白名单放行无扩展名文件（`UploadPolicy.rejectionReason` 仅在扩展名非空时校验），该文件随后被 O2 的一致性检查拒绝（派生：依据 `FileTypeScanStage.isExtensionConsistent` 分支推演）。
- 响应无统一信封：成功 200 返回 `UploadResponse` record，错误返回 `ErrorResponse(code, message)`（`infrastructure/rest/GlobalExceptionHandler.java:26`）。
- 上传文件名经 `cleanedName()` 剥离目录部分、经 `FileUtils.sanitizeForLog` 剥离控制字符后才进日志与下游命名。

<details><summary>Request／Response 契约、处理逻辑、错误映射</summary>

**Request**

`multipart/form-data`，必需 part `file`（`@RequestParam("file") MultipartFile`）。三个值进入命令：`originalFilename`（客户端可控）、`size`、`content`（InputStream）；见 `application/fileupload/UploadFileCommand.java#of`。无其他参数、无 Header 契约。

**Response — 200（终态，同步链路成功时 status 恒为 CLEAN）**

```json
{
  "status": "CLEAN",
  "message": "文件安全",
  "filePath": "{uuid}.txt"
}
```

结构示意：`scanId` 因 `@JsonInclude(NON_NULL)` 且同步恒为 null 而省略；`filePath` ← `ScanResult.details`（存储文件名 `UUID.ext`，不含路径）；含打标时 `message` 形如 `"文件安全（macro-flagged: Office 文档包含 VBA 宏）"`。字段证据：`rest/fileupload/representation/UploadResponse.java#of`。

**Response — 错误（结构示意）**

```json
{
  "code": "VIRUS_DETECTED",
  "message": "ClamAV: Eicar-Test-Signature: eicar.txt"
}
```

**处理逻辑**

1. `commandOf(file)`：multipart → `UploadFileCommand`；流打开抛 IOException 时按业务拒绝表达 400。
2. `checker.checkSyncLimit`：size > 20MB → 400（文案引导异步端点）。
3. `checker.check`（委托 `domain/fileupload/UploadPolicy`）：size ≤ 0 / > 100MB / 扩展名不在白名单 → 400。
4. O1 落盘 staging，得到 `stagingPath`。
5. `FileScanService.scanFile`：获取 Semaphore 许可（条件：许可耗尽则挂起等待）→ 管道执行（条件：`zxf.virus-scan.enabled=false` 时直通 CLEAN）→ O2→O3→O4→O5 顺序执行，短路：首个非 `Passed` 结论即终止并出结果。
6. O6 按 `FileDisposition` 处置暂存文件；CLEAN 时 details 替换为存储文件名。
7. `translate(result)`：CLEAN → 200 返回；REJECTED → 400；INFECTED → 422；其余（ERROR）→ 502。
- 异步：无（全程请求线程内同步完成；虚拟线程仅作为请求载体与引擎内部等待手段）。

**错误映射**（翻译层 `infrastructure/rest/GlobalExceptionHandler.java`；ErrorCode 三要素见 `infrastructure/domain/ErrorCode.java`）

| 场景 | 来源 | 对外响应 | 泄漏 |
|---|---|---|---|
| 上传体超 multipart 限制 | 平台或框架 | 413 `FILE_TOO_LARGE`「文件超过大小限制」 | 否 |
| 缺 file part / 非 multipart 请求 | 平台或框架 | 400 `FILE_REJECTED`「缺少上传文件或请求格式非法」 | 否 |
| multipart 流打开失败 | 本地校验 | 400 `FILE_REJECTED`「文件暂存失败，请稍后重试」 | 否 |
| size > 20MB 同步阈值 | 本地校验 | 400 `FILE_REJECTED`（文案含异步端点指引） | 否 |
| 空文件 / size > 100MB / 扩展名不在白名单 | 本地校验 | 400 `FILE_REJECTED` | 否 |
| MIME 不在白名单 / 扩展名与内容不符 / ZIP 炸弹 | 业务处理 | 400 `FILE_REJECTED` | 否 |
| O3/O4/O5 检出威胁 | 业务处理 | 422 `VIRUS_DETECTED`（message 为威胁描述） | 否 |
| 引擎故障（不可达/超时/熔断/损坏 zip）且 fail-strategy=CLOSED（默认） | 下游传播 | 502 `SCAN_ENGINE_ERROR` 固定文案 | 否（detail 仅日志，ERROR 级附堆栈） |
| 引擎故障且 fail-strategy=OPEN | 下游传播 | 200 CLEAN + threat=`scan-engine-degraded` | — |
| 管道 IO 异常（非业务异常） | 下游传播 | 502 `SCAN_ENGINE_ERROR` | 否 |
| 扫描排队被中断 | 业务处理 | 502 `SCAN_ENGINE_ERROR` | 否 |
| 其他未预见异常 | 未捕获 | 500 `INTERNAL_ERROR` 固定文案 | 否 |
| 无匹配路由 / 方法不支持 | 平台或框架 | 404 `NOT_FOUND` / 405 `BAD_REQUEST` | 否 |

</details>

## Operations（O2–O5 构成顺序短路的扫描管道；O6 为处置段）

### O1. 文件系统 → staging 暂存写入

主坐标：`infrastructure/fileupload/StagingService.java#stage`

> 将上传内容流复制到 staging 目录并返回路径，供后续全部扫描阶段与处置使用。

**关键事实**
- 写入目录默认 `./data/upload-staging`（`UPLOAD_STAGING` 环境变量可覆盖），构造期 `createDirectories`。
- 文件名 `{UUID}.{ext}`，ext 取自清洗后文件名的小写扩展名；与 multipart 请求级临时文件生命周期解耦。
- 落盘失败（磁盘满/IO 错误）时删除残留文件、路径仅进 ERROR 日志、对外 400「文件暂存失败，请稍后重试」。

<details><summary>数据映射与失败表</summary>

- 数据映射：`content`（InputStream）→ staging 文件字节；返回 `stagingPath` → `UploadFile.withStagingPath` → O2–O6 的文件定位。
- 写入使用 `Files.copy(..., REPLACE_EXISTING)`；UUID 命名下实际不会覆盖既有文件。

| 症状 | 处置 |
|---|---|
| 磁盘满/IO 错误 | 致命-中止（400，见 I1 错误映射） |

</details>

### O2. 进程内重要操作 → 类型校验与 ZIP 炸弹防护（进程内，无外部系统）

主坐标：`application/filescan/stage/FileTypeScanStage.java#scan`

> 用 Tika 对暂存文件做一次内容探测，执行 MIME 白名单、扩展名一致性、ZIP 四限防护；`detectedMime` 写入管道上下文供 O5 复用。

**关键事实**
- 探测基于文件内容而非扩展名：`org.apache.tika:tika-core` 3.3.2（`pom.xml:39-44`）。
- MIME 白名单 14 类，由 `MIME_TO_PRIMARY_EXT` 派生（`infrastructure/config/FileScanProperties.java:25-39`）：pdf/docx/xlsx/pptx/doc/xls/ppt/jpg/png/gif/bmp/txt/csv/zip。
- 扩展名一致性特判两对互认：image/jpeg↔jpg|jpeg、text/plain↔txt|csv（CSV 常被 Tika 探测为 text/plain）。
- ZIP（detectedMime=application/zip）触发流式防护，四条上限任一超限即拒绝：条目数 10000、单 entry 解压 100MB、累计解压 1GB、压缩比 100:1（默认值，`application.yml:19-23`）。
- 防护不信任 zip header 声明大小，全部条目实际解压逐字节计数（`inspectZip` 注释）。
- 不递归展开嵌套 zip：内层 .zip 条目由 O3/O4 对原始字节扫描兜底。
- 无扩展名文件在本阶段一致性检查必然被拒（派生：`isExtensionConsistent` 各分支均不接受空扩展名）。

<details><summary>失败表</summary>

| 症状 | 处置 |
|---|---|
| MIME 不在白名单 | 致命-中止（400，见 I1） |
| 扩展名与内容不符 | 致命-中止（400） |
| ZIP 任一上限超限 | 致命-中止（400，文案含具体限值） |
| Tika 探测 IO 失败 | 致命-中止（502，转 SCAN_ENGINE_ERROR） |
| zip 流损坏/声明与实际不符 | 致命-中止（502，按引擎故障 fail-closed） |

</details>

### O3. 自定义 TCP → ClamAV 守护进程（病毒扫描）

主坐标：`infrastructure/filescan/ClamAvScanner.java#scan`

> 经 `xyz.capybara:clamav-client` 2.1.2 将暂存文件流送 ClamAV 扫描，返回威胁描述或 null（null = 干净）。

**关键事实**
- 目标 `${CLAMAV_HOST:localhost}:${CLAMAV_PORT:3310}`（`application.yml:24-27`）。
- 使用 INSTREAM 扫描（依据：`ClamAvScanStage` 类注释「ClamAV INSTREAM 病毒扫描」+ `client.scan(InputStream)`；协议帧细节属客户端库内部，外部默认）。
- 库版本 2.1.2 无 socket 超时配置（类注释实证），挂起防护自建：扫描提交到每任务一个虚拟线程的执行器，限时等待，超时 `${CLAMAV_TIMEOUT_SECONDS:60}` 秒。
- 超时后任务线程被放弃（阻塞在 socket read 上不可中断），依赖 TCP 超时或服务端关闭回收。
- 熔断为 `io.github.resilience4j:resilience4j-circuitbreaker` 2.4.0 编程式使用：计数窗口 10、最小调用 5、失败率阈值 50% 打开、OPEN 保持 30s、半开放行 3 次。
- 熔断打开期间调用直接失败：`CallNotPermittedException` → 502「ClamAV 熔断中，扫描暂时不可用」。
- 命中时威胁消息格式 `ClamAV: {病毒名}: {文件名列表}`（`VirusFound` 拼接）。
- 本阶段无重试：一次调用，失败或超时即走失败处置。

<details><summary>失败表</summary>

| 症状 | 处置 |
|---|---|
| 连接失败/协议错误 | 致命-中止（502，见 I1；计入熔断失败率） |
| 扫描超时（60s） | 致命-中止（502；计入熔断失败率） |
| 熔断打开 | 致命-中止（502） |
| 调用被中断 | 致命-中止（502） |

</details>

### O4. 子进程 → yara CLI（自定义规则匹配）

主坐标：`infrastructure/filescan/YaraScanner.java#scan`

> 用 ProcessBuilder 启动 yara 二进制对暂存文件执行规则匹配，按 stdout 是否非空判定命中。

**关键事实**
- 完整命令：`{YARA_BINARY:yara} -s --fail-on-warnings {rulesPath} {暂存文件绝对路径}`（`YaraScanner.java:50-55`）。
- 规则 `classpath:rules/malware.yar` 启动时复制为临时文件（`deleteOnExit`）；当前含 2 条规则：`Ransomware_Note_Indicators`、`Suspicious_Office_Macro`（已读规则文件实证）。
- `zxf.virus-scan.yara.enabled=false` 是完整逃生舱：本阶段直接返回 null，且不解析规则路径。
- 双重超时：进程执行 60s（`timeout-seconds: 60`），退出后 stdout 排空宽限 5s（`OUTPUT_DRAIN_GRACE_SECONDS`）；finally 中 `destroyForcibly` 清理存活进程。
- stdout 读取移入独立虚拟线程，防 yara 挂起且不关闭输出时调用线程永久阻塞。
- 退出码语义：0 = 执行成功（是否命中看输出），非 0 = 执行错误（502，输出拼入仅进日志的 detail）。
- `redirectErrorStream(true)`：stderr 混入 stdout 参与判定；命中规则名取非缩进首列去重拼接，消息格式 `YARA: {规则名, …}`。

<details><summary>失败表</summary>

| 症状 | 处置 |
|---|---|
| 二进制不存在/启动失败 | 致命-中止（502，见 I1） |
| 进程超时（60s） | 致命-中止（502）-强杀进程树 |
| 非 0 退出码（含 --fail-on-warnings 触发） | 致命-中止（502，输出仅进日志） |
| 输出排空超时（5s） | 致命-中止（502） |

</details>

### O5. 进程内重要操作 → 文档威胁检测（进程内，无外部系统）

主坐标：`domain/filescan/documentthreat/DocumentThreatScanner.java#scan`

> 按 O2 探测的 MIME 路由三种浅检测（OOXML 条目名 / OLE2 宏 token / PDF 关键字），检出结果按 `ThreatKind` 分级处置。

**关键事实**
- 路由依据 `detectedMime`（内容探测结果），不信任客户端可控的扩展名；非文档格式直接 Passed。
- OOXML（docx/xlsx/pptx）：`ZipFile` 仅遍历条目名——含 `vbaproject.bin` 判 MACRO，含 `activex/` 判 ACTIVE_X。
- OOXML 中 ActiveX 先于 VBA 判定：防止两者并存时宏被 FLAG 策略放行从而绕过「ActiveX 恒拦截」（代码注释）。
- OLE2（doc/xls/ppt）：POI 5.5.1 `VBAMacroReader` 提取宏模块，命中 10 个可疑 token 之一（autoopen/shell/cmd.exe/powershell 等）才判威胁；仅含良性宏返回干净。
- PDF：1MB 分块 + 64B 重叠窗口 ISO-8859-1 扫描 `/javascript` `/openaction` `/launch`；对 FlateDecode 压缩对象流无效属已知限制（类注释），深度检测由 O4/O3 兜底。
- 分级规则在领域层：`ThreatKind.canBeFlagged()` 仅 MACRO 为 true；`macro-policy=FLAG` 时宏 → `Flagged`（放行打标 `macro-flagged: …`），默认 BLOCK 时宏 → `Infected`；ACTIVE_X 与 PDF_ACTION 不受策略影响恒 `Infected`。
- 解析异常统一翻译为 `scanFailed`（502），非业务拒绝。

<details><summary>失败表</summary>

| 症状 | 处置 |
|---|---|
| 文档解析异常（POI/Zip/PDF IO） | 致命-中止（502，见 I1） |
| OLE2 非 OLE2 文件（IllegalArgumentException） | 忽略-静默丢弃（按干净处理，双保险分支） |

</details>

### O6. 文件系统 → 扫描后处置（入库/隔离/清理）

主坐标：`application/filescan/FileScanService.java#handleResult`

> 按领域处置映射 `FileDisposition.of(status)` 执行暂存文件生命周期终局：CLEAN 入库、INFECTED 隔离、其余清理；调用方只拿结果不再接触临时文件。

**关键事实**
- 映射定义于 `domain/filescan/FileDisposition.java:12-18`：CLEAN→STORE、INFECTED→QUARANTINE、REJECTED/ERROR→DISCARD。
- STORE：复制为 `{storage}/{UUID}.{ext}`（默认 `./data/upload-storage`，`UPLOAD_STORAGE` 可覆盖），随后删除 staging；`details` 替换为存储文件名（不含路径，防内部路径外泄）。
- STORE 保留 threat 打标（`macro-flagged` / `scan-engine-degraded`）透传到响应 message。
- QUARANTINE：move 为 `{quarantine}/{UUID}.quarantined`（默认 `./data/upload-quarantine`，目录权限收敛 POSIX `rwx------`）；move 失败降级 copy+delete，仍失败仅 ERROR 日志、文件滞留 staging。
- DISCARD：`deleteQuietly` 删除 staging，失败仅 WARN 不抛出。
- fail-open 降级入库（引擎故障 + OPEN）复用 STORE 路径并打标 `scan-engine-degraded`；降级入库失败转 502。
- 业务拒绝（非 SCAN_ENGINE_ERROR）不受 fail-open 影响：删除 staging 后原异常上抛（`handleEngineFailure` 的 ErrorCode 守卫）。

<details><summary>失败表</summary>

| 症状 | 处置 |
|---|---|
| STORE 复制失败（IO） | 致命-中止（502，见 I1） |
| QUARANTINE move 跨文件系统失败 | 降级-跳过（copy+delete 兜底） |
| QUARANTINE copy+delete 再失败 | 忽略-记日志（文件滞留 staging，告警并入 ERROR 日志） |
| DISCARD 删除失败 | 忽略-记日志（WARN） |
| fail-open 降级入库失败 | 致命-中止（502「fail-open 降级存储失败」） |

</details>

## 补充说明

- 背压闸门与线程模型：同步与异步端点共用 `FileScanService` 构造的同一 `Semaphore(maxConcurrentScans=16)`；`spring.threads.virtual.enabled=true` 下请求线程与 `@Async` 均为虚拟线程，许可耗尽时挂起等待而非拒绝。
- 管道总开关：`zxf.virus-scan.enabled=false` 时 `ScanPipeline.execute` 直通返回 CLEAN（`domain/filescan/ScanPipeline.java:24-27`），大小/扩展名预检（I1 步骤 3）仍执行。
- 错误响应与日志分级：引擎故障 ERROR 级附完整堆栈，4xx 业务拒绝 WARN 无堆栈（`GlobalExceptionHandler#handleBusiness`）；静态调查结论，未运行验证，行为证据见 `src/test/java/zxf/upload/rest/fileupload/FileUploadControllerTest.java`（200/400/422/502/缺 part 400）与 `src/test/java/zxf/upload/EicarScanIT.java`（Testcontainers 真实 ClamAV：EICAR → 422、干净文件 → 200）。

## 运行元数据（非报告内容）

实际读取过的项目源码文件（含配置与测试，去重，绝对路径）：

1. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/FileUploadApplication.java
2. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/ApplicationService.java
3. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/FileScanService.java
4. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/ScanPipelineConfig.java
5. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/stage/FileTypeScanStage.java
6. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/stage/ClamAvScanStage.java
7. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/stage/YaraScanStage.java
8. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/filescan/stage/DocumentThreatScanStage.java
9. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/fileupload/UploadFileCommand.java
10. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/fileupload/UploadFileCommandChecker.java
11. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/application/fileupload/SyncUploadCommandExecutor.java
12. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/ScanContext.java
13. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/ScanPipeline.java
14. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/ScanStage.java
15. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/ScanVerdict.java
16. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/FileDisposition.java
17. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/model/ScanResult.java
18. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/model/ScanStatus.java
19. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/model/TypeCheck.java
20. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/documentthreat/DocumentThreat.java
21. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/documentthreat/DocumentThreatScanner.java
22. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/filescan/documentthreat/ThreatKind.java
23. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/fileupload/UploadFile.java
24. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/domain/fileupload/UploadPolicy.java
25. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/config/AsyncConfig.java
26. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/config/FileScanDomainConfig.java
27. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/config/FileScanProperties.java
28. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/config/FileUploadDomainConfig.java
29. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/domain/BusinessException.java
30. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/domain/ErrorCode.java
31. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/filescan/ClamAvScanner.java
32. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/filescan/YaraScanner.java
33. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/fileupload/FileStorageService.java
34. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/fileupload/StagingService.java
35. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/io/FileUtils.java
36. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/infrastructure/rest/GlobalExceptionHandler.java
37. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/rest/fileupload/FileUploadController.java
38. /home/davis/Work/zxf-springboot-file-upload/src/main/java/zxf/upload/rest/fileupload/representation/UploadResponse.java
39. /home/davis/Work/zxf-springboot-file-upload/src/main/resources/application.yml
40. /home/davis/Work/zxf-springboot-file-upload/src/main/resources/rules/malware.yar
41. /home/davis/Work/zxf-springboot-file-upload/pom.xml
42. /home/davis/Work/zxf-springboot-file-upload/src/test/java/zxf/upload/application/fileupload/SyncUploadCommandExecutorTest.java
43. /home/davis/Work/zxf-springboot-file-upload/src/test/java/zxf/upload/rest/fileupload/FileUploadControllerTest.java
44. /home/davis/Work/zxf-springboot-file-upload/src/test/java/zxf/upload/EicarScanIT.java

总数：44 个。
