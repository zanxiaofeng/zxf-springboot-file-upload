# 范例：两份真实开源项目的调查报告

> 案例选自两个公开项目（Apache-2.0，代码事实于 2026-10 按 main/master 分支核实）：
>
> - **案例 A · 分布式形态** — [OpenTelemetry Astronomy Shop](https://github.com/open-telemetry/opentelemetry-demo)（CNCF 官方 demo）：电商下单链路，覆盖 gRPC × 4、HTTP 下游 × 3、Kafka，展示循环调用、容错分级、错误跨层传播、调查深度规则
> - **案例 B · 单体 CRUD 形态** — [Spring Petclinic REST](https://github.com/spring-petclinic/spring-petclinic-rest)（Spring 官方 sample）：典型 REST + JPA，覆盖 SQL 出站、认证/校验错误分支、contract-first 入口定位
>
> 新调查对齐此密度即可；格式模板见 `formats.md`。

***

## 案例 A：电商下单 — checkout 服务的 PlaceOrder（OpenTelemetry Astronomy Shop）

背景：frontend（Next.js）暴露 HTTP `POST /api/checkout`，经 gRPC 调用 checkout 服务（Go）的 `PlaceOrder`。本报告调查对象是 **checkout 服务**的 `PlaceOrder`——上游 HTTP 入口的错误映射附在末尾。

代码依据：`src/checkout/main.go`（PlaceOrder 及各 prepare/charge/ship/empty/confirm/sendToPostProcessor 函数）、`src/checkout/kafka/producer.go`、`src/frontend/pages/api/checkout.ts`。

```
Inbound:
RPC oteldemo.CheckoutService/PlaceOrder          ← gRPC（调用方：frontend）
request : { userId, userCurrency,                ← protobuf JSON 示意
            address { streetAddress, city, state, country, zipCode },
            email, creditCard { number, expirationMonth, expirationYear, cvv } }
response: { order { orderId, shippingTrackingId, shippingAddress,
                    items[{ item{productId, quantity}, cost }],
                    shippingCost, total } }

→ OK                                    下单成功（frontend 映射为 HTTP 200）
→ gRPC error（见 Outbounds 各条目的失败传播）  ← 任一前置出站失败即返回，
                                            错误码语义在补充说明（frontend 侧翻译）
```

```
Outbounds:   （按执行次序编号；Outbound 2 为逐商品循环——购物车 N 件商品产生 2N 次 gRPC；
              Outbound 7/8 失败不影响下单结果；Outbound 9 异步发送不阻塞响应。

1. 取购物车 — gRPC（cart 服务）
   CALL oteldemo.CartService/GetCart            addr=${CART_ADDR}
   request : { userId }
   response: { items[{ productId, quantity }] }
   失败: 下单中止 → RPC error

2. 逐商品组装 — gRPC × 2/商品（product-catalog + currency）
   a. CALL oteldemo.ProductCatalogService/GetProduct   addr=${PRODUCT_CATALOG_ADDR}
      request : { id }            response: { name, usd }
   b. CALL oteldemo.CurrencyService/Convert            addr=${CURRENCY_ADDR}
      request : { from=USD, to={userCurrency}, units/nanos }
      response: { currencyCode, units, nanos }
   失败: 任一次失败 → 下单中止 → RPC error

3. 运费报价 — HTTP（shipping 服务，JSON over HTTP）
   POST ${SHIPPING_ADDR}/get-quote
   Content-Type: application/json
   { "address": {…}, "items": [{ "productId": "{id}", "quantity": {n} }] }
   → 200 { "costUsd": { "units": {u}, "nanos": {n} } }
   失败: 下单中止 → RPC error

4. 运费换汇 — gRPC（currency 服务）
   CALL oteldemo.CurrencyService/Convert         addr=${CURRENCY_ADDR}
   request : { from=USD, to={userCurrency}, amount=运费 }      ← 与 2b 同一方法
   失败: 下单中止 → RPC error

5. 扣款 — gRPC（payment 服务）
   CALL oteldemo.PaymentService/Charge           addr=${PAYMENT_ADDR}
   request : { userId, creditCard, amount{currency, units+nanos}=商品+运费合计 }
   response: { transactionId }
   分支: feature flag "paymentUnreachable" 开启时故意连向坏地址（demo 演示故障注入）
   失败: 下单中止 → RPC error（frontend 依错误 details 前缀翻译为 422，见补充说明）

6. 发货下单 — HTTP（shipping 服务）
   POST ${SHIPPING_ADDR}/ship-order
   { "address": {…}, "items": [{…}] }
   → 200 { "trackingId": "{trackingId}" }
   失败: 下单中止 → RPC error

7. 清空购物车 — gRPC（cart 服务）
   CALL oteldemo.CartService/EmptyCart           addr=${CART_ADDR}
   request : { userId }
   失败: 显式忽略（源码丢弃错误）——用户可重放，不影响已成交订单

8. 订单确认邮件 — HTTP（email 服务）
   POST ${EMAIL_ADDR}/send_order_confirmation
   { "email": "{customerEmail}", "order": {…订单全文} }
   失败: 仅记 WARN 日志，不影响下单结果

9. 订单事件发布 — Kafka（Sarama 异步生产者）
   SEND topic=${KAFKA_TOPIC:-orders}
   payload: protobuf OrderResult { orderId, shippingTrackingId, shippingCost,
                                   shippingAddress, items, transactionId }
   header : W3C traceparent（消费侧 fraud-detection 以此关联链路）
   可靠性: 异步发送 + RequiredAcks=NoResponse——发送失败只进 error 日志，可能丢消息
   分支: ${KAFKA_ADDR} 为空时整步跳过
```

补充说明：

- **错误跨层传播**（frontend `checkout.ts` 实证）：Outbound 5 失败时错误 details 以 `failed to charge card:` 开头，frontend 据此把 gRPC error 翻译为 `HTTP 422 { error: "Your payment could not be processed…", code: "PAYMENT_FAILED" }`（且排除 `Unavailable/Internal` 状态码）；其余任何失败 → `HTTP 500 { error: "Failed to place order." }`。客户端可见的错误分支要追到**发起翻译的那一层**。
- **无 Database 调用**——checkout 服务无本地存储；cart 服务的 Redis、product-catalog 的自持有数据均属下游内部实现，不在本报告展开（调查深度规则见 SKILL.md）。
- **容错分级**是本案例要点：1–6 致命（失败即中止下单）、7 静默忽略、8 降级告警、9 异步尽力而为——同一 endpoint 内四种失败语义，逐一标注。

***

## 案例 B：单体 CRUD — POST /api/owners（Spring Petclinic REST）

背景：Spring 官方 sample，典型三层单体（Controller → Service → Spring Data JPA）。**contract-first**：Controller 实现的 `OwnersApi` 接口由 `src/main/resources/openapi.yml` 经 openapi-generator 生成——搜 route 注解时注意路径可能定义在生成代码/规范文件里。

代码依据：`rest/controller/v1/OwnerRestControllerV1.java`（addOwner）、`service/ClinicServiceImpl.java`（saveOwner → ownerRepository.save）、`model/Owner.java`（@Table owners）、`rest/advice/ExceptionControllerAdvice.java`（错误映射）、`resources/openapi.yml`（OwnerFields 契约）。

```
Inbound:
POST /api/owners
Content-Type: application/json
Authorization: Basic …              ← @PreAuthorize("hasRole(@roles.OWNER_ADMIN)")

{
  "firstName": "George",
  "lastName": "Franklin",
  "address": "110 W. Liberty St.",
  "city": "Madison",
  "telephone": "6085551023"
}

约束（openapi.yml OwnerFields → 生成的 Bean Validation）:
  firstName / lastName  长度 1–30 + 人名 pattern（'-'、空格、'.');
  address ≤255; city ≤80; telephone 有格式 pattern

→ 201 Created
  Location: /api/owners/{id}         ← IDENTITY 主键 persist 后回填
  { "id": 1, "firstName": "George", … }

→ 错误分支（ExceptionControllerAdvice，ProblemDetail RFC 9457 格式）
  401 / 403        未认证 / 角色不足（OWNER_ADMIN）
  400              Bean Validation 失败（MethodArgumentNotValidException → ProblemDetail）
  404              DataIntegrityViolationException（来自 Outbound 1 约束冲突）→ 404
                   （该项目特有映射——数据冲突按 404 表达，调查时如实记录并加注，
                   不按"应该 409"臆改）
  500              兜底（ProblemDetail）
```

```
Outbounds:

1. 保存业主 — Database（Spring Data JPA → Hibernate）
   INSERT INTO owners (first_name, last_name, address, city, telephone)
   VALUES (?, ?, ?, ?, ?)                       ← ClinicServiceImpl.saveOwner
                                                  → ownerRepository.save()（JPA persist，
                                                  由生成的 IDENTITY 策略取回主键）
   -- 后续读写路径参考: SELECT … FROM owners o WHERE o.id = ?（getOwner，不在本 endpoint）
```

补充说明：

- **无缓存 / 消息队列 / 下游 HTTP / SFTP**——典型单体 CRUD 的全部出站就是一条 INSERT；报告价值在于把入站契约（校验、认证、错误映射）与 SQL 实证写准。
- 本 endpoint 无事务注解：事务由 `JpaRepository.save()` 方法级默认事务承担；涉及多实体写操作的 endpoint（如 deleteOwner 级联）才需要标注事务边界与多条 SQL。

***

## 两案例对照（写报告前先对号入座）

| 维度 | 案例 A（分布式） | 案例 B（单体 CRUD） |
|------|-----------------|-------------------|
| Inbound | gRPC（也演示了上游 HTTP 入口的错误翻译） | REST + Basic 认证 |
| 出站技术 | gRPC ×4、HTTP ×3、Kafka | Database ×1 |
| 循环/条件调用 | 逐商品 2N 次 gRPC、feature flag、KAFKA_ADDR 空跳过 | 无 |
| 容错分级 | 致命/忽略/降级/异步四档 | 单条 INSERT，失败即 500 |
| 特有教学点 | 错误跨层传播（422 由 frontend 翻译）；深度规则（下游的下游不展开） | contract-first 入口定位；错误映射如实记录（404 而非想当然的 409） |
