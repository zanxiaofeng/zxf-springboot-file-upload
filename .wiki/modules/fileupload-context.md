---
type: module
status: accepted
owner: davis
stale_after: 2027-01-02
sources:
  - src/main/java/zxf/upload/domain/fileupload/UploadPolicy.java
  - src/main/java/zxf/upload/application/fileupload/
  - src/main/java/zxf/upload/infrastructure/fileupload/
---

# FileUpload 限界上下文（上游）

**职责**：定义文件模型（`UploadFile` 值对象）、上传预检规则（`UploadPolicy`：空文件/大小/扩展名/同步路由阈值）、受理暂存与文件保管。**角色 = 上游**：为 [[filescan-context]] 提供 staged 文件与文件生命周期设施。

## 组成与写侧时序

- 用例（受理边 CQRS 写侧）：`UploadFileCommand` → `UploadFileCommandChecker`（委托 `UploadPolicy`）→ `SyncUploadCommandExecutor` / `AsyncUploadCommandExecutor`（内 `toUploadFile()` 转换 → staging 落盘 → 调用扫描 → 结果翻译）。
- 基础设施：`StagingService`（暂存）/ `FileStorageService`（保管，被 filescan 处置时反向消费）。
- `UploadPolicy` 由 `FileUploadDomainConfig` `@Bean` 从 properties 提取**纯值**装配——domain 不依赖 infra。

## 修改注意

- **预检规则只加在 `UploadPolicy`**，不在 Checker/Executor 里散落 if——规则唯一来源原则。
- 领域对象保持零 Spring import；`UploadFileCommand` 携带 `InputStream` 属已声明偏离（见 [[adr-0004-declared-pragmatic-deviations]]）。
- 依赖方向硬禁令见 [[bounded-context-dependency-direction]]。
