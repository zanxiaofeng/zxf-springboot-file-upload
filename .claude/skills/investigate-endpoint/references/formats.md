# Outbound 格式模板库

写 Outbounds 时按技术类型套用对应格式。原则：**格式必须让该技术的从业者一眼看懂"发了什么、期待什么回应"**。动态值用 `{占位符}`，可变的配置值保留 `${ENV:default}` 形式。模板中的示例是通用业务场景（订单/通知/归档），调查时一律替换为**目标代码实证出的真实值**。

***

## 1. 下游 HTTP

完整报文格式，含认证头与错误分支；`{...}` 处写明实际使用的客户端库（RestClient / WebClient / axios / fetch / http.Client…）：

```
3. 通知服务回调 — HTTP（JSON over REST，{客户端库}）
   ─────────────────────────────────────────
   POST ${NOTIFICATION_BASE_URL:-http://notify.internal}/api/v1/notifications
   Content-Type: application/json
   Authorization: Bearer {token 的来源：配置/令牌服务/请求透传}

   { "userId": "{id}", "event": "USER_CREATED" }

   → 200                                       → 成功
   → 4xx {下游错误码}                          → {处置：记日志返回 false / 转业务异常}
   → 5xx / 连接超时({N}s) / 读超时({N}s)       → {处置：降级 / 重试 / 熔断计数}
   ─────────────────────────────────────────
   保护: {熔断器名}（失败率 {N}%，OPEN {M}s）；无重试
```

要点：base-url 指向真实配置键/环境变量；超时与重试参数必写；下游错误码如何影响本端点的 Inbound 错误分支要说明。

## 2. Database

输出**真实 SQL**。按访问方式取材：

- **ORM**（JPA/Hibernate、Entity Framework、GORM、Active Record…）：save()/find() 等写出等价 SQL 并注明由哪个仓库方法派生；对象查询语言（JPQL/HQL 等）原样抄录并附 SQL 对照
- **SQL 映射框架**（MyBatis、Hibernate @Query…）：SQL 即源码，逐字抄录
- **原生驱动 / 查询构建器**：保留参数占位符原样（`?` / `$1` / `:param` / `%s`）

```
2. 保存订单 — Database（{ORM/框架}，{驱动}）
   INSERT INTO orders (user_id, amount, status, created_at)
   VALUES (?, ?, ?, ?)                    ← OrderRepository.save() 派生，时间戳由实体回调填充

2. 查询用户 — Database（对象查询语言原文）
   SELECT u FROM User u JOIN FETCH u.orders WHERE u.id = :id
                                          ← 附 SQL 对照: SELECT … FROM users u JOIN orders o …
```

要点：读写分开列；事务边界（在哪个方法/作用域上开启）注明；触发约束冲突的路径追到 Inbound 错误分支（唯一键冲突 → 真实状态码，勿想当然）；分页查询附 `LIMIT/OFFSET` 与上限 clamp。读写分离/从库路由若存在也要注明。

## 3. 缓存（Redis / Memcached / 本地缓存）

命令序列格式（Redis 用命令原文）：

```
4. 会话读取 — Redis（{客户端库}）
   GET session:{userId}                 → 命中: 反序列化，TTL 剩余 {t}s
                                        → MISS: 回源 DB（见 Outbound N），随后
                                          SET session:{userId} {json} EX 1800
```

要点：key 模式、TTL、miss 回源路径、淘汰策略；本地缓存（Caffeine 等）标注「进程内」并写容量/TTL。

## 4. 消息队列

**Kafka**（produce）：

```
5. 订单事件发布 — Kafka（{客户端库}，{同步/异步}生产者）
   SEND topic={orders-events} key={orderId}
   payload: { "eventType": "ORDER_CREATED", "orderId": "{id}", … }   ← 序列化格式注明
   acks=all, retries=3                   ← 发送失败: {处置——记 ERROR 丢弃 / 本地表补偿 / 阻塞失败}
```

**RabbitMQ / AMQP**：

```
5. 订单事件发布 — RabbitMQ（{客户端库}）
   PUBLISH exchange={orders.x} routing-key={order.created} mandatory=false
   props : content_type=application/json, delivery_mode=2（持久化）
```

要点：topic/exchange/routing key 实证；key 分区语义（保序）；发送失败的处置与可靠性等级（at-most-once / at-least-once）；**消费侧**调查则写 `CONSUME {topic/queue} + 处理逻辑 + ack 方式（自动/手动/重入队）`。

## 5. SFTP / FTP

命令序列（对应协议会话操作）：

```
3. 对账单上传 — SFTP（{客户端库}）
   CONNECT {sftp.host}:{22}, auth={用户名密码 / 密钥}
   CD    /outbound/reports
   PUT   {localTmpPath} → {yyyyMM}/report-{orderId}.csv   (overwrite=false)
   LS    {yyyyMM}/report-{orderId}.csv                    ← 确认落盘
   DISCONNECT
   保护: 连接池 {max}；PUT 失败 → 删除本地临时文件 → 500
```

要点：路径模板、覆盖策略、失败时的本地清理动作；连接复用/池化与 known_hosts 校验注明。

## 6. 自定义 TCP / UDP 协议

报文交换图（`C:` 客户端发出 / `S:` 服务端回应），分隔线围出一个完整会话：

```
3. 风控引擎查询 — TCP（自定义协议，{客户端库}）
   ─────────────────────────────────────────
   TCP {host}:{port}
   C: <2-byte BE 长度>{JSON 请求体}
   S: <2-byte BE 长度>{"code": 0, "risk": "LOW"}
   S: <断开>                                ← 超时语义
   ─────────────────────────────────────────
   保护: 超时 {N}s；客户端库不含 socket 超时（如实写明，它决定超时保护必须在应用层实现）
```

要点：帧/长度前缀等二进制细节用文字标注（`4-byte big-endian`、`\0` 结尾）；库的能力缺陷（如无 socket 超时、线程阻塞模型）如实记录——它们解释了外层为何自建保护。

## 7. 子进程 / CLI

命令行 + 输出解析 + 退出码语义：

```
4. 文档转 PDF — 子进程（CLI 调用）
   $ libreoffice --headless --convert-to pdf --outdir {outDir} {inputFile}
   stdout: 解析规则（如 "convert … -> {outPath} using filter: writer_pdf_Export"）
   退出码: 0 = 成功；非 0 = 转换失败（写明与"业务性失败"如何区分）
   超时 {N}s → 强杀进程树（kill -9 / destroyForcibly，注意孤儿子进程）
```

要点：每个参数含义、stdout/stderr 解析规则、退出码语义（工具退出码 ≠ 业务结果时要写明映射）、超时强杀行为、工作目录与环境变量继承。

## 8. 文件系统

操作动词 + 路径模板；同一业务的多个操作按次序列出：

```
1. 上传暂存 — 文件系统
   write  {uploadDir}/{uuid}.{ext}        ← 请求流原样落盘

6. 处置 — 文件系统（按处理结果分流）
   成功:   copy   {uploadDir}/{uuid}.{ext} → {archiveDir}/{yyyyMM}/{uuid}.{ext}
           delete {uploadDir}/{uuid}.{ext}
   失败:   move   {uploadDir}/… → {deadLetterDir}/{uuid}.failed
           （跨文件系统 move 失败降级 copy+delete）
   拒绝:   delete {uploadDir}/{uuid}.{ext}
```

要点：路径来自配置要写配置键/环境变量；目录权限（如 0700）、失败降级链、临时文件清理责任写全。

## 9. 邮件（SMTP）

```
3. 告警邮件 — SMTP（{客户端库}）
   SMTP {host}:{port}, {STARTTLS / 明文}, auth={凭证来源}
   FROM {from} TO {to}（{抄送/密送如有}）
   SUBJECT: [{环境}] {告警标题}
   BODY: {模板来源，动态字段 {占位符}}
   失败: 记 WARN 不重试（异步任务内已捕获，不影响主流程）
```

## 10. 对象存储（S3 / OSS / MinIO / GCS）

```
2. 备份归档 — S3（{SDK}）
   PUTObject bucket={bucket} key={prefix}/{yyyyMM}/{uuid}.zip
   头: Content-MD5={md5}, StorageClass=STANDARD_IA
   → 200 ETag:{etag}    → 5xx 由 SDK 内建重试 {N} 次
   凭证: {AK/SK 来源——实例角色 / 环境变量 / 配置}
```

## 11. gRPC

```
4. 风控判定 — gRPC（{同步 stub / 异步 stub}）
   CALL risk.v1.RiskService/CheckOrder
   request : { orderId: "{id}", amount: {amount} }          ← protobuf 字段示要
   response: { decision: PASS|BLOCK, reason: "{…}" }
   deadline: {N}ms；UNAVAILABLE → {处置：按降级策略 / 直接失败}
```

要点：全限定 method 名、deadline、状态码处置；与 HTTP 下游的区别在于错误模型是 status code 而非 HTTP 状态码。

## 12. 进程内重解析（重量级库读本地数据）

不算出站系统，但为保序完整仍编号列出，**末尾标注**：

```
5. 文档结构解析 — 进程内（{解析库}），无外部系统
   read {uploadDir}/{uuid}.{ext}
   按格式分流：压缩容器遍历条目名 / 结构化格式逐节点解析 / 分块扫描关键字
```

***

## 格式选择速查

| 出站技术 | 格式 | 关键必写项 |
|---------|------|-----------|
| 下游 HTTP | 完整报文 | 配置键/环境变量、认证头、超时、错误分支 |
| Database | SQL 原文 | 参数占位、派生来源、事务边界、约束冲突路径 |
| 缓存 | 命令序列 | key 模式、TTL、miss 回源 |
| Kafka / AMQP | produce/consume | topic/exchange、payload 序列化、可靠性等级 |
| SFTP / FTP | 命令序列 | 路径模板、失败清理 |
| 自定义 TCP/UDP | 报文交换图（C:/S:） | 二进制细节、客户端库能力缺陷 |
| 子进程 | CLI 命令 | 参数语义、stdout 解析、退出码映射、超时强杀 |
| 文件系统 | 操作 + 路径 | 配置来源、降级链、权限、清理责任 |
| SMTP | 会话 | 收发件人、模板、失败处置 |
| 对象存储 | API 操作 | bucket/key、凭证来源、SDK 重试 |
| gRPC | method + message | 全限定名、deadline、状态码处置 |
| 进程内 | 读操作说明 | 「进程内，无外部系统」标注 |
