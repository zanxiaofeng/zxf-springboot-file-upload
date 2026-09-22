# Spring Boot Web API 架构选型参考手册

> 版本：v1.6　|　日期：2026-09-22
>
> v1.1 修订：修复决策流程 Q5 可达性、六边形依赖方向表述、Consumer 口径、Entity 语义注释、ArchUnit 规则适用范围等。
> v1.2 修订：第四章包结构示例由骨架级扩充为类级别（含具体类名与职责注释）。
> v1.3 修订：新增第八章"异步 + 轮询 API"专题；修复包结构复审问题（4.6 共享仓储违反"禁跨切片共享"规则、4.7 包名含连字符不合法、JPA 组件命名误导、A 方案 VO 口径、4.2 补消费入口等）。
> v1.4 修订：修复决策流程 Q3=否 死路与兜底可达性、B 方案写链口径（Entity 兼 PO）、2.1 补微服务成九种、ArchUnit PO 规则对六边形布局失效、幂等命中状态码统一、@Async 自调用陷阱提示；补 4.2 client/task 包、4.5 bootstrap 内容；新增 2.3 管道（Pipe-Filter）维度与 4.9 Clean/Onion 包结构。复审补遗：4.1 补 listener/ 入口、3.2 垂直切片定时任务表述对齐 4.6、第五章补 Clean/Onion 行、2.3 措辞与 Verdict 结论对齐、4.9 ArchUnit 包名映射补全、8.6 改用 Mapper 词汇。
> v1.5 修订：第三章概念体系补齐（① 实时通道/其他协议入口、② Repository/Port 接口与 Factory、③ Cache/对象存储出站依赖、④ VO 三途定义、"四类之外：横切与装配"定位说明、3.4 标题泛化）；新增第九章"事件驱动设计"专题（双形态对照、六环节生命周期与 AFTER_COMMIT 相位、事件本体设计与 Outbox 表字段、常见坑、各方案落位速查）。
> v1.6 修订：新增 9.6"事件类的包结构安放"（7 角色安放铁律 + 逐方案落点表 + Spring 纯 POJO 发布前提）；第四章同步落位：4.1 补 event/、4.2 补 command/event/、4.3 补领域事件与进程内 Handler、4.4 补集成事件载荷、4.5 领域事件改为随聚合分包（修平铺矛盾）、4.6 补切片间事件通信、4.9 补事件三处落点。
>
> **如何使用本文档**：先在【第一章】用决策流程和决策表锁定候选方案；再到【第三章】理解四类核心概念（入口适配器 / 核心业务 / 出口适配器 / 数据结构）在该架构中的位置与数据流转；然后从【第四章】复制包结构骨架开工；最后按【第七章】的命名约定、ArchUnit 守护规则和检查清单落地护航。

---

## 第一章　决策速查（先看这里）

### 1.1 快速决策流程

按顺序回答，命中即停；Q5 不选方案，是最后的**叠加判断**（在已选方案或兜底 B 之上）：

| 步骤 | 问题 | 判断与去向 |
|---|---|---|
| Q1 | 业务是否以 CRUD 为主（管理后台、内部系统、MVP）？ | 是 → **方案 A 经典分层** |
| Q2 | 业务中等，但列表/报表/复杂查询很多？ | 是 → **方案 B 分层 + 轻量 CQRS** |
| Q3 | 复杂核心业务域（交易、订单、计费、风控）？ | 是 → 继续 Q4；否 → 落 **方案 B 兜底**（随后仍过 Q5） |
| Q4 | 读远多于写、查询极复杂、或下游系统多需解耦？ | 是 → **方案 D 全配置（DDD + 六边形 + CQRS + 事件驱动）**；否 → **方案 C（DDD + 六边形）** |
| Q5 | 业务边界不稳定、未来有拆分微服务预期？ | 是 → 在已选方案（含兜底 B）外套 **E 模块化单体**的模块外壳（E 也可独立作为整体形态，模块内部按需选 A~D）；否 → 维持已选方案 |

兜底规则：流程未命中任何方案（Q1/Q2/Q3 均答"否"的"既不简单也不复杂"中等系统）→ 默认 **B**，务实起步、保留演进空间。

三个降级/约束条件（优先级高于上述流程）：

- **团队无 DDD 经验或工期紧** → 降级回 A/B，勿硬上 C/D（架构债比技术债难还）；
- **强一致性要求**（如账务核心）→ 即使选 D，CQRS 也只做**逻辑读写分离**（同库同事务），不引入物理分离；
- **查询切片极多但写简单** → 可在任意方案上叠加垂直切片组织查询侧。

### 1.2 场景—方案决策表

| 场景特征 | 推荐方案 | 理由 |
|---|---|---|
| 简单 CRUD、管理后台、MVP 原型 | A 经典分层 | 成本最低，交付最快 |
| 业务中等、报表/复杂查询多 | B 分层 + 轻 CQRS | 查询绕开领域层，务实高效 |
| 核心复杂业务（交易/计费/风控）、规则多 | C DDD + 六边形 | 复杂逻辑有归属，可测试可演进 |
| C 的场景 + 高并发 + 读多写少 + 多下游 | D 全配置 | 读写独立扩展，事件解耦 |
| 边界未稳定、有微服务预期 | E 模块化单体 | 保留拆分可能性，避免过早分布式 |
| 团队经验不足 + 工期紧 | A/B（降级） | 架构债比技术债更难还 |
| 强一致性（账务核心） | C；若已选 D，CQRS 降级为逻辑分离（同库同事务） | 规避最终一致性风险 |

### 1.3 决策维度速查（按权重排序）

| 维度 | 倾向简单方案（A/B） | 倾向复杂方案（C/D/E） |
|---|---|---|
| 业务复杂度 | CRUD 为主 | 规则/状态机/流程复杂 |
| 团队经验 | 初中级为主 | 有 DDD 实践者带队 |
| 项目生命周期 | 1 年以内或一次性 | 3 年以上持续演进 |
| 读写比例 | 均衡 | 读远大于写，或查询极复杂 |
| 外部集成 | 少 | 多 MQ、多第三方系统 |
| 交付压力 | 紧急 | 允许前期建模投入 |

---

## 第二章　架构模式全景

### 2.1 九种模式一句话对比

| 模式 | 一句话 | 核心优点 | 核心代价 | 适用 |
|---|---|---|---|---|
| 经典分层 | Controller→Service→Mapper 单向依赖 | 简单、零学习成本 | Service 易膨胀成上帝类 | CRUD、小项目 |
| 轻量 CQRS | 读写两条代码路径（同库即可） | 查询不被领域绑架 | 代码路径翻倍 | 报表/查询多的 B 端系统 |
| 六边形 | 领域居中，端口+适配器，依赖倒置 | 可测试、技术栈可替换 | 接口与对象转换代码多 | 外部集成多、长期演进 |
| DDD 四层 | 接口/应用/领域/基础设施，聚合承载规则 | 复杂业务的应对之道 | 学习曲线陡 | 核心复杂域 |
| 全配置 D | DDD + 六边形 + CQRS + 事件驱动 | 读写独立扩展、彻底解耦 | 复杂度最高；投影滞后与重建、事件 schema 演进、重放乱序等运维成本 | 高并发核心系统 |
| Clean/Onion | 同心圆，依赖只朝内 | 与六边形同族 | 同六边形 | 同六边形 |
| 垂直切片 | 按用例组织而非按层组织 | 切片自包含、改动范围小 | 共享抽取需克制 | 用例差异大、快速迭代 |
| 模块化单体 | 单体内按限界上下文划模块 | 演进到微服务的最佳前站 | 需纪律与工具守护边界 | 边界未定的中期项目 |
| 微服务 | 一个限界上下文 = 一个独立部署的服务 | 独立演进、独立伸缩、技术异构 | 分布式复杂性：最终一致、集成测试、运维成本 | 边界已稳定、规模与团队撑得起 |

### 2.2 模式之间是正交可组合的，不是互斥的

- **分层**管"代码怎么放"（纵向职责切分）；
- **六边形/Clean** 管"依赖朝哪个方向"（内圈不依赖外圈）；
- **DDD** 管"业务模型怎么建"（聚合、事件、限界上下文）；
- **CQRS** 管"读写怎么分"（两条模型/路径）；
- **垂直切片**管"按什么维度组织代码"（用例 vs 层）；
- **管道**管"核心流程怎么执行"（阶段化、顺序即规则、可短路，见 2.3）；
- **模块化单体 / 微服务**管"系统边界怎么划"（模块 vs 服务）。

一个系统完全可以各取所长：例如"模块化单体 + 订单模块内部用 DDD 四层 + 查询侧轻 CQRS + 用户模块内部经典三层"。

### 2.3 补充维度：管道（Pipe-Filter）——当核心域是"一条流程"

§2.2 的其余维度回答"代码怎么组织、边界怎么划"；管道这一维回答的是"**核心流程怎么执行**"。若业务核心是一条多阶段处理链——文件安全扫描、内容审核、风控规则链、数据加工/ETL——"厚 Service 里一坨顺序 if-else"不是唯一解，管道模式更贴形：

- **骨架**：`Stage` 接口（`execute(Context) → Verdict`）+ `Pipeline`（按序执行）+ `Verdict`（sealed 结论，如 Passed / Infected / Rejected / Flagged）/ `Context`（阶段间传参与上游产物）；**首个非 Passed 结论即短路**；
- **顺序即领域规则**：阶段顺序（先类型校验后病毒扫描、先便宜后昂贵）是业务规则不是技术细节——应显式装配在一个配置类里，新增阶段 = 新实现 + 装配加一行（OCP）；
- **与六边形天然组合**：管道骨架（接口/结论/上下文/执行器）放 domain，零框架依赖可纯单测；依赖引擎与配置的 Stage 实现放 application；引擎客户端是普通次适配器。包结构示例：

```
com.example.app
├── domain/scan/                        # 管道骨架：零框架依赖，可纯单测
│   ├── ScanStage.java                  #   阶段接口：execute(ScanContext) → ScanVerdict
│   ├── ScanVerdict.java                #   sealed 结论：Passed / Infected / Rejected / Flagged
│   ├── ScanContext.java                #   阶段间上下文（携带上游产物）
│   └── ScanPipeline.java               #   按序执行 + 首个非 Passed 短路
├── application/scan/
│   ├── ScanPipelineConfig.java         #   阶段顺序显式装配（顺序即领域规则）
│   └── stage/                          #   Stage 实现 ×N（可依赖引擎客户端/配置）
│       ├── FileTypeScanStage.java
│       └── VirusScanStage.java
└── infrastructure/scan/                # 引擎客户端（次适配器）
    └── clamav/ClamAvClient.java
```

- **入域判定**：阶段的构成、顺序与短路由业务说了算 → 管道骨架入 domain；只是技术性加工步骤（无业务规则）→ 留在 application 编排即可，不必入 domain。
- 实例参考：文件上传安全扫描管道（类型校验 → 病毒扫描 → YARA 规则 → 文档威胁，四阶段短路）即"六边形骨架 + 管道核心 + 受理边 CQRS"的组合落地。

---

## 第三章　四类核心概念在架构中的位置（坐标系）

### 3.1 概念定义与职责

**① 入口适配器（Inbound / 驱动侧）——把外部触发翻译成用例调用**

| 概念 | 职责 | 铁律 |
|---|---|---|
| Web Controller | HTTP 请求 → 参数校验 → 调应用服务/用例 → DTO 返回 | 保持薄，不写业务 |
| Message Consumer | MQ 消息 → 翻译成命令 → 调应用服务/用例 | 必须幂等；与 Controller 平级 |
| Scheduler | 定时触发（@Scheduled / XXL-Job / Quartz）→ 调用例 | 只触发不处理；常用于 Outbox 轮询、超时关单、对账补偿 |
| 实时通道 / 其他协议 | SSE / WebSocket 推送连接（见 8.1）、gRPC / GraphQL 端点、应用启动钩子 | 同 Controller：把协议报文/触发翻译成用例调用，协议差异不出入口层 |

**② 核心业务（Domain & Use Case）——业务规则唯一的安放地**

| 概念 | 职责 |
|---|---|
| Use Case / Application Service | 用例编排、事务边界、权限/日志等横切编排；本身无业务规则 |
| Aggregate / Entity / ValueObject | 充血模型，业务规则与状态守护（聚合根是唯一入口） |
| Domain Service | 跨聚合的领域逻辑 |
| Domain Event | 已发生的业务事实（OrderPlacedEvent），聚合内产生 |
| Repository / Port 接口 | 领域声明的持久化与外部依赖契约（"我要存什么/调什么"），实现在出口适配器——依赖倒置的支点 |
| Factory | 复杂创建逻辑的归属（简单创建用构造函数/静态工厂即可，勿为Factory而工厂） |

**③ 出口适配器（Outbound / 被驱动侧）——领域对外部世界只有接口声明**

| 概念 | 职责 |
|---|---|
| Persistence | 仓储/持久化实现（JPA、MyBatis），领域只定义接口（端口） |
| Client | 外部 HTTP/RPC 调用，兼**防腐层**：外部 DTO 就地翻译成内部模型 |
| Message Publisher | 领域事件 → 集成事件 → MQ；配事务性发件箱保证一致性 |
| Cache / Object Storage | Redis 等缓存、OSS 等对象存储——同为被驱动的出站依赖，照端口+适配器处理（注意：读缓存 ≠ 读模型，后者是 CQRS 投影） |

**④ 数据结构——跨层流动的载体**

| 结构 | 定义 | 出生/消亡位置 |
|---|---|---|
| DTO | 接口边界传输对象（request/response） | 入口适配器层；不出接口层 |
| Command | 写意图封装（PlaceOrderCmd） | 入口 → 应用层 |
| Query | 读意图封装（OrderDetailQuery） | 入口 → 查询服务 |
| Entity / Model | 领域对象（含行为） | 只在核心层 |
| PO / DO | 持久化对象（表映射） | 只在持久化适配器；不出基础设施层 |
| VO | 读侧视图对象（来源三途：查询 SQL 直出 / 读模型投影产出 / Clean Presenter 产出） | 查询链路终点 |
| Event | 事件载荷（领域事件/集成事件） | 核心层产生，MQ 传播 |

**转换铁律**：跨层才转换，层内不转；统一用 MapStruct；DTO 不出接口层，PO 不出基础设施层，Entity 不出核心层。

> **四类之外：横切与装配**。config/（SecurityConfig、Bean 装配）、common/（异常体系、Result、枚举工具）、bootstrap/（启动装配）不承载业务概念，不进这个坐标系，但每种方案的包结构里都有它们的固定位置（见第四章）。

### 3.2 概念 × 架构 总矩阵

| 架构 | 入口适配器 | 核心业务 | 出口适配器 | 数据结构 |
|---|---|---|---|---|
| **A 经典分层** | controller/；task/、listener/ 直调 Service | service/（贫血过程式）；无聚合、无事件概念 | mapper/（DAO）；client/ 建议收敛；MQ 发送散在 service | DTO ↔ PO（entity 兼任业务对象，例外见 4.1 注）；无 Cmd/Query 之分，response 可用 VO 或直接复用 DO |
| **B 轻量 CQRS** | 命令/查询 Controller 分离；消费端与定时任务只进写侧 | command 侧 service（规则唯一所在）；读侧无核心 | 写：repository；读：query.mapper 直出 SQL；client 仅写侧 | 写链 DTO → Cmd → Entity（兼 PO）；读链 Query → VO |
| **六边形** | adapter 的 in.web / in.messaging / in.scheduler 三者并列，只做"翻译 + 调 port.in" | port.in 用例接口 + application 实现 + domain 模型与服务 | port.out 三端口 + adapter.out 三适配器（persistence / messaging / client） | DTO → Cmd → Model ↔ PO（转换在适配器内） |
| **DDD 四层** | interfaces 下 rest / consumer / task 三者并列 | application（Cmd/Query、事务）+ domain（聚合、领域服务、领域事件、仓储接口） | infrastructure 下 persistence（仓储实现）/ client（ACL 防腐）/ messaging（publisher + outbox） | DTO → Cmd → Aggregate ↔ PO；事件双形态（领域 → 集成）；查询出 VO |
| **D 全配置** | interfaces 下 rest（命令/查询分离）、consumer（含读模型投影）、task（对账补偿） | application.command（Handler）+ application.query + domain（聚合、事件、端口） | 写库 persistence + 读模型 readstore（ES/Redis）+ publisher（Outbox 强制）+ client（写路径慎用同步） | 读写两套：Cmd → Aggregate → DomainEvent → IntegrationEvent → 读模型 VO |
| **Clean/Onion** | 适配器圈：web / consumer / scheduler | 用例圈（接口+端口）+ 实体圈 | 适配器圈实现 Gateway，框架驱动最外圈 | 同六边形（包结构见 4.9） |
| **垂直切片** | 每切片自带 Controller；consumer 按 Topic/消息类型分发到切片 Handler；定时任务独立 scheduler 入口，只触发切片 Handler | 切片内 Handler 即轻量用例；共享实体下沉 shared/domain | 每切片自决 persistence；client 切片内自包含、谨慎下沉 | 每切片私有 Request/Response；共享 Entity；查询切片直出 VO |
| **模块化单体** | 模块 internal 内的 controller / consumer / scheduler；consumer 订阅他模块 api.event | 模块 internal 自选（可分层可 DDD）；对外只暴露 api.Facade | persistence 模块私有（禁跨模块 JOIN）；模块间调用 = api.Facade（进程内 client）；publisher 发布 api 包定义的集成事件 | api.dto 与 api.event 是跨模块契约；internal 内自选 |
| **微服务** | 各服务自己的 controller / consumer / scheduler | 服务内部任选 A~D 结构 | persistence 库私有（铁律）；client = xxx-api 契约包 + 熔断降级；messaging 为服务间主干、schema 版本化 | api 包承载跨服务 DTO 与事件 schema；内部自选 |

### 3.3 各架构数据流转链

```
A 经典分层
  写/读: HTTP → Controller(DTO) → Service(逻辑) ↔ Entity(即PO) → Mapper → DB

B 轻量 CQRS
  写: HTTP → CmdController → CmdService(Cmd) ↔ Entity(兼 PO) → Repository → DB
  读: HTTP → QueryController → QueryService(Query) → QueryMapper(SQL) → VO

六边形 / Clean
  入站: HTTP|MQ|Scheduler → Adapter.in → DTO→Cmd → UseCase(port.in)
  核心: UseCaseImpl → Domain Model(规则)
  出站: Model → RepositoryPort → Adapter: Model↔PO → DB
        Model → EventPublisherPort → MQ
        Model → PaymentGatewayPort → HTTP Client（DTO 就地翻译）

DDD 四层
  HTTP|MQ|Scheduler → interfaces(DTO) → application(Cmd/Query, 事务边界)
    → domain(Aggregate 规则, DomainEvent) → infrastructure(PO / MQ / ACL)

D 全配置（三条链）
  写链: HTTP → CmdHandler → Aggregate → DomainEvent → Outbox → MQ
  读链: HTTP → QueryService → ReadStore(ES/Redis) → VO
  投影链: MQ → Consumer → 读模型更新（最终一致）

垂直切片
  HTTP → 切片Controller → Handler → (共享Entity | 私有SQL) → Response/VO
```

### 3.4 入口适配器的共性规则

1. **同一用例，多个入口**：一个下单用例应能同时被 REST、MQ 消息、定时重试触发——入口只做协议翻译（HTTP 报文 / 消息体 / 定时参数 / 订阅帧 → Command），业务只在用例与领域里。检验标准：新增一种触发源时，核心代码零改动。
2. **Consumer 必须幂等，且分两类**：业务命令类 Consumer 翻译后只进写侧用例；读模型投影类 Consumer 只更新读模型、不回写领域。两者都用业务唯一键/去重表保证可重入。
3. **Scheduler 只触发不处理**：典型职责是 Outbox 轮询投递、超时关单扫描、对账补偿——它扫出"该做的事"，交给用例去做，自己不写业务。
4. 在六边形/DDD/Clean 中，三者包位置平级：adapter 下的 `in.web` / `in.messaging` / `in.scheduler`（或 interfaces 下的 `rest` / `consumer` / `task`，Clean 则在 interfaceadapters 下并列）。

---

## 第四章　包结构设计汇总

> 统一约定：根包 `com.example.{project}`；后缀约定 DTO（传输）、VO（视图）、Entity/Model（领域）、PO/DO（持久化）、Cmd/Query（命令/查询）。
> 节-方案映射：4.1=A、4.2=B、4.3+4.4=C、4.5=D、4.6=垂直切片（可叠加于任意方案）、4.7=E、4.8=微服务、4.9=Clean/Onion（与 4.3 同族）。

### 4.1 A 经典分层

```
com.example.app
├── AppApplication.java
├── controller/                        # 接口层：协议适配、参数校验，保持薄
│   ├── UserController.java            #   REST 入口，返回统一 Result<T>
│   ├── OrderController.java
│   └── advice/
│       └── GlobalExceptionHandler.java    # @RestControllerAdvice：异常→错误码
├── service/                           # 业务层：业务规则与事务边界所在
│   ├── UserService.java               #   接口（小项目可省略，直接写实现类）
│   ├── OrderService.java
│   └── impl/
│       ├── UserServiceImpl.java       #   @Transactional 标在这一层
│       └── OrderServiceImpl.java      #   可注入多个 Mapper 做编排
├── manager/                           # （可选）Service 与 Mapper 之间的复用层
│   └── OrderManager.java              #   缓存读写、RPC 调用等可复用编排
├── mapper/                            # 数据访问层：MyBatis 用 XxxMapper
│   ├── UserMapper.java                #   JPA 则用 repository/ + XxxRepository
│   └── OrderMapper.java               #   （extends JpaRepository）
├── entity/                            # 持久化对象（A 方案例外，见下注）
│   ├── UserDO.java
│   └── OrderDO.java
├── dto/
│   ├── request/                       # CreateUserRequest（@Validated 分组校验）
│   └── response/                      # UserVO / OrderVO
├── convert/                           # MapStruct 转换器：DTO ↔ DO（PO）
│   └── UserConvert.java
├── config/                            # SecurityConfig / SwaggerConfig / RedisConfig
├── common/
│   ├── result/Result.java             # 统一响应包装
│   ├── exception/BizException.java    # 业务异常 + ErrorCode 枚举
│   └── enums/  constant/  util/
├── event/                             # 进程内事件（见 9.6）：定义 + @TransactionalEventListener 订阅
│   └── OrderPlacedEvent.java          #   发布在 Service；Spring 可直接发布纯 POJO
├── task/                              # @Scheduled / XXL-Job，直调 Service
│   └── OrderTimeoutJob.java
└── listener/                          # MQ 监听直调 Service（与 Controller/task 同为触发入口，须幂等）
    └── PaymentCallbackListener.java
```
> **注（Entity 语义例外）**：统一约定中 Entity=领域对象，但 A 方案无领域层，`entity/` 实指**持久化对象**（MyBatis/JPA 实体惯例），不承载领域行为。演进到 C/D 时，请让领域对象独占 Entity/Model 语义，持久化对象改称 PO。

规模稍大即改为**按业务分包**：user、order 各自内含 controller/service/mapper/entity/dto，外加 common/。
规则：controller→service→mapper 单向；禁止 Controller 直连 Mapper。

### 4.2 B 分层 + 轻量 CQRS

```
com.example.app
├── command/                           # 写侧：业务规则与一致性的唯一入口
│   ├── controller/
│   │   └── OrderCommandController.java    # POST/PUT/DELETE 写操作
│   ├── service/
│   │   └── OrderCommandService.java       # 事务边界、业务规则
│   ├── cmd/                           # 命令对象
│   │   ├── CreateOrderCmd.java
│   │   └── CancelOrderCmd.java
│   ├── model/                         # 写模型实体（可贫血/半充血，JPA 下兼任 PO）
│   │   └── Order.java
│   ├── event/                         # 进程内事件（见 9.6）：定义 + 订阅 Handler，只属写侧
│   ├── repository/                    # 写侧仓储（面向实体）
│   │   └── OrderRepository.java
│   ├── client/                        # 外部调用只挂写侧（读侧禁用）
│   │   └── PaymentClient.java
│   ├── consumer/                      # MQ 消费入口：消息→Cmd→写侧 service，幂等
│   │   └── PaymentCallbackConsumer.java
│   └── task/                          # 定时入口：只触发，调写侧 service
│       └── OrderTimeoutJob.java
├── query/                             # 读侧：不建领域对象，怎么快怎么来
│   ├── controller/
│   │   └── OrderQueryController.java      # GET 列表/详情/导出
│   ├── service/
│   │   └── OrderQueryService.java         # 只读，无写事务
│   ├── mapper/
│   │   └── OrderQueryMapper.java          # 联表/聚合 SQL，直出 VO
│   └── vo/                            # OrderListVO / OrderDetailVO / OrderExportVO
└── common/                            # Result / 异常 / 枚举 / MapStruct 转换器
```
规则：读写代码路径完全分离；读侧禁止经过写侧实体；只有写侧有写事务；事件只由写侧产生（读侧仅在引入读缓存时挂投影类 Consumer，见 9.6）。

### 4.3 C-① 六边形

```
com.example.app
├── domain/                                # 内核：纯 POJO，零框架、零 Spring 依赖
│   ├── model/
│   │   ├── Order.java                     # 实体（建议充血：行为内聚）
│   │   ├── OrderNo.java                   # 值对象
│   │   └── event/OrderPlacedEvent.java    #   领域事件：随聚合、纯 POJO（见 9.6）
│   ├── service/
│   │   └── OrderPricingService.java       # 领域服务：跨实体的领域逻辑
│   └── port/
│       ├── in/                            # 入站端口（用例接口，驱动侧契约）
│       │   ├── PlaceOrderUseCase.java     #   方法签名用 Cmd / 领域对象表达
│       │   └── QueryOrderUseCase.java
│       └── out/                           # 出站端口（被驱动侧契约）
│           ├── OrderRepositoryPort.java   #   领域声明"我要存什么"
│           ├── PaymentGatewayPort.java    #   领域声明"我要调支付"
│           └── EventPublisherPort.java    #   领域声明"我要发事件"
├── application/                           # 用例实现层：编排，无业务规则
│   ├── PlaceOrderService.java             # implements PlaceOrderUseCase
│   ├── QueryOrderService.java             #   编排事务、调用 port.out 接口
│   └── event/
│       └── OrderPlacedHandler.java        #   进程内订阅（@TransactionalEventListener，见 9.6）
├── adapter/
│   ├── in/
│   │   ├── web/                           # REST 入站适配器
│   │   │   ├── OrderController.java       #   只依赖 port.in 接口
│   │   │   ├── dto/  assembler/           #   DTO ↔ Cmd/VO（MapStruct）
│   │   │   └── WebExceptionHandler.java
│   │   ├── messaging/                     # MQ 消费入站适配器
│   │   │   └── PaymentCallbackConsumer.java  # 消息→Cmd→port.in，幂等
│   │   └── scheduler/                     # 定时入站适配器
│   │       └── OrderTimeoutJob.java       #   扫描→port.in，不写业务
│   └── out/
│       ├── persistence/                   # 持久化出站适配器
│       │   ├── OrderPersistenceAdapter.java  # implements OrderRepositoryPort
│       │   ├── po/OrderPO.java            #   表映射对象
│       │   ├── repository/OrderJpaRepository.java  # Spring Data JPA（MyBatis 场景则用 mapper/）
│       │   └── converter/OrderConverter.java # Model ↔ PO（MapStruct）
│       ├── messaging/                     # MQ 发布出站适配器
│       │   ├── RocketMqEventPublisher.java   # implements EventPublisherPort
│       │   └── event/OrderPlacedMsg.java     #   集成事件载荷 + 领域→集成翻译（见 9.6）
│       └── client/                        # 外部服务出站适配器（防腐）
│           ├── PaymentGatewayAdapter.java    # implements PaymentGatewayPort
│           └── dto/                       #   外部报文，就地翻译不渗漏
└── bootstrap/                             # 装配入口
    ├── AppApplication.java
    └── config/BeanConfig.java             # 用 @Bean 将实现装配到 port 接口
```
规则：依赖只朝内——adapter.in 只依赖 port.in 接口（其实现在 application）；application 调用 port.out 接口（其实现在 adapter.out）；domain（含 port）不依赖任何外层与框架。检验标准：领域层单测不启动 Spring。

### 4.4 C-② DDD 经典四层

```
com.example.app
├── interfaces/                            # 用户接口层：协议适配
│   ├── rest/
│   │   ├── OrderController.java
│   │   └── assembler/OrderDTOAssembler.java   # DTO ↔ Cmd/VO
│   ├── dto/                               # PlaceOrderRequest / OrderVO
│   ├── consumer/                          # MQ 消费者（另一种入口）
│   │   └── StockDeductedConsumer.java
│   └── task/                              # 定时入口
│       └── OrderTimeoutJob.java           #   超时关单扫描→应用服务
├── application/                           # 应用层：用例编排、事务边界
│   ├── OrderAppService.java               #   加载聚合→调聚合行为→仓储保存
│   ├── command/                           # PlaceOrderCmd / CancelOrderCmd
│   ├── query/                             # 查询用例（轻 CQRS 挂这里）
│   │   ├── OrderDetailQuery.java
│   │   └── OrderQueryService.java
│   └── event/
│       └── OrderEventHandler.java         # 订阅领域事件做后续编排
├── domain/
│   ├── order/                             # 按聚合分包（不是按类型！）
│   │   ├── Order.java                     # 聚合根（充血：place/cancel/pay）
│   │   ├── OrderItem.java                 # 聚合内实体
│   │   ├── OrderNo.java  Money.java       # 值对象
│   │   ├── OrderStatus.java               # 状态枚举 + 状态机守卫
│   │   ├── OrderRepository.java           # 仓储接口（仅此聚合一个）
│   │   ├── OrderDomainService.java        # 跨聚合/依赖外部 ports 的领域逻辑
│   │   ├── OrderFactory.java              # 复杂创建逻辑
│   │   └── event/
│   │       └── OrderPlacedEvent.java      # 领域事件
│   ├── inventory/                         # 另一个聚合（同构）
│   └── shared/                            # 共享值对象、通用枚举
├── infrastructure/
│   ├── persistence/
│   │   ├── OrderRepositoryImpl.java       # implements domain 的仓储接口
│   │   ├── po/OrderPO.java                # 表映射对象
│   │   ├── repository/OrderJpaRepository.java  # MyBatis 场景则用 mapper/
│   │   └── converter/OrderConverter.java  # 聚合 ↔ PO（MapStruct）
│   ├── messaging/
│   │   ├── outbox/                        # 发件箱表 + 轮询投递 Job
│   │   ├── event/OrderPlacedMsg.java      #   集成事件载荷（契约非领域，见 9.6）
│   │   └── MqProducer.java
│   └── client/                            # 防腐层 ACL
│       ├── payment/PaymentACL.java
│       └── dto/                           # 外部模型就地翻译
└── common/
```
规则：interfaces→application→domain，infrastructure 依赖倒置实现 domain 接口；跨聚合只引用聚合根 ID；**一个事务原则上只修改一个聚合**，跨聚合一致性走领域事件/最终一致；业务 if-else 长在聚合里而非 AppService。

### 4.5 D 全配置（DDD + 六边形 + CQRS + 事件驱动）

```
com.example.app
├── interfaces/
│   ├── rest/
│   │   ├── OrderCommandController.java    # 写：命令入口
│   │   └── OrderQueryController.java      # 读：查询入口
│   ├── consumer/
│   │   ├── PaymentResultConsumer.java     # 集成消费：消息→命令 Handler
│   │   └── OrderProjectionConsumer.java   # 投影消费：事件→更新读模型
│   └── task/                              # 对账、补偿、Outbox 轮询
├── application/
│   ├── command/
│   │   ├── PlaceOrderCmd.java
│   │   └── PlaceOrderHandler.java         # 加载聚合→行为→保存→收集事件
│   └── query/
│       ├── OrderDetailQuery.java
│       └── OrderQueryService.java         # 直奔 readstore，不碰 domain
├── domain/
│   ├── model/order/                       # 聚合（同 4.4，按聚合分包）
│   │   └── event/OrderPlacedEvent.java    #   领域事件随聚合（见 9.6）
│   ├── service/
│   └── port/out/                          # OrderRepositoryPort / EventBusPort
├── infrastructure/
│   ├── persistence/                       # 写库：仓储实现（JPA/MyBatis）
│   ├── readstore/                         # 读模型：ES / Redis / 读库
│   │   ├── OrderReadModelDAO.java
│   │   └── OrderReadModelUpdater.java     # 投影更新器（被投影 Consumer 调用）
│   │                                      # 投影链：投影 Consumer 直调 Updater，不经 application；HTTP 读链仍走 application.query
│   ├── messaging/                         # Outbox → MQ 投递
│   └── client/                            # 防腐层（写路径慎用同步调用）
└── bootstrap/                             # 装配入口
    ├── AppApplication.java
    └── config/
        ├── OutboxRelayConfig.java         # Outbox 轮询投递装配
        └── ReadModelConfig.java           # readstore 连接与投影注册
```
规则：读链永远不碰 domain 包；聚合内收集领域事件，仓储保存后统一投递；读写物理分离则接受最终一致。

### 4.6 垂直切片

```
com.example.app
├── features/
│   ├── createorder/                       # 一个用例 = 一个切片，自包含
│   │   ├── CreateOrderController.java
│   │   ├── CreateOrderHandler.java        #   该用例的全部编排逻辑
│   │   ├── CreateOrderRepository.java     #   仓储切片私有，禁跨切片共享
│   │   ├── CreateOrderRequest.java  CreateOrderResponse.java
│   │   ├── CreateOrderValidator.java
│   │   └── OrderPlacedEvent.java          #   本切片对外契约：其他切片 import 订阅合法（见 9.6）
│   ├── cancelorder/
│   │   ├── CancelOrderController.java  CancelOrderHandler.java
│   │   └── CancelOrderRequest.java
│   └── getorderdetail/                    # 查询切片：直接 SQL，不经领域
│       ├── GetOrderDetailController.java
│       ├── GetOrderDetailHandler.java
│       └── OrderDetailVO.java
├── consumer/
│   └── OrderMessageRouter.java            # 按 Topic/消息类型分发到对应 Handler
├── scheduler/
│   └── CloseExpiredOrdersJob.java         # 独立入口包，按切片原则组织：Job 只触发，业务在切片 Handler
└── shared/                                # 仅放真正跨切片复用的内核
    ├── domain/Order.java                  # 共享实体（持久化各切片自决，不下沉仓储）
    └── common/                            # Result / 异常 / 枚举
```
规则：切片间禁止直接互相调用，通信用事件（产生切片定义、消费切片订阅，见 9.6）；复用逻辑下沉 shared，下沉不了宁可复制；查询切片与命令切片并列（天然 CQRS）。

### 4.7 模块化单体

```
com.example.app
├── order/                               # 模块 = 一个限界上下文
│   ├── api/                             # 对外契约（他模块只允许依赖此包）
│   │   ├── OrderFacade.java             #   模块间同步调用接口
│   │   ├── dto/OrderSummaryDTO.java     #   跨模块传输对象
│   │   └── event/OrderPlacedEvent.java  #   对外发布的集成事件
│   └── internal/                        # 模块内部（对外不可见）
│       ├── controller/OrderController.java
│       ├── application/OrderAppService.java
│       ├── domain/                      #   本模块自选结构（这里用 DDD）
│       ├── infrastructure/
│       └── InternalConfig.java
├── inventory/
│   ├── api/  (InventoryFacade + dto + event)
│   └── internal/                        #   本模块可以是简单三层
├── payment/  user/                      # 同构：api/ + internal/
└── sharedkernel/                        # 通用语言内核（Java 包名不能含连字符）
    ├── Money.java  PageQuery.java       # 共享值对象、基础类型
    └── event/DomainEvent.java           # 事件基类
```
规则：跨模块只 import 对方 api 包（ArchUnit 强制）；禁跨模块 JOIN（走 Facade 或事件冗余）；模块内结构自选；拆服务 = 搬走模块 + Facade 改 RPC。

### 4.8 微服务（仓库/契约布局）

```
order-service/                               # 一个限界上下文 = 一个服务
├── order-api/                               # 对外契约模块（打 jar 供他服务依赖）
│   ├── dto/OrderDTO.java
│   ├── feign/OrderFeignClient.java          # 声明式客户端；Fallback 建议消费方自定义，api 包只留钩子
│   └── event/schema/OrderPlacedEventV1.java # 事件 schema，版本化管理
└── order-server/                            # 服务实现（内部结构任选 A~D）
    └── src/main/java/com/example/order/{...}
inventory-service/                           # 同构：xxx-api/ + xxx-server/
payment-service/
```
规则：只依赖对方 api 包；Database per Service；事件 schema 版本化；配契约测试（Spring Cloud Contract / Pact）；**跨服务一致性用 Saga/补偿，不用分布式事务**。

### 4.9 Clean/Onion（与 4.3 同族，布局对照）

> Clean Architecture 是六边形的同心圆表述，依赖规则与数据流转完全同构（§3.2/3.3 中两者并列即此意）；差别主要在**圈层命名**与 Presenter 约定。与 4.3 二选一，勿混用。

```
com.example.app
├── domain/                                # 最内圈：实体 + 出站端口（Onion 惯例称 Gateway）
│   ├── entity/
│   │   ├── Order.java                     # 充血实体
│   │   ├── OrderNo.java                   # 值对象
│   │   └── event/OrderPlacedEvent.java    #   领域事件，纯 POJO（见 9.6）
│   └── gateway/
│       └── OrderRepositoryGateway.java    # 语义同 port.out：接口在圈内，实现在圈外
├── usecase/                               # 用例圈：入站端口 + 输出端口 + 用例实现
│   ├── input/
│   │   └── PlaceOrderInputPort.java       # 语义同 port.in，Controller 只依赖它
│   ├── output/
│   │   └── NotificationOutputPort.java    # 出站端口（事件发布 EventPublisherOutputPort 同理，见 9.6）
│   ├── event/
│   │   └── OrderPlacedEventHandler.java   # 进程内订阅（@TransactionalEventListener）
│   └── PlaceOrderInteractor.java          # 用例实现（Onion 惯例称 Interactor）
├── interfaceadapters/                     # 接口适配圈：入口协议 + 出站实现
│   ├── web/
│   │   ├── OrderController.java
│   │   └── presenter/OrderPresenter.java  # 用例输出 → 视图模型，Controller 不见领域对象
│   ├── messaging/
│   │   └── OrderEventPublisherGateway.java #  集成事件载荷 + 领域→集成翻译 + MQ 发布（见 9.6）
│   └── gateway/
│       └── OrderRepositoryGatewayImpl.java
└── frameworks/                            # 最外圈：框架驱动与装配
    ├── AppApplication.java
    └── config/
```
规则：依赖只朝内（entities ← usecases ← interfaceadapters ← frameworks）；领域层零框架依赖可纯单测；4.3 的 ArchUnit 守护规则同样适用（包名映射：`adapter`→`interfaceadapters`、`bootstrap`→`frameworks`、`application`→`usecase`）。

---

## 第五章　出站组件定位（persistence / messaging / client）

通用角色：**persistence 与 client 都是被驱动的出站依赖；messaging 是双向的——发布在出站、消费在入站（与 Controller 平级）**。架构演进的主线就是把三者从"随手调用"推向"端口 + 适配器 + 防腐层"。

| 架构 | persistence | messaging | client |
|---|---|---|---|
| A 经典分层 | 最底层 DAO，Service 直接用 | 无正式位置，散在 Service；注意先发消息后提交事务的不一致坑 | 无正式位置；至少收敛 client/ 包管超时重试 |
| B 轻量 CQRS | 写仓储 + 读查询 Mapper 分裂 | 消费路由到写侧命令 | 只允许写侧调用 |
| 六边形 | 出站端口 + 持久化适配器；PO 与领域分离 | 发布 = 出站端口；消费 = 入站适配器 | 出站端口 + 防腐适配器，外部 DTO 就地翻译 |
| Clean/Onion | 同六边形（布局见 4.9） | 同六边形 | 同六边形 |
| DDD 四层 | 领域仓储：接口在 domain、每聚合一个、实现在 infrastructure | 领域事件（内，同事务）/ 集成事件（外，MQ）+ Outbox | 防腐层 ACL，属上下文映射 |
| D 全配置 | 写库仓储 + 读模型存储（可异构） | 系统枢纽：集成 + 读模型投影；Outbox + 幂等强制 | 写路径避免同步调用，能事件化就事件化 |
| 垂直切片 | 每切片自决，禁跨切片共享 Mapper | 命令切片内发布，consumer 按 Topic/类型分发 | 切片内自包含，谨慎下沉 |
| 模块化单体 | 模块私有，禁跨模块 JOIN | 模块间首选通信，契约即拆服务资本 | 模块间走 Facade；外部 client 归具体模块 |
| 微服务 | 库私有（铁律） | 服务间主干，schema 版本化 + 死信队列 | api 契约包 + 熔断降级 + 契约测试；跨服务一致性走 Saga |

**一致性三件套（与架构选型正交，引入 MQ 就必须做）**：
1. **事务性发件箱（Outbox）**：业务数据与事件同库同事务落库，保证"落库与发消息"原子化；投递侧二选一——后台轮询（简单，有 DB 压力）或 CDC 日志捕获（如 Debezium，无轮询压力、多一个组件）；
2. **消费幂等**：业务唯一键 / 去重表，消费可重入；
3. **最终一致性兜底**：对账任务（Scheduler 的典型职责）+ 补偿流程。

---

## 第六章　推荐组合方案速查

| 方案 | 架构骨架 | 技术栈建议 | 适用 | 演进路径 |
|---|---|---|---|---|
| A 经典分层 | Controller→Service→Mapper | Spring Boot + MyBatis-Plus + Validation + MapStruct | CRUD、内部系统、MVP | 查询变复杂 → B |
| B 分层 + 轻 CQRS | 写 Service + 读 QueryService 双路径 | 同上，读侧可 JdbcTemplate | 查询多的 B 端业务系统 | 核心域变复杂 → C |
| C DDD + 六边形 | 双骨架任选：4.3 端口式（domain/application/adapter/bootstrap）或 4.4 四层式（interfaces/application/domain/infrastructure），勿混用 | Spring Boot + JPA/MyBatis + MapStruct + ArchUnit | 核心复杂域 | 并发与解耦诉求 → D |
| D 全配置 | C + CQRS 物理分离 + 事件驱动 | 追加 MQ、ES/Redis、Outbox | 高并发核心系统 | — |
| E 模块化单体 | 模块 api/internal 隔离，事件通信 | Spring Modulith（模块边界验证 + 事件发布订阅开箱即用），或 Spring Boot + ApplicationEvent + ArchUnit（事件后平移 MQ） | 边界未定、有拆分预期 | 热点模块抽微服务 |

---

## 第七章　落地保障

### 7.1 对象命名与转换约定

| 后缀 | 层 | 说明 |
|---|---|---|
| DTO / Request / Response | 接口层 | 不出接口层 |
| Cmd / Query | 应用层入口 | 写/读意图 |
| Entity / 聚合根名 | 领域层 | 充血，不出核心层（A/B 方案例外：见 4.1 注、4.2 model 兼任 PO） |
| PO / DO | 基础设施层 | 不出持久化适配器 |
| VO | 查询链路 | SQL 直出 |
| Event | 领域层/MQ | 领域事件 vs 集成事件分开命名（约定见 9.3） |

跨层转换统一 MapStruct；**跨层才转换，层内不转换**。

### 7.2 ArchUnit 架构守护（示例，按适用方案选用）

```java
@AnalyzeClasses(packages = "com.example.app")
class ArchitectureTest {

    // 【适用 C/D】领域层不得依赖框架
    @ArchTest
    static final ArchRule domain_纯净 =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..");

    // 【适用 六边形/Clean】内层不得依赖外层适配器与装配
    @ArchTest
    static final ArchRule 六边形_依赖朝内 =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..adapter..", "..bootstrap..");

    // 【适用 六边形】application 只依赖 port.out 接口，不得直接依赖出站适配器实现
    @ArchTest
    static final ArchRule 六边形_应用不碰适配器 =
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..adapter.out..", "..adapter.in..");

    // 【适用 4.4/4.5 布局（DDD 四层/D）】PO 不得出持久化实现包
    @ArchTest
    static final ArchRule po_不出基础设施 =
        noClasses().that().resideOutsideOfPackage("..infrastructure.persistence..")
            .should().dependOnClassesThat().resideInAPackage("..po..");

    // 【适用 4.3 六边形布局】PO 在 adapter.out.persistence.po，须按该布局另写一条，否则规则静默失效
    @ArchTest
    static final ArchRule po_不出六边形适配器 =
        noClasses().that().resideOutsideOfPackage("..adapter.out.persistence..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.out.persistence.po..");

    // 【适用 E】模块 internal 包禁止跨模块访问
    @ArchTest
    static final ArchRule 模块internal隔离 =
        noClasses().that().resideOutsideOfPackage("..order..")
            .should().accessClassesThat().resideInAPackage("..order.internal..");

    // 【适用 A/B】Controller 不得直连 Mapper
    @ArchTest
    static final ArchRule 分层不穿透 =
        noClasses().that().resideInAPackage("..controller..")
            .should().accessClassesThat().resideInAPackage("..mapper..");
}
```

规则多时也可改用 ArchUnit 自带的 `onionArchitecture()`（六边形/Clean）或 `layeredArchitecture()`（A/B）DSL，以声明方式一次定义全部圈层关系，比逐条手工规则更不易漏。

### 7.3 常见误区清单

- 为 CRUD 项目上 DDD 全套（杀鸡用牛刀）；
- 认为 CQRS 必须分库 / 必须事件溯源（最常见落地是同库两条代码路径）；
- 六边形三层对象手写转换（用 MapStruct，并约定跨层才转）；
- 复杂业务仍用贫血模型，DDD 只剩空壳包结构；
- MQ 先发消息后提交事务（上 Outbox）；
- 按层分包后跨业务互相 import（优先 package by feature / by module）；
- Scheduler / Consumer 里写业务逻辑（入口只做翻译与触发）；
- 模块化单体不做边界守护（上 ArchUnit，否则三个月退化成大泥球）；
- 低估 D 的运维成本：投影重建、事件 schema 演进、消息重放与乱序都需要预案。

### 7.4 选型检查清单（立项时逐项回答）

1. 业务是 CRUD 为主还是规则/状态机密集？
2. 读是否远多于写、报表/搜索查询是否复杂？
3. 团队是否有 DDD / CQRS 实战经验？
4. 项目预期生命周期是否超过 3 年？
5. 交付压力是否允许前期建模投入？
6. 外部集成有几个（MQ、第三方 HTTP）？
7. 一致性要求是否可接受最终一致？
8. 是否有拆分微服务的预期？边界是否稳定？
9. 是否要求领域层可脱离 Spring 纯单测？
10. 谁负责架构守护（ArchUnit 规则、Code Review 清单）？

**评分建议**：2、5、6 答"是" → 在 C/D 中选；7 答"可接受最终一致" → 可考虑 D 的物理读写分离，答"强一致" → 留在 C 或 D 的逻辑分离形态；4、8 答"是" → 叠加 E 的模块化；3、5 答"否" → 降级 A/B。

---

## 第八章　实战专题：异步 + 轮询 API

### 8.1 模式三件套

长耗时操作（报表导出、批量导入、转码、对账）不能同步阻塞 HTTP 请求，标准解法是"异步执行 + 状态轮询"三件套：

1. **提交接口** `POST /api/tasks` → 立即返回 `202 Accepted`，Body 带 `taskId` 与 `statusUrl`（或响应头 `Location: /api/tasks/{taskId}`）；
2. **状态接口** `GET /api/tasks/{taskId}` → 返回 `{ status, progress, resultUrl?, error? }`，客户端轮询直到终态；
3. **异步执行器**：后台真正干活的角色——线程池 / MQ 消费者 / 定时 Worker。

替代方案对比：

| 方案 | 适用 | 代价 |
|---|---|---|
| 同步阻塞 | 3 秒内的快速操作 | 超时风险、占用连接 |
| **轮询** | 通用默认；客户端无状态、实现最简单 | 有延迟、空轮询消耗 |
| Webhook 回调 | 客户端有公网可达端点（B2B 集成） | 需签名验证、重试与可达性保障 |
| SSE / WebSocket | 实时进度推送（C 端页面） | 长连接成本与扩容复杂度 |

实战常用组合：**轮询为主 + Webhook 为辅**（有回调能力的客户可少查几次）。

### 8.2 任务模型与状态机

任务表（TaskDO）关键字段：`task_id`（UUID/雪花）、`biz_type`（任务类型）、`biz_no`（业务幂等键）、`status`、`progress`、`payload`（入参快照 JSON）、`result_url`、`error_msg`、`retry_count`、`created_at / updated_at / expire_at`。

状态机：`PENDING → RUNNING → SUCCESS / FAILED`；`FAILED` 可重试 → 回 `PENDING`；`RUNNING` 超时 → `TIMEOUT`；进行中可 `CANCEL`。**终态不可逆，状态推进用条件更新（乐观锁）抢占执行权**：

```sql
UPDATE task SET status='RUNNING' WHERE task_id=? AND status='PENDING'
-- 影响行数 = 1 才算抢到执行权，天然防并发重复执行
```

### 8.3 执行器的三种实现（Spring Boot）

| 实现 | 适用 | 要点 |
|---|---|---|
| @Async + 自建线程池 | 单机、任务轻、量小 | 必须自定义 ThreadPoolTaskExecutor（队列上限 + 拒绝策略），禁用默认执行器；重启丢任务 → 配 Scheduler 恢复扫描 PENDING |
| MQ + Consumer | 分布式、量大、需削峰（推荐） | 任务表与发消息走 Outbox；消费幂等；复用 MQ 重试/死信 |
| Scheduler 扫描 | 兜底角色 | 超时检测（RUNNING 超阈值 → 重试/TIMEOUT）、结果清理（expire_at） |

### 8.4 与四类概念的对位——各架构中的位置

核心洞察：**提交是写侧命令，轮询是读侧查询，执行是入站适配器触发的用例**——这个模式天然是 CQRS 的形状，也是 3.4"同一用例、多个入口"的典型实例。

| 架构 | 提交（Command） | 轮询（Query） | 执行器（入站触发用例） | 说明 |
|---|---|---|---|---|
| A 经典分层 | TaskController.submit → Service 写任务表 | TaskController.status → Service 查表 | task/ 包内 @Async 方法或 XXL-Job | 单机够用；状态机枚举放 common |
| B 轻量 CQRS | command 侧 SubmitTaskCmd → 写侧仓储 | query 侧 TaskQueryMapper 直出 TaskVO | Consumer 消费任务消息 → 调 command 侧用例 | **最契合的落点**：读写本就分离 |
| 六边形 | port.in SubmitTaskUseCase；TaskRepositoryPort 出站 | port.in QueryTaskUseCase | adapter.in.messaging / scheduler 触发；执行中经 port.out 调外部 | 任务状态机放 domain.model，纯 POJO 可单测 |
| DDD 四层 | TaskAppService.submit：同事务写任务聚合 + Outbox | application.query 查任务 | consumer → TaskAppService.execute | Task 建模为独立聚合，状态机内聚在聚合根 |
| D 全配置 | 命令侧 Handler 写聚合 | 读侧查 readstore（ES/Redis 扛轮询） | 执行 Consumer 跑任务；投影 Consumer 更新读模型 | 高频轮询打在读模型，写库无压力 |
| 垂直切片 | submittask 切片 | gettaskstatus 切片 | executetask 切片（consumer 路由进入） | 三个角色三个切片，互不调用 |
| 模块化单体 | 各业务模块 api.Facade 暴露 submit | 同左提供 status 查询 | internal consumer 执行；跨模块靠事件 | 也可建独立 task 模块做通用任务中心 |
| 微服务 | 任务服务统一承接或各服务自建 | 同左 | 消费者独立部署伸缩 | 跨服务长流程升级为 Saga |

### 8.5 关键实现要点清单

1. **提交幂等**：`biz_no` 唯一索引；首次提交返回 202，重复提交直接返回已有 taskId（200 而非新建）——两种状态码有意区分；
2. **一致性**：任务表与业务数据同事务落库；MQ 触发走 Outbox（复用第五章三件套）；
3. **执行幂等**：执行器可重入，状态推进用条件更新抢执行权；
4. **超时与重试**：Scheduler 兜底扫描，最大重试次数 + 退避；
5. **轮询抗压**：状态接口是高频只读——走 Redis 缓存或读库，必要时限流；响应带 `retryAfter` 提示间隔，客户端指数退避；
6. **结果生命周期**：大结果不进任务表，放 OSS/临时 URL，`expire_at` 到期由清理 Job 回收；
7. **安全**：taskId 用不可猜测的 UUID；查询校验任务归属（越权读他人任务进度也是漏洞）。

### 8.6 最小代码骨架（方案 A 语境）

```java
// ① 提交：首次 202 + Location + taskId；幂等命中（bizNo 已存在）→ 200 + 已有 taskId，不新建
@PostMapping("/api/export-tasks")
public ResponseEntity<TaskSubmitVO> submit(@RequestBody @Validated ExportTaskRequest req) {
    String taskId = exportTaskService.submit(req);        // bizNo 命中则返回已有任务
    return ResponseEntity.accepted()
            .location(URI.create("/api/export-tasks/" + taskId))
            .body(new TaskSubmitVO(taskId, 2));           // retryAfter 建议 2 秒
}

// ② 轮询：读侧直出
@GetMapping("/api/export-tasks/{taskId}")
public TaskStatusVO status(@PathVariable String taskId) {
    return exportTaskService.queryStatus(taskId);         // status/progress/resultUrl/error
}

// ③ 执行：@Async 单机版（分布式换 MQ Consumer，业务逻辑同一处）
// ⚠ @Async 自调用失效：在 submit() 同类里直接调 this.execute(taskId) 不走代理、异步静默失效；
//    须经代理调用（拆独立 TaskExecutor Bean / 注入自身代理 / 由提交链路之外触发）
@Async("taskExecutor")
public void execute(String taskId) {
    if (!taskMapper.tryMarkRunning(taskId)) return;   // 条件更新抢执行权
    try {
        String resultUrl = doExport(taskId);
        taskMapper.markSuccess(taskId, resultUrl);
    } catch (Exception e) {
        taskMapper.markFailed(taskId, e.getMessage()); // 重试判断（retry_count，见 8.2）略
    }
}
```

---

## 第九章　实战专题：事件驱动设计

> 前文各章零散用到事件（3.1 的 Domain Event、第五章的 Outbox、8.4 的投影 Consumer）；本章把它们串成一条完整链路：**一个事件从聚合出生，到进程内分发、跨上下文传播、消费端落地的一生**。

### 9.1 双形态对照：领域事件 vs 集成事件

| 维度 | 领域事件 Domain Event | 集成事件 Integration Event |
|---|---|---|
| 语义 | 上下文内部已发生的业务事实 | 对外发布的事实，是跨上下文/跨服务**契约** |
| 产生 | 聚合行为内产生、聚合收集 | 由领域事件翻译而来（publisher 适配器 / Outbox 写入时） |
| 一致性 | 与业务同事务（落库即事实） | 最终一致（Outbox 投递） |
| 传输 | 进程内（ApplicationEvent），不出上下文 | MQ / 模块间 api.event / 服务间版本化事件 |
| 消费方 | 本上下文 EventHandler（后续编排）、投影 | 他上下文/他服务的 Consumer |
| 演进 | 随代码自由重构 | 契约演进：只加可选字段，破坏性变更版本化（V1/V2 并存期） |

一句话：**领域事件是"日记"，集成事件是"公告"**——日记随便改，公告要存档编号。

### 9.2 生命周期链（六个环节）

```
① 聚合产生:   order.place() 内部 registerEvent(new OrderPlacedEvent(...))
② 收集带出:   应用层保存聚合时取出事件（聚合自己不发——聚合不依赖基础设施）
③ 同事务落库:  业务数据 + Outbox 记录同一事务写入（复用第五章三件套）
④ 分发:
     进程内:   ApplicationEventPublisher → @EventListener / @TransactionalEventListener
     跨上下文: Outbox 轮询/CDC → 翻译成集成事件 → MQ
⑤ 消费:      幂等（业务唯一键/去重表）；命令类进写侧用例、投影类只更新读模型（3.4-2）
⑥ 兜底:      重试 + 死信 + 对账（Scheduler 的典型职责）
```

**@TransactionalEventListener 的两个相位**（进程内分发最常用的坑点）：

- 默认 `AFTER_COMMIT`：事务提交后才执行——**监听器里的 DB 操作不在原事务里，失败不会回滚业务**，只适合发通知、更新 Outbox 状态类操作；
- 需要"与业务同事务"的后续处理（如同库扣库存）：用 `BEFORE_COMMIT`，或根本不走事件、直接同步调用领域服务。

### 9.3 事件本体设计

- **胖事件 vs 瘦事件**：胖事件携带全量快照（消费方不求人，但生产者改一个字段就伤一片消费者）；瘦事件只带标识与最小事实（`OrderPlaced(id, amount)`），消费方按需回查。缺省建议**瘦事件**，热点查询才允许胖；
- **命名**：过去式陈述事实（`OrderPlaced`，不是 `PlaceOrder`——那是 Command）；集成事件带上下文前缀（`order.order-placed.v1`），Topic 与事件类型一一对应；
- **Outbox 表关键字段**：`event_id`、`aggregate_type / aggregate_id`、`event_type`、`payload`（JSON）、`status`（PENDING/SENT/DEAD）、`retry_count`、`next_retry_at`、`created_at`——投递侧轮询或 CDC（见第五章）；
- **schema 演进**：只加可选字段，不删不改语义；消费端容忍未知字段（不得反序列化失败）；破坏性变更发 V2 并存，双写观察期后下线 V1。

### 9.4 常见坑清单

- 聚合里直接调 publisher 发 MQ（聚合依赖了基础设施，且事务外发送必丢）→ 聚合只 `registerEvent`，发布交给应用层/Outbox；
- `AFTER_COMMIT` 监听器里写业务库，失败不回滚 → 见 9.2；
- 监听器异常被吞（log 完就完）→ 事件即丢失；必须 重试 → 死信 → 对账/人工兜底；
- 事件循环：A 发事件 → B 消费后又触发 A → 死循环；跨上下文事件触发新命令时，幂等键 + traceId 环路检测；
- 全系统一种"Event"类，领域/集成双形态混用 → 公告上桌了日记的内部字段，一改即事故。

### 9.5 各方案落位速查

| 方案 | 事件起步姿势 | 演进 |
|---|---|---|
| A | Spring `ApplicationEventPublisher` + `@TransactionalEventListener`，零中间件够用 | 引入 MQ 时再上 Outbox |
| B | 写侧发布；读侧只消费投影类事件 | — |
| C | `EventPublisherPort` 出站端口 + 适配器实现（4.3）；DDD 四层 `application/event/` 订阅（4.4） | — |
| D | 领域事件 → 集成事件强制走 Outbox；投影链更新读模型（8.4） | schema 版本化强制 |
| E / 微服务 | 模块/服务间只走 api.event 集成事件（Spring Modulith 事件或 MQ）；领域事件不出模块 | 拆分时 api.event 直接映射 MQ Topic |

### 9.6 事件类的包结构安放（第四章各方案的落点）

事件相关类先按**角色**拆开，角色决定落包——共 7 种：

| 角色 | 进程内/外 | 本质 | 安放铁律 |
|---|---|---|---|
| ① 领域事件定义（`OrderPlacedEvent`） | 进程内 | 领域概念（业务事实） | **随聚合** `domain/{聚合}/event/`，纯 POJO |
| ② 事件收集机制（`registerEvent`/聚合基类） | 进程内 | 聚合行为 | domain，与实体同处 |
| ③ 进程内订阅 Handler | 进程内 | 用例编排 | **application 层**——"事实发生后做什么"是用例，不是领域规则 |
| ④ 发布端口（`EventPublisherPort`/`EventBusPort`） | 跨界 | 出站端口 | domain（Clean 放 usecase/output/），与仓储接口同级 |
| ⑤ 集成事件定义（`order-placed.v1` 载荷） | 进程外 | **契约**，非领域概念 | 与 publisher 同处（infrastructure/messaging/）；跨模块/跨服务进 api 包 |
| ⑥ 发布器 + 领域→集成翻译 + Outbox | 进程外 | 次适配器 | infrastructure/messaging/（翻译即 9.2 环节④） |
| ⑦ 集成事件 Consumer | 进程外 | 入站适配器 | 与 Controller 平级的消费入口 |

技术前提：**Spring 4.2+ 的 `ApplicationEventPublisher` 可发布任意 POJO**——领域事件无需继承 `ApplicationEvent`、不碰 Spring API，这是①能留在 domain 的关键。

逐方案落点（与第四章包结构一一对应）：

| 方案 | ① 领域事件 | ③ Handler | ④ 端口 | ⑤⑥ 集成侧 | ⑦ Consumer |
|---|---|---|---|---|---|
| A 4.1 | `event/` 根包（按业务分包后随业务包） | `event/` | —（直用 publisher） | — | `listener/` |
| B 4.2 | `command/model/` 旁 | `command/event/` | — | 写侧发布（只 command） | 命令类 `command/consumer/`；投影类 `query/consumer/`（引入读缓存才有） |
| 六边形 4.3 | `domain/model/event/` | `application/event/` | `domain/port/out/EventPublisherPort` | `adapter/out/messaging/`（载荷+翻译+发布） | `adapter/in/messaging/` |
| DDD 四层 4.4 | `domain/{聚合}/event/` | `application/event/` | 仓储接口同级 | `infrastructure/messaging/`（含载荷） | `interfaces/consumer/` |
| D 4.5 | `domain/model/{聚合}/event/` | `application/` | `domain/port/out/EventBusPort` | `infrastructure/messaging/` | `interfaces/consumer/`（命令/投影两类） |
| 垂直切片 4.6 | **产生事件的切片内**（切片对外契约，消费切片 import 合法；事件基类下沉 `shared/`） | 消费切片内 | — | 切片自包含或 `shared/messaging/` | `consumer/` Router |
| 模块化单体 4.7 | `internal/domain/{聚合}/event/` | `internal/application/event/` | —（直接发 api.event） | `api/event/` 契约 + `internal/infrastructure/messaging/` | 模块 internal 内 |
| 微服务 4.8 | 服务内按所选 A~D | 同左 | 同左 | 跨服务一律 `xxx-api/event/schema/`（版本化） | 服务内 |
| Clean 4.9 | `domain/entity/event/` | `usecase/event/` | `usecase/output/` | `interfaceadapters/messaging/` | interfaceadapters 下与 web 平级 |

---

*本手册完。选型不是一锤子买卖：从能满足当前需求的最简方案起步，用 ArchUnit 守住边界，按第六章的演进路径按需升级。*
