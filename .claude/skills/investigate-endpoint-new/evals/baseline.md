# 质量评测最低基线

本文件只供评分者使用，测试会话不得读取。它列出各固定版本的最低事实与覆盖期望，不是完整报告，也不要求逐字匹配。任何争议都回指对应 commit 的源码、契约和配置解决。

## 固定输入

| 用例 | 仓库与版本 | 调查目标 |
|---|---|---|
| smoke-crud | `spring-petclinic/spring-petclinic-rest` @ `5180cc9bd0163393c4e628bedc1bc4c27cfaab1b` | `POST /api/owners` |
| smoke-distributed | `open-telemetry/opentelemetry-demo` @ `0aa29ab5e1e34bbf33239d495976109cdef384cc` | `oteldemo.CheckoutService/PlaceOrder` |
| smoke-async-upload | `zanxiaofeng/zxf-springboot-file-upload` @ `cc0917ce78e4766069557a387eabd73fc058b71f` | 异步上传及独立轮询 |
| heldout-sync-upload | 同上一固定版本 | `POST /api/files/sync/upload` |

## smoke-crud

最低覆盖与事实：

- I1 为 `POST /api/owners`，识别 contract-first 的 OpenAPI／生成 API 证据，不能只凭 Controller 注解推断完整契约。
- 记录当前版本的 Basic 鉴权与 `OWNER_ADMIN` 授权，以及鉴权失败的对外语义。
- 主成功路径为输入校验、Owner 保存、主键回填、`201` 响应与 `Location`。
- O1 是本服务到 `owners` 表的 Database 直接交互；若给出 SQL，必须标为 ORM 映射派生而非运行时抓包。
- Request 与 Response 至少有 `firstName`、`lastName`、`address`、`city`、`telephone`，成功响应另有 `id`；追踪五个输入到 O1、`id` 回到响应与 Location。
- 错误翻译覆盖本地校验、鉴权、O1 传播和未捕获异常；保留该项目将相关约束冲突映射为 `404` 的特有行为，不按通用惯例改成 `409`。
- I1、O1 均有有效主坐标，OpenAPI 契约与异常翻译另可核验。

## smoke-distributed

最低覆盖与事实：

- I1 为 checkout 的 `PlaceOrder`，覆盖 Request／Response 的主要业务字段及 frontend 可见错误翻译边界，但不展开 frontend 或下游服务自己的内部依赖。
- 共 9 张主 O 卡：5 张 gRPC、3 张 HTTP、1 张 Kafka；目标计数必须区分调用卡数与目标服务数。O2 是一张业务卡，内部 O2a／O2b 为两个顺序调用，不另算两张主卡。
- 主成功路径包括取购物车、逐商品报价与换汇、运费报价与换汇、扣款、发货，以及清购物车、邮件和订单事件收尾。
- 明确逐商品循环、O2 内部顺序、必经步骤与收尾步骤的不同失败语义；编号不得被解释为所有分支的运行时全序。
- O1–O6 失败会中止或传播，O7–O9 的失败不改变已经完成的下单结果；各收尾策略分别描述。
- 当前版本中 O1–O7 无显式超时／deadline，O8 显式为 1s；不得把 O8 纳入无超时共性结论。
- O5 的 `PaymentUnreachable` 是条件故障注入，不是默认跳过扣款。
- O9 的 NoResponse 语义标为 at-most-once；无消息 key，因而不提供按订单 key 的分区保序；记录 `${KAFKA_ADDR}` 为空时的跳过条件及故障注入的教学性质。
- 关键字段至少追踪用户 ID、币种、商品、地址、支付信息、运费、tracking ID 与订单事件去向；敏感支付字段与 `EmitRawPii` 风险不得泄露真实值。

## smoke-async-upload

最低覆盖与事实：

- I1 为 `POST /api/files/async/upload`，I2 为独立的 `GET /api/files/async/scan/{scanId}`；不得把轮询合并成异步上传的 Response。
- I1 预检后经 O1 暂存并返回 `202` 与 `scanId`，后台不等待地完成扫描；`scanId → I2`。
- 外部／本机交互为 O1／O6 文件系统、O3 ClamAV 自定义 TCP、O4 YARA 子进程；O2 类型与压缩包检查、O5 文档威胁检测标为进程内且无外部系统。
- 主链路表达 O2–O5 顺序及短路、O2–O6 后台异步执行，以及全局开关旁路 O2–O5 时仍保留受理预检与 O1／O6。
- multipart 缺 part／非 multipart 归为平台或框架错误并映射 `400 FILE_REJECTED`；multipart 超限映射 `413 FILE_TOO_LARGE`；业务预检失败为 `400 FILE_REJECTED`；流打开或 staging IO 失败不得误归为 Bean Validation。
- I2 对格式非法 ID 返回 `400`；格式合法但未命中、缓存淘汰或重启丢失均表现为 `SCANNING` 占位而非 `404`。记录 TTL 30 分钟及由此产生的客户端放弃策略需求。
- 数据流至少包括文件流、原始文件名／大小、staging Path、检测 MIME、扫描终态和 CLEAN 时的入库文件名。
- O3 记录 INSTREAM 与当前限时／熔断证据；O4 记录命令、退出码不等于未命中、禁用条件和超时处置；O5 记录 MIME 路由与检测边界；O6 记录入库、隔离、清理及 move 降级。
- fail-open 只适用于引擎故障；业务拒绝和已检出威胁不得因此放行。

## heldout-sync-upload

该用例检验从异步范例泛化到同步入口的能力，不要求复用异步报告的 I／O 编号或措辞。

最低覆盖与事实：

- 入口必须是 `FileUploadController#syncUpload` 对应的 `POST /api/files/sync/upload`，在请求线程内执行完整扫描并返回最终结果；不得写成 `202 + scanId` 或虚构独立轮询依赖。
- 复用与异步链路相同的直接交互和重要进程内阶段：staging、类型／压缩包检查、ClamAV、YARA、文档威胁检测以及最终入库／隔离／清理；按源码标注条件、短路与开关。
- 成功完成扫描时通过 HTTP `200` 返回最终 `ScanResult` 语义；区分业务拒绝、威胁检出、引擎故障与未捕获异常的对外表达，不套用异步轮询的占位语义。
- Request 仍是 multipart 的 `file` part；追踪文件流和元数据进入暂存与扫描，最终结果及 CLEAN 文件路径进入响应。
- 覆盖 multipart 平台限制、业务预检、流打开／staging IO、扫描引擎故障和全局异常翻译；来源分类与固定版本的 `GlobalExceptionHandler` 一致。
- 每张卡有当前 commit 的有效主坐标；范例未直接展示的同步编排与响应构造必须有独立源码证据。

## 基线维护

- 固定 commit 下源码不会漂移；若基线与源码冲突，先修正基线并记录证据。
- 更换目标版本属于新建基线，必须重新人工复核，不得只替换 commit 字符串。
- 新发现的非关键教学点可补充到清单；不得把某一种排版或完整范文设为唯一正确答案。
