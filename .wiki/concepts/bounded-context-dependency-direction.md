---
type: concept
status: accepted
owner: davis
stale_after: 2027-01-02
sources:
  - .claude/rules/project-architecture.instructions.md
  - src/main/java/zxf/upload/domain/filescan/ScanContext.java
  - src/main/java/zxf/upload/application/fileupload/AsyncUploadCommandExecutor.java
---

# 上下文依赖方向：FileUpload 上游 / FileScan 下游，层内单向、层间反向

**FileUpload = 上游**（定义文件模型、提供受理暂存与文件保管）；**FileScan = 下游**（消费 staged 文件，产出扫描结论与处置指令）。三层各自内部严格单向，但方向相反——这是本项目最易误判的结构规则：

| 层 | 方向 | 体现 |
|----|------|------|
| domain | `filescan → fileupload` | `ScanContext` 携带 `UploadFile`；管道消费上游产物 |
| application | `fileupload → filescan` | 受理 Executor 调 `FileScanService` / `AsyncScanProcessor` |
| infrastructure | `filescan → fileupload` | `FileScanService` 处置时委托 `FileStorageService` |

## 陷阱

- **领域依赖方向 ≠ 应用流程依赖方向**：上传流程在用例层"驱动"扫描，故 application 里 fileupload 依赖 filescan；但在领域层，扫描消费上传的产物，方向相反。两表都合法，混用两层口径才会得出"循环依赖"的错觉。
- 硬禁令：禁止 `fileupload → filescan` 的 domain import，禁止 `filescan → fileupload` 的 application import。

## 影响与关联

- 判定新代码落位时先过这张表：[[fileupload-context]]、[[filescan-context]]
- 方向规则源自架构总纲 [[adr-0001-hexagonal-pipeline-cqrs]]
