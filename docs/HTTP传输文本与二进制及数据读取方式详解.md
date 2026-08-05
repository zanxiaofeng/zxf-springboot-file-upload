# HTTP 传输文本与二进制的方式差异及数据读取模式深度解析

> 从 Content-Type 到内存模型：一次讲清文本与二进制的传输差异，以及全量缓冲与流式处理的本质区别。

## 目录

- [一、核心结论](#一核心结论)
- [二、HTTP 传输文本的方式](#二http-传输文本的方式)
- [三、HTTP 传输二进制的方式](#三http-传输二进制的方式)
- [四、传输增强：Content-Encoding 与 Transfer-Encoding](#四传输增强content-encoding-与-transfer-encoding)
- [五、三种方式对比总结](#三种方式对比总结)
- [六、为什么 Base64 内嵌 JSON 无法流式处理](#六为什么-base64-内嵌-json-无法流式处理)
- [七、流式处理内存常数级的根本原因](#七流式处理内存常数级的根本原因)
- [八、工程实践准则](#八工程实践准则)
- [九、常见误区与踩坑清单](#九常见误区与踩坑清单)

---

## 一、核心结论

HTTP 协议本身对消息体（body）是**透明的字节流**——协议层并不区分"文本"和"二进制"。两者的差异体现在四个层面：

1. **Content-Type**：声明 body 的解释方式；
2. **编码方式**：文本需要字符集编解码，二进制原样传输或 Base64 文本化；
3. **传输增强**：`Content-Encoding`（如 gzip）压缩体积，`Transfer-Encoding: chunked` 分块流式发送——两者对文本和二进制都适用；
4. **读取方式**：文本通常全量解码为字符串，二进制可以逐块流式消费。

而读取方式上的**全量缓冲**与**流式处理**，本质区别在于：处理动作是否要求"数据完整"作为前置条件——这决定了峰值内存是 `O(N)` 还是 `O(1)`。

---

## 二、HTTP 传输文本的方式

### 2.1 直接传输（最常见）

文本本质也是字节，通过指定字符集直接放在 body 中：

```http
POST /api/user HTTP/1.1
Content-Type: application/json; charset=UTF-8
Content-Length: 38

{"name":"张三","age":28}
```

常见文本类型：

| Content-Type | 用途 |
|---|---|
| `text/plain` | 纯文本 |
| `application/json` | JSON 数据 |
| `application/xml` | XML |
| `text/html` | HTML |
| `application/x-www-form-urlencoded` | 表单（key=value&...） |

**关键点**：文本需要协商**字符编码**（UTF-8 / GBK），客户端和服务端必须一致，否则乱码。

### 2.2 文本的底层过程

```
String --(charset 编码)--> byte[] --(TCP)--> byte[] --(charset 解码)--> String
```

文本传输的全部特殊性就在于两端各多了一次**字符集编解码**。

---

## 三、HTTP 传输二进制的方式

### 3.1 原始二进制流（上传/下载文件）

```http
POST /upload HTTP/1.1
Content-Type: application/octet-stream
Content-Length: 102400

<原始字节>
```

下载时服务端返回：

```http
HTTP/1.1 200 OK
Content-Type: image/png
Content-Disposition: attachment; filename="logo.png"
Content-Length: 20480
```

**特点**：零编解码开销、保真、体积最小，是传输文件、图片的首选。ProtoBuf / gRPC 也基于原始字节流传输，但使用专属 Content-Type（`application/x-protobuf`、`application/grpc`）。

### 3.2 multipart/form-data（表单上传文件）

浏览器表单上传文件的标准方式，用 boundary 分隔多个部分：

```http
POST /upload HTTP/1.1
Content-Type: multipart/form-data; boundary=----WebKitBoundary

------WebKitBoundary
Content-Disposition: form-data; name="desc"

头像文件
------WebKitBoundary
Content-Disposition: form-data; name="file"; filename="a.png"
Content-Type: image/png

<二进制字节>
------WebKitBoundary--
```

boundary 的存在让容器可以**逐 part、逐块解析**，天然支持流式处理。

### 3.3 Base64 编码（把二进制变成文本）

当信道只允许文本时（如 JSON 内嵌图片、证书、某些老协议），将二进制编码为 ASCII 字符：

```json
{"avatar": "iVBORw0KGgoAAAANSUhEUg..."}
```

**代价**：

- 体积膨胀约 **33%**（3 字节 → 4 字符）；
- 额外的编码/解码 CPU 开销；
- 丧失流式处理能力（详见第六节）。

---

## 四、传输增强：Content-Encoding 与 Transfer-Encoding

### 4.1 Content-Encoding（压缩传输）

`Content-Encoding` 在 Content-Type 之上再加一层**编码变换**——通常是压缩。客户端通过 `Accept-Encoding` 声明支持的算法，服务端据此选择：

```http
POST /api/user HTTP/1.1
Content-Type: application/json; charset=UTF-8
Content-Encoding: gzip
Accept-Encoding: gzip, br

<gzip 压缩后的 JSON 字节>
```

| 算法 | 特点 |
|------|------|
| `gzip` | 通用，文本压缩比 70%~90% |
| `br` (Brotli) | 比 gzip 压缩率更高，现代浏览器均支持 |
| `deflate` | 较少使用 |

**关键特性：**

- **文本受益最大**：JSON / XML / HTML 等重复模式多，压缩比极高（一个 1MB 的 JSON 可能压缩到 100KB）；
- **二进制几乎无收益**：图片（PNG / JPEG）、视频、已压缩文件本身已是高熵数据，再压缩体积不减反增（gzip 头开销）；
- **压缩与流式兼容**：gzip 逐块压缩/解压，可以无缝叠加在流式管线上（`GZIPInputStream` 包装 `InputStream`，内存仍为常数级）。

### 4.2 Transfer-Encoding: chunked（分块传输）

正常情况下，发送端必须提前知道 body 总长度并写入 `Content-Length`。但很多场景下数据是**边生成边发送**的（流式导出、SSE、动态渲染），总长度事先未知。`Transfer-Encoding: chunked` 解决了这个问题：将 body 拆成若干**带长度前缀的块**，最后以零长度块标记结束。

```http
HTTP/1.1 200 OK
Transfer-Encoding: chunked
Content-Type: text/plain

C\r\n              ← 第一块：12 字节（十六进制 C = 12）
Hello, world\r\n
7\r\n              ← 第二块：7 字节
chunked!\r\n
0\r\n              ← 零长度块：传输结束
\r\n
```

**关键特性：**

- **与 `Content-Length` 互斥**：两者不能同时出现在同一条消息中；
- **免除预知总长度**：发送端可以"生成一块、发送一块"，不需要在内存中拼好完整 body 再发——这对大文件流式下载、数据库导出等场景至关重要；
- **文本和二进制都适用**：chunked 是传输分帧层的机制，与 body 内容无关；
- **接收端透明**：HTTP 客户端库（JDK HttpClient、OkHttp、浏览器）会自动解分帧，使用者拿到的仍是一个连续的 `InputStream`。

> **与流式的关系**：chunked 解决的是"发送端不预先知道总长度"的问题，让"边生成边发送"成为可能；而 §七 讨论的流式处理解决的是"接收端不一次性加载全部内容"的问题。两者正交——可以只用 chunked 不用流式（接收端仍全量缓冲），也可以只用流式不用 chunked（有 Content-Length 但逐块读取）。两者结合才能实现端到端的流式管线。

---

## 五、三种方式对比总结

| 维度 | 文本传输 | 原始二进制 | Base64 文本化 |
|---|---|---|---|
| Content-Type | json/xml/text | octet-stream、image/* | 同文本 |
| 体积开销 | 无 | 无 | +33% |
| gzip 压缩收益 | **极高**（70%~90%） | 几乎无（已高熵） | 低（Base64 字符多样性低） |
| 可读性 | 好 | 不可读 | 不可读但可嵌入文本协议 |
| 编解码 | 字符集 | 无 | Base64 |
| 流式处理 | 可以流式¹ | 天然支持 | 不可用（标准管线下） |
| 典型场景 | REST API | 文件上传下载、ProtoBuf | JSON 内嵌小图、证书 |

> ¹ **文本本身可以流式传输**（chunked + 流式 JSON 解析器如 Jackson Streaming API），只是 Spring MVC / Boot 的声明式数据绑定（`@RequestBody`）默认全量缓冲，并非文本协议层面的限制。详见 §六。

### Java 视角的最小示例

**客户端发文本**：

```java
HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/user"))
    .header("Content-Type", "application/json; charset=UTF-8")
    .POST(BodyPublishers.ofString("{\"name\":\"张三\"}", StandardCharsets.UTF_8))
    .build();
```

**客户端发二进制**：

```java
HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/upload"))
    .header("Content-Type", "application/octet-stream")
    .POST(BodyPublishers.ofFile(Path.of("a.png")))   // 直接字节流，无编码
    .build();
```

**Spring 服务端接收**：

```java
// 方式一：application/octet-stream 裸流
@PostMapping("/upload")
public void upload(@RequestBody byte[] data) {          // 二进制
    Files.write(Path.of("out.png"), data);
}

// 方式二：multipart/form-data 表单上传（本项目使用的方式）
@PostMapping("/upload")
public void upload(@RequestParam("file") MultipartFile file) {
    Path staging = Path.of("staging", file.getOriginalFilename());
    try (InputStream in = file.getInputStream()) {      // MultipartFile 提供 InputStream
        Files.copy(in, staging);
    }
}

@PostMapping("/user")
public void user(@RequestBody UserDto dto) {            // 文本 JSON，自动按 charset 解码
}
```

> **本项目实际使用 `multipart/form-data`（方式二）**，因为浏览器表单上传的标准格式是 multipart，且天然支持流式逐 part 解析。详见 `FileUploadController`。

---

## 六、为什么 Base64 内嵌 JSON 无法流式处理

### 6.1 先澄清一个前提

严格来说，**Base64 算法本身是可以流式解码的**（每 4 个字符独立解码为 3 字节，逐块处理完全可行）。真正的瓶颈在于 **JSON 这个容器格式**和**主流框架的处理管线**，两者叠加才使流式变成不可能。

### 6.2 原因一：JSON 是"单文档"格式，字段值必须完整才合法

JSON 语法上，一个字符串字段是一个**不可分割的原子 token**：

```json
{"avatar": "iVBORw0KGgo...（500MB 的 Base64 字符）...AAA=="}
```

- 解析器必须读到**闭合引号** `"` 才能确认这个字符串结束；
- 整个 JSON 文档必须完整才是合法文档——无法"解析一半"就拿到可用对象；
- 相比之下，multipart 有 boundary 分隔，裸字节流有 `Content-Length`，都可以**边收边消费**。

### 6.3 原因二：框架的解码管线要求"先完整、后映射"

以 Spring + Jackson 为例，`@RequestBody UserDto dto` 的处理流程是：

```
HTTP body 字节
  → HttpMessageConverter 读完整 body
  → 按 charset 解码成完整 String（或解析成 JsonNode 树）
  → 反序列化成 UserDto（avatar 字段又是一个完整 String）
  → 你拿到对象后才能 Base64.decode(...)
```

每一步都是**全量物化**，没有任何一环把数据当流传递。

### 6.4 原因三：内存被多倍放大

一个 100MB 的文件走 Base64-in-JSON，高峰期内存里同时存在：

| 副本 | 大小 |
|---|---|
| 原始请求字节（Base64 文本） | ~133MB |
| 解码出的 String / JsonNode | ~133MB（JDK 9+ Compact Strings 对纯 ASCII 的 Base64 用 Latin-1 存储，每字符 1 byte） |
| Base64.decode 后的 byte[] | 100MB |
| GC 前的临时数组 | 若干 |

保守估计高峰期 **~366MB**（133 + 133 + 100），相比原始 100MB 文件约 **3.7 倍放大**；若叠加 Jackson 内部解析的中间缓冲、GC 未及时回收的临时对象，实际可达 4~5 倍——大文件直接 OOM，这正是"大文件不能这么传"的硬约束。

### 6.5 原因四：流式解码的"起点"被锁死

就算想流式 Base64 解码，也需要先逐字符拿到字段值。要绕过数据绑定，只能手写底层流式解析：

```java
// 理论上可行，但要手写 Jackson Streaming API，框架帮不了你
JsonParser p = jsonFactory.createParser(inputStream);
while (p.nextToken() != null) {
    if ("avatar".equals(p.currentName())) {
        // readBinaryValue 内部逐 4 字符解码，边读边写
        p.readBinaryValue(Base64Variants.getDefaultVariant(), fileOutputStream);
    }
}
```

这意味着放弃 `@RequestBody` 的声明式便利，手写解析逻辑——既然都要手写流处理，直接用 multipart 或裸流简单得多，工程上没人这么干。

### 6.6 一句话总结

> Base64-in-JSON 的流式障碍 = **JSON 要求文档完整才合法**（容器层面锁死）× **框架要求全量物化后才能映射对象**（管线层面锁死）× **多层副本导致内存 3~5 倍放大**（资源层面不可行）。Base64 本身可流式解码，但被包进 JSON 字符串字段后，这个能力在标准处理链路上完全用不上。

---

## 七、流式处理内存常数级的根本原因

### 7.1 一句话本质

> **流式处理把"文件总量"从内存需求中消去了：同一时刻内存里只需要容纳"正在路上的一小段"，处理完一块就落盘，下一块复用同一块缓冲区。内存需求取决于"管道横截面"而非"管道总长"。**

### 7.2 时间维度上的复用，而非空间维度上的累积

| 模式 | 峰值内存 | 与文件大小 N 的关系 |
|---|---|---|
| 全量缓冲 | `O(N)`：必须先把 N 字节全部装入内存，才具备处理条件 | 正相关，N 多大内存多大 |
| 流式处理 | `O(1)`：任何时刻只有一块缓冲区（如 8KB）在内存中 | 无关 |

关键区别在于**处理的"前置条件"**：

- **全量模式**：处理动作（JSON 解析、Base64 解码、对象映射）要求"数据完整"才能开始，内存必须先累积到 N。
- **流式模式**：处理动作（`read → write`）对**任意一小块数据都成立**，不需要"完整"这个前提：

```java
byte[] buf = new byte[8192];        // 唯一的一块内存，循环外分配一次
int n;
while ((n = in.read(buf)) != -1) {  // 从网络读最多 8KB
    out.write(buf, 0, n);           // 立刻写向磁盘
    // 本轮 8KB 的使命结束，下一轮循环覆盖复用同一块 buf
}
```

N=10MB 和 N=10GB 的唯一区别是**循环次数不同**，每次循环的内存消耗完全相同。这就是"常数级"的精确含义：**内存是每轮迭代复用的固定成本，不是随输入累积的变量成本**。

### 7.3 深层支撑：每一层都不需要持有完整数据

流式不是应用层一个 `while` 循环的功劳，而是**整个 I/O 栈每一层都按"窗口/缓冲"设计**，没有任何一层要求"凑齐整个文件"：

```
发送端
  │  TCP 字节流（无消息边界，天然可分片）
  ▼
┌───────────────────────────────────────────────┐
│ 网卡 / 内核 Socket 接收缓冲区（默认几十KB~几MB，固定大小）│
│   → 满了就通过 TCP 滑动窗口通告"别发了"           │
├───────────────────────────────────────────────┤
│ JVM InputStream.read(byte[8192])               │
│   → 内核态 copy 到用户态的固定 8KB buf            │
├───────────────────────────────────────────────┤
│ FileOutputStream.write(buf)                    │
│   → 写入内核 Page Cache（固定窗口，异步刷盘）       │
├───────────────────────────────────────────────┤
│ 磁盘文件：随写随增长，增长发生在磁盘而非内存         │
└───────────────────────────────────────────────┘
```

逐层解读：

1. **TCP 层**：TCP 是无边界的字节流协议，配合**滑动窗口**机制——接收缓冲区快满时，窗口通告收缩，发送端自动降速（背压）。文件"无限大"也只是传输时间变长，缓冲区不增长。
2. **JVM 应用层**：`read(byte[8192])` 的语义是"**最多**给我 8KB"——它从不承诺、也从不需要一次性拿到全部数据。这是流式 API 与 `readAllBytes()` 的分水岭。
3. **写入层**：写盘经过 Page Cache，脏页由内核异步回刷。文件在**磁盘上**累积变大——累积发生的地方不是内存。

于是整条链路上，**文件的增长全部转化为"磁盘的占用"和"时间的消耗"，没有转化为"内存的占用"**。

### 7.4 对照：流式成立的三个必要条件

| 条件 | Base64-in-JSON 是否满足 |
|---|---|
| ① 数据通道允许"边收边给" | ✓ HTTP/TCP 本身没问题 |
| ② 解析/处理动作对局部数据成立 | ✗ JSON 必须完整文档才合法 |
| ③ 框架管线逐块传递数据 | ✗ Converter 先全量物化再交付 |

② 被 JSON 语法破坏、③ 被框架设计破坏，处理前置条件退化为"先有完整数据"，内存需求被迫回到 `O(N)`（还要乘上 Base64 的 1.33 倍和多层副本）。

### 7.5 补充：缓冲区 ≠ 内存的全部

"常数级"是近似说法，实际内存还包括：

- 内核 Socket 缓冲区（固定上限，可通过 `SO_RCVBUF` 调整）；
- Page Cache（内核全局调度，内存紧张时自动回收，不算应用负担）；
- 缓冲流（`BufferedInputStream`）只是把 8KB 换成另一个固定值。

这些全部是**固定大小的窗口**，与文件大小 N 无关——结论不变：**流式的峰值内存由"管道各层缓冲区之和"这个常数决定，文件大小只影响传输时长。**

---

## 八、工程实践准则

| 场景 | 推荐方式 | 理由 |
|---|---|---|
| REST API 结构化数据 | `application/json` | 可读、生态成熟 |
| JSON API 体积优化 | `Content-Encoding: gzip` 或 `br` | 文本压缩比 70%~90%，大幅减少传输耗时 |
| 小图标 / 证书 / 几 KB 二进制 | Base64 内嵌 JSON | 图方便，体积放大可忽略 |
| 表单上传文件 | `multipart/form-data` | 浏览器标准，可流式 |
| 大文件上传/下载 | `application/octet-stream` 裸流 | 零开销，内存常数级 |
| 大文件断点续传 / 并发分片 | `Range: bytes=...` + `206 Partial Content` | 支持中断恢复、多线程下载 |
| 服务间高性能传输 | ProtoBuf / gRPC 字节流 | 紧凑、无文本编解码 |
| 超大 body 流式收发 | `Transfer-Encoding: chunked` | 文本二进制都适用，免除预知 Content-Length |

---

## 九、常见误区与踩坑清单

1. **用 `String` 接收二进制**：按字符集解码再编码会损坏字节（如 UTF-8 替换非法序列），图片直接打不开。二进制一律用 `byte[]` / `InputStream`。
2. **文本漏写 charset**：`application/json` 按 RFC 8259 默认 UTF-8；但 `text/*` 类型在 Content-Type 未声明 charset 时，按 RFC 7231 默认 ISO-8859-1，中文必乱码。养成习惯显式声明 `charset=UTF-8`。
3. **大文件用 Base64 内嵌 JSON**：体积放大 33%、无法流式、内存 3~5 倍放大。超过 MB 级一律改 multipart 或裸字节流。
4. **误以为流式是应用层的技巧**：流式依赖 TCP 滑动窗口、Socket 缓冲、Page Cache 的逐层窗口化设计，是整条 I/O 栈的协作结果。
5. **混淆"常数级内存"与"零内存"**：流式仍有固定缓冲区开销（Socket 缓冲、8KB buf、Page Cache），只是与文件大小无关。
6. **对二进制开启 gzip 压缩**：图片 / 视频 / 已压缩文件本身已是高熵数据，再压缩体积不减反增（gzip 头开销），白费 CPU。只对文本类（JSON / XML / HTML）开启。
7. **混淆 chunked 与流式**：`Transfer-Encoding: chunked` 解决的是"发送端不预先知道总长度"，让边生成边发送成为可能；流式处理解决的是"接收端不一次性加载全部内容"。两者正交——chunked 不等于接收端流式，有 Content-Length 也可以逐块读取。
