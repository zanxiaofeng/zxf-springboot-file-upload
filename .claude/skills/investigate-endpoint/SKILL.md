---
name: investigate-endpoint
description: Investigate a web API endpoint's full execution path and produce a structured Inbound/Outbounds report. Use when asked to investigate, analyze, or trace an endpoint, its call chain, request handling flow, or what an API does（调查/梳理某个接口、API 调用链、请求处理流程）.
---

# Investigate Endpoint — API 执行链路调查报告

## 产出目标

「Inbound 卡片（通常 1 张；多入口时 I{n}）+ N 张 Outbound 卡片」的同构集合，一次讲清「这个 endpoint 对外契约是什么、对外部世界做了什么」：

- **Inbound**：入站契约卡片——暴露方式、路由、鉴权、入参契约、成功响应、错误映射表
- **Outbounds**：按**运行时执行次序**编号的出站卡片，每张 = 元信息表 + 处理逻辑 + 类型专属 schema（HTTP 三件套 / DB 四段 / MQ 四段…见 `references/formats.md`）

概念模型：等价于 OpenTelemetry 中 1 个 server span（Inbound）+ N 个 client span（Outbounds）；path 用路由模板（`/api/users/{id}`）而非具体实例。报告服务于接口文档化、新人 onboarding、故障排查（外部依赖一眼可见）、安全与依赖审查。

## 调查流程（先读完代码再写报告，禁止边读边猜格式）

1. **定位入口** — route 定义处（定位方法见「入口定位速查」）。记录：HTTP method、path 模板、参数来源（path / query / header / cookie / body——JSON、XML、multipart 表单、二进制流等）、入站前置限制（请求体大小上限、过滤器/中间件、认证与授权）。
2. **沿调用链下行** — handler → 应用服务/用例层 → 领域逻辑 → 基础设施客户端，每跳记 `file:line`，直到触及进程边界。注意纯转发层（门面/代理）——真正的编排在它下一层，别在此停。
3. **识别出站交互** — 判定标准：**跨进程 / 跨网络 / 跨信任边界**。三类：
   - 网络出站：下游 HTTP/gRPC、数据库、缓存、消息队列、邮件、对象存储、自定义 TCP/UDP 协议
   - 本机出站：子进程执行、有业务意义的文件系统读写（结果归档、文件导出、批处理中间文件等）
   - 进程内重计算（大报文解析、加解密、媒体转码等重量级库操作）：编号列出，但必须标注「进程内，无外部系统」
4. **提取协议真实格式** — SQL / 命令 / 报文一律从客户端代码**实证提取**，禁止臆造；动态值写 `{占位符}`。可配置的地址与参数找到真实读取点（配置文件、环境变量、密钥管理），默认值与覆盖关系交叉核实，`${ENV:default}` 形式原样保留。
5. **识别控制流** — 短路（管道/责任链/校验链）、策略分支（开关/枚举/feature flag）、并行、异步（后台任务/事件/消息投递）、弹性（超时/重试/熔断）、背压（信号量/连接池上限/限流）。这些决定 Outbounds 的编号次序与标注方式（标注语法见「输出结构契约」）。

## 输出结构契约（强制）

结构不靠自觉，按本契约执行，违反任意一条即不合格。卡片总模板与各类型 schema 全文见 `references/formats.md`。

### 卡片语法

- 每个 Inbound / Outbound 是一张独立的 H3 卡片：
  - Inbound：`### I{n}. {暴露方式} → {路由 / Topic / 触发源}`
  - Outbound：`### O{n}. {类型} → {目标系统 / 端点}`
- 每张卡片三段式，顺序固定：**元信息表 → 处理逻辑 → 类型专属子结构**
- 处理逻辑为必填：①②③ 编号列出本链路内实际发生的步骤，不得写成叙述性段落；控制流用行内标注嵌入步骤——`②（循环：逐商品 2N 次）`、`③（与 ② 并行）`、`④（条件：feature flag 开启时）`、`⑤（异步：不等结果）`
- 全局控制流（短路 / 并行组 / 背压）在 Outbounds 标题行下一行说明
- I 与 O 使用独立编号空间；卡片间以编号互相引用（Inbound 的「下游依赖」字段、处理逻辑步骤内的 `调用 O2`），形成 I1 → O1…On 的调用图

### 硬性禁令与空值语义

- **禁止任何超过 3 行且无标题的叙述性段落**：事实性内容必须落在 H4/H5 标题、列表或表格之下；背景与补充说明一律 bullet 列表
- 协议细节一律以 `file:line` 佐证，无依据不得写入
- **空值语义（防填表编造）**：schema 规定的小节 / 字段在代码中无对应事实时，写一行显式事实陈述——「无重试」「无超时配置，默认无限等待」「无事务注解，由框架默认事务承担」。错误处理表只收**代码中真实存在的分支**；代码中不存在的分支以事实行入表或注明。宁可写「无」，不可编一行来填表。

## 输出骨架

```
代码依据: <入口 handler → 中间层 → 出站点的调用链概览，含 file:line>

## Inbound
### I1. {暴露方式} → {路由 / Topic / 触发源}
（元信息表 + 处理逻辑 + 入口类型专属 schema，模板见 references/formats.md §一/§二）

## Outbounds    （<短路 / 并行 / 背压等全局控制流，一行>）
### O1. {类型} → {目标系统 / 端点}
（元信息表 + 处理逻辑 + 出站类型专属 schema）
### O2. …

## 补充说明
- <显式声明不存在的类别：无 DB / 无下游 HTTP / 无消息队列 …>
- <跨条目信息：降级策略全貌、异步链路去向、错误如何映射回 I1>
```

## Inbound 卡片规范

- 元信息表字段（暴露方式 / 入口点 / 路由契约 / 触发方式 / 鉴权与限流 / 幂等 / 处理逻辑 / 下游依赖）逐项填写；字段不适用于该入口类型时写「不适用」，代码中无对应机制时写「无」
- method + **路由模板**；Content-Type 必写；认证方式（Bearer / Basic / API Key / OAuth）在鉴权字段体现；限流规则附 `file:line`
- 非 HTTP 入口（gRPC 服务端 / MQ 消费者 / 定时任务 / CLI / 事件监听）不用 HTTP 骨架，用各自专属 schema（`references/formats.md` §二）
- 请求/响应示例用**真实字段名**（从请求/响应模型实证），动态值 `{占位符}` 或真实形态（UUID）；非 JSON 请求体（multipart 表单、XML、二进制流）按原生结构展示，不笼统略过
- 序列化细节要体现：核实哪些字段真正出现在响应里（null 省略、字段命名 camelCase/snake_case、包装信封）
- **错误映射表为必填**（Inbound 特有子结构）：内部异常/场景 → 对外响应 → 映射位置 `file:line` → 是否泄漏内部信息；错误来源分本地校验 / 鉴权 / 下游传播 / 未捕获四类；**表只收代码中真实存在的分支**（鉴权有无看元信息表「鉴权与限流」字段，不进表）；平台级限制（如 413 请求体超限）与业务校验（400/422）分开归因
- 客户端可见的错误分支要追到**发起翻译的那一层**——下游错误常在上游网关/frontend 被翻译成 HTTP 状态码（如 gRPC 错误 details 前缀 → 422），分支写在翻译层，不在错误发生层
- 异步受理（202 + 轮询/回调）写明结果查询路径；有幂等键/重放语义的注明

## Outbound 卡片规范

- 一张卡片 = **一个业务动作**（不是一次调用）：动作内的多方法组合用 a/b 子条目、循环用次数标注表达，不拆卡；`O{n}` 的 n = **运行时执行次序**（不是代码定义次序）
- 「类型」细分到协议/库（如 `消息队列（Kafka，异步生产者）`、`HTTP（JSON over REST）`、`子进程（CLI 调用）`）；跨进程 / 跨网络 / 跨信任边界的判定标准见调查流程第 3 步
- 卡片体按类型专属 schema 展开（`references/formats.md` §三），每类必填小节缺一即不合格；schema 字段的**值**保持原生格式——SQL 原文、原生 JSON 报文、命令序列、C:/S: 报文交换图
- 条件执行在「触发条件」字段与处理逻辑行内标注；循环调用标注次数规则（如「逐条目 2N 次」）；并行调用标注「与 O{n} 并行」；异步/投递类操作标注后续去向（谁消费、如何查询）
- 保护机制（超时值、重试次数、熔断参数、背压许可数）写「保护与幂等」字段及 schema 对应段，数值从配置实证
- 出站只展开到**本服务直接发出**的一跳；下游服务自己的依赖（下游的下游）不展开，在该卡片内注明去向即可（如「cart 服务自有 Redis 持久化，不在本层展开」）
- 进程内重计算照常编号，类型写「进程内」，末尾标注「无外部系统」

## 质量自查（报告发出前）

- [ ] 每个协议细节都能给出 `file:line` 依据，无臆造（参数名、报文、SQL、默认值）
- [ ] `O{n}` 编号次序 = 执行次序；短路点、循环与条件触发已标注（行内标注或触发条件字段）
- [ ] I 与 O 卡片结构对称：均为「元信息表 → 处理逻辑 → 类型专属子结构」三段式
- [ ] 编号闭环：I1「下游依赖」列出的每个 O 编号在报告中均有对应卡片；每个 O 卡片能在至少一个 I 或上游 O 卡片中被引用（无孤儿 O、无悬空引用）
- [ ] 处理逻辑为 ①②③ 编号步骤，无叙述性段落；全篇无超过 3 行的无标题段落
- [ ] Inbound 错误映射表覆盖认证 + 成功 + 全部错误分支（端点无认证时在元信息表显式写无），错误码与状态码经错误处理层（全局 handler / 错误中间件）核实，每行含映射位置与是否泄漏内部信息
- [ ] 每个 Outbound 的失败处理 / Failed 表每行含 `file:line`；代码中不存在的分支以「无 X」事实行表达，无编造
- [ ] 配置默认值 / 环境变量已从真实读取点核实，保留 `${ENV:default}` 原样
- [ ] 进程内 vs 真实出站有区分标注
- [ ] 不涉及的类别已显式声明（「无 DB 调用」比留白更可信）
- [ ] 语言跟随会话语言；HTTP 动词、SQL 关键字、协议命令保留英文原文

## 调查深度规则

- Outbounds 只列**本 endpoint 链路实际触达**的调用；同一客户端/仓库里本链路不经过的方法不列入（可作为附注参考路径，见 `references/example.md` 案例 B 的 getOwner）。
- 跨服务全链路调查（trace 视角）时：每服务一份报告，或按「上游入口 → 第一跳下游」注明层级归属。
- 隐式出站（AOP / 动态代理 / 框架拦截器发出，源码无显式调用点）按「入口定位速查」的排查小节处理；排查不到的标注「疑似隐式出站，未在源码确认」。

## 入口定位速查（按技术栈）

- **Spring MVC / WebFlux**：`@RestController` + `@RequestMapping`。⚠️ contract-first 项目的 Controller 可能 implements 生成的 API 接口（openapi-generator），路径注解在生成代码或 `openapi.yml`——grep `@PostMapping` 找不到时先查 spec 的 `operationId`。
- **Node**：Express 路由注册点 / NestJS `@Controller` 装饰器；Next.js 看 `pages/api/**` 或 `app/api/**/route.ts`。
- **Python**：Django `urls.py` → ViewSet/View；FastAPI `@app.post` 装饰器。
- **Go**：`http.ServeMux` 或框架路由注册（gin/echo）。
- **Rails**：`config/routes.rb` → `controller#action`。
- **gRPC 服务端**：`.proto` 的 service 定义 → handler 实现。
- 通用技巧：HTTP path 片段反查（grep）、从 API 测试反查（测试通常点名 endpoint）、从 OpenAPI spec 的 `operationId` 反查。

### 隐式出站排查（源码无显式客户端调用点时）

- 检查 AOP 切面 / 拦截器 / Filter / 中间件中是否有网络或 DB 操作
- 检查动态代理（JDK Proxy / CGLIB）与字节码增强（ASM / ByteBuddy）
- 检查框架级出站：Spring Cloud Feign / RestTemplate 拦截器 / gRPC ClientInterceptor / `@Async` 后台任务 / `@EventListener` 事件链
- 排查后仍无法定位的，在报告中标注「疑似隐式出站，未在源码确认」，**不得臆造**

## 安全边界

本 Skill 只做静态代码阅读与结构化输出：

- 不执行任何网络请求，不启动 / 调用目标服务
- 不修改目标代码库
- 不在报告中外泄凭证明文：凭证**值**一律 `{来源}` 占位；配置键 / 环境变量名 / 密钥管理来源可以写（调查需从配置文件实证读取点，但只引用、不复制值）
- 不附带脚本、不臆造脚本调用（本 Skill 无 scripts/ 目录）；shell 检索工具（grep / rg / find）属调查手段，不受此限

## 参考文件

- `references/formats.md` — 卡片总模板 + 全部 Inbound / Outbound 类型专属 schema（写报告前按目标类型直达 §二/§三 对应小节查阅，无需全文通读）
- `references/example.md` — 不合格反例 + 两份真实开源项目的完整卡片制范例：OpenTelemetry Astronomy Shop 下单链路（gRPC/HTTP/Kafka 多类型、循环调用、容错分级、错误跨层传播、空值语义示范）+ Spring Petclinic REST（SQL 单出站、认证/校验错误映射、contract-first 定位）
