---
name: investigate-endpoint
description: Investigate a web API endpoint's full execution path and produce a structured Inbound/Outbounds report. Use when asked to investigate, analyze, or trace an endpoint, its call chain, request handling flow, or what an API does（调查/梳理某个接口、API 调用链、请求处理流程）.
---

# Investigate Endpoint — API 执行链路调查报告

## 产出目标

两段式报告，一次讲清「这个 endpoint 对外契约是什么、对外部世界做了什么」：

- **Inbound**：入站契约——method、path、认证、请求格式、成功响应、**错误分支**
- **Outbounds**：按**运行时执行次序**编号的出站交互，**每种技术用自己的原生格式**（HTTP 报文 / SQL / 命令行 / 报文交换…）

概念模型：等价于 OpenTelemetry 中 1 个 server span（Inbound）+ N 个 client span（Outbounds）；path 用路由模板（`/api/users/{id}`）而非具体实例。报告服务于接口文档化、新人 onboarding、故障排查（外部依赖一眼可见）、安全与依赖审查。

## 调查流程（先读完代码再写报告，禁止边读边猜格式）

1. **定位入口** — route 定义处（定位方法见「入口定位速查」）。记录：HTTP method、path 模板、参数来源（path / query / header / cookie / body——JSON、XML、multipart 表单、二进制流等）、入站前置限制（请求体大小上限、过滤器/中间件、认证与授权）。
2. **沿调用链下行** — handler → 应用服务/用例层 → 领域逻辑 → 基础设施客户端，每跳记 `file:line`，直到触及进程边界。注意纯转发层（门面/代理）——真正的编排在它下一层，别在此停。
3. **识别出站交互** — 判定标准：**跨进程 / 跨网络 / 跨信任边界**。三类：
   - 网络出站：下游 HTTP/gRPC、数据库、缓存、消息队列、邮件、对象存储、自定义 TCP/UDP 协议
   - 本机出站：子进程执行、有业务意义的文件系统读写（结果归档、文件导出、批处理中间文件等）
   - 进程内重计算（大报文解析、加解密、媒体转码等重量级库操作）：编号列出，但必须标注「进程内，无外部系统」
4. **提取协议真实格式** — SQL / 命令 / 报文一律从客户端代码**实证提取**，禁止臆造；动态值写 `{占位符}`。可配置的地址与参数找到真实读取点（配置文件、环境变量、密钥管理），默认值与覆盖关系交叉核实，`${ENV:default}` 形式原样保留。
5. **识别控制流** — 短路（管道/责任链/校验链）、策略分支（开关/枚举/feature flag）、并行、异步（后台任务/事件/消息投递）、弹性（超时/重试/熔断）、背压（信号量/连接池上限/限流）。这些决定 Outbounds 的编号次序与标注方式。

## 输出格式

### 总骨架

```
代码依据: <入口 handler → 中间层 → 出站点的调用链概览，含 file:line>

Inbound:
<METHOD> <路由模板>
Content-Type: <入站类型>
Authorization: <认证方式；匿名则省略此行>

<请求示例：headers + body（按实际 Content-Type 的原生结构展示）；动态值用 {占位符}>

约束: <大小限制 / 参数校验 / 认证授权 / 限流 / 幂等键（如有）>

→ <成功状态码>（<什么条件下返回它>）
<响应 body 示例，字段后行内注释动态语义>

→ 错误分支
  <状态码> <错误码/标识>   <触发条件，含来自哪个 Outbound>

（异步受理形态：202 + 轮询地址/回调，注明结果如何查询）

Outbounds:   （按运行时执行次序编号；<此行写短路/并行/背压等全局控制流>）

1. <业务动作名> — <技术类型>（<协议/库细节>）
<该技术的原生格式，模板见 references/formats.md>
<超时/重试/熔断/降级等保护机制附在条目内>

2. ...

补充说明:
- <显式声明不存在的类别：无 DB / 无下游 HTTP / 无消息队列 …>
- <跨条目信息：降级策略全貌、异步链路去向、错误如何映射回 Inbound 分支>
```

### Inbound 规范

- method + **路由模板**；Content-Type 必写；认证方式（Bearer / Basic / API Key / OAuth）在 headers 中体现；非 JSON 请求体（multipart 表单、XML、二进制流）按原生结构展示，不笼统略过
- 非 HTTP 入口（gRPC 服务端 method / MQ 消费者 / 定时任务）不用 `METHOD path` 骨架，改用该入口的原生形态——gRPC 写全限定 method 名 + request/response message，MQ 消费写 `CONSUME {topic/queue} + 处理逻辑 + ack 方式`，形态参照 `references/example.md` 案例 A
- 请求/响应示例用**真实字段名**（从请求/响应模型实证），动态值 `{占位符}` 或真实形态（UUID）
- 序列化细节要体现：核实哪些字段真正出现在响应里（null 省略、字段命名 camelCase/snake_case、包装信封）
- 成功响应之外，**错误分支逐行列出**：状态码 + 错误码 + 触发条件（含由哪个 Outbound 失败引起）——错误覆盖度是报告质量的主要区分点
- 平台级限制（如 413 请求体超限）与业务校验（400/422）分开归因
- 异步受理（202 + 轮询/回调）写明结果查询路径；有幂等键/重放语义的注明

### Outbounds 规范

- 一个出站操作一个编号，次序 = **运行时执行次序**（不是代码定义次序）
- 条目标题：`N. <业务动作名> — <技术类型>`，技术类型细分到协议/库（如 `消息队列（Kafka，异步生产者）`、`HTTP（JSON over REST）`、`子进程（CLI 调用）`）
- 格式选型：**每种技术用适合自己的格式**，完整模板库见 `references/formats.md`
- 条件执行标注触发条件；短路链路在 Outbounds 头部一行说明
- 循环调用标注次数规则（如「逐条目 2N 次」）；并行调用标注「与 N 并行」；异步/投递类操作标注后续去向（谁消费、如何查询）
- 保护机制（超时值、熔断参数、重试次数、背压许可数）写在对应条目内，数值从配置实证
- 进程内操作照常编号，末尾标注「进程内，无外部系统」

## 质量自查（报告发出前）

- [ ] 每个协议细节都能给出 `file:line` 依据，无臆造（参数名、报文、SQL、默认值）
- [ ] 编号次序 = 执行次序；短路点、循环与条件触发已标注
- [ ] Inbound 覆盖认证 + 成功 + 全部错误分支，错误码与状态码经错误处理层（全局 handler / 错误中间件）核实
- [ ] 配置默认值 / 环境变量已从真实读取点核实，保留 `${ENV:default}` 原样
- [ ] 进程内 vs 真实出站有区分标注
- [ ] 不涉及的类别已显式声明（「无 DB 调用」比留白更可信）
- [ ] 语言跟随会话语言；HTTP 动词、SQL 关键字、协议命令保留英文原文

## 调查深度规则

- Outbounds 只列**本 endpoint 链路实际触达**的调用；同一客户端/仓库里本链路不经过的方法不列入（可作为附注参考路径，见 `references/example.md` 案例 B 的 getOwner）。
- 出站调用只展开到**本服务直接发出**的一跳；下游服务自己的依赖（下游的下游）不展开，在该条目内注明去向即可（如「cart 服务自有 Redis 持久化，不在本层展开」）。
- 跨服务全链路调查（trace 视角）时：每服务一份报告，或按「上游入口 → 第一跳下游」注明层级归属。
- 客户端可见的错误分支要追到**发起翻译的那一层**——下游错误常在上游网关/frontend 被翻译成 HTTP 状态码（如 gRPC 错误 details 前缀 → 422），分支写在翻译层，不在错误发生层。

## 入口定位速查（按技术栈）

- **Spring MVC / WebFlux**：`@RestController` + `@RequestMapping`。⚠️ contract-first 项目的 Controller 可能 implements 生成的 API 接口（openapi-generator），路径注解在生成代码或 `openapi.yml`——grep `@PostMapping` 找不到时先查 spec 的 `operationId`。
- **Node**：Express 路由注册点 / NestJS `@Controller` 装饰器；Next.js 看 `pages/api/**` 或 `app/api/**/route.ts`。
- **Python**：Django `urls.py` → ViewSet/View；FastAPI `@app.post` 装饰器。
- **Go**：`http.ServeMux` 或框架路由注册（gin/echo）。
- **Rails**：`config/routes.rb` → `controller#action`。
- **gRPC 服务端**：`.proto` 的 service 定义 → handler 实现。
- 通用技巧：HTTP path 片段反查（grep）、从 API 测试反查（测试通常点名 endpoint）、从 OpenAPI spec 的 `operationId` 反查。

## 参考文件

- `references/formats.md` — 各技术类型的 Outbound 格式模板库（写 Outbounds 前查阅）
- `references/example.md` — 两份真实开源项目的完整范例：OpenTelemetry Astronomy Shop 下单链路（gRPC/HTTP/Kafka 多格式、循环调用、容错分级、错误跨层传播）+ Spring Petclinic REST（SQL 单出站、认证/校验错误分支、contract-first 定位）
