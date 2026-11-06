# 范例：不合格反例 + 三份调查报告（真实项目 ×3）

> 案例选自两个公开项目（Apache-2.0；案例 A 事实于 2026-11、案例 B 于 2026-10 按 main/master 分支核实）：
>
> - **案例 A · 分布式形态** — [OpenTelemetry Astronomy Shop](https://github.com/open-telemetry/opentelemetry-demo)（CNCF 官方 demo）：电商下单链路，gRPC × 4、HTTP 下游 × 3、Kafka × 1，展示循环调用、容错分级、错误跨层传播、共性风险上提 Summary
> - **案例 B · 单体 CRUD 形态** — [Spring Petclinic REST](https://github.com/spring-petclinic/spring-petclinic-rest)（Spring 官方 sample）：典型 REST + JPA，覆盖 SQL 出站、认证/校验错误映射、contract-first 入口定位
> - **案例 C · 异步受理形态** — 一个文件上传安全扫描服务（Spring Boot 4 + 虚拟线程）：202 受理 + 轮询 + 四阶段扫描管道，覆盖错误通道分段、轮询语义（SCANNING 占位 / TTL 淘汰）、管道短路链标注、文件系统与进程内出站
>
> 新调查对齐此密度即可；模板、字段规范与配额见 `formats.md`。范例内嵌报告标题降一级展示（区块 `###`、卡片 `####`），独立报告中应为 `##` / `###`；折叠区形态环境自适应——本文件按 GFM 环境用 `<details>`（GitHub 等查看器中折叠），终端输出改用 `▸` 标记行 + 2 空格缩进（表格与代码块顶格）。

***

## 先看反面：六类不合格样例

> ❌ **散文化**：系统接收下单请求，参数有 skuId 和数量，会校验参数，没登录会返回 401，库存不够返回 409，下游挂了返回 503。——无卡片、无表格、无坐标，无法逐条审阅，也无法核实。

> ❌ **结构正确但事实编造**：卡片齐整、表格规范，但 SQL 是按方法名猜的（真实取材点在别处的 JPQL / Repository 派生查询）、「超时 2s」无配置实证（代码根本未设置，应作「会猜错的无」写关键事实）、坐标指向不存在的行——结构救不了事实编造。

> ❌ **空壳填表**：幂等设计「未实现」、重试策略「无」、错误映射「无特殊」——各占 2–3 行的信息量为零段落。正确做法是整段不出现（formats.md §一省略三档）。

> ❌ **证据泛滥**：主路径贴函数体源码、处理逻辑每步尾随 `file:line`、「无超时」在每个 O 卡重复一遍——结论被证据淹没。正确做法：每卡 1 个主坐标 + ≤ 2 个风险坐标，共性（如「全部出站均无超时」）上提 Summary 写一次。

> ❌ **数据盲区**：卡片齐全、控制流清晰，但 Request / Response Body 只写「JSON body」「含订单信息」一笔带过，无真实字段、无流向标注——数据流动是报告第一位，Body 缺实证即不合格。

> ❌ **主线迷失**：报告大半篇幅在追 `@ControllerAdvice` 源码、连接池内部策略、AOP 切面链——横切关注点与底层机制喧宾夺主，Happy flow 的调用链与数据流反而不清。——主线优先（SKILL.md 原则三）：预算优先花在直接引用链 + Happy flow 上，横切 / 底层必要时可放弃、无需声明；仅实证改变本链路行为（短路 / 改写请求响应 / 隐式出站）才纳入。

***

## 案例 A：电商下单 — checkout 服务 PlaceOrder（OpenTelemetry Astronomy Shop）

调查对象是 checkout 服务（Go）的 `PlaceOrder`；frontend 的 HTTP `POST /api/checkout` 是上游入口，客户端可见的错误翻译发生在 frontend（见补充说明）。

### Executive Summary

- 功能：结算下单——取购物车 → 逐商品组装报价 → 运费换汇 → 扣款 → 发货 → 收尾三连（清购物车 / 邮件 / 发事件）
- 外部依赖：O1 取购物车、O2 逐商品组装报价（循环）、O3 运费报价、O4 运费换汇、O5 扣款、O6 发货（gRPC ×4 + HTTP ×2）、O7 清购物车、O8 确认邮件、O9 Kafka 订单事件
- 注意：O1–O7 无超时 / deadline 配置（默认无限等待）；O9 at-most-once（NoResponse），或丢消息；O7/O8/O9 失败不影响下单结果

> 坐标相对于仓库根；checkout 服务代码在 `src/checkout/`。

### Inbound

#### I1. gRPC Server → oteldemo.CheckoutService/PlaceOrder（调用方 frontend）

| 鉴权 | 主坐标 |
|---|---|
| 无 | src/checkout/main.go#PlaceOrder |

> 结算下单总编排：五个必经步 + 三个可失败收尾步，任一必经步失败即整体中止。

**关键事实**
- 无幂等——重放产生重复订单
- flag `EmitRawPii` 开启时明文卡号 / CVV 写入 span 属性（泄漏面在可观测后端）
- 客户端可见错误由 frontend 翻译为 422/500（细则见补充说明）src/frontend/pages/api/checkout.ts

<details><summary>处理逻辑 + Request/Response + 错误映射</summary>

**处理逻辑**
① 取购物车（O1）
② 组装报价（O2，循环：购物车 N 件 = 2N 次调用）
③ 运费报价与换汇（O3 → O4）
④ 扣款（O5，条件：flag `PaymentUnreachable` 开启时连坏地址——demo 故障注入）
⑤ 发货（O6）；收尾：清购物车（O7，失败静默）；邮件（O8，失败告警）；发事件（O9，at-most-once）

**Request**
- Body（数据承载）: `PlaceOrderRequest`（protobuf，schema 定义在 `demo.proto`）——真实字段 JSON 示意：
```json
{ "userId": "{id}", "userCurrency": "{code}", "email": "{email}",
  "address": { "streetAddress": "{…}", "city": "{…}", "…": "{…}" },
  "creditCard": { "creditCardNumber": "{…}", "creditCardExpirationMonth": "{m}", "creditCardExpirationYear": "{y}", "creditCardCvv": "{…}" } }
```
- Schema 校验: 无应用层校验（protobuf 解析失败即 gRPC error）

**Response — Success**：`OK`（frontend → HTTP 200）；`{ order: { orderId, shippingTrackingId, shippingCost, shippingAddress, items[] } }`（无 total——合计只进日志与 span）

**错误映射**
| 场景 | 来源 | 对外响应 | 泄漏 |
|---|---|---|---|
| O1–O6 任一失败 | 下游传播 | gRPC error，下单中止（frontend 翻译见补充说明） | 否 |
| O7/O8/O9 失败 | 下游传播 | 不影响响应 | 否 |

</details>

### Outbounds（O2 逐商品循环；O7/O8/O9 为收尾步，失败不影响下单结果；O9 at-most-once）

#### O1. gRPC → cart 服务（取购物车）

主坐标：`src/checkout/main.go#getUserCart`

> CALL GetCart 取 items 返回主流程。

<details><summary>Method 与 Message + 失败表</summary>

- `CALL oteldemo.CartService/GetCart`（同步 stub，addr=`${CART_ADDR}`）；request `{ userId }` → response `{ items[{ productId, quantity }] }`

**失败表**
| 症状 | 处置 |
|---|---|
| 任意错误 | 致命-中止（RPC error） |

</details>

#### O2. gRPC → product-catalog + currency（逐商品组装，循环 2N 次）

主坐标：`src/checkout/main.go#prepOrderItems`

> 每件商品取品名再换汇到用户币种，串行两连调。

<details><summary>Method 与 Message + 失败表</summary>

- a. `CALL oteldemo.ProductCatalogService/GetProduct`（addr=`${PRODUCT_CATALOG_ADDR}`）；`{ id }` → `{ name, priceUsd }`
- b. `CALL oteldemo.CurrencyService/Convert`（addr=`${CURRENCY_ADDR}`）；`{ from={priceUsd}, toCode={userCurrency} }` → Money `{ currencyCode, units, nanos }`

**失败表**
| 症状 | 处置 |
|---|---|
| 任一次失败 | 致命-中止（RPC error） |

</details>

#### O3. 下游 HTTP → shipping 服务（运费报价）

| 路由 | 主坐标 |
|---|---|
| `POST ${SHIPPING_ADDR}/get-quote` | src/checkout/main.go#quoteShipping |

> 取运费报价参与订单合计。

<details><summary>Request / Response + 失败表</summary>

- Headers: `Content-Type: application/json`；Body: `{ "address": {…}, "items": [ { "productId", "quantity" } ] }`
- Success: `200 { "cost_usd": { "units": {u}, "nanos": {n} } }`（JSON tag 原样 snake_case）

**失败表**
| 症状 | 处置 |
|---|---|
| 非 200 / 连接失败 | 致命-中止（RPC error） |

</details>

#### O4. gRPC → currency 服务（运费换汇）

主坐标：`src/checkout/main.go#convertCurrency`

> 运费 USD 换算到用户币种（与 O2b 同一 Convert 方法）。

<details><summary>Method 与 Message + 失败表</summary>

- `CALL oteldemo.CurrencyService/Convert`；`{ from={运费USD}, toCode={userCurrency} }` → Money `{ currencyCode, units, nanos }`

**失败表**
| 症状 | 处置 |
|---|---|
| 任意错误 | 致命-中止（RPC error） |

</details>

#### O5. gRPC → payment 服务（扣款，条件执行）

| 触发 | 主坐标 |
|---|---|
| 条件（flag `PaymentUnreachable`——demo 故障注入） | src/checkout/main.go#chargeCard |

> CALL Charge 扣款（商品 + 运费合计）；transactionId 只进日志与 span，不进订单回执。

<details><summary>Method 与 Message + 失败表</summary>

- `CALL oteldemo.PaymentService/Charge`（addr=`${PAYMENT_ADDR}`）；`{ amount, creditCard }` → `{ transactionId }`

**失败表**
| 症状 | 处置 |
|---|---|
| 任意错误 | 致命-中止（RPC error；对外翻译见补充说明） |

</details>

#### O6. 下游 HTTP → shipping 服务（发货下单）

| 路由 | 主坐标 |
|---|---|
| `POST ${SHIPPING_ADDR}/ship-order` | src/checkout/main.go#shipOrder |

> 发货下单，取 trackingId 进订单。

<details><summary>Request / Response + 失败表</summary>

- Body: `{ "address": {…}, "items": [{…}] }`
- Success: `200 { "tracking_id": "{trackingId}" }`（JSON tag 原样 snake_case）

**失败表**
| 症状 | 处置 |
|---|---|
| 非 200 / 连接失败 | 致命-中止（RPC error） |

</details>

#### O7. gRPC → cart 服务（清空购物车，收尾步）

主坐标：`src/checkout/main.go#emptyUserCart`

> 下单成功后清空购物车。

<details><summary>Method 与 Message + 失败表</summary>

- `CALL oteldemo.CartService/EmptyCart`；request `{ userId }`

**失败表**
| 症状 | 处置 |
|---|---|
| 任意错误 | 忽略-静默丢弃（`_ =` 显式丢弃，可重放补救） |

</details>

#### O8. 下游 HTTP → email 服务（订单确认邮件，收尾步）

| 路由 | 主坐标 |
|---|---|
| `POST ${EMAIL_ADDR}/send_order_confirmation` | src/checkout/main.go#sendOrderConfirmation |

> 发订单确认邮件（Body `{email, order}`）；响应无业务消费（源码不解析——告知性调用）。

**关键事实**
- 全链路唯一显式超时：1s（const `emailRequestTimeout`）

<details><summary>失败表</summary>

| 症状 | 处置 |
|---|---|
| 任意失败 | 忽略-记日志（WARN，不影响下单结果） |

</details>

#### O9. MQ → orders topic（Kafka，sarama 异步生产者）

主坐标：`src/checkout/main.go#sendToPostProcessor`

> 发送订单事件（protobuf OrderResult）；同步等本地确认，NoResponse 不等 broker。

**关键事实**
- `${KAFKA_ADDR}` 为空时整步跳过（条件执行）
- 携带 W3C traceparent header（消费侧 fraud-detection 以此关联链路）
- flag `KafkaQueueProblems` > 0 时额外注入 N 条过载消息（demo 故障注入）

<details><summary>消息定义 + 可靠性 + 失败表</summary>

- `SEND topic=${KAFKA_TOPIC:orders}`（无 key，不保序）；payload：OrderResult（protobuf：orderId / shippingTrackingId / shippingCost / shippingAddress / items）

**可靠性**
- sarama `RequiredAcks=NoResponse`——at-most-once，可能丢消息，下游需对账；无本地重试

**失败表**
| 症状 | 处置 |
|---|---|
| 发送失败 | 忽略-记日志（error 级，尽力而为） |

</details>

### 补充说明

- **错误跨层翻译**（frontend 实证，坐标见 I1 关键事实）：O5 失败且错误 details 以 `failed to charge card:` 开头 → `HTTP 422 { code: "PAYMENT_FAILED" }`（且排除 `Unavailable` / `Internal` 状态码）；其余任何失败 → `HTTP 500 { error: "Failed to place order." }`。客户端可见的错误分支要追到**发起翻译的那一层**——本 endpoint 的翻译层是 frontend，不是 checkout。
- **无本地存储**：cart 的 Redis、product-catalog 的自持有数据均属下游内部实现，不在本层展开（调查深度规则见 investigation-guide.md）。

***

## 案例 B：单体 CRUD — POST /api/owners（Spring Petclinic REST）

Spring 官方 sample，典型三层单体（Controller → Service → Spring Data JPA）；**contract-first**：Controller 实现的 `OwnersApi` 接口由 `openapi.yml` 经 openapi-generator 生成——route 注解搜不到时先查 spec 的 `operationId`。

### Executive Summary

- 功能：新增业主——Bean Validation 校验 → 鉴权 → JPA 落库 → 201 回执
- 外部依赖：O1（DB → owners 表，单条 INSERT）
- 注意：约束冲突映射为 **404**（项目特有，非想当然的 409）

> 坐标相对于 `src/main/java/`（openapi.yml 在 `src/main/resources/`）。

### Inbound

#### I1. 同步 HTTP → POST /api/owners（contract-first）

| 鉴权 | 主坐标 |
|---|---|
| Basic+RBAC | rest/controller/v1/OwnerRestControllerV1.java#addOwner |

> 新增业主：校验 → 落库（O1）→ 201 + Location。

**关键事实**
- 路由定义在 `openapi.yml` → 生成的 `OwnersApi` 接口（grep 注解定位不到）
- 无幂等——重放产生重复业主记录
- 全部错误经 `ExceptionControllerAdvice` 翻译（ProblemDetail RFC 9457）rest/advice/ExceptionControllerAdvice.java

<details><summary>Request / Response / 错误映射</summary>

**Request**
- Body（数据承载）: `{ "firstName", "lastName", "address", "city", "telephone" }`（真实字段实证）
- Schema 校验: OpenAPI 契约 → openapi-generator 生成 Bean Validation（机制整体引用，约束清单不展开）

**Response — Success**：`201`；`Location: /api/owners/{id}`（IDENTITY 主键回填，见 O1）；body `{ id, firstName, … }`

**错误映射**
| 场景 | 来源 | 对外响应 | 泄漏 |
|---|---|---|---|
| 未认证 / 角色不足 | 鉴权 | `401` / `403`（`@PreAuthorize` OWNER_ADMIN） | 否 |
| Bean Validation 失败 | 本地校验 | `400`（MethodArgumentNotValidException → ProblemDetail） | 否 |
| 唯一键等约束冲突（O1 传播） | 下游传播 | `404`（项目特有映射——如实记录，勿臆改为 409） | 否 |
| 未捕获异常 | 未捕获 | `500`（ProblemDetail 兜底） | 否 |

</details>

### Outbounds

#### O1. DB → owners 表（Spring Data JPA）

主坐标：`service/ClinicServiceImpl.java#saveOwner`

> 保存业主实体，IDENTITY 主键回填。

**关键事实**
- 无方法级 `@Transactional`——事务由 `JpaRepository.save()` 方法级默认事务承担（无注解 ≠ 无事务）

<details><summary>SQL + 参数绑定 + 失败表</summary>

```sql
INSERT INTO owners (first_name, last_name, address, city, telephone)
VALUES (?, ?, ?, ?, ?)     -- ClinicServiceImpl.saveOwner → ownerRepository.save()（JPA persist）派生
```

- 入参：Owner 实体五字段（@Table owners）；映射：persist 回填 `id` → I1 的 `Location` 头与 body（数据流向标注）
- 附注（不在本链路）：后续读路径参考 `ClinicServiceImpl#getOwner`（按 id 单行查询，形态与上同）

**失败表**
| 症状 | 处置 |
|---|---|
| `DataIntegrityViolationException` | 致命-中止（映射见 I1） |

</details>

***

## 案例 C：异步受理 + 轮询 — POST /api/files/async/upload（文件上传安全扫描服务）

一个文件上传安全扫描服务的异步受理端点，202 异步受理形态（formats.md §4.1）的完整示范：受理时错误走 HTTP 状态码，202 之后一切终态走轮询 body 的 `status`；代码事实于 2026-11 实证核实。

### Executive Summary

- 功能：异步受理文件上传——预检 → 暂存 → 后台四阶段安全扫描（类型 / ClamAV / YARA / 文档威胁）→ 按结论处置文件
- 外部依赖：O3（TCP → ClamAV daemon）、O4（子进程 → yara CLI）、O1/O6（文件系统：staging / 存储与隔离区）；进程内：O2、O5
- 注意：全链路无鉴权、无幂等（重复上传每次新 scanId）/ 引擎故障 fail-closed 默认拒绝（异步侧表现为轮询 ERROR）/ 轮询结果 TTL 30 分钟（I2：淘汰后与进行中不可区分）

> 坐标相对于该服务源码主包根（upload 模块；配置实际值见 application.yml）。

### Inbound

#### I1. 同步 HTTP → POST /api/files/async/upload（202 异步受理，multipart）

| 鉴权 | 主坐标 |
|---|---|
| 无 | rest/fileupload/FileUploadController.java#asyncUpload |

> 受理上传：预检 → 落盘（O1）→ 分发后台扫描（O2–O6），立即 202 返回受理凭证 scanId。

**关键事实**
- 大小双层限制分开归因：multipart 层超限 413（单文件 100MB / 整请求 110MB），业务预检拒绝 400
- 无扩展名文件通过受理预检（白名单只拦「有扩展名但不支持」），但被 O2 判「类型与扩展名不符」拒绝——终态轮询 REJECTED 而非受理 400

<details><summary>处理逻辑 + Request/Response + 错误映射（通道分段）</summary>

**处理逻辑**
① 预检（Checker → UploadPolicy：空文件 / >100MB / 扩展名白名单）
② 落盘 staging（O1，UUID 重命名保留扩展名）
③ 生成 scanId（UUID），分发异步扫描（O2–O6，@Async 虚拟线程不等结果）

**Request**
- Method / Path: `POST /api/files/async/upload`（Content-Type: multipart/form-data）
- 参数: `file` part（二进制；filename / size 由 MultipartFile 派生进 Command，流打开失败按 400 表达）
- Schema 校验: 无应用层 schema 校验（缺 file part / 非 multipart 由框架层 400 拦截）；大小 / 扩展名预检属业务规则（见 ①），不写字段校验清单

**Response — Success**
```json
202 { "scanId": "{uuid}", "status": "SCANNING", "message": "扫描进行中" }
```
- `scanId` → I2 轮询凭证；`filePath` 为 null 时整体省略（`@JsonInclude(NON_NULL)`）

**错误映射（受理时，HTTP 状态码）**
| 场景 | 来源 | 对外响应 | 泄漏 |
|---|---|---|---|
| 缺 file part / 非 multipart | 本地校验 | `400 {"code":"FILE_REJECTED"}` | 否 |
| multipart 超限 100MB | 平台限制 | `413 FILE_TOO_LARGE` | 否 |
| 预检拒绝（空文件 / 业务超限 / 扩展名） | 本地校验 | `400 FILE_REJECTED` + 文案 | 否 |
| staging IO 失败 | 本地校验 | `400`「文件暂存失败」 | 否 |

**错误映射（202 之后，一切终态走轮询 status，查询入口 I2）**
| 场景 | 来源 | 对外响应 | 泄漏 |
|---|---|---|---|
| 管道拒绝（类型不符 / ZIP 炸弹） | 下游传播 | `200 status=REJECTED` + message | 否 |
| 检出威胁（O3 / O4 / O5） | 下游传播 | `200 status=INFECTED`（同步端点 422 在此转移） | 否 |
| 引擎故障 fail-closed | 下游传播 | `200 status=ERROR`（同步端点 502 在此转移；detail 只进日志） | 否 |

</details>

#### I2. 同步 HTTP → GET /api/files/async/scan/{scanId}（轮询）

| 鉴权 | 主坐标 |
|---|---|
| 无 | rest/filescan/ScanResultController.java#scanStatus |

> 查询扫描状态：格式合法的 scanId 恒 200——命中结果缓存返回终态，未命中返回 SCANNING 占位。

**关键事实**
- scanId 非 UUID 格式 → 400（`@Pattern` 校验）
- 结果缓存 TTL 30 分钟且纯内存——淘汰或重启丢失后与「进行中」不可区分

<details><summary>轮询语义 + Response 终态示例</summary>

**轮询语义**
- 进行中占位：`SCANNING`；终态：`CLEAN` / `INFECTED` / `REJECTED` / `ERROR`（CLEAN 携带打标时 message 透传，如「文件安全（macro-flagged: …）」）
- 结果保留：Caffeine `expireAfterWrite` 30 分钟 + 上限 10 万条（防内存堆积）
- 淘汰后轮询：返回 SCANNING 占位——与进行中不可区分，客户端无服务端超时判据（需自带放弃策略）；缓存纯内存，服务重启后全部 scanId 失联，同样恒返占位

**Response — 终态示例（CLEAN）**
```json
{ "scanId": "{uuid}", "status": "CLEAN", "message": "文件安全", "filePath": "{uuid}.{ext}" }
```
- `filePath` ← O6 入库文件名（纯文件名，无路径——防内部路径泄漏）；非 CLEAN 终态无 `filePath`

</details>

### Outbounds（管道短路：O2→O3→O4→O5 顺序执行，首个非 Passed 终止；背压：Semaphore=16 同步/异步共用闸门；O2–O6 运行于 @Async 虚拟线程，不阻塞 202）

#### O1. 文件系统 → staging 落盘

主坐标：infrastructure/fileupload/StagingService.java#stage

> 把上传内容同步落盘为 {uuid}.{ext}，后续扫描只传 Path，与 multipart 请求级临时文件生命周期解耦。

<details><summary>操作序列 + 失败表</summary>

```text
write  ${UPLOAD_STAGING:./data/upload-staging}/{uuid}.{ext}   ← Files.copy(multipart 流)
delete {staging}/{uuid}.{ext}                                 ← 失败时清理残留不完整文件
```

**失败表**
| 症状 | 处置 |
|---|---|
| IO 失败（磁盘满 / 权限） | 致命-中止（400，见 I1 映射） |

</details>

#### O2. 进程内（Tika + commons-compress）→ 类型校验 + ZIP 炸弹防护，无外部系统

主坐标：application/filescan/stage/FileTypeScanStage.java#scan

> MIME 探测 + 扩展名一致性 + ZIP 流式防护，探测结果（detectedMime）写入上下文供 O5 复用。

**关键事实**
- ZIP 四重上限全按实际解压逐字节计数（条目 1 万 / 单 entry 100MB / 累计 1GB / 压缩比 100:1）——header 声明值不信任
- 不递归解压，内层 zip 由 O3/O4 对原始字节扫描兜底

#### O3. TCP → ClamAV daemon（capybara INSTREAM）

主坐标：infrastructure/filescan/ClamAvScanner.java#scan

> 流式病毒扫描，检出返回威胁描述，引擎故障抛 502 语义异常交 fail 策略收口。

**关键事实**
- capybara 2.1.2 无 socket 超时——应用层虚拟线程限时 60s 兜底，超时计入熔断失败率
- 熔断：10 次窗口 / 50% 失败率 / OPEN 30s；熔断打开期间调用快速失败

<details><summary>协议 + 弹性 + 失败表</summary>

- `INSTREAM ${CLAMAV_HOST:localhost}:${CLAMAV_PORT:3310}`（扫描 staged 文件流式上传）
- 弹性：单次超时 `${CLAMAV_TIMEOUT_SECONDS:60}`；被放弃的挂起虚拟线程等 TCP 超时自然回收（不耗信号量许可）

**失败表**
| 症状 | 处置 |
|---|---|
| 不可达 / 协议错误 / 超时 | 致命-中止（见 I1 映射下半） |
| 熔断打开 | 致命-中止（同上） |

</details>

#### O4. 子进程 → yara CLI（本地签名规则匹配）

主坐标：infrastructure/filescan/YaraScanner.java#scan

> 按内置规则集扫描文件，命中返回签名列表，超时强杀进程。

**关键事实**
- 退出码 0 = 执行成功（与是否命中无关），命中以 stdout 非空判定
- 规则文件从 classpath 解到临时文件（deleteOnExit）；`enabled=false` 为完整逃生舱（规则缺失也能启动）

<details><summary>命令行 + 退出码 + 失败表</summary>

```bash
yara -s --fail-on-warnings {rulesFile} {stagedFile}
# rulesFile ← classpath:rules/malware.yar 启动时解到临时文件
```

- 超时：`waitFor` 60s 超时即失败；stdout 读取移入虚拟线程防子进程挂起阻塞调用线程；`finally` 强杀存活进程

**失败表**
| 症状 | 处置 |
|---|---|
| exit ≠ 0 / 启动失败 / 超时 | 致命-中止（见 I1 映射下半） |

</details>

#### O5. 进程内（Apache POI + JDK 流式解析）→ 文档威胁 + 宏策略分级，无外部系统

主坐标：application/filescan/stage/DocumentThreatScanStage.java#scan

> 按文档格式路由检测（OOXML / OLE2 / PDF），威胁按类别分级处置。

**关键事实**
- 仅宏（MACRO）可在 macro-policy=FLAG 时放行打标（macro-flagged）；ActiveX 与 PDF 危险动作始终拦截
- MIME 复用 O2 的探测结果（上下文传递），不重复探测
- PDF 关键字扫描对 FlateDecode 压缩对象流无效（已知限制，深度检测由 O3/O4 兜底）

<details><summary>检测矩阵</summary>

**检测矩阵**（按 O2 的 detectedMime 路由；非文档格式直通）
| 格式 | 检测方式 | 判定 |
|---|---|---|
| OOXML（docx/xlsx/pptx） | ZipFile 遍历条目名 | `activex/` 条目 → ACTIVE_X（**优先于 VBA 判定**——防 FLAG 策略放行宏时绕过「始终拦截」语义）；vbaProject.bin → MACRO |
| OLE2（doc/xls/ppt） | POI VBAMacroReader 提取宏模块 + 可疑 API token 匹配（shell / powershell 等 10 个） | 可疑宏 → MACRO；良性宏放行 |
| PDF | 1MB 分块 + 64B 重叠窗口流式扫描 | /Launch、或 /JavaScript+/OpenAction 并存 → PDF_ACTION |

</details>

#### O6. 文件系统 → 处置三态（入库 / 隔离 / 清理）

主坐标：application/filescan/FileScanService.java#handleResult

> 按扫描状态查表执行文件生命周期：CLEAN 入库、INFECTED 隔离、其余清理 staging。

**关键事实**
- 存储与隔离均 UUID 重命名（防原名冲突与路径泄漏）；入库返回纯文件名 → I2 的 `filePath`
- 隔离失败（跨文件系统且降级 copy+delete 也失败）仅记 ERROR 日志，威胁文件滞留 staging
- fail-strategy=CLOSED（默认）：引擎故障拒绝上传；OPEN 时降级入库并打标 scan-engine-degraded

<details><summary>处理逻辑 + 操作序列</summary>

**处理逻辑**
① CLEAN：复制入库 `{base}/{uuid}.{ext}`，删 staging（返回文件名进 I2）
② INFECTED：移入隔离区（跨文件系统降级 copy+delete）
③ REJECTED / ERROR：删 staging

**操作序列**
```text
copy   {staging}/{uuid}.{ext} → ${UPLOAD_STORAGE:./data/upload-storage}/{uuid}.{ext}   ← ①
move   {staging}/{uuid}.{ext} → ${UPLOAD_QUARANTINE:./data/upload-quarantine}/{uuid}.quarantined   ← ②
delete {staging}/{uuid}.{ext}                                                          ← ③（① 后亦删）
```
- 隔离区权限：POSIX `rwx------`（构造器收敛，防同机其他账号读取威胁文件）

</details>

### 补充说明

- **fail-open 安全守卫**：仅 `SCAN_ENGINE_ERROR` 可走 OPEN 降级入库；业务拒绝（REJECTED / INFECTED）被 fail-open 放行将是安全漏洞，守卫在 FileScanService.handleEngineFailure——降级路径只认引擎故障错误码。
- **同步对照端点**：`POST /api/files/sync/upload` 共用 O1–O6、同一信号量背压与同一处置映射；差异仅在终态表达（同步抛 BusinessException → HTTP 400/422/502，异步收敛为轮询 status）与路由阈值（同步超 20MB 预检 400 引导走异步端点，异步仅受 100MB 总上限约束）。
- **管道总开关**：`enabled=false` 时 O2–O5 全部直通（CLEAN 直接入库），受理预检与 O1/O6 仍执行。

***

## 三案例对照（写报告前先对号入座）

| 维度 | 案例 A（分布式） | 案例 B（单体 CRUD） | 案例 C（异步受理） |
|------|----------------|-------------------|--------------------------|
| Inbound | gRPC Server（上游 HTTP 翻译在补充说明 + I1 关键事实） | REST + Basic 认证（contract-first） | REST ×2：受理 202 + 轮询（无鉴权） |
| 出站 | O1–O9：gRPC ×4、HTTP ×3、Kafka ×1 | O1：Database ×1 | O1–O6：文件系统 ×2、TCP ×1、子进程 ×1、进程内 ×2 |
| 控制流标注 | O2 循环 2N 次、O5 feature flag、O9 条件跳过 + at-most-once | 无 | 管道短路 O2→O5、背压信号量 16、全链异步、熔断 |
| 容错分级 | 致命 / 静默 / 告警 / 尽力而为四档（失败表处置枚举） | 失败即中止（失败表单行） | 处置三态（入库 / 隔离 / 清理）+ fail-closed/OPEN 策略 |
| 共性风险上提 | 「O1–O8 均无超时」只在 Summary 出现一次 | 404 特有映射（Summary 一句 + 错误映射行内加注） | 无鉴权 / 无幂等上提 Summary（TTL 伪 SCANNING 归 I2 关键事实） |
| 折叠区示范 | 9 步编排合并为 5 步 + Body 数据实证与 Schema 校验分离 | 框架默认事务（会猜错的无）+ SQL 实证 + Body/schema 分离（openapi.yml） | 错误映射通道分段两段表 + 轮询语义（SCANNING 占位 / TTL 淘汰） |
| 特有教学点 | 错误跨层传播（422 由 frontend 翻译）；下游的下游不展开 | contract-first 入口定位；如实记录 404 而非臆改 409 | 错误语义从 HTTP 状态码转移到轮询 status；管道短路链标注；进程内卡 ×2 |
