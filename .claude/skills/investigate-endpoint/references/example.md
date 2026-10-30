# 范例：不合格反例 + 两份真实开源项目的卡片制调查报告

> 案例选自两个公开项目（Apache-2.0，代码事实于 2026-10 按 main/master 分支核实）：
>
> - **案例 A · 分布式形态** — [OpenTelemetry Astronomy Shop](https://github.com/open-telemetry/opentelemetry-demo)（CNCF 官方 demo）：电商下单链路，覆盖 gRPC × 4、HTTP 下游 × 3、Kafka，展示循环调用、容错分级、错误跨层传播、空值语义
> - **案例 B · 单体 CRUD 形态** — [Spring Petclinic REST](https://github.com/spring-petclinic/spring-petclinic-rest)（Spring 官方 sample）：典型 REST + JPA，覆盖 SQL 出站、认证/校验错误映射、contract-first 入口定位
>
> 新调查对齐此密度即可；卡片模板与各类型 schema 见 `formats.md`。

***

## 先看反面：不合格样例（散文化）

> ❌ **Inbound 不合格**：系统接收下单请求，参数有 skuId 和数量，会校验参数，没登录会返回 401，库存不够返回 409，下游挂了返回 503。
>
> ❌ **Outbound 不合格**：下单时会调用库存服务预占库存，用的是 HTTP POST，请求里带 skuId 和数量，如果库存不够会报错，超时的话会重试。

不合格的原因：无 H3 卡片标题、无元信息表、无 ①②③ 处理逻辑、错误分支没有表格与映射位置、全篇没有 `file:line`——无法逐条审阅，也无法核实。合格形态 = 下述案例：每张卡片三段式（元信息表 → 处理逻辑 → 类型专属子结构），错误分支表格化，事实有依据。

***

## 案例 A：电商下单 — checkout 服务的 PlaceOrder（OpenTelemetry Astronomy Shop）

背景：frontend（Next.js）暴露 HTTP `POST /api/checkout`，经 gRPC 调用 checkout 服务（Go）的 `PlaceOrder`。本报告调查对象是 **checkout 服务**的 `PlaceOrder`——上游 HTTP 入口的错误翻译在补充说明。

代码依据：`src/checkout/main.go`（PlaceOrder 及各 prepare/charge/ship/empty/confirm/sendToPostProcessor 函数）、`src/checkout/kafka/producer.go`、`src/frontend/pages/api/checkout.ts`。

### I1. gRPC Server → oteldemo.CheckoutService/PlaceOrder

| 字段 | 内容 |
|---|---|
| 暴露方式 | gRPC Server（调用方：frontend） |
| 入口点 | checkout 服务 handler（`src/checkout/main.go`，PlaceOrder） |
| 路由/契约 | `oteldemo.CheckoutService/PlaceOrder`（proto 定义） |
| 触发方式 | 同步 RPC（frontend `POST /api/checkout` 转入） |
| 鉴权与限流 | 无 |
| 幂等 | 无（重放产生重复订单） |
| 处理逻辑 | ① 取购物车（O1） ② 逐商品组装报价（O2，循环：购物车 N 件产生 2N 次调用） ③ 运费报价（O3） ④ 运费换汇（O4） ⑤ 扣款（O5，条件：feature flag `paymentUnreachable` 开启时故意连坏地址） ⑥ 发货下单（O6） ⑦ 清空购物车（O7，失败静默） ⑧ 确认邮件（O8，失败仅告警） ⑨ 发订单事件（O9，异步不阻塞响应） |
| 下游依赖 | O1、O2、O3、O4、O5、O6、O7、O8、O9 |

##### Request（入参契约）
- Method: `oteldemo.CheckoutService/PlaceOrder`
- 契约定义位置：proto service 定义（demo.proto）
- Message（protobuf JSON 示意）:
  ```json
  { "userId": "{id}", "userCurrency": "{code}",
    "address": { "streetAddress": "{…}", "city": "{…}", "state": "{…}", "country": "{…}", "zipCode": "{…}" },
    "email": "{email}",
    "creditCard": { "number": "{…}", "expirationMonth": {m}, "expirationYear": {y}, "cvv": "{…}" } }
  ```

##### Response — Success
- Status: `OK`（frontend 映射为 HTTP 200）
- Message:
  ```json
  { "order": { "orderId": "{uuid}", "shippingTrackingId": "{…}", "shippingAddress": {…},
               "items": [ { "item": { "productId": "{…}", "quantity": {n} }, "cost": {…} } ],
               "shippingCost": {…}, "total": {…} } }
  ```

##### Response — Failed（错误映射表）
| 场景 | 错误来源 | 对外响应 | 映射位置 | 是否泄漏内部信息 |
|---|---|---|---|---|
| O1–O6 任一失败 | 下游传播 | gRPC error（下单中止，见各 O 卡片失败处理） | `src/checkout/main.go` 各 prepare/charge/ship 函数 | 否 |
| O7/O8/O9 失败 | 下游传播 | 不影响响应（静默/告警/异步） | `src/checkout/main.go` | 否 |

> frontend 侧的 HTTP 翻译（422/500）见补充说明——错误分支写在发起翻译的那一层。

## Outbounds（按执行次序编号；O2 为逐商品循环——购物车 N 件产生 2N 次 gRPC；O7/O8 失败不影响下单结果；O9 异步发送不阻塞响应）

### O1. gRPC → cart 服务（取购物车）

| 字段 | 内容 |
|---|---|
| 类型 | gRPC（同步 stub，addr=${CART_ADDR}） |
| 触发点 | `src/checkout/main.go`（prepare） |
| 触发条件 | 主链路必经 |
| 处理逻辑 | ① CALL GetCart ② 取 items 返回主流程 |
| 失败处理 | 致命：下单中止 → RPC error |
| 保护与幂等 | 无重试；无超时配置（deadline 未显式设置） |

##### Method 与 Message
- `CALL oteldemo.CartService/GetCart`；request `{ userId }`；response `{ items[{ productId, quantity }] }`

##### Deadline
- 未显式设置（gRPC 默认无限等待）

##### 状态码处置
- 无按状态码区分的处置——任何错误同等对待

##### 失败处理
- 任意错误 → 下单中止 → RPC error（`src/checkout/main.go`）

### O2. gRPC → product-catalog + currency（逐商品组装，循环 2N 次）

| 字段 | 内容 |
|---|---|
| 类型 | gRPC（同步 stub）× 2 方法 |
| 触发点 | `src/checkout/main.go`（prepare） |
| 触发条件 | 主链路必经（循环：逐商品 a→b 串行，N 件商品 = 2N 次调用） |
| 处理逻辑 | ①（循环：每件商品）CALL GetProduct 取 name/usd ② CALL Convert 换算到 userCurrency |
| 失败处理 | 致命：任一次失败 → 下单中止 → RPC error |
| 保护与幂等 | 无重试；deadline 未显式设置 |

##### Method 与 Message
- a. `CALL oteldemo.ProductCatalogService/GetProduct`（addr=${PRODUCT_CATALOG_ADDR}）；request `{ id }`；response `{ name, usd }`
- b. `CALL oteldemo.CurrencyService/Convert`（addr=${CURRENCY_ADDR}）；request `{ from=USD, to={userCurrency}, units, nanos }`；response `{ currencyCode, units, nanos }`

##### Deadline
- 未显式设置（两个调用均无）

##### 状态码处置
- 无按状态码区分的处置——任一次失败即中止

##### 失败处理
- 任一次失败 → 下单中止 → RPC error（`src/checkout/main.go`）

### O3. 下游 HTTP → shipping 服务（运费报价）

| 字段 | 内容 |
|---|---|
| 类型 | 下游 HTTP（JSON over HTTP，Go 标准客户端） |
| 触发点 | `src/checkout/main.go`（shipOrder 前置报价） |
| 触发条件 | 主链路必经 |
| 处理逻辑 | ① POST /get-quote ② 取 costUsd 参与合计 |
| 失败处理 | 致命：下单中止 → RPC error |
| 保护与幂等 | 无重试；无超时配置 |

##### Request
- Method / Path: `POST ${SHIPPING_ADDR}/get-quote`
- Headers: `Content-Type: application/json`
- Body: `{ "address": {…}, "items": [ { "productId": "{id}", "quantity": {n} } ] }`

##### Response — Success
- Status: `200`；Body: `{ "costUsd": { "units": {u}, "nanos": {n} } }`

##### Response — Failed
| 场景 | 状态码 / 异常 | 本地行为 | 源码依据 |
|---|---|---|---|
| 非 200 / 连接失败 | 任意 | 下单中止 → RPC error | `src/checkout/main.go` |

### O4. gRPC → currency 服务（运费换汇）

| 字段 | 内容 |
|---|---|
| 类型 | gRPC（同步 stub，addr=${CURRENCY_ADDR}） |
| 触发点 | `src/checkout/main.go` |
| 触发条件 | 主链路必经 |
| 处理逻辑 | ① CALL Convert（与 O2b 同一方法，from=USD，amount=运费） |
| 失败处理 | 致命：下单中止 → RPC error |
| 保护与幂等 | 无重试；deadline 未显式设置 |

##### Method 与 Message
- `CALL oteldemo.CurrencyService/Convert`；request `{ from=USD, to={userCurrency}, amount=运费 }`；response `{ currencyCode, units, nanos }`

##### Deadline
- 未显式设置（gRPC 默认无限等待）

##### 状态码处置
- 无按状态码区分的处置——任何错误同等对待

##### 失败处理
- 任意错误 → 下单中止 → RPC error（`src/checkout/main.go`）

### O5. gRPC → payment 服务（扣款）

| 字段 | 内容 |
|---|---|
| 类型 | gRPC（同步 stub，addr=${PAYMENT_ADDR}） |
| 触发点 | `src/checkout/main.go`（charge） |
| 触发条件 | 主链路必经（条件：feature flag `paymentUnreachable` 开启时故意连向坏地址——demo 故障注入） |
| 处理逻辑 | ① CALL Charge（amount=商品+运费合计） ② 取 transactionId 进订单 |
| 失败处理 | 致命：下单中止 → RPC error；frontend 依错误 details 前缀翻译为 422（见补充说明） |
| 保护与幂等 | 无重试；deadline 未显式设置 |

##### Method 与 Message
- `CALL oteldemo.PaymentService/Charge`；request `{ userId, creditCard, amount { currency, units+nanos } }`；response `{ transactionId }`

##### Deadline
- 未显式设置（gRPC 默认无限等待）

##### 状态码处置
- 无按状态码区分的处置——任何错误同等对待

##### 失败处理
- 任意错误 → 下单中止 → RPC error；frontend 依错误 details 前缀翻译为 422（见补充说明，`src/checkout/main.go`）

### O6. 下游 HTTP → shipping 服务（发货下单）

| 字段 | 内容 |
|---|---|
| 类型 | 下游 HTTP（JSON over HTTP） |
| 触发点 | `src/checkout/main.go`（shipOrder） |
| 触发条件 | 主链路必经 |
| 处理逻辑 | ① POST /ship-order ② 取 trackingId 进订单 |
| 失败处理 | 致命：下单中止 → RPC error |
| 保护与幂等 | 无重试；无超时配置 |

##### Request
- Method / Path: `POST ${SHIPPING_ADDR}/ship-order`；Headers: `Content-Type: application/json`
- Body: `{ "address": {…}, "items": [{…}] }`

##### Response — Success
- Status: `200`；Body: `{ "trackingId": "{trackingId}" }`

##### Response — Failed
| 场景 | 状态码 / 异常 | 本地行为 | 源码依据 |
|---|---|---|---|
| 非 200 / 连接失败 | 任意 | 下单中止 → RPC error | `src/checkout/main.go` |

### O7. gRPC → cart 服务（清空购物车）

| 字段 | 内容 |
|---|---|
| 类型 | gRPC（同步 stub，addr=${CART_ADDR}） |
| 触发点 | `src/checkout/main.go`（emptyCart） |
| 触发条件 | 主链路必经（下单成功后） |
| 处理逻辑 | ① CALL EmptyCart ② 错误显式丢弃（源码忽略错误） |
| 失败处理 | 静默忽略——用户可重放，不影响已成交订单 |
| 保护与幂等 | 无重试；deadline 未显式设置 |

##### Method 与 Message
- `CALL oteldemo.CartService/EmptyCart`；request `{ userId }`

##### Deadline
- 未显式设置（gRPC 默认无限等待）

##### 状态码处置
- 无处置——错误被显式丢弃

##### 失败处理
- 任意错误 → 显式忽略（源码丢弃错误），用户可重放（`src/checkout/main.go`）

### O8. 下游 HTTP → email 服务（订单确认邮件）

| 字段 | 内容 |
|---|---|
| 类型 | 下游 HTTP（JSON over HTTP，addr=${EMAIL_ADDR}） |
| 触发点 | `src/checkout/main.go` |
| 触发条件 | 主链路必经（下单成功后） |
| 处理逻辑 | ① POST /send_order_confirmation（email + 订单全文） |
| 失败处理 | 降级：仅记 WARN 日志，不影响下单结果 |
| 保护与幂等 | 无重试；无超时配置 |

##### Request
- Method / Path: `POST ${EMAIL_ADDR}/send_order_confirmation`；Body: `{ "email": "{customerEmail}", "order": {…订单全文} }`

##### Response — Success
- Status: `200`；响应 body 无业务消费（源码不解析——告知性调用）

##### Response — Failed
| 场景 | 状态码 / 异常 | 本地行为 | 源码依据 |
|---|---|---|---|
| 任意失败 | 任意 | 记 WARN，继续 | `src/checkout/main.go` |

### O9. 消息队列（Kafka）→ orders 事件

| 字段 | 内容 |
|---|---|
| 类型 | 消息队列（Kafka，Sarama **异步**生产者） |
| 触发点 | `src/checkout/kafka/producer.go`（sendToPostProcessor） |
| 触发条件 | 异步：不阻塞下单响应；条件：`${KAFKA_ADDR}` 为空时整步跳过 |
| 处理逻辑 | ① 序列化 OrderResult（protobuf） ② 异步 SEND ③ 携带 W3C traceparent header |
| 失败处理 | 异步尽力而为：发送失败只进 error 日志，可能丢消息 |
| 保护与幂等 | 无本地重试；`RequiredAcks=NoResponse`（at-most-once） |

##### 消息定义
- `SEND topic=${KAFKA_TOPIC:-orders}`；payload（protobuf OrderResult）: `{ orderId, shippingTrackingId, shippingCost, shippingAddress, items, transactionId }`
- Headers: W3C traceparent（消费侧 fraud-detection 以此关联链路）

##### 发送模式
- 异步生产者（不阻塞下单响应）；条件：`${KAFKA_ADDR}` 为空时整步跳过

##### 可靠性
- 确认: `RequiredAcks=NoResponse`；投递语义: at-most-once（可能丢消息，下游需知晓）

##### 失败处理
- 发送失败只进 error 日志（`src/checkout/kafka/producer.go`）

## 补充说明

- **错误跨层传播**（frontend `src/frontend/pages/api/checkout.ts` 实证）：O5 失败时错误 details 以 `failed to charge card:` 开头，frontend 据此把 gRPC error 翻译为 `HTTP 422 { error: "Your payment could not be processed…", code: "PAYMENT_FAILED" }`（且排除 `Unavailable/Internal` 状态码）；其余任何失败 → `HTTP 500 { error: "Failed to place order." }`。客户端可见的错误分支要追到**发起翻译的那一层**。
- **无 Database 调用**——checkout 服务无本地存储；cart 服务的 Redis、product-catalog 的自持有数据均属下游内部实现，不在本报告展开（调查深度规则见 SKILL.md）。
- **容错分级**是本案例要点：O1–O6 致命（失败即中止下单）、O7 静默忽略、O8 降级告警、O9 异步尽力而为——同一 endpoint 内四种失败语义，逐一标注在「失败处理」字段。

***

## 案例 B：单体 CRUD — POST /api/owners（Spring Petclinic REST）

背景：Spring 官方 sample，典型三层单体（Controller → Service → Spring Data JPA）。**contract-first**：Controller 实现的 `OwnersApi` 接口由 `src/main/resources/openapi.yml` 经 openapi-generator 生成——搜 route 注解时注意路径可能定义在生成代码/规范文件里。

代码依据：`rest/controller/v1/OwnerRestControllerV1.java`（addOwner）、`service/ClinicServiceImpl.java`（saveOwner → ownerRepository.save）、`model/Owner.java`（@Table owners）、`rest/advice/ExceptionControllerAdvice.java`（错误映射）、`resources/openapi.yml`（OwnerFields 契约）。

### I1. HTTP Server → POST /api/owners

| 字段 | 内容 |
|---|---|
| 暴露方式 | HTTP Server（REST） |
| 入口点 | `OwnerRestControllerV1.addOwner`（`rest/controller/v1/OwnerRestControllerV1.java`） |
| 路由/契约 | `POST /api/owners`（contract-first：路径定义在 `openapi.yml` → 生成的 `OwnersApi` 接口） |
| 触发方式 | 同步请求 |
| 鉴权与限流 | Basic + `@PreAuthorize("hasRole(@roles.OWNER_ADMIN)")`；无限流 |
| 幂等 | 无（重放产生重复业主记录） |
| 处理逻辑 | ① 解析 + Bean Validation 校验（openapi.yml OwnerFields → 生成的约束） ② 鉴权（OWNER_ADMIN 角色） ③ 委托 `saveOwner`（O1） ④ 组装 201 响应 |
| 下游依赖 | O1 |

##### Request（入参契约）
- Method / Path: `POST /api/owners`；Content-Type: `application/json`；Authorization: `Basic …`
- Body 模型（OwnerFields，`openapi.yml`）:
  ```json
  { "firstName": "George", "lastName": "Franklin", "address": "110 W. Liberty St.",
    "city": "Madison", "telephone": "6085551023" }
  ```
- 校验规则（openapi.yml OwnerFields → 生成的 Bean Validation）：firstName / lastName 长度 1–30 + 人名 pattern（`-`、空格、`.`）；address ≤255；city ≤80；telephone 格式 pattern

##### Response — Success
- Status: `201 Created`；`Location: /api/owners/{id}`（IDENTITY 主键 persist 后回填，见 O1）
- Body: `{ "id": 1, "firstName": "George", … }`（组装位置 `OwnerRestControllerV1.java`）

##### Response — Failed（错误映射表，ProblemDetail RFC 9457 格式）
| 内部异常 / 场景 | 错误来源 | 对外响应 | 映射位置 | 是否泄漏内部信息 |
|---|---|---|---|---|
| 未认证 / 角色不足 | 鉴权 | `401` / `403` | Spring Security + `@PreAuthorize` | 否 |
| Bean Validation 失败 | 本地校验 | `400`（MethodArgumentNotValidException → ProblemDetail） | `rest/advice/ExceptionControllerAdvice.java` | 否 |
| 唯一键等约束冲突（O1 传播） | 下游传播 | `404`（DataIntegrityViolationException） | `rest/advice/ExceptionControllerAdvice.java` | 否 |
| 未捕获异常 | 未捕获 | `500`（ProblemDetail 兜底） | `rest/advice/ExceptionControllerAdvice.java` | 否 |

> ⚠️ 约束冲突 → 404 是**该项目特有映射**——数据冲突按 404 表达，调查时如实记录并加注，不按「应该 409」臆改。

## Outbounds

### O1. DB → owners 表（保存业主）

| 字段 | 内容 |
|---|---|
| 类型 | DB（Spring Data JPA → Hibernate） |
| 触发点 | `ClinicServiceImpl.saveOwner` → `ownerRepository.save()` |
| 触发条件 | 主链路必经 |
| 处理逻辑 | ① persist Owner 实体 ② IDENTITY 策略取回主键回填 ③ 提交 |
| 失败处理 | 约束冲突 → DataIntegrityViolationException → I1 映射表 404 行 |
| 保护与幂等 | 无重试；无显式事务注解（见事务与隔离段） |

##### Statement
- 操作类型: INSERT
- SQL:
  ```sql
  INSERT INTO owners (first_name, last_name, address, city, telephone)
  VALUES (?, ?, ?, ?, ?)     -- ClinicServiceImpl.saveOwner → ownerRepository.save()（JPA persist）派生
  ```

##### 参数绑定与返回值
- 入参: Owner 实体五字段（`model/Owner.java`，@Table owners）
- 返回: IDENTITY 主键 persist 后回填 `id`，用于 201 的 Location 与 body

##### 事务与隔离
- 事务边界: 无方法级 `@Transactional`——由 `JpaRepository.save()` 方法级默认事务承担（空值事实行示范：框架默认即事实）
- 隔离级别: 默认（未显式设置）

##### 失败处理
| 异常 | 本地行为 | 源码依据 |
|---|---|---|
| `DataIntegrityViolationException` | 冒泡至 advice → 404（见 I1 映射表） | `rest/advice/ExceptionControllerAdvice.java` |

> 附注（不在本 endpoint）：后续读路径参考 `SELECT … FROM owners o WHERE o.id = ?`（getOwner）。

## 补充说明

- **无缓存 / 消息队列 / 下游 HTTP / SFTP**——典型单体 CRUD 的全部出站就是一条 INSERT；报告价值在于把入站契约（校验、认证、错误映射）与 SQL 实证写准。
- 多实体写操作的 endpoint（如 deleteOwner 级联）才需要标注事务边界与多条 SQL；本 endpoint 单条 INSERT，事务由框架默认承担。

***

## 两案例对照（写报告前先对号入座）

| 维度 | 案例 A（分布式） | 案例 B（单体 CRUD） |
|------|-----------------|-------------------|
| Inbound 卡片 | I1 gRPC Server（上游 HTTP 翻译在补充说明） | I1 REST + Basic 认证 |
| 出站卡片 | O1–O9：gRPC ×4、HTTP ×3、Kafka ×1 | O1：Database ×1 |
| 控制流标注 | O2 循环 2N 次、O5 feature flag、O9 条件跳过 + 异步 | 无 |
| 失败处理分级 | 致命 / 静默 / 降级 / 异步四档（元信息表「失败处理」字段） | 失败即 500（映射表单行） |
| 空值语义示范 | O1–O5 deadline「未显式设置」；I1 鉴权「无」 | O1 事务「无注解，框架默认承担」 |
| schema 齐整性 | 每张 O 卡片小节按 §三 schema 齐备（信息少的段用一行事实陈述） | 同 |
| 特有教学点 | 错误跨层传播（422 由 frontend 翻译）；下游的下游不展开 | contract-first 入口定位；错误映射如实记录（404 而非想当然的 409） |
