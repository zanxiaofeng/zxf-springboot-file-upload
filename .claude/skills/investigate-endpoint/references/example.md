# 范例：不合格反例 + 两份真实开源项目的新格式调查报告

> 案例选自两个公开项目（Apache-2.0，代码事实于 2026-10 按 main/master 分支核实）：
>
> - **案例 A · 分布式形态** — [OpenTelemetry Astronomy Shop](https://github.com/open-telemetry/opentelemetry-demo)（CNCF 官方 demo）：电商下单链路，gRPC × 4、HTTP 下游 × 3、Kafka × 1，展示循环调用、容错分级、错误跨层传播、共性风险上提 Summary
> - **案例 B · 单体 CRUD 形态** — [Spring Petclinic REST](https://github.com/spring-petclinic/spring-petclinic-rest)（Spring 官方 sample）：典型 REST + JPA，覆盖 SQL 出站、认证/校验错误映射、contract-first 入口定位
>
> 新调查对齐此密度即可；模板、字段规范与配额见 `formats.md`。范例内嵌报告标题降一级展示（区块 `###`、卡片 `####`），独立报告中应为 `##` / `###`；折叠区形态环境自适应——本文件按 GFM 环境用 `<details>`（GitHub 等查看器中折叠），终端输出改用 `▸` 标记行 + 2 空格缩进（表格与代码块顶格）。

***

## 先看反面：五类不合格样例

> ❌ **散文化**：系统接收下单请求，参数有 skuId 和数量，会校验参数，没登录会返回 401，库存不够返回 409，下游挂了返回 503。——无卡片、无表格、无坐标，无法逐条审阅，也无法核实。

> ❌ **结构正确但事实编造**：卡片齐整、表格规范，但 SQL 是按方法名猜的（真实取材点在别处的 JPQL / Repository 派生查询）、「超时 2s」无配置实证（代码根本未设置，应作「会猜错的无」写关键事实）、坐标指向不存在的行——结构救不了事实编造。

> ❌ **空壳填表**：幂等设计「未实现」、重试策略「无」、错误映射「无特殊」——各占 2–3 行的信息量为零段落。正确做法是整段不出现（formats.md §一省略三档）。

> ❌ **证据泛滥**：主路径贴函数体源码、处理逻辑每步尾随 `file:line`、「无超时」在每个 O 卡重复一遍——结论被证据淹没。正确做法：每卡 1 个主坐标 + ≤ 2 个风险坐标，共性（「O1–O8 均无超时」）上提 Summary 写一次。

> ❌ **数据盲区**：卡片齐全、控制流清晰，但 Request / Response Body 只写「JSON body」「含订单信息」一笔带过，无真实字段、无流向标注——数据流动是报告第一位，Body 缺实证即不合格。

***

## 案例 A：电商下单 — checkout 服务 PlaceOrder（OpenTelemetry Astronomy Shop）

调查对象是 checkout 服务（Go）的 `PlaceOrder`；frontend 的 HTTP `POST /api/checkout` 是上游入口，客户端可见的错误翻译发生在 frontend（见补充说明）。

### Executive Summary

- 功能：结算下单——取购物车 → 逐商品组装报价 → 运费换汇 → 扣款 → 发货 → 收尾三连（清购物车 / 邮件 / 发事件）
- 外部依赖：O1 取购物车、O2 逐商品组装报价（循环）、O3 运费报价、O4 运费换汇、O5 扣款、O6 发货（gRPC ×4 + HTTP ×2）、O7 清购物车、O8 确认邮件、O9 Kafka 订单事件
- 注意：O1–O8 全部无超时 / deadline 配置（默认无限等待）；O9 at-most-once，或丢消息；O7/O8/O9 失败不影响下单结果

> 坐标相对于仓库根；checkout 服务代码在 `src/checkout/`。

### Inbound

#### I1. gRPC Server → oteldemo.CheckoutService/PlaceOrder（调用方 frontend）

| 鉴权 | 主坐标 |
|---|---|
| 无 | src/checkout/main.go#PlaceOrder |

> 结算下单总编排：五个必经步 + 三个可失败收尾步，任一必经步失败即整体中止。

**关键事实**
- 无幂等——重放产生重复订单
- 客户端可见错误由 frontend 翻译为 422/500（细则见补充说明）src/frontend/pages/api/checkout.ts

<details><summary>处理逻辑 + Request/Response + 错误映射</summary>

**处理逻辑**
① 取购物车（O1）
② 组装报价（O2，循环：购物车 N 件 = 2N 次调用）
③ 运费报价与换汇（O3 → O4）
④ 扣款（O5，条件：flag `paymentUnreachable` 开启时连坏地址——demo 故障注入）
⑤ 发货（O6）；收尾：清购物车（O7，失败静默）；邮件（O8，失败告警）；发事件（O9，异步）

**Request**
- Body（数据承载）: `PlaceOrderRequest`（protobuf，schema 定义在 `demo.proto`）——真实字段 JSON 示意：
```json
{ "userId": "{id}", "userCurrency": "{code}", "email": "{email}",
  "address": { "streetAddress": "{…}", "city": "{…}", "…": "{…}" },
  "creditCard": { "number": "{…}", "expirationMonth": "{m}", "expirationYear": "{y}", "cvv": "{…}" } }
```
- Schema 校验: 无应用层校验（protobuf 解析失败即 gRPC error）

**Response — Success**：`OK`（frontend → HTTP 200）；`{ order: { orderId, shippingTrackingId, items[], shippingCost, total } }`

**错误映射**
| 场景 | 来源 | 对外响应 | 泄漏 |
|---|---|---|---|
| O1–O6 任一失败 | 下游传播 | gRPC error，下单中止（frontend 翻译见补充说明） | 否 |
| O7/O8/O9 失败 | 下游传播 | 不影响响应 | 否 |

</details>

### Outbounds（O2 逐商品循环；O7/O8/O9 为收尾步，失败不影响下单结果；O9 异步）

#### O1. gRPC → cart 服务（取购物车）

主坐标：`src/checkout/main.go#prepare`

> CALL GetCart 取 items 返回主流程。

<details><summary>Method 与 Message + 失败表</summary>

- `CALL oteldemo.CartService/GetCart`（同步 stub，addr=`${CART_ADDR}`）；request `{ userId }` → response `{ items[{ productId, quantity }] }`

**失败表**
| 症状 | 处置 |
|---|---|
| 任意错误 | 致命-中止（RPC error） |

</details>

#### O2. gRPC → product-catalog + currency（逐商品组装，循环 2N 次）

主坐标：`src/checkout/main.go#prepare`

> 每件商品取品名再换汇到用户币种，串行两连调。

<details><summary>Method 与 Message + 失败表</summary>

- a. `CALL oteldemo.ProductCatalogService/GetProduct`（addr=`${PRODUCT_CATALOG_ADDR}`）；`{ id }` → `{ name, usd }`
- b. `CALL oteldemo.CurrencyService/Convert`（addr=`${CURRENCY_ADDR}`）；`{ from=USD, to={userCurrency}, units, nanos }` → `{ currencyCode, units, nanos }`

**失败表**
| 症状 | 处置 |
|---|---|
| 任一次失败 | 致命-中止（RPC error） |

</details>

#### O3. 下游 HTTP → shipping 服务（运费报价）

| 路由 | 主坐标 |
|---|---|
| `POST ${SHIPPING_ADDR}/get-quote` | src/checkout/main.go#shipOrder |

> 取 costUsd 参与订单合计。

<details><summary>Request / Response + 失败表</summary>

- Headers: `Content-Type: application/json`；Body: `{ "address": {…}, "items": [ { "productId", "quantity" } ] }`
- Success: `200 { "costUsd": { "units": {u}, "nanos": {n} } }`

**失败表**
| 症状 | 处置 |
|---|---|
| 非 200 / 连接失败 | 致命-中止（RPC error） |

</details>

#### O4. gRPC → currency 服务（运费换汇）

主坐标：`src/checkout/main.go#shipOrder`

> 运费 USD 换算到用户币种（与 O2b 同一 Convert 方法）。

<details><summary>Method 与 Message + 失败表</summary>

- `CALL oteldemo.CurrencyService/Convert`；`{ from=USD, to={userCurrency}, amount=运费 }` → `{ currencyCode, units, nanos }`

**失败表**
| 症状 | 处置 |
|---|---|
| 任意错误 | 致命-中止（RPC error） |

</details>

#### O5. gRPC → payment 服务（扣款，条件执行）

| 触发 | 主坐标 |
|---|---|
| 条件（flag `paymentUnreachable` 开启时连坏地址——demo 故障注入） | src/checkout/main.go#charge |

> CALL Charge 扣款（商品 + 运费合计），取 transactionId 进订单。

<details><summary>Method 与 Message + 失败表</summary>

- `CALL oteldemo.PaymentService/Charge`（addr=`${PAYMENT_ADDR}`）；`{ userId, creditCard, amount }` → `{ transactionId }`

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
- Success: `200 { "trackingId": "{trackingId}" }`

**失败表**
| 症状 | 处置 |
|---|---|
| 非 200 / 连接失败 | 致命-中止（RPC error） |

</details>

#### O7. gRPC → cart 服务（清空购物车，收尾步）

主坐标：`src/checkout/main.go#emptyCart`

> 下单成功后清空购物车。

<details><summary>Method 与 Message + 失败表</summary>

- `CALL oteldemo.CartService/EmptyCart`；request `{ userId }`

**失败表**
| 症状 | 处置 |
|---|---|
| 任意错误 | 忽略-静默丢弃（源码显式丢弃错误，用户可重放补救） |

</details>

#### O8. 下游 HTTP → email 服务（订单确认邮件，收尾步）

| 路由 | 主坐标 |
|---|---|
| `POST ${EMAIL_ADDR}/send_order_confirmation` | src/checkout/main.go |

> 发订单确认邮件（email + 订单全文）；响应无业务消费（源码不解析——告知性调用）。

<details><summary>失败表</summary>

| 症状 | 处置 |
|---|---|
| 任意失败 | 忽略-记日志（WARN，不影响下单结果） |

</details>

#### O9. MQ → orders topic（Kafka 异步生产者）

主坐标：`src/checkout/kafka/producer.go#sendToPostProcessor`

> 异步发送订单事件（protobuf OrderResult），不阻塞下单响应。

**关键事实**
- `${KAFKA_ADDR}` 为空时整步跳过（条件执行）
- 携带 W3C traceparent header（消费侧 fraud-detection 以此关联链路）

<details><summary>消息定义 + 可靠性 + 失败表</summary>

- `SEND topic=${KAFKA_TOPIC:-orders} key={orderId}`（key 保序）；payload：OrderResult（protobuf：orderId / shippingTrackingId / shippingCost / items / transactionId）

**可靠性**
- `RequiredAcks=NoResponse`——at-most-once，可能丢消息，下游需对账；无本地重试

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

- 入参：Owner 实体五字段（`model/Owner.java`，@Table owners）；映射：persist 回填 `id` → I1 的 `Location` 头与 body（数据流向标注）
- 附注（不在本链路）：后续读路径参考 getOwner 的 `SELECT … FROM owners WHERE id = ?`

**失败表**
| 症状 | 处置 |
|---|---|
| `DataIntegrityViolationException` | 致命-中止（映射见 I1） |

</details>

***

## 两案例对照（写报告前先对号入座）

| 维度 | 案例 A（分布式） | 案例 B（单体 CRUD） |
|------|----------------|-------------------|
| Inbound | gRPC Server（上游 HTTP 翻译在补充说明 + I1 关键事实） | REST + Basic 认证（contract-first） |
| 出站 | O1–O9：gRPC ×4、HTTP ×3、Kafka ×1 | O1：Database ×1 |
| 控制流标注 | O2 循环 2N 次、O5 feature flag、O9 条件跳过 + 异步 | 无 |
| 容错分级 | 致命 / 静默 / 告警 / 异步四档（失败表处置枚举） | 失败即中止（失败表单行） |
| 共性风险上提 | 「O1–O8 均无超时」只在 Summary 出现一次 | 404 特有映射（Summary 一句 + 错误映射行内加注） |
| 折叠区示范 | 9 步编排合并为 5 步 + Body 数据实证与 Schema 校验分离 | 框架默认事务（会猜错的无）+ SQL 实证 + Body/schema 分离（openapi.yml） |
| 特有教学点 | 错误跨层传播（422 由 frontend 翻译）；下游的下游不展开 | contract-first 入口定位；如实记录 404 而非臆改 409 |
