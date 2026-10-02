---
type: decision
status: accepted
owner: davis
stale_after: 2027-10-02
sources:
  - .claude/rules/project-architecture.instructions.md
  - src/main/java/zxf/upload/domain/filescan/documentthreat/DocumentThreatScanner.java
  - src/main/java/zxf/upload/rest/fileupload/representation/UploadResponse.java
  - src/main/java/zxf/upload/application/fileupload/UploadFileCommand.java
---

# ADR-0004 已声明的务实偏离清单（评审时勿当 bug 修）

以下 5 条是与通用规范冲突时**显式声明保留**的偏离。评审/审计时不得按规范机械报告为缺陷；修改它们须先推翻对应决策。

1. `PollScanResultExecutor` 与门面 `fileScanStatus` 返回 rest 层 `UploadResponse`——异步受理回执与轮询 body 共用统一响应模型（支撑 [[adr-0005-async-polling-not-sse]] 的纯轮询契约）。
2. `AsyncScanProcessor` 依赖 rest 类型——偏离 1 的连锁：缓存 `UploadResponse` 供轮询读取。
3. `FileTypeScanStage` 在 application 层读 `@ConfigurationProperties`——domain 禁依赖 infra，故类型校验规则不入 domain。
4. `UploadFileCommand` 携带 `InputStream content`——multipart 无 @RequestBody，流是写操作的必要输入；领域对象 `UploadFile` 不含流。
5. `DocumentThreatScanner`（domain）依赖 POI、Lombok `@Slf4j` 与 `infrastructure.domain.BusinessException`——POI 按指南严格口径应抽端口+适配器，但反模式 #12 禁预抽接口，**两规范冲突时选务实偏离**；BusinessException 属跨层异常体系豁免（2026-09-24 决策：补声明，不迁移异常包、不加翻译层）。

## 影响与关联

- domain 保持**零 Spring import**（2026-09-23 达成：`UploadFile` 改 JDK 原生、`DocumentThreatScanner` 去 `@Component` 改 `FileScanDomainConfig` @Bean 装配）；豁免仅限第三方领域库、编译期工具与跨层异常体系。
- 异常体系本体见 [[adr-0003-exception-pattern-b]]；相关上下文见 [[fileupload-context]] 与 [[filescan-context]]
