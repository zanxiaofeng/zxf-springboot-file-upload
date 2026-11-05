# 调查执行指南（入口定位 / 隐式出站 / 调查深度 / 安全边界）

执行调查时参考本文件；输出结构契约见 `../SKILL.md`，schema 菜单与证据配额见 `formats.md`。

## 入口定位速查（按技术栈）

- **Spring MVC / WebFlux**：`@RestController` + `@RequestMapping`。⚠️ contract-first 项目的 Controller 可能 implements 生成的 API 接口（openapi-generator），路径注解在生成代码或 `openapi.yml`——grep `@PostMapping` 找不到时先查 spec 的 `operationId`。
- **Node**：Express 路由注册点 / NestJS `@Controller` 装饰器；Next.js 看 `pages/api/**` 或 `app/api/**/route.ts`。
- **Python**：Django `urls.py` → ViewSet/View；FastAPI `@app.post` 装饰器。
- **Go**：`http.ServeMux` 或框架路由注册（gin/echo）。
- **Rails**：`config/routes.rb` → `controller#action`。
- **gRPC 服务端**：`.proto` 的 service 定义 → handler 实现。
- 通用技巧：HTTP path 片段反查（grep）、从 API 测试反查（测试通常点名 endpoint）、从 OpenAPI spec 的 `operationId` 反查。

## 隐式出站排查（源码无显式客户端调用点时；次优先兜底——主线调查完成后执行，必要时可放弃，见 SKILL.md 原则三）

- 检查 AOP 切面 / 拦截器 / Filter / 中间件中是否有网络或 DB 操作
- 检查动态代理（JDK Proxy / CGLIB）与字节码增强（ASM / ByteBuddy）
- 检查框架级出站：Spring Cloud Feign / RestTemplate 拦截器 / gRPC ClientInterceptor / `@Async` 后台任务 / `@EventListener` 事件链
- 二者区分：**放弃** = 未查、不查且报告中不提（原则三默认姿态）；**查而未果** = 已排查但无法定位，在报告中标注「疑似隐式出站，未在源码确认」，**不得臆造**

## 出站交互三类（判定标准：跨进程 / 跨网络 / 跨信任边界）

- **网络出站**：下游 HTTP / gRPC、数据库、缓存、消息队列、邮件、对象存储、自定义 TCP/UDP 协议
- **本机出站**：子进程执行（CLI 工具调用）、有业务意义的文件系统读写（暂存落盘、结果归档、文件导出、批处理中间文件、隔离区转移等）
- **进程内重计算**：大报文解析、加解密、媒体转码等重量级库操作——照常编号列出，但必须标注「进程内，无外部系统」

## 调查深度规则

- **调查优先级**（SKILL.md 原则三）：主线 = 直接引用链 + Happy flow，预算优先；横切关注点与系统底层机制排查（含上方「隐式出站排查」）是次优先方向，必要时可放弃、报告中无需声明——已实证改变本链路行为的除外。
- Outbounds 只列**本 endpoint 链路实际触达**的调用；同一客户端/仓库里本链路不经过的方法不列入（可作为附注参考路径，见 `example.md` 案例 B 的 getOwner）。
- 出站只展开到**本服务直接发出**的一跳；下游服务自己的依赖（下游的下游）不展开，在该卡片内注明去向即可（如「cart 服务自有 Redis 持久化，不在本层展开」）。
- 跨服务全链路调查（trace 视角）时：每服务一份报告，或按「上游入口 → 第一跳下游」注明层级归属。

## 安全边界

本 Skill 只做静态代码阅读与结构化输出：

- 不执行任何网络请求，不启动 / 调用目标服务
- 不修改目标代码库
- 不在报告中外泄凭证明文：凭证**值**一律 `{来源}` 占位；配置键 / 环境变量名 / 密钥管理来源可以写（调查需从配置文件实证读取点，但只引用、不复制值）
- 不附带脚本、不臆造脚本调用（本 Skill 无 scripts/ 目录）；shell 检索工具（grep / rg / find）属调查手段，不受此限
