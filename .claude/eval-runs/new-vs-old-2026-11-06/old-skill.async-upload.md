# POST /api/files/async/upload 调查报告

## Executive Summary

- 功能：multipart 文件异步受理——预检与暂存落盘在请求线程内同步完成后立即返回 `202 + scanId`，四阶段扫描管道（类型校验 → ClamAV → YARA → 文档威胁）在虚拟线程后台执行，客户端经 `GET /api/files/async/scan/{scanId}` 轮询终态
- 外部依赖：O3（自定义 TCP → ClamAV clamd `${CLAMAV_HOST:localhost}:${CLAMAV_PORT:3310}`）、O4（子进程 → yara CLI）；本机文件系统：O1（staging 落盘）、O6（正式存储/隔离区）；进程内：O2、O5
- 注意：
  1. 轮询结果仅存进程内 Caffeine 缓存（TTL 30 分钟、上限 10 万条）且无 404——结果淘汰、服务重启或未知 scanId 一律返回 SCANNING，客户端无法区分"进行中"与"已丢失"，会无限轮询
  2. 受理后扫描 at-most-once：`@Async` fire-and-forget 无重试、无持久化，进程崩溃即丢失（202 已发出但无任何痕迹）
  3. 受理与轮询两端点均无鉴权，也无幂等约束（重复上传各自生成 scanId 独立扫描）

> 坐标相对于 `src/main/java/zxf/upload/`。

## Inbound

### I1. 同步 HTTP → POST /api/files/async/upload（multipart 异步受理，202 + scanId）

| 鉴权 | 主坐标 |
|---|---|
| 无 | FileUploadController.java:37-41 |

> 受理上传：预检 → 落盘 → 发 scanId 即返回 202，扫描转后台。

**关键事实**
- 预检与落盘在 202 前同步完成，仅扫描阶段异步（AsyncUploadCommandExecutor.java:24-30）
- multipart 容器级 100MB 上限先于业务预检拦截（413，与业务 400 分开归因）

<details><summary>处理逻辑 / Request·Response schema / 错误映射</summary>

**处理逻辑**
- ① 构造 Command：取 multipart 原始文件名、大小与内容流
- ② 预检空文件、100MB 上限与扩展名白名单
- ③ 调用 O1 落盘暂存区（UUID 重命名）
- ④ 生成 scanId（UUID 随机）
- ⑤ 分发后台扫描（异步：不等结果，虚拟线程执行 O2→O6）

**Request**
- Content-Type: multipart/form-data，单一 part `file`（filename={originalFilename}，客户端可控）
- Body 为原始字节流（非 JSON）；originalFilename → O1 暂存命名的扩展名来源、O2 扩展名一致性比对；目录部分由 `UploadFile.cleanedName()` 剥离（防路径混淆）
- Schema 校验：无 Bean Validation，受理边仅命令式 `UploadPolicy` 预检（空文件 / 大小 / 非空扩展名白名单；无扩展名放行）；文件内容真实性由管道 O2 的 MIME 白名单把关

**Response — Success（202 Accepted）**
```json
{"scanId":"{uuid}","status":"SCANNING","message":"扫描进行中"}
```
- `scanId` ← Executor 生成（UUID）；`filePath` 为 null 被 `@JsonInclude(NON_NULL)` 省略
- 异步受理形态：终态不经本端点返回，查询走 I2；进行中占位 SCANNING，结果保留与淘汰语义见 I2

**错误映射表**（受理通道；202 之后的失败不走 HTTP 错误码，见 I2）

| 场景 | 来源 | 对外响应 | 泄漏 |
|---|---|---|---|
| 空文件 / 超 100MB / 扩展名不在白名单 | 本地校验 | `400 FILE_REJECTED`（文案含原因） | 否 |
| 超容器 multipart 上限 100MB | 平台限制 | `413 FILE_TOO_LARGE` | 否 |
| 缺 file part / 非 multipart 请求 | 平台限制 | `400 FILE_REJECTED` | 否 |
| 暂存落盘 IO 失败（磁盘满等，经 O1） | 下游传播 | `400 FILE_REJECTED`（固定通用文案） | 否（内部路径只进日志） |
| 未捕获异常 | 未捕获 | `500 INTERNAL_ERROR`（固定文案） | 否 |
</details>

### I2. 同步 HTTP → GET /api/files/async/scan/{scanId}（轮询终态）

| 鉴权 | 主坐标 |
|---|---|
| 无 | ScanResultController.java:27 |

> 查询扫描当前状态：命中终态即返回，否则 SCANNING 占位。

**关键事实**
- 202 后一切终态走 body 的 `status` 字段：INFECTED / REJECTED / ERROR 均 200（无 HTTP 错误码）
- 读链路零出站：仅查进程内 Caffeine 缓存；未命中一律 SCANNING 占位（AsyncScanProcessor.java:48-51）

<details><summary>轮询语义 / Response schema / 错误映射</summary>

**轮询语义**
- 进行中占位：`SCANNING` + "扫描进行中"（200，与 I1 的 202 回执 body 同构）
- 结果保留：Caffeine `expireAfterWrite` 30 分钟（`zxf.virus-scan.result-retention-minutes`）+ `maximumSize` 100_000；纯进程内，不持久化
- 淘汰 / 重启 / 未知 scanId：行为见 Summary 注意 1

**Response — Success（200）**
```json
{"scanId":"{uuid}","status":"CLEAN","message":"文件安全","filePath":"{uuid}.{ext}"}
{"scanId":"{uuid}","status":"INFECTED","message":"检测到威胁: {threat}"}
{"scanId":"{uuid}","status":"REJECTED","message":"文件被拒绝: {reason}"}
{"scanId":"{uuid}","status":"ERROR","message":"扫描引擎暂时不可用，请稍后重试"}
```
- SCANNING 形态同 I1 回执；`filePath` ← O6 入库返回的存储文件名（仅 CLEAN 出现）
- `threat` ← O3（"ClamAV: {病毒名}"）/ O4（"YARA: {规则名}"）/ O5（威胁描述）；`reason` ← O2 拒绝原因
- CLEAN 打标透传：`message` 变为"文件安全（{threat}）"（macro-flagged / scan-engine-degraded）

**错误映射表**

| 场景 | 来源 | 对外响应 | 泄漏 |
|---|---|---|---|
| scanId 非 UUID 格式（`@Pattern` 方法级校验） | 本地校验 | `400 VALIDATION_ERROR` | 否 |
| 无匹配路由 | 平台限制 | `404 NOT_FOUND` | 否 |
</details>

## Outbounds（O1 在受理请求线程；O2→O6 于 @Async 虚拟线程；管道 O2→O5 顺序执行、首个非 Passed 结论短路；Semaphore(16) 为同步/异步统一背压闸门）

### O1. 文件系统 → staging 暂存落盘（受理线程内同步）

主坐标：StagingService.java:42

> 把上传字节流落盘暂存区并返回路径，失败自清理。

**关键事实**
- 暂存名 = `{uuid}.{ext}`，扩展名取自客户端原始文件名（目录部分已剥离）
- 落盘失败删除残留文件后对外 400 通用文案，完整路径只进日志（映射见 I1 错误表）

### O2. 进程内（Apache Tika + commons-compress）→ MIME 探测 + ZIP 炸弹防护，无外部系统（管道阶段 1）

主坐标：FileTypeScanStage.java:43

> 按内容探测 MIME 并做白名单与扩展名一致性判定，zip 另做解压炸弹四限检查。

**关键事实**
- detectedMime 单次探测写入管道上下文，O5 复用免重复探测
- ZIP 检查不递归解压内层 zip，嵌套威胁由 O3/O4 原始字节扫描兜底
- header 声明大小不可信：全部条目实际解压逐字节计数（条目数 1 万 / 单条 100MB / 累计 1GB / 压缩比 100:1 四限）

<details><summary>处理逻辑 / 失败表</summary>

**处理逻辑**
- ① Tika 探测文件内容 MIME（进程内单次）
- ② MIME 白名单与扩展名一致性判定（jpg/jpeg、txt/csv 互认）
- ③（条件：detected=application/zip）流式解压逐字节四限检查
- ④ 通过则 detectedMime 写入上下文供 O5 复用

**失败表**

| 症状 | 处置 |
|---|---|
| Tika 探测 IO 失败 | 致命-中止（转 O6 fail 策略） |
| zip 损坏 / 声明值与实际不符致流错位 | 致命-中止（转 O6 fail 策略） |
</details>

### O3. 自定义 TCP → ClamAV clamd（capybara 客户端，INSTREAM；管道阶段 2）

主坐标：ClamAvScanner.java:76

> 全文件流式送 ClamAV 扫毒：null=干净，非 null=病毒描述。

**关键事实**
- 客户端库（clamav-client 2.1.2）无 socket 超时——超时靠提交虚拟线程限时等待模拟（默认 60s），被放弃任务阻塞至 TCP 层自然回收（ClamAvScanner.java:108-114）
- Resilience4j 熔断：计数窗口 10、失败率 50% 打开，OPEN 30s、半开放行 3 次；熔断中直接快速失败

<details><summary>Request / 失败表</summary>

**Request**
- TCP `${CLAMAV_HOST:localhost}:${CLAMAV_PORT:3310}`，clamd INSTREAM 会话，payload = staged 文件全量字节（`client.scan(InputStream)`）
- 超时 `${CLAMAV_TIMEOUT_SECONDS:60}` 秒

**失败表**

| 症状 | 处置 |
|---|---|
| 引擎不可达 / 协议错误 / 扫描超时 / 熔断中 | 致命-中止（转 O6 fail 策略） |
</details>

### O4. 子进程 → yara CLI（管道阶段 3）

主坐标：YaraScanner.java:45

> 外部 yara 进程按规则集匹配：命中返回规则名，输出为空即干净。

**关键事实**
- `yara.enabled=false` 是完整逃生舱：不解析规则路径（规则文件缺失也能启动），扫描直通
- 命中判定看 stdout 是否为空——退出码 0 仅表执行成功，不表示是否命中

<details><summary>命令行与参数 / 输出解析 / 失败表</summary>

**命令行**
```bash
yara -s --fail-on-warnings {rulesPath} {stagedFile 绝对路径}
# rulesPath 默认 classpath:rules/malware.yar，启动时解压为临时文件
```

**输入与环境**
- 环境变量全继承；stdout/stderr 合流后由独立虚拟线程逐行读取（防子进程挂起堵死调用线程）

**输出解析与退出码**
- 非缩进行首列为"规则名 文件路径"，取规则名去重拼接为 threat；退出码非 0 = 执行错误

**失败表**

| 症状 | 处置 |
|---|---|
| 进程超时 60s | 致命-中止（强杀进程树） |
| 启动失败 / 退出码非 0 | 致命-中止（转 O6 fail 策略） |
| 退出后输出排空超时 5s | 致命-中止 |
</details>

### O5. 进程内（Apache POI / 流式扫描）→ 文档威胁检测，无外部系统（管道阶段 4）

主坐标：DocumentThreatScanner.java:54

> 对文档类文件做宏/ActiveX/PDF 危险动作检测，按威胁类别分级。

**关键事实**
- 宏策略 FLAG 仅放行 MACRO 类（`ThreatKind.canBeFlagged()`）；ActiveX 与 PDF 危险动作不受策略影响始终拦截
- OOXML 判定 ActiveX 先于 VBA：防两者并存时 FLAG 放行宏导致"始终拦截"被绕过
- PDF 关键字扫描对 FlateDecode 压缩对象流无效（已知限制，深度检测靠 O3/O4 兜底）

<details><summary>处理逻辑</summary>

**处理逻辑**
- ①（条件：非文档 MIME）直通放行
- ② 按内容 MIME 分支扫描：OOXML 条目名 / OLE2 宏提取 / PDF 分块关键字
- ③（条件：MACRO 且策略 FLAG）放行并打标 macro-flagged，终止管道
- ④ 其余威胁判 INFECTED 拦截；OLE2 宏未命中可疑 token 视为良性放行
</details>

### O6. 文件系统 → 扫描后处置：入库 / 隔离 / 清理（管道后，按处置映射三向）

主坐标：FileScanService.java:81

> 按扫描状态执行文件生命周期：CLEAN 入库、INFECTED 隔离、其余删除暂存。

**关键事实**
- 隔离两跳均失败时威胁文件滞留 staging，仅记 ERROR 不告警（FileStorageService.java:57-63）
- fail-open 降级仅适用于 `SCAN_ENGINE_ERROR`，业务拒绝被降级放行将是安全漏洞故不适用（FileScanService.java:117-131）
- 入库返回存储文件名（`{uuid}.{ext}`）不含路径，内部目录不外泄

<details><summary>处理逻辑 / 操作序列 / 失败表</summary>

**处理逻辑**
- ① 查处置表：CLEAN→STORE / INFECTED→QUARANTINE / 其余→DISCARD
- ② STORE：复制入库换 UUID 名，删暂存，details 换为存储文件名
- ③ QUARANTINE：移入隔离区重命名 `{uuid}.quarantined`（目录权限 rwx------）
- ④ DISCARD：静默删除暂存文件
- ⑤（条件：引擎故障且 fail-strategy=OPEN）降级入库并打标 scan-engine-degraded

**操作序列**
```text
copy   {staging}/{uuid}.{ext} → {storage}/{uuid}.{ext}              ← CLEAN（filePath ← 存储文件名）
move   {staging}/{uuid}.{ext} → {quarantine}/{uuid}.quarantined     ← INFECTED
delete {staging}/{uuid}.{ext}                                       ← REJECTED/ERROR/受理失败清理
```

**失败表**

| 症状 | 处置 |
|---|---|
| 隔离 move 跨文件系统失败 | 降级-copy+delete |
| 降级 copy 仍失败 | 忽略-记日志（文件滞留 staging） |
| fail-open 降级存储失败 | 致命-中止（轮询 ERROR） |
</details>

## 补充说明

- **scanId 数据主线**：I1 生成（UUID）→ 202 回执字段 → AsyncScanProcessor 结果缓存 key（Caffeine，进程内）→ I2 路径参数按 key 查询；全链路不落任何持久化存储
- **管道总开关**：`zxf.virus-scan.enabled=false` 时 O2→O5 整体跳过、直接 CLEAN 入库（预检与 O1 仍执行）
- **fail 策略与终态映射**：引擎故障经 O2/O3/O4 抛 `SCAN_ENGINE_ERROR`，CLOSED（默认）在异步路径终态为 ERROR（I2）；OPEN 为 CLEAN + 打标。异步受理的引擎故障不产生 HTTP 502（502 语义仅存在于同步端点 /sync/upload）

## 运行元数据（非报告内容）

实际读取过的项目源码文件（含配置与测试，去重）：

1. src/main/java/zxf/upload/FileUploadApplication.java
2. src/main/java/zxf/upload/application/ApplicationService.java
3. src/main/java/zxf/upload/application/filescan/AsyncScanProcessor.java
4. src/main/java/zxf/upload/application/filescan/FileScanService.java
5. src/main/java/zxf/upload/application/filescan/PollScanResultExecutor.java
6. src/main/java/zxf/upload/application/filescan/ScanPipelineConfig.java
7. src/main/java/zxf/upload/application/filescan/stage/ClamAvScanStage.java
8. src/main/java/zxf/upload/application/filescan/stage/DocumentThreatScanStage.java
9. src/main/java/zxf/upload/application/filescan/stage/FileTypeScanStage.java
10. src/main/java/zxf/upload/application/filescan/stage/YaraScanStage.java
11. src/main/java/zxf/upload/application/fileupload/AsyncUploadCommandExecutor.java
12. src/main/java/zxf/upload/application/fileupload/UploadFileCommand.java
13. src/main/java/zxf/upload/application/fileupload/UploadFileCommandChecker.java
14. src/main/java/zxf/upload/domain/filescan/FileDisposition.java
15. src/main/java/zxf/upload/domain/filescan/ScanContext.java
16. src/main/java/zxf/upload/domain/filescan/ScanPipeline.java
17. src/main/java/zxf/upload/domain/filescan/ScanStage.java
18. src/main/java/zxf/upload/domain/filescan/ScanVerdict.java
19. src/main/java/zxf/upload/domain/filescan/documentthreat/DocumentThreat.java
20. src/main/java/zxf/upload/domain/filescan/documentthreat/DocumentThreatScanner.java
21. src/main/java/zxf/upload/domain/filescan/documentthreat/ThreatKind.java
22. src/main/java/zxf/upload/domain/filescan/model/ScanResult.java
23. src/main/java/zxf/upload/domain/filescan/model/ScanStatus.java
24. src/main/java/zxf/upload/domain/filescan/model/TypeCheck.java
25. src/main/java/zxf/upload/domain/fileupload/UploadFile.java
26. src/main/java/zxf/upload/domain/fileupload/UploadPolicy.java
27. src/main/java/zxf/upload/infrastructure/config/AsyncConfig.java
28. src/main/java/zxf/upload/infrastructure/config/FileScanDomainConfig.java
29. src/main/java/zxf/upload/infrastructure/config/FileScanProperties.java
30. src/main/java/zxf/upload/infrastructure/config/FileUploadDomainConfig.java
31. src/main/java/zxf/upload/infrastructure/domain/BusinessException.java
32. src/main/java/zxf/upload/infrastructure/domain/ErrorCode.java
33. src/main/java/zxf/upload/infrastructure/filescan/ClamAvScanner.java
34. src/main/java/zxf/upload/infrastructure/filescan/YaraScanner.java
35. src/main/java/zxf/upload/infrastructure/fileupload/FileStorageService.java
36. src/main/java/zxf/upload/infrastructure/fileupload/StagingService.java
37. src/main/java/zxf/upload/infrastructure/io/FileUtils.java
38. src/main/java/zxf/upload/infrastructure/rest/GlobalExceptionHandler.java
39. src/main/java/zxf/upload/rest/fileupload/FileUploadController.java
40. src/main/java/zxf/upload/rest/fileupload/representation/UploadResponse.java
41. src/main/java/zxf/upload/rest/filescan/ScanResultController.java
42. src/main/resources/application.yml
43. src/test/java/zxf/upload/EicarScanIT.java
44. src/test/java/zxf/upload/application/filescan/AsyncScanProcessorTest.java
45. src/test/java/zxf/upload/application/fileupload/AsyncUploadCommandExecutorTest.java
46. src/test/java/zxf/upload/rest/filescan/ScanResultControllerTest.java
47. src/test/java/zxf/upload/rest/fileupload/FileUploadControllerTest.java
48. pom.xml

总计：48 个文件。
