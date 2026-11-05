# POST /api/files/async/upload 调查报告

> 教学范例及本地金标准来源：文件上传安全扫描服务，源码版本 `cc0917ce78e4766069557a387eabd73fc058b71f`。坐标相对于 `../../../../src/main/java/zxf/upload`；配置位于 `src/main/resources/`。

## Executive Summary

- 调查范围：静态调查异步上传及其独立轮询入口，覆盖本服务直接交互。
- 功能：预检并暂存上传文件，立即返回 `202 + scanId`；后台执行类型、ClamAV、YARA、文档威胁四阶段管道，终态经 I2 查询。
- 外部依赖：O1／O6（文件系统）、O3（ClamAV TCP）、O4（YARA 子进程）；进程内：O2、O5。
- 注意：无鉴权和幂等；结果缓存 TTL 30 分钟，淘汰或重启后与进行中不可区分；引擎故障默认 fail-closed，OPEN 模式才允许降级入库。

## Inbound

### I1. 同步 HTTP → POST /api/files/async/upload（202 异步受理）

| 鉴权 | 主坐标 |
|---|---|
| 无 | `rest/fileupload/FileUploadController.java#asyncUpload` |

> 预检 → O1 暂存 → 生成 scanId 并异步执行 O2–O6 → 返回受理凭证。

**关键事实**
- multipart 平台限制与业务预检分开：前者可映射 `413`，后者映射 `400`。
- 无扩展名文件可通过受理预检，但会在 O2 的类型一致性检查中被拒绝。

<details><summary>Request、Response、处理逻辑与受理错误</summary>

**Request**

- Content-Type：`multipart/form-data`；part 名 `file`。
- 数据载体：文件字节流 → O1；`originalFilename` 和 `size` 用于预检、清理和扩展名处理。
- Schema 校验：无独立请求 schema；框架处理 multipart 结构，业务策略执行空文件、大小和扩展名预检。

**处理逻辑**

1. 执行空文件、大小和扩展名预检。
2. 调用 O1 将文件写入 staging。
3. 生成 scanId，异步执行 O2 → O3 → O4 → O5 → O6，不等待扫描结果。
4. 返回 `202` 和受理凭证。

**Response — Success**

Status：`202`。Body 字段契约：`scanId`、`status`、`message`；`filePath` 在受理时为 null 并省略。

```json
{
  "scanId": "{uuid}",
  "status": "SCANNING",
  "message": "扫描进行中"
}
```

`scanId → I2` 的路径参数。

**受理错误映射**

| 场景 | 来源 | 对外响应 | 泄漏 |
|---|---|---|---|
| 缺 file part／非 multipart | 平台或框架 | `400 FILE_REJECTED` | 否 |
| multipart 超限 | 平台或框架 | `413 FILE_TOO_LARGE` | 否 |
| 空文件／业务超限／扩展名拒绝 | 本地校验 | `400 FILE_REJECTED` | 否 |
| 流打开或 staging IO 失败 | 业务处理／O1 传播 | `400 FILE_REJECTED` | 内部路径仅日志 |
| 未捕获异常 | 未捕获 | `500 INTERNAL_ERROR` | 否 |

错误翻译证据：`infrastructure/rest/GlobalExceptionHandler.java`。

</details>

### I2. 同步 HTTP → GET /api/files/async/scan/{scanId}（轮询）

| 鉴权 | 主坐标 |
|---|---|
| 无 | `rest/filescan/ScanResultController.java#scanStatus` |

> 返回扫描终态；格式合法但未命中缓存的 scanId 同样返回 SCANNING 占位。

**关键事实**
- scanId 格式非法返回 `400`；不存在、已淘汰或服务重启后丢失均不返回 `404`。
- 结果使用进程内 Caffeine 缓存，TTL 30 分钟且有容量上限。

<details><summary>轮询语义、Response 与终态映射</summary>

**轮询语义**

- 进行中或占位：`SCANNING`。
- 终态：`CLEAN`、`INFECTED`、`REJECTED`、`ERROR`。
- 淘汰或重启后无法与进行中区分，客户端需要自己的放弃或超时策略。

**Response — CLEAN 结构示意**

```json
{
  "scanId": "{uuid}",
  "status": "CLEAN",
  "message": "文件安全",
  "filePath": "{uuid}.{ext}"
}
```

`filePath ← O6` 入库文件名，仅 CLEAN 出现；其他终态不返回内部路径。

**终态映射**

| 场景 | 来源 | 轮询语义 |
|---|---|---|
| 类型不符／ZIP 炸弹等业务拒绝 | O2 或文档策略 | `200 status=REJECTED` |
| 检出威胁 | O3／O4／O5 | `200 status=INFECTED` |
| 引擎故障且 fail-closed | O3／O4 等 | `200 status=ERROR` |
| 引擎故障且 fail-open | O6 降级 | `200 status=CLEAN` 并携带降级标记 |

</details>

## Operations（O2–O5 顺序短路；O2–O6 后台异步执行）

### O1. 文件系统 → staging 暂存

主坐标：`infrastructure/fileupload/StagingService.java#stage`

> 将 I1 文件流写为 UUID 命名的暂存文件，后续操作只传递 Path。

<details><summary>操作与失败处置</summary>

```text
write  {staging}/{uuid}.{ext} ← I1 file part
delete {staging}/{uuid}.{ext} ← 写入失败时清理
```

- 写入失败：清理残留并向 I1 传播，由受理错误映射转为 `400`。
- 文件名只保留原扩展名，客户端原始路径不进入存储路径。
</details>

### O2. 进程内 → 类型校验与 ZIP 炸弹防护（Tika + commons-compress，无外部系统）

主坐标：`application/filescan/stage/FileTypeScanStage.java#scan`

> 探测真实 MIME、校验扩展名一致性并对 ZIP 实际解压计数；`detectedMime → O5`。

**关键事实**
- ZIP 限制覆盖条目数、单 entry、累计解压量和压缩比；不信任 header 声明值。
- 不递归解压；失败按当前管道策略进入业务拒绝或引擎故障通道。

### O3. 自定义 TCP → ClamAV clamd（INSTREAM）

主坐标：`infrastructure/filescan/ClamAvScanner.java#scan`

> 流式送检暂存文件；检出威胁进入 INFECTED，引擎故障进入 fail-strategy。

**关键事实**
- 当前客户端未提供 socket 超时配置，应用层使用 60s 限时和熔断兜底。
- 熔断、超时和连接失败证据均以当前 `ClamAvScanner` 与依赖版本为准。

<details><summary>协议与数据流</summary>

```text
C: zINSTREAM\0
C: <4-byte BE 长度><数据块>
C: <4-byte 0>
S: stream: OK | stream: {病毒名} FOUND
```

- 文件字节 ← O1 staging。
- 检出描述 → I2 的 INFECTED message；干净结果 → O4。
</details>

### O4. 子进程 → YARA CLI

主坐标：`infrastructure/filescan/YaraScanner.java#scan`

> 使用规则文件扫描 O1 暂存文件；输出非空表示命中，超时或执行失败进入引擎故障策略。

<details><summary>命令与退出语义</summary>

```bash
yara -s --fail-on-warnings {rulesPath} {stagedFile}
```

- 退出码 `0` 只表示执行成功，不表示未命中。
- `enabled=false` 时跳过本阶段；超时强杀进程并读取输出。
</details>

### O5. 进程内 → 文档威胁检测（POI + JDK，无外部系统）

主坐标：`domain/filescan/documentthreat/DocumentThreatScanner.java#scan`

> 按 O2 的 MIME 路由 OOXML、OLE2、PDF 检测；宏可按策略打标，ActiveX 和 PDF 危险动作始终拦截。

**关键事实**
- `MACRO + FLAG` 可形成带标记的 CLEAN；其他危险类别不走该放行策略。
- PDF 关键字扫描不能覆盖 FlateDecode 压缩对象流，属于已知检测边界。

### O6. 文件系统 → 入库、隔离或清理

主坐标：`application/filescan/FileScanService.java#handleResult`

> CLEAN 入库、INFECTED 隔离、REJECTED／ERROR 清理；入库文件名 → I2 `filePath`。

<details><summary>操作序列与降级</summary>

```text
copy   {staging}/{uuid}.{ext} → {storage}/{uuid}.{ext}
move   {staging}/{uuid}.{ext} → {quarantine}/{uuid}.quarantined
delete {staging}/{uuid}.{ext}
```

- 跨文件系统 move 失败时降级 copy + delete；再失败仅记录错误并可能遗留 staging 文件。
- fail-open 只允许引擎故障降级入库；业务拒绝和已检出威胁不得借此放行。
</details>

## 补充说明

- 同步上传端点复用 O1–O6，但在请求线程内完成并通过 HTTP 错误表达终态；它是相关入口，不并入本次 I1。
- `zxf.virus-scan.enabled=false` 可旁路 O2–O5；受理预检与 O1／O6 仍执行。
