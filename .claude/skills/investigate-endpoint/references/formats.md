# 报告 Schema 模板库（Inbound + Outbound）

写报告前查阅——按目标类型直达对应小节即可，无需全文通读。四部分：§一 卡片总模板（I / O 通用三段式）、§二 Inbound 类型专属 schema、§三 Outbound 类型专属 schema（12 类）、§四 速查表。

原则：

- schema 字段的**值**保持原生格式——SQL 原文、原生 JSON 报文、命令序列、C:/S: 报文交换图；「结构化」约束的是报告骨架，不是把报文改写成散文
- 动态值用 `{占位符}`，可变配置保留 `${ENV:default}` 形式
- 模板中的示例是通用业务场景（订单/通知/归档），调查时一律替换为**目标代码实证出的真实值**
- **空值语义**：字段 / 小节在代码中无对应事实时写一行显式事实陈述（「无重试」「无超时配置，默认无限等待」），禁止编一行来填表——模板中的「无 X」示例即此用法

***

## 一、卡片总模板（I / O 通用三段式：元信息表 → 处理逻辑 → 类型专属子结构）

Inbound 卡片（子结构按入口类型选 §二 schema）：

### I1. HTTP Server → POST /api/orders

| 字段 | 内容 |
|---|---|
| 暴露方式 | HTTP Server / gRPC Server / MQ Consumer / 定时任务 / CLI / Webhook / 事件监听 |
| 入口点 | {类.方法}（`file:line`） |
| 路由/契约 | `POST /api/orders` / `oteldemo.OrderService/CreateOrder` / `topic=order.created` |
| 触发方式 | 同步请求 / 异步消费 / 周期触发（cron）/ 外部推送 |
| 鉴权与限流 | JWT / Basic / API-Key / 无；限流规则及来源 `file:line` |
| 幂等 | 幂等键 {…} / 天然幂等 / 无（如实写——它决定重放后果） |
| 处理逻辑 | ① 校验入参 ② 鉴权 ③ 调用 O1 ④ 调用 O2（条件：库存校验开启时） ⑤ 落库（O2） ⑥ 组装响应 |
| 下游依赖 | O1、O2、O3（见对应卡片） |

{入口类型专属子结构，见 §二}

Outbound 卡片（子结构按出站类型选 §三 schema）：

### O1. 下游 HTTP → 库存服务

| 字段 | 内容 |
|---|---|
| 类型 | 下游 HTTP / gRPC / DB / 缓存 / MQ / SFTP / 对象存储 / 子进程 / 文件系统 / SMTP / 自定义协议 / 进程内 |
| 触发点 | {类.方法}（`file:line`） |
| 触发条件 | 主链路必经 / 条件分支（{条件}）/ 异步 |
| 处理逻辑 | ① … ② …（循环：逐条目 N 次） ③ …（与 ② 并行） |
| 失败处理 | 致命（中止本链路）/ 静默忽略 / 降级 / 异步补偿——落到具体异常与后续状态 |
| 保护与幂等 | 超时 {N}s / 无超时配置；重试 {N} 次（退避 {…}）/ 无重试；幂等键 {…} / 无 |
| file:line | 客户端 `x:y`、调用点 `a:b`、错误映射 `c:d` |

{出站类型专属子结构，见 §三}

***

## 二、Inbound 类型专属 Schema

### 2.1 HTTP / gRPC Server（同步服务入口）

三件套必填：`Request（入参契约）/ Response — Success / Response — Failed（错误映射表）`。

##### Request（入参契约）
- Method / Path: `POST /api/orders`（路由模板；gRPC 写全限定 method `oteldemo.OrderService/CreateOrder` + request message）
- 契约定义位置: `OrderController.java:34`（注解 / 路由表 / OpenAPI spec——contract-first 项目查 spec 的 `operationId`）
- 参数: Path `{orderId}`（string, 必填）；Query `?expand={…}`；Headers `Authorization: Bearer <jwt>`
- Body 模型: `CreateOrderRequest { skuId, qty }`（`dto/CreateOrderRequest.java:12`）；非 JSON（multipart / XML / 二进制流）按原生结构展示
- 校验规则: `@NotNull skuId`、`qty >= 1`（`OrderController.java:40` / `@Valid`）
- 平台级限制: 请求体上限 {N}MB（与业务校验分开归因）

##### Response — Success
- Status: `201`
- Body: `{ "orderId": "{uuid}", "status": "CREATED" }`（核实真实字段：null 省略、命名 camelCase/snake_case、包装信封）
- 组装位置: `OrderController.java:58`；写副作用: 创建 Order 记录（属 O2）
- 异步受理形态: `202` + 轮询地址 / 回调，注明结果如何查询

##### Response — Failed（错误映射表——Inbound 特有必填）
| 内部异常 / 场景 | 错误来源 | 对外响应 | 映射位置 | 是否泄漏内部信息 |
|---|---|---|---|---|
| 参数校验失败 | 本地校验 | `400 {"code":"INVALID_ARG"}` | `GlobalExceptionHandler.java:20` | 否 |
| 未认证 | 鉴权 | `401` | `AuthFilter.java:30` | 否 |
| 库存不足（O1 传播） | 下游传播 | `409 {"code":"INSUFFICIENT"}` | `OrderService.java:96` | 否 |
| 下游 5xx（O1） | 下游传播 | `503 {"code":"DEPENDENCY_UNAVAILABLE"}` | `GlobalExceptionHandler.java:41` | 否 |
| 未捕获异常 | 未捕获 | `500 {"code":"INTERNAL"}` | `GlobalExceptionHandler.java:55` | 需确认是否含堆栈 |

> 规则：错误来源分四类（本地校验 / 鉴权 / 下游传播 / 未捕获）；跨层翻译（gRPC → HTTP 等）写**发起翻译的那一层**的位置；每行 `file:line`；代码中无对应的分支（如无限流）在元信息表写「无」，不进表。

### 2.2 MQ Consumer（异步消费入口）

##### 消息契约
- Topic / Queue: `order.created`；消费组: `order-service`
- Message Body: `{...}`；Headers: `traceId`
- 反序列化与 Schema 校验位置: `...:line`

##### 消费语义
- 模式: 推 / 拉；并发度 {N}；顺序性要求 {按 key 保序 / 无}
- 幂等: `messageId` 去重表 / 天然幂等 / 无
- 重试与 DLQ: 重试 {N} 次 → 死信队列 `order.created.dlq` / 无 DLQ（写明毒消息的去向）

##### 失败处理
| 场景 | 本地行为 | 源码依据 |
|---|---|---|
| 业务异常 | nack 重试 {N} 次 / 直接 DLQ | `...` |
| 反序列化失败 | 转 DLQ + 告警 | `...` |

### 2.3 定时任务 / CLI / 事件监听

统一四段：

- **触发定义**: cron `{...}` / 命令行 `{...}` / 事件源 `{...}`；并发控制 {单实例锁 / 允许并行}
- **入参来源**: 环境变量 / 命令行参数 / 事件体
- **主处理逻辑**: ①②③（引用 O 编号）
- **失败处理**: 重试、告警、幂等、漏跑补偿（错过的周期如何补）

***

## 三、Outbound 类型专属 Schema

### 3.1 下游 HTTP

三件套必填：`Request / Response — Success / Response — Failed`。客户端库（RestClient / WebClient / axios / fetch / http.Client…）写在 Request 首行。

##### Request
- Method / Path: `POST ${NOTIFICATION_BASE_URL:-http://notify.internal}/api/v1/notifications`（{客户端库}）
- Headers: `Content-Type: application/json`；`Authorization: Bearer {token 的来源：配置/令牌服务/请求透传}`；`Idempotency-Key: {orderId}`（如有）
- Query: 无 / `?a=b`
- Body: `{ "userId": "{id}", "event": "USER_CREATED" }`
- 源码取材: `NotificationClient.java:55-62`（DTO `NotificationRequest`）

##### Response — Success
- Status: `200`
- Body: `{ "notificationId": "string", "sentAt": "iso8601" }`
- 本地消费: 取 `notificationId` 记入订单流水（`OrderService.java:128`）

##### Response — Failed
| 场景 | 状态码 / 异常 | 本地行为 | 源码依据 |
|---|---|---|---|
| 业务拒绝 | `409 {"code":"INSUFFICIENT"}` | 抛 `InventoryRejectedException` → 订单置 FAILED | `InventoryClient.java:70` |
| 下游 5xx | `500 / 503` | 重试 2 次 → 降级「待确认」 | `InventoryClient.java:75-83` |
| 连接 / 读超时 | `SocketTimeout {N}s` | 同 5xx，转异步补偿 | `InventoryClient.java:80` |
| 反序列化失败 | `JsonParseException` | 按 5xx 处理 | `InventoryClient.java:84` |

> 规则：Failed 表只收**代码真实存在的分支**，每行 `file:line`；代码未配置的分支写事实行（如「超时：未配置，默认无限等待」）。下游错误如何传导到 Inbound 错误映射表，在元信息表「失败处理」注明去向；熔断 / 重试参数写元信息表「保护与幂等」。

### 3.2 Database

四段必填：`Statement / 参数绑定与返回值 / 事务与隔离 / 失败处理`。SQL 一律**实证**，读写分开列条目。按访问方式取材：ORM（JPA/Hibernate…）写派生 SQL 并注明来源方法，对象查询语言（JPQL…）原样抄录附 SQL 对照；SQL 映射框架（MyBatis…）逐字抄录；原生驱动保留占位符原样（`?` / `$1` / `:param` / `%s`）。

##### Statement
- 操作类型: INSERT
- SQL:
  ```sql
  INSERT INTO orders (user_id, amount, status, created_at)
  VALUES (?, ?, ?, ?)        -- OrderRepository.save() 派生，时间戳由实体回调填充
  ```
- 对象查询语言对照: `SELECT u FROM User u JOIN FETCH u.orders WHERE u.id = :id` → `SELECT … FROM users u JOIN orders o …`

##### 参数绑定与返回值
- 入参: `userId`, `amount`, `status`（绑定位置 `OrderRepository.save()`）
- 返回 / 影响行数: IDENTITY 主键 persist 后回填；影响行数校验 `==1`，否则抛 `OptimisticLockException`
- 分页: `LIMIT {n} OFFSET {m}`，上限 clamp `{max}` / 无分页

##### 事务与隔离
- 事务边界: `@Transactional`（`OrderService.java:60`）；无注解时写明实际承担者（如「框架方法级默认事务」）
- 隔离级别 / 锁: READ_COMMITTED / `SELECT … FOR UPDATE` / 默认（未显式设置）
- 读写分离 / 从库路由: 无 / 有（注明路由条件）

##### 失败处理
| 异常 | 本地行为 | 源码依据 |
|---|---|---|
| `DataIntegrityViolationException` | 唯一键冲突 → 400（映射位置 `ExceptionAdvice.java:41`，如实记录勿想当然） | `...:92` |
| `DeadlockLoserDataAccessException` | 重试 1 次 | `...:95` |

### 3.3 缓存（Redis / Memcached / 本地缓存）

四段必填：`Command / Value 结构 / TTL 与一致性 / 失败处理`。命令用原文（Redis 命令序列）；本地缓存（Caffeine / Guava）无命令协议，以客户端 API 调用形态替代（如 `Cache.getIfPresent(key)`），并标注「进程内」。

##### Command
```redis
GET session:{userId}          → 命中: 反序列化，TTL 剩余 {t}s
                              → MISS: 回源 DB（O2），随后 SET session:{userId} {json} EX 1800
```
- Key 模式: `session:{userId}`，前缀来源 `CacheKeys.java:12`

##### Value 结构
- 序列化: JSON / JDK 序列化 / Protobuf；结构 `{ "userId": "{id}", "loginAt": "{ts}" }`

##### TTL 与一致性
- TTL: 1800s；更新策略: 写后删除 / 双删 / 订阅 binlog / 无失效
- 淘汰 / 容量: 本地缓存（Caffeine 等）标注「进程内」并写容量与 TTL

##### 失败处理
| 场景 | 本地行为 | 源码依据 |
|---|---|---|
| 连接超时 | 降级直查 DB（O2） | `...:66` |
| 缓存穿透 | 空值缓存 60s | `...:70` |

### 3.4 消息队列（Producer）

四段必填：`消息定义 / 发送模式 / 可靠性 / 失败处理`。Kafka 与 AMQP 各按原生格式。

##### 消息定义
- Kafka: `SEND topic=${KAFKA_TOPIC:-orders} key={orderId}`；payload `{ "eventType": "ORDER_CREATED", "orderId": "{id}" }`（序列化格式注明 protobuf / JSON）；key 分区语义（保序）
- AMQP: `PUBLISH exchange={orders.x} routing-key={order.created} mandatory=false`；`props: content_type=application/json, delivery_mode=2（持久化）`
- Headers: `traceId`、`schemaVersion`

##### 发送模式
- 同步 / 异步生产者；事务消息 / 普通发送

##### 可靠性
- 确认: `acks=all` / publisher confirm / `RequiredAcks=NoResponse`；投递语义 at-least-once / at-most-once（如实写，它决定下游对账方式）
- 失败兜底: 本地重试 {N} 次 → 失败落表 `mq_outbox` 后台补偿 / 记 ERROR 丢弃

##### 失败处理
| 场景 | 本地行为 | 源码依据 |
|---|---|---|
| Broker 不可达 | 写入 outbox，后台补偿 | `...:88` |
| 发送超时 | 记 ERROR 丢弃（at-most-once） | `...:92` |

> **消费侧**调查属 Inbound（§2.2），不在此展开。

### 3.5 gRPC（下游调用）

四段必填：`Method 与 Message / Deadline / 状态码处置 / 失败处理`。

##### Method 与 Message
- `CALL risk.v1.RiskService/CheckOrder`（{同步 stub / 异步 stub}，addr=${RISK_ADDR}）
- request: `{ orderId: "{id}", amount: {amount} }`（protobuf 字段示意）
- response: `{ decision: PASS|BLOCK, reason: "{…}" }`

##### Deadline
- `{N}ms` / 未显式设置（写明——gRPC 默认无限等待，超时保护缺失本身就是值得记录的事实）

##### 状态码处置
- `UNAVAILABLE → {按降级策略放行 / 直接失败}`；与 HTTP 下游的区别：错误模型是 status code 而非 HTTP 状态码

##### 失败处理
| 状态码 / 异常 | 本地行为 | 源码依据 |
|---|---|---|
| `DEADLINE_EXCEEDED` | 按「下游不可用」降级 | `...:70` |

### 3.6 对象存储（S3 / OSS / MinIO / GCS）

四段必填：`操作与路径 / 请求头与元数据 / 凭证与 SDK 行为 / 失败处理`。

##### 操作与路径
- `PUTObject bucket={bucket} key={prefix}/{yyyyMM}/{uuid}.zip`（{SDK}）

##### 请求头与元数据
- `Content-MD5={md5}`、`StorageClass=STANDARD_IA`

##### 凭证与 SDK 行为
- 凭证: {实例角色 / 环境变量 / 配置}（只写来源，不写值）
- SDK 内建重试: 5xx 重试 {N} 次 / 无

##### 失败处理
| 场景 | 本地行为 | 源码依据 |
|---|---|---|
| 上传失败 | 保留本地临时文件 + 告警人工处理 | `...:77` |

### 3.7 SFTP / FTP

四段必填：`会话与认证 / 传输操作 / 触发与时机 / 失败处理`。操作用命令序列原文。

##### 会话与认证
- `CONNECT {sftp.host}:{22}`，auth={用户名密码 / 密钥}；连接复用/池化 {max}；known_hosts 校验 {有 / 无}

##### 传输操作
```sftp
CD    /outbound/reports
PUT   {localTmpPath} → {yyyyMM}/report-{orderId}.csv   (overwrite=false)
LS    {yyyyMM}/report-{orderId}.csv                    ← 确认落盘
DISCONNECT
```

##### 触发与时机
- 主链路必经 / 对账批处理（每日 {cron}）；目录不存在时 {mkdir -p / 失败}

##### 失败处理
| 场景 | 本地行为 | 源码依据 |
|---|---|---|
| PUT 失败 | 删除本地临时文件 → 500 | `...:90` |

### 3.8 自定义 TCP / UDP

四段必填：`帧格式与会话 / 连接与超时 / 粘包与分包 / 失败处理`。会话用 C:/S: 报文交换图原文——它是二进制协议最有表达力的形态。

##### 帧格式与会话
```text
C: <2-byte BE 长度>{JSON 请求体}
S: <2-byte BE 长度>{"code": 0, "risk": "LOW"}
S: <断开>                                ← 超时语义
```

##### 连接与超时
- `TCP {host}:{port}`；超时 {N}s / 客户端库不含 socket 超时（如实写明——它决定超时保护必须在应用层实现）

##### 粘包与分包
- 长度前缀 {4-byte big-endian} / `\0` 结尾 / 无（裸流）

##### 失败处理
| 场景 | 本地行为 | 源码依据 |
|---|---|---|
| 连接拒绝 | 熔断 {N}s 后重试 | `...:58` |

### 3.9 子进程 / CLI

四段必填：`命令行与参数 / 输入与环境 / 输出解析与退出码 / 失败处理`。

##### 命令行与参数
- `$ libreoffice --headless --convert-to pdf --outdir {outDir} {inputFile}`（每个参数含义注明）

##### 输入与环境
- stdin: 无 / {内容}；环境变量继承: {全继承 / 白名单}；工作目录: {dir}

##### 输出解析与退出码
- stdout 解析规则: `"convert … -> {outPath}"` 提取产物路径
- 退出码: 0 = 成功；非 0 = 转换失败（工具退出码 ≠ 业务结果时写明映射）

##### 失败处理
| 场景 | 本地行为 | 源码依据 |
|---|---|---|
| 超时 {N}s | 强杀进程树（kill -9 / destroyForcibly，注意孤儿子进程） | `...:45` |

### 3.10 文件系统

四段必填：`路径与权限 / 写入模式 / 操作序列 / 失败处理与清理责任`。操作用动词 + 路径模板原文。

##### 路径与权限
- `{workDir}/{batchId}.csv`；来源 {配置键 / 环境变量}；目录权限 {0700}

##### 写入模式
- 覆盖 / 追加 / 临时文件 + rename（原子性）

##### 操作序列
```text
write  {workDir}/{batchId}.csv                          ← 批处理结果写出
move   {workDir}/{batchId}.csv → {archiveDir}/{yyyyMM}/ ← 成功归档（跨文件系统失败降级 copy+delete）
move   {workDir}/{batchId}.csv → {failedDir}/{batchId}.failed   ← 失败归档
delete {workDir}/{batchId}.csv                          ← 收尾清理（无论分流去向）
```

##### 失败处理与清理责任
| 场景 | 本地行为 | 源码依据 |
|---|---|---|
| move 失败 | 降级 copy+delete；仍失败告警 | `...:120` |

### 3.11 SMTP

四段必填：`会话与认证 / 收件人与模板 / 发送模式 / 失败处理`。

##### 会话与认证
- `SMTP {host}:{port}`，{STARTTLS / 明文}，auth={凭证来源}

##### 收件人与模板
- `FROM {from} TO {to}`（抄送/密送如有）；SUBJECT: `[{环境}] {告警标题}`；BODY 模板来源 + `{占位符}` 动态字段

##### 发送模式
- 同步 / 异步任务 / 队列

##### 失败处理
| 场景 | 本地行为 | 源码依据 |
|---|---|---|
| 发送失败 | 记 WARN 不重试（异步任务内已捕获，不影响主流程） | `...:33` |

### 3.12 进程内重计算（重量级库）

不算出站系统，为保序完整仍编号，**不套四段**，两行说清 + 末尾标注：

- `O5. 进程内（{解析库}）→ 报文结构校验，无外部系统`
- 操作: parse {payload 来源}；按内容分流: schema 校验 / 逐节点解析 / 大报文分块扫描

***

## 四、Schema 速查表

| 类型 | 必填小节 | 关键必写项 |
|---|---|---|
| Inbound: HTTP / gRPC Server | Request / Success / Failed 错误映射表 | 契约定义位置、校验规则、错误来源四分类、映射位置 |
| Inbound: MQ Consumer | 消息契约 / 消费语义 / 失败处理 | 反序列化位置、幂等、DLQ 去向 |
| Inbound: 定时 / CLI / 事件 | 触发定义 / 入参来源 / 主处理逻辑 / 失败处理 | 并发控制、漏跑补偿 |
| Outbound: 下游 HTTP | Request / Success / Failed 表 | 配置键、认证头、超时、错误分支 |
| Outbound: Database | Statement / 参数绑定 / 事务隔离 / 失败处理 | SQL 实证、占位符、事务边界、约束冲突路径 |
| Outbound: 缓存 | Command / Value 结构 / TTL 一致性 / 失败处理 | key 模式、TTL、miss 回源 |
| Outbound: MQ Producer | 消息定义 / 发送模式 / 可靠性 / 失败处理 | topic/exchange、序列化、投递语义 |
| Outbound: gRPC | Method 与 Message / Deadline / 状态码处置 / 失败处理 | 全限定名、deadline、状态码处置 |
| Outbound: 对象存储 | 操作与路径 / 头与元数据 / 凭证与 SDK / 失败处理 | bucket/key、凭证来源、SDK 重试 |
| Outbound: SFTP / FTP | 会话与认证 / 传输操作 / 触发时机 / 失败处理 | 路径模板、失败清理 |
| Outbound: 自定义 TCP/UDP | 帧格式与会话 / 连接超时 / 粘包分包 / 失败处理 | C:/S: 交换图、库能力缺陷 |
| Outbound: 子进程 | 命令行 / 输入环境 / 输出与退出码 / 失败处理 | 参数语义、stdout 解析、超时强杀 |
| Outbound: 文件系统 | 路径权限 / 写入模式 / 操作序列 / 失败清理 | 配置来源、降级链、清理责任 |
| Outbound: SMTP | 会话认证 / 收件人与模板 / 发送模式 / 失败处理 | 模板来源、是否影响主链路 |
| Outbound: 进程内 | 轻量两行 + 「无外部系统」标注 | 分流逻辑 |
