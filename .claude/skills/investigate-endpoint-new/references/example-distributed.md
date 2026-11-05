# oteldemo.CheckoutService/PlaceOrder 调查报告

> 教学范例：OpenTelemetry Astronomy Shop checkout 服务，源码版本 `0aa29ab5e1e34bbf33239d495976109cdef384cc`。坐标相对于仓库根。该例演示多调用业务卡和控制流，不表示其他版本仍具有相同行号或默认值。

## Executive Summary

- 调查范围：静态调查 checkout 的 `PlaceOrder`，覆盖本服务直接一跳；frontend 仅在说明客户端错误翻译时涉及。
- 功能：取购物车 → 逐商品报价与换汇 → 运费报价与换汇 → 扣款 → 发货 → 清购物车、邮件和订单事件。
- 外部依赖：O1（gRPC → cart）、O2（gRPC → product-catalog + currency，含两个顺序调用）、O3（HTTP → shipping 报价）、O4（gRPC → currency）、O5（gRPC → payment）、O6（HTTP → shipping 发货）、O7（gRPC → cart 清空）、O8（HTTP → email）、O9（Kafka → orders）；共 9 张 O 卡，其中 5 张 gRPC、3 张 HTTP、1 张 Kafka，涉及 4 个 gRPC 目标服务、2 个 HTTP 目标服务和 1 个 Kafka topic。
- 注意：O1–O7 未显式配置超时／deadline；O8 显式 1s；O9 为 at-most-once；O7–O9 失败不改变已完成的下单结果。

## Inbound

### I1. gRPC Server → oteldemo.CheckoutService/PlaceOrder

| 鉴权 | 主坐标 |
|---|---|
| 无 | `src/checkout/main.go#PlaceOrder` |

> 编排下单主链路；必经步骤失败即中止，三个收尾步骤按各自策略处理。

**关键事实**
- 无幂等键，重放可能产生重复订单。
- `EmitRawPii` 开启时敏感支付字段可能进入可观测后端。
- 客户端可见错误由 frontend 翻译，证据入口为 `src/frontend/pages/api/checkout.ts`。

<details><summary>Request、Response、处理逻辑与错误映射</summary>

**Request**

`PlaceOrderRequest` 字段契约：`userId`、`userCurrency`、`email`、`address`、`creditCard`。address 包含街道、城市、州／省、国家和邮编；creditCard 包含卡号、过期月、过期年和 CVV。实际 proto 字段名以当前 `demo.proto` 为准。

**处理逻辑**

1. 调用 O1 取购物车。
2. 调用 O2 逐商品报价与换汇（循环：N 件商品执行 N 组两连调）。
3. 调用 O3 报价，再调用 O4 换汇。
4. 调用 O5 扣款；`PaymentUnreachable` 仅改变故障注入目标，不表示扣款步骤默认被跳过。
5. 调用 O6 发货；随后执行 O7、O8、O9 收尾，失败不影响下单结果。

**Response — Success**

gRPC `OK`；返回订单字段 `orderId`、`shippingTrackingId`、`shippingCost`、`shippingAddress`、`items`。frontend 将成功翻译为 HTTP 200。

**错误映射**

| 场景 | 来源 | 对外响应 | 泄漏 |
|---|---|---|---|
| O1–O6 必经步骤失败 | 下游传播 | gRPC error；frontend 再翻译 | 否 |
| O7–O9 收尾失败 | 下游传播 | 不改变下单响应 | 按各卡策略记录 |

</details>

## Operations（O2 循环；O7–O9 收尾；O9 at-most-once）

### O1. gRPC → cart 服务（取购物车）

主坐标：`src/checkout/main.go#getUserCart`

> `GetCart {userId}` 返回商品列表，任一错误向 I1 传播并中止。

### O2. gRPC → product-catalog + currency（逐商品报价）

主坐标：`src/checkout/main.go#prepOrderItems`

> 对每件商品先取商品信息，再换汇到用户币种；这是一张业务卡内的两个顺序调用。

<details><summary>内部调用与数据流</summary>

- O2a：`ProductCatalogService/GetProduct`，输入商品 ID，输出名称和 USD 价格。
- O2b：`CurrencyService/Convert`，输入 O2a 的 USD 价格与 I1 的用户币种，输出目标币种金额。
- 任一调用失败即向 I1 传播；循环次数由购物车商品数决定。
</details>

### O3. 下游 HTTP → shipping 服务（运费报价）

主坐标：`src/checkout/main.go#quoteShipping`

> `POST ${SHIPPING_ADDR}/get-quote` 输入地址和商品列表，返回 `cost_usd` 供订单计价。

### O4. gRPC → currency 服务（运费换汇）

主坐标：`src/checkout/main.go#convertCurrency`

> 将 O3 的 USD 运费转换为 I1 指定的用户币种，失败向 I1 传播。

### O5. gRPC → payment 服务（扣款，含条件故障注入）

主坐标：`src/checkout/main.go#chargeCard`

> `PaymentService/Charge` 扣减商品与运费合计；返回 transactionId 供日志和可观测性使用。

**关键事实**
- `PaymentUnreachable` 开启时使用故障注入地址；关闭时仍正常执行扣款。

### O6. 下游 HTTP → shipping 服务（发货）

主坐标：`src/checkout/main.go#shipOrder`

> `POST ${SHIPPING_ADDR}/ship-order` 创建发货单，返回 `tracking_id` 并进入 I1 订单响应。

### O7. gRPC → cart 服务（清空购物车，收尾）

主坐标：`src/checkout/main.go#emptyUserCart`

> 下单成功后清空购物车；错误被显式忽略，不改变 I1 成功结果。

### O8. 下游 HTTP → email 服务（确认邮件，收尾）

主坐标：`src/checkout/main.go#sendOrderConfirmation`

> 发送订单和收件邮箱；失败记录告警，不改变下单结果。

**关键事实**
- 当前链路中 O8 显式使用 1s 超时，不能纳入「O1–O7 无超时」的共性结论。

### O9. MQ → orders topic（Kafka，异步生产者）

主坐标：`src/checkout/main.go#sendToPostProcessor`

> 发送 OrderResult 事件；等待本地发送结果，但 NoResponse 不等待 broker 确认。

**关键事实**
- `${KAFKA_ADDR}` 为空时跳过；无 key 因而不提供按订单 key 的分区保序。
- `RequiredAcks=NoResponse` 对应 at-most-once，下游需考虑对账。
- `KafkaQueueProblems` 可注入额外消息，属于 demo 故障注入。

## 补充说明

- frontend 对支付失败和其他失败执行不同 HTTP 翻译；报告只记录客户端可见语义，不把 frontend 的内部依赖继续展开。
- cart 的 Redis、product-catalog 的自有数据属于下游内部，不计入本服务 O 卡。
