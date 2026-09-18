# Spring Resource 资源抽象与释放处置详解

> **定位**：spring-core 的统一资源访问体系（`org.springframework.core.io`）—— 用一套"资源句柄"接口屏蔽 File / URL / classpath / jar / 内存字节数组之间的访问差异，并给出与之配套的**资源释放约定**。
> **对应代码**：Spring Framework 7.0.8（本项目 Boot 4.1 所带版本）源码，关键类 `Resource`、`WritableResource`、`DefaultResourceLoader`、`PathMatchingResourcePatternResolver`。
> **为什么值得单独一篇**：Resource 的设计核心是"**句柄与流的生命周期刻意分离**"——句柄可复用不关闭，流一次性用完即关。理解这一点，资源泄漏与 `FileNotFoundException` 这类问题就能在写代码前规避。

---

## 一、问题本质：JDK 原生资源访问为什么"割裂"

不引入抽象时，Java 里访问一份内容至少有三套互不兼容的方式：

| 方式 | 能访问什么 | 痛点 |
|---|---|---|
| `java.io.File` | 本地文件系统 | 无法表达 classpath / 远程资源；jar 内资源拿不到 File |
| `java.net.URL` | 有协议的资源（http/file/jar…） | 访问 API 贫弱（无 contentLength/exists 语义），注册自定义协议要动 `URL` 静态工厂 |
| `ClassLoader.getResourceAsStream` | classpath | 只能读流；无法探测存在性/大小，跨 jar 场景易踩空 |

而框架代码恰恰需要"拿来一个位置就能读"，且位置可能是任意一种：`@PropertySource("classpath:app.properties")`、邮件附件（内存字节）、`RestTemplate` 上传（本地文件或用户上传流）。

`Resource` 把这件事统一成**一个小的句柄接口**：

- **探测**：`exists()` / `isReadable()` / `isFile()` / `isOpen()`
- **读**：`getInputStream()` / `readableChannel()` / `getContentAsByteArray()` / `getContentAsString(Charset)`
- **元数据**：`contentLength()` / `lastModified()` / `getURL()` / `getFilename()` / `getDescription()`
- **导航**：`createRelative(String)`

设计基调写在最底层的 `InputStreamSource` javadoc 里：**"It is usually expected that every such call creates a *fresh* stream"** —— 每次 `getInputStream()` 都应返回新流。这一句话决定了整个体系的释放模型（见第五章）。

---

## 二、类型体系

```
InputStreamSource                    # 唯一根抽象：InputStream getInputStream()（@FunctionalInterface）
└── Resource                         # 读 + 元数据 + 导航
    ├── WritableResource             # + isWritable() / getOutputStream() / writableChannel()
    ├── ContextResource              # 声明"相对于所属上下文的路径"（ApplicationContext 内部用）
    │
    ├── AbstractResource             # 骨架：实现 equals/hashCode/公共 default
    │   ├── AbstractFileResolvingResource    # 能解析为 File 的公共逻辑（jar 内 getFile 的行为在这定）
    │   ├── UrlResource              # java.net.URL：http/https/file/jar/ftp…
    │   ├── ClassPathResource        # classpath（按 Class 或 ClassLoader 定位），jar 内也可读流
    │   ├── FileSystemResource       # File / Path（实现 WritableResource）
    │   ├── FileUrlResource          # file: URL，且可直接当 File 用
    │   ├── InputStreamResource      # 已有流/流源的一次性包装（isOpen() = true）
    │   ├── ByteArrayResource        # 内存 byte[]（实现 WritableResource）
    │   ├── ModuleResource           # JPMS Module 内资源（6.1+）
    │   ├── VfsResource              # JBoss VFS（遗留）
    │   └── DescriptiveResource      # 只有描述没有内容（占位）
    │
    └── EncodedResource（support 包）# 包装器：Resource + charset，非数据源
```

选型速查：

| 你手里有什么 / 想干什么 | 用哪个 |
|---|---|
| 一个本地文件/路径 | `FileSystemResource`（7.0 起 `PathResource` 已 `@Deprecated(forRemoval)`，一律用它替代） |
| classpath 上的模板/配置/规则文件 | `ClassPathResource` |
| 远程 URL 或任意协议 URL | `UrlResource` |
| 内存里已有字节数组（邮件附件、测试桩） | `ByteArrayResource` |
| 别人递给你一个**只能消费一次**的 `InputStream` | `InputStreamResource`（详见第五章例外条款） |
| 按模式批量找资源 | `ResourcePatternResolver` + Ant 通配符（第四章） |

---

## 三、两种读取语义：流式 vs 对象式（`isFile()` 是分水岭）

**流式读取**（任何实现都支持）：

```java
Resource resource = new ClassPathResource("rules/malware.yar");

try (InputStream in = resource.getInputStream()) {   // 每次调用都是新流
    // ...
}

// 便利方法：小内容一次性读出，内部用 FileCopyUtils 自动关流
byte[] bytes = resource.getContentAsByteArray();
String text   = resource.getContentAsString(StandardCharsets.UTF_8);
```

**对象式读取**（仅当资源物理上就是文件）：

```java
if (resource.isFile()) {
    File file = resource.getFile();          // FileSystemResource / 磁盘上的 file: URL
    Path path = resource.getFilePath();      // default 方法，等价 getFile().toPath()
}
```

关键分水岭在 `AbstractFileResolvingResource.getFile()`：它**只在资源能解析为文件系统真实文件时返回 File**。
`ClassPathResource` 指向的东西如果**在 jar 包里**（`target/*.jar!rules/malware.yar`），`getFile()` 直接抛 `FileNotFoundException` —— jar 里的条目不是文件，只能流式读。

由此得到一条工程铁律：

> **写面向任意 Resource 的代码时，先问 `isFile()`；不是文件就老老实实走流。** 永远不要对"Resource 一定能拿到 File"做假设。

`isOpen()` 的语义也在此埋下伏笔：默认 `false`（每次读都是新流，句柄与流无关）；唯一的 `true` 实现是 `InputStreamResource`（流是外来的、已打开的）。

---

## 四、如何获得 Resource：ResourceLoader 与前缀协议

### 4.1 直接 new（场景明确时最简单）

```java
new FileSystemResource(Path.of("./data/upload-storage", name));
new ClassPathResource("rules/malware.yar");
new UrlResource("https://example.com/feed.xml");
new ByteArrayResource(bytes, "attachment.pdf");
```

### 4.2 ResourceLoader（字符串位置 → Resource）

`DefaultResourceLoader.getResource(location)` 的真实解析顺序（7.0.8 源码）：

```
① 注册的 ProtocolResolver 逐个尝试（扩展点：自定义协议，如加密卷前缀）
② "/" 开头            → getResourceByPath()
③ "classpath:" 前缀   → new ClassPathResource(...)
④ 其余按 URL 解析成功 → UrlResource（file: URL 则是 FileUrlResource）
⑤ URL 解析失败        → 回落 getResourceByPath()
```

注意两个反直觉点：

- **无前缀 ≠ 文件系统**：`getResourceByPath()` 默认返回 ClassPathResource（`ClassPathContextResource`）。`loader.getResource("app.properties")` 找的是 classpath，不是当前目录。想要文件系统语义：显式 `file:` 前缀，或换 `FileSystemResourceLoader`（覆写了 `getResourceByPath`）。
- **`file:` URL 得到的不是 UrlResource 而是 FileUrlResource** —— Spring 知道你会拿它当文件用。

### 4.3 模式匹配：ResourcePatternResolver

```java
ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
Resource[] scripts = resolver.getResources("classpath*:db/migration/*.sql");
```

| 前缀 | 行为 |
|---|---|
| `classpath:` | 只查**第一个**命中的 classpath 条目 |
| `classpath*:` | **扫描所有** classpath 条目（跨 jar），仅 PatternResolver 支持 |
| `file:/opt/app/conf/*.yml` | 文件系统 Ant 通配符 |

批量扫描有启动/IO 成本，生产上避免在热路径用宽模式（如 `classpath*:**/*.xml`）。

### 4.4 注入与包装

- 字段注入：`@Value("classpath:templates/mail.txt") private Resource template;`（`ResourceEditor` + 转换服务，数组用 `ResourceArrayPropertyEditor`）
- 配置加载：`@PropertySource("classpath:app.properties")`、`PropertiesLoaderUtils.loadProperties(resource)`
- 文本编码：`new EncodedResource(resource, StandardCharsets.UTF_8)`（包装器，自身不持有流）

### 4.5 与上传项目的衔接：MultipartFile#getResource()

Spring MVC 的 `MultipartFile` 自 5.1 起提供：

```java
Resource part = multipartFile.getResource();   // MultipartFileResource 适配
restTemplate.postForObject(url, new HttpEntity(part, headers), String.class);
```

它把上传件适配成 Resource（携带 filename 与 contentLength），可直接交给 `RestTemplate`/`WebClient`，**不必**先 `getBytes()` 全量塞进内存再包 `ByteArrayResource` —— 对大文件这是明显的内存差异。（本项目是 Path 为中心的自管管线，不依赖它；但对接框架 API 时这是最短的桥。）

---

## 五、资源释放的处置模式（本章是正题）

### 5.1 原则 0：先分清"谁需要释放"

| 对象 | 需要释放吗 |
|---|---|
| `Resource` 句柄本身 | **不需要** —— 无状态、可复用、接口**刻意没有 close()、不实现 AutoCloseable** |
| `getInputStream()` 返回的流 | **需要** —— 谁调用谁关闭 |
| `getOutputStream()` / `readableChannel()` / `writableChannel()` | **需要** —— 通道同样占 fd |
| `getFile()` 拿到的 File/Path | 不需要 —— 文件句柄在你后续自己 open 的流里 |

这是整套设计最重要的决定：**Resource 不实现 `AutoCloseable`**。句柄像"地址"，流像"按地址开的一次性门"——地址无所谓开关，门用完必须关。如果 Resource 实现了 AutoCloseable，try-with-resources 会诱导用户去关句柄，反而掩盖了真正的泄漏点。

### 5.2 原则 1：谁 getInputStream，谁负责关

```java
// ✅ 标准姿势：try-with-resources，异常路径同样释放
try (InputStream in = resource.getInputStream()) {
    process(in);
}

// ❌ 泄漏：lambda / 回调里开流，异常路径没人管
files.forEach(r -> {
    InputStream in = r.getInputStream();   // 后面任何一行抛异常 → fd 泄漏
    ...
});
```

框架自身遵守同一约定，所以边界很清楚：**Spring 组件内部开的流由 Spring 关**（`@PropertySource` 加载、`ResourceHttpRequestHandler` 写静态资源响应、`PropertiesLoaderUtils`……）；而**你显式调用的 `getInputStream()` 归你关**。

### 5.3 原则 2：一次性读取，优先用"自动关流"的便利方法

```java
String content = resource.getContentAsString(StandardCharsets.UTF_8);
byte[] raw     = resource.getContentAsByteArray();
```

这两个 default 方法内部走 `FileCopyUtils`，而 `FileCopyUtils` 的拷贝方法用 try-with-resources **把传入的流关掉**（7.0 源码即 `try (in; out)` 简写）。你拿到内容时流已被关闭，零释放负担。

### 5.4 原则 3：拷贝工具的"谁关流"约定必须分清

spring-core 里有两套流拷贝工具，语义**相反**：

| 工具 | 关流行为 | 适用 |
|---|---|---|
| `FileCopyUtils.copy(in, out)` | **关闭两个流** | 流的生命周期到此为止 |
| `StreamUtils.copy(in, out)` | **不关闭**（非关闭变体） | 调用方在同一 try 里组合/继续使用这些流 |

`getContentAs*()`、`DatabasePopulator` 等基于前者；Web 层写响应常基于后者（响应流由容器关）。混用而不看 javadoc，是"看起来关了其实没关"或"double close 报错"的常见根因。**用之前先确认它替不替你关。**

### 5.5 原则 4：唯一的例外 —— InputStreamResource

```java
Resource r = new InputStreamResource(someExistingStream);
r.isOpen();            // true —— 流是外来的、已打开
r.getInputStream();    // 第一次：OK
r.getInputStream();    // 第二次：IllegalStateException("InputStream has already been read ...")
```

- 它包住的是**别人创建的流**（7.0 起可包任意 `InputStreamSource`），不能也不该重新打开；
- 单次消费场景：作为邮件附件、请求体恰好读一次；
- 流本身的 close 仍归**创建流的一方**（或由最终消费方在 try-with-resources 里关）—— Resource 不会替你关它；
- **需要多次读取？换 `ByteArrayResource`**（内存可重复读，javadoc 推荐的邮件附件方案），或继承 `AbstractResource` 自造数据源。这是 `InputStreamSource` javadoc 原文给的选型指引。

### 5.6 原则 5：WritableResource 同理，且别忘了 flush

```java
WritableResource out = new FileSystemResource(path);
try (OutputStream os = out.getOutputStream()) {
    os.write(data);
    os.flush();          // try-with-resources 只关不 flush，写场景必须显式
}
```

若只是文件复制，且句柄本来就是 `FileSystemResource`，直接 `Files.copy(source, target)` 走 NIO 更直 —— Resource 此时只承担"位置描述符"角色。

### 5.7 模式串联：三类典型任务的完整写法

```java
// ① 小文本（模板/规则）：便利方法，零手工关流
String sql = templateResource.getContentAsString(UTF_8);

// ② 大文件流式处理：try-with-resources 包住整个消费过程
try (InputStream in = archiveResource.getInputStream()) {
    inspect(in);   // 边读边校验，超限即断
}

// ③ 批量资源：每个 Resource 独立开流、独立关
for (Resource r : resolver.getResources("classpath*:db/migration/*.sql")) {
    try (InputStream in = r.getInputStream()) {
        runner.execute(in);
    }
}
```

### 5.8 一点辨析：Resource ≠ DataBuffer

`org.springframework.core.io.buffer.DataBuffer`（WebFlux/响应式栈）名字里也有 Resource，但释放模型完全不同：它是**引用计数**（`PooledDataBuffer.release()`、`DataBufferUtils.release(buffer)`，底层 Netty 池化内存），没有 close()。阻塞栈（本项目）用 Resource + stream-close 即可；两套体系不要互相套用心智模型。

---

## 六、常见坑速查

| 坑 | 后果 / 对策 |
|---|---|
| 对 jar 内 `ClassPathResource` 调 `getFile()` | `FileNotFoundException`；先 `isFile()`，非文件走流 |
| `InputStreamResource` 读第二次 | `IllegalStateException`；需多次读换 `ByteArrayResource` |
| 到处找 `Resource.close()` | 接口没有 close，也不实现 AutoCloseable；**关流不关句柄** |
| 假设无前缀路径指文件系统 | `DefaultResourceLoader` 无前缀按 **classpath** 解析；文件系统用 `file:` 或 `FileSystemResourceLoader` |
| `classpath:` 与 `classpath*:` 混用 | 前者只取第一个命中；跨 jar 扫描必须后者（仅 PatternResolver 支持，且有扫描成本） |
| 继续使用 `PathResource` | 7.0 起 `@Deprecated(forRemoval)`；换 `FileSystemResource(Path)` |
| `StreamUtils` / `FileCopyUtils` 混用 | 一个关流一个不关；用前看 javadoc 确认"谁关" |
| `readableChannel()` 拿了不关 | 通道同样占用 fd，try-with-resources |
| `EncodedResource` 与 `getContentAsString(charset)` 同时用 | 二选一，编码指定一处即可 |
| 上传件先 `getBytes()` 再包 `ByteArrayResource` | 全量入内存；用 `MultipartFile#getResource()` 适配 |

---

## 七、动手实验（jshell，无工程依赖）

```bash
jshell --class-path ~/.m2/repository/org/springframework/spring-core/7.0.8/spring-core-7.0.8.jar
```

```java
// 1) 本地文件句柄：isFile() 为真，便利方法已自动关流
import org.springframework.core.io.*;
import java.nio.charset.StandardCharsets;
var r = new FileSystemResource("pom.xml");
System.out.println(r.isFile());                                    // true
System.out.println(r.getContentAsString(StandardCharsets.UTF_8)
                   .lines().findFirst());                          // <?xml ...（返回时流已被便利方法关闭）

// 2) jar 内资源：getFile 失败，getInputStream 成功
var inJar = new ClassPathResource("org/springframework/core/io/Resource.class");
System.out.println(inJar.isFile());                                // false（条目在 spring-core jar 里）
System.out.println(inJar.getFile());                               // → FileNotFoundException
try (var in = inJar.getInputStream()) { System.out.println(in.read()); }  // 流式读取 OK

// 3) 无前缀的默认语义
var loader = new DefaultResourceLoader();
System.out.println(loader.getResource("pom.xml").getClass());      // ClassPathResource$ClassPathContextResource —— 本质是 classpath 解析！
System.out.println(loader.getResource("file:./pom.xml").getClass()); // FileUrlResource
```

---

## 八、总结

一句话模型：

> **Resource 是可复用的地址，Stream 是一次性内容。地址不用关，门（流）用完必须关；谁调用 `getInputStream` 谁负责关；唯一"流在门外"的例外是 `InputStreamResource` —— 一次消费，别复用。**

配套记忆点：

1. 便利方法（`getContentAs*`）已替你关流，小内容首选；
2. `isFile()` 不为真就别碰 `getFile()`（jar 内必炸）；
3. 拷贝工具先看"谁关流"：`FileCopyUtils` 关，`StreamUtils` 不关；
4. 批量与通配符找 `ResourcePatternResolver`，跨 jar 用 `classpath*:`；
5. 需要写：`WritableResource` + try-with-resources + 显式 flush；7.0 起文件句柄一律 `FileSystemResource(Path)`。
