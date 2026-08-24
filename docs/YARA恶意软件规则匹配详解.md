# YARA 恶意软件规则匹配详解

> 定位：本项目扫描管道的第三站 —— "恶意软件的模式匹配瑞士军刀"，用**自定义规则**识别 ClamAV 病毒库覆盖不到的定向威胁与业务特征。
> 对应代码：`YaraScanner`（CLI 子进程封装）、`src/main/resources/rules/malware.yar`（规则文件）

## 目录

- [一、核心结论](#一核心结论)
- [二、YARA 是什么，与 ClamAV 如何分工](#二yara-是什么与-clamav-如何分工)
- [三、规则语法详解](#三规则语法详解)
- [四、本项目三条规则逐条解读](#四本项目三条规则逐条解读)
- [五、yara CLI 与进程集成](#五yara-cli-与进程集成)
- [六、规则工程：误报控制与规则管理](#六规则工程误报控制与规则管理)
- [七、动手实验](#七动手实验)
- [八、常见坑与最佳实践](#八常见坑与最佳实践)
- [九、延伸阅读](#九延伸阅读)

---

## 一、核心结论

1. **YARA 是描述"恶意文件长什么样"的规则语言 + 匹配引擎**（VirusTotal 出品）：一条规则 = 若干字符串/字节特征 + 一个布尔条件，命中即报告。业界用它给恶意家族"画像"（APT 组织样本分类的通用语言）。
2. 与 ClamAV 互补：ClamAV 库广但更新滞后、不可定制；YARA **规则完全由你定义**，能表达业务语义（如"Office 文件里出现自动执行宏 + 命令执行 API"），且规则可独立热更。
3. 本项目通过 **ProcessBuilder 调 yara CLI 子进程**扫描（每次一个进程），规则文件从 classpath 解出到临时路径；**退出码只表示"执行是否成功"，是否命中要看输出** —— 这是集成 yara CLI 最容易搞错的一点。
4. 写规则的核心矛盾是**召回 vs 误报**：单字符串条件误报率极高，实践中用"魔数门控 + 多信号组合 + 计数/位置约束"收敛。

---

## 二、YARA 是什么，与 ClamAV 如何分工

YARA（Yet Another Ridiculous Acronym）2008 年由 Victor Alvarez 创建，现由 VirusTotal/Google 维护。核心是 **libyara**（C 库）+ `yara` 命令行 + 各语言绑定，安全研究、威胁狩猎（在磁盘/内存里找家族样本）、EDR/沙箱几乎都以它为规则底座。知名公共规则库如 YARA Rules（yara-rules GitHub 组织）维护着按家族/勒索软件分类的成套规则。

| 维度 | ClamAV | YARA（本项目独立层） |
|---|---|---|
| 规则来源 | 官方病毒库（百万级，每日更新） | **自己编写**，完全可控 |
| 擅长 | 已知恶意样本的广覆盖 | 定向威胁、业务语义规则、ClamAV 尚未收录的新样本特征 |
| 表达能力 | 签名格式（哈希/字节模式/逻辑组合） | 完整语言：十六进制通配、正则、计数、偏移、PE/ELF 结构化模块 |
| 更新方式 | freshclam 自动 | 换规则文件即生效（本项目挂外部目录可热更） |
| 故障隔离 | clamd 容器 | 独立子进程，超时强杀 |

两层都保留的原因：**杀毒世界没有单一权威来源**。ClamAV 对上周才出现的定向钓鱼宏文档可能还没签名；而 YARA 规则可以在事发当天就由你自己写出来上线。

---

## 三、规则语法详解

一条 YARA 规则的骨架（三段全可选，但 strings 与 condition 通常成对出现）：

```yara
import "pe"                                  # 可选：导入结构化模块

rule FamilyX_Backdoor : backdoor familyx {   # 规则名 + 可选标签
    meta:                                     # 元数据：不参与匹配，供报告用
        author      = "davis"
        date        = "2026-08-24"
        description = "FamilyX 后门回连特征"
        severity    = "high"
    strings:                                  # 特征定义
        $cfg_url   = "http://update.familx" nocase ascii wide
        $hex_chain = { 6A ?? 68 [2-4] E8 [4] 68 ?? ?? ?? ?? }
        $re_cmd    = /cmd(\.exe| ?\/c)/ nocase
    condition:                                # 命中条件（布尔表达式）
        uint16(0) == 0x5A4D and               # 文件头两字节小端 = MZ（PE 文件门控）
        pe.is_pe and
        filesize < 500KB and
        $cfg_url and 2 of ($hex_chain, $re_cmd) and
        #re_cmd < 5 and                       # 命中次数约束
        pe.imports("ws2_32.dll", "WSAStartup")
}
```

### 3.1 strings：三种特征

| 种类 | 语法 | 说明 |
|---|---|---|
| 文本串 | `$a = "cmd.exe"` | 支持转义 `\x41` `\n` |
| 十六进制串 | `$b = { 6A ?? 68 [2-4] E8 }` | `??` 单字节通配；`[n]` 固定跳 n 字节；`[n-m]` 跳 n~m 字节；`(A\|B)` 分支或 |
| 正则 | `$c = /pow[a-z]{3}ell/` | 语法接近 PCRE 子集，开销高于前两者 |

文本串修饰符（可组合）：

| 修饰符 | 含义 |
|---|---|
| `nocase` | 忽略大小写 |
| `wide` | 同时匹配 UTF-16LE 展开（Windows API 常见形态，字节间插 `\x00`） |
| `ascii` | 匹配单字节（默认；与 `wide` 同用时两者都试） |
| `fullword` | 全词匹配，`cmd` 不匹配 `cmdexefoo` |
| `xor` | 匹配单字节异或混淆后的形态（4.x） |

### 3.2 condition：布尔表达式

| 表达式 | 含义 |
|---|---|
| `$a` | 串 a 是否出现 |
| `#a` | 串 a 出现次数（`#a > 3`） |
| `@a` | 串 a 首次出现的偏移（`@a == 0`） |
| `!a` | 串 a 匹配段长度 |
| `$a in (0 .. 100)` | 限定出现位置范围 |
| `2 of ($a, $b, $c)` / `all of them` / `any of ($*)` | 组合计数 |
| `uint8/16/32(n)` | 从偏移 n 按小端读无符号整数 —— **魔数门控标准写法** |
| `filesize` | 文件大小（字节） |
| `for any i in (1..#a) : ( @a[i] < 100 )` | 对每次出现位置做量化判断 |

`uint16(0) == 0xCFD0` 为什么等于"OLE2 文件"：文件头两字节是 `D0 CF`，小端读 16 位即低位字节 D0、高位字节 CF → 数值 `0xCFD0`。同理 ZIP 头 `50 4B`（`PK`）→ `uint16(0) == 0x4B50`。

### 3.3 模块：结构化特征（了解即可）

`import "pe"` 后可用 `pe.machine`、`pe.imports(dll, func)`、`pe.sections`、`pe.rich_signature` 等；`math.entropy(0, filesize) > 7.2` 判高熵（加壳/加密段的经典指标）；另有 `elf`、`hash`、`dotnet` 等模块。本项目规则未用模块（保持 CLI 兼容面最小），需要更强的结构判断时是扩展方向。

---

## 四、本项目三条规则逐条解读

`src/main/resources/rules/malware.yar` 是"基线规则集"，三条规则分别对应三类威胁：

### 4.1 Ransomware_Note_Indicators —— 勒索信文本特征

```yara
rule Ransomware_Note_Indicators {
    strings:
        $msg1 = "your files have been encrypted" nocase
        $msg2 = "bitcoin" nocase
        $msg3 = "decrypt your files" nocase
        $msg4 = "pay the ransom" nocase
    condition:
        2 of them
}
```

要点：`2 of them` —— 单独出现 "bitcoin" 不能说明什么（谈加密货币的正常文档太多），**两条勒索话术同时命中**才可疑。这是最基本的误报控制手法。

### 4.2 Suspicious_Office_Macro —— 恶意宏文档（核心规则）

```yara
rule Suspicious_Office_Macro {
    strings:
        $auto_open = "AutoOpen" nocase        # ← 自动执行锚点
        $auto_exec = "AutoExec" nocase
        $doc_open  = "Document_Open" nocase
        $shell     = "Shell(" nocase          # ← 危险 API 锚点
        $cmd       = "cmd.exe" nocase
        $powershell = "powershell" nocase
        $wscript   = "WScript.Shell" nocase
    condition:
        (uint16(0) == 0xCFD0 or uint16(0) == 0x4B50) and
        1 of ($auto_open, $auto_exec, $doc_open) and
        1 of ($shell, $cmd, $powershell, $wscript)
}
```

条件是三段 AND，语义即"**Office 容器 + 自动执行宏 + 命令执行**"：

1. **魔数门控**：`0xCFD0`（OLE2：.doc/.xls/.ppt）或 `0x4B50`（ZIP：.docx/.xlsx），把匹配范围限定在 Office 文档 —— 这些字符串出现在文本文件里没有恶意含义；
2. **自动执行锚点**：`AutoOpen` / `AutoExec` / `Document_Open` 是宏的"开机自启"入口（详见[文档威胁检测详解](文档威胁检测详解.md)）；
3. **危险 API 锚点**：`Shell(` / `cmd.exe` / `powershell` / `WScript.Shell` 是宏落地下载器/执行命令的必经 API。

注意：VBA 宏源码在 `vbaProject.bin` 里是**压缩存储**的（MS-OVBA），字节级扫描可能看不到明文 —— 所以这条规则与 `DocumentThreatScanner` 的 POI 真实解压提取是互补关系：文档检测层解压看内容，YARA 兜底扫解压层之外的特征。

### 4.3 PDF_Embedded_JS_AutoExec —— 自动执行 JS 的 PDF

```yara
rule PDF_Embedded_JS_AutoExec {
    strings:
        $pdf_header = "%PDF"
        $js = "/JavaScript"
        $open_action = "/OpenAction"
    condition:
        $pdf_header at 0 and $js and $open_action
}
```

`$pdf_header at 0`：魔数必须在文件头（把匹配钉在真实 PDF 上）；`/JavaScript` 与 `/OpenAction` 同时出现 = 打开文档即执行脚本。与 `DocumentThreatScanner.scanPdf` 检测同样的特征 —— 双保险：一个走 Java 分块扫描，一个走字节级签名（两者对压缩对象流同样存在盲区，详见文档威胁检测详解第六节）。

---

## 五、yara CLI 与进程集成

### 5.1 命令行用法

```bash
yara [选项] <规则文件> <目标文件或目录>

# 常用选项
yara -s  rules/malware.yar suspicious.doc     # -s：打印命中串与偏移
yara -r  rules/ /var/data/                    # 递归扫目录
yara -m  rules/malware.yar file               # 打印 meta 字段
yara -n  rules/malware.yar file               # 只打印未命中规则
yara --fail-on-warnings ...                   # 警告视为失败（本项目启用）
yarac rules/malware.yar rules.yarc            # 预编译规则（加速、防泄露）
```

### 5.2 输出与退出码（集成的关键）

`yara -s` 的输出格式：

```
Suspicious_Office_Macro /tmp/xxx.doc        ← 命中行：规则名 + 空格 + 目标路径
0x1a3c:$auto_open AutoOpen                  ← 命中串明细：偏移:串名 内容（带缩进）
0x23f0:$shell Shell(
```

退出码语义（**容易踩坑**）：

| 退出码 | 含义 |
|---|---|
| 0 | **执行成功 —— 与是否命中无关** |
| 非 0 | 执行错误：规则编译失败、文件不可读、超时等 |

因此 `YaraScanner` 的判定逻辑是：

- 退出码非 0 → `ScanFailedException`（基础设施故障，走 fail-strategy）；
- 退出码 0 且**输出为空** → 干净；
- 退出码 0 且有输出 → 解析非缩进行的首列（规则名）作为命中签名。

### 5.3 子进程集成的工程细节

`YaraScanner` 用 `ProcessBuilder` 起 yara 进程，几个细节值得留意（都是经典坑的规避）：

1. **先读完输出再 `waitFor`**：子进程 stdout 缓冲区（通常 64KB）写满后进程会阻塞；如果先 `waitFor(timeout)` 再读输出，命中输出多时会互相等待形成死锁。项目实现先 `redirectErrorStream(true)` 合并输出、循环 `readLine()` 读到 EOF，再 `waitFor`。
2. **超时强杀**：`waitFor(60s)` 超时后 `destroyForcibly()`，`finally` 里兜底再杀一次，防止僵尸进程。
3. **classpath 规则解出临时文件**：yara CLI 只认文件系统路径，`classpath:rules/malware.yar` 在构造器里复制到临时文件并 `deleteOnExit`。
4. **虚拟线程友好**：`waitFor` 与流读取都是阻塞 IO，虚拟线程上会正常卸载载体线程，不占平台线程。
5. **`enabled=false` 逃生舱**：规则文件缺失的环境（如无 yara CLI 的 CI）也能启动应用。

---

## 六、规则工程：误报控制与规则管理

### 6.1 误报控制套路

| 手法 | 示例 |
|---|---|
| 魔数/结构门控 | `uint16(0) == 0xCFD0 and …` —— 先确认"这是 Office 文件"再谈内容 |
| 多信号 AND | 4.2 规则的三段式；单串条件几乎必然误报 |
| 计数约束 | `#suspicious >= 3`：出现一次可能是巧合，多次是特征 |
| 位置约束 | `$x in (0..512)`：配置串只在头部出现 |
| `fullword` | 防子串误命中 |
| 文件大小约束 | `filesize < 500KB`：恶意投放样本通常是小文件 |

规则写完必须在**干净样本集**上回归（本项目走 `mvn test` 上传白名单文件即可快速验证），新规则先观察再上生产。

### 6.2 规则生命周期

本项目策略（方案文档 Task 14）：classpath 内置规则只是**基线**；生产环境应挂载外部规则目录（`zxf.virus-scan.yara.rules-path: /etc/yara/rules/`），规则文件独立版本化、评审后替换生效 —— 规则即策略，和代码一样需要变更管理。

---

## 七、动手实验

### 7.1 安装与单文件体验

```bash
sudo apt install yara          # 或 brew install yara
yara --version

# 用项目规则扫描任意文件（无命中则无输出、退出码 0）
yara -s src/main/resources/rules/malware.yar pom.xml
```

### 7.2 写一条 EICAR 规则体验命中

```bash
cat > /tmp/eicar.yar <<'EOF'
rule EICAR_Test_File {
    meta:
        description = "EICAR 标准测试串"
    strings:
        $e = "$EICAR-STANDARD-ANTIVIRUS-TEST-FILE"
    condition:
        $e
}
EOF

echo 'X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*' > eicar.txt
yara -s /tmp/eicar.yar eicar.txt
# 输出：
# EICAR_Test_File eicar.txt
# 0x25:$e $EICAR-STANDARD-ANTIVIRUS-TEST-FILE
```

对照 5.2 节，理解"命中看输出、成败看退出码"。

### 7.3 构造命中项目规则的样本

```bash
# 拼一个 OLE2 头 + 宏锚点字符串的假文档（仅测试用）
python3 -c "
data = bytes.fromhex('D0CF11E0A1B11AE1') + b'\x00'*100
data += b'AutoOpen Shell(\"cmd.exe /c calc.exe\")'
open('fake-macro.doc','wb').write(data)
"
yara -s src/main/resources/rules/malware.yar fake-macro.doc   # 应命中 Suspicious_Office_Macro
```

### 7.4 走完整链路

```bash
mvn spring-boot:run
curl -F "file=@fake-macro.doc" http://localhost:8080/api/files/upload
# 期望 422，threat 含 "YARA: Suspicious_Office_Macro"
```

---

## 八、常见坑与最佳实践

| 坑 | 说明 / 对策 |
|---|---|
| 用退出码判断命中 | 退出码 0 表示执行成功（无论命中与否）；命中与否看 stdout 是否为空 —— `YaraScanner` 已按此实现 |
| 规则编译错误在运行期才爆 | 启动时先 `yara rules.yar /dev/null` 验编译；`--fail-on-warnings` 让规则问题尽早暴露 |
| classpath 规则直接传给 yara | CLI 只认文件系统路径，需解出到临时文件（项目 `resolveRulesPath` 已处理） |
| 正则/宽串性能 | 正则、`wide`、大跳转 hex 串显著拖慢扫描；规则多目标大时考虑 `yarac` 预编译或 `--fast` |
| 每次扫描起一个进程 | 进程 fork + 规则重编译有固定开销（毫秒级）；本方案吞吐下可接受，高吞吐可换 libyara 常驻服务 |
| 单字符串条件 | 误报杀手；任何生产规则都应至少两信号 AND + 门控 |
| 输出解析脆弱 | 解析 CLI 文本输出依赖格式稳定性（-s 明细行缩进/`0x` 前缀）；本项目过滤规则做了防御性过滤，升级 yara 版本需回归 |
| 规则散落无版本管理 | 规则即策略：外部目录 + git 版本化 + 评审，替换即生效 |

---

## 九、延伸阅读

- 官方文档（规则语法权威来源）：<https://yara.readthedocs.io/>（重点 "Writing YARA rules" 与 "Modules"）
- YARA Rules 社区规则库：<https://github.com/Yara-Rules/rules>
- VirusTotal 博客的 YARA 使用系列文章
- 本仓库相关文档：[病毒扫描技术栈总览](病毒扫描技术栈总览.md) · [文档威胁检测详解](文档威胁检测详解.md)（宏攻击链与 vbaProject.bin 的结构背景）· 实现方案见 `virus-scan-solution-v3.md` Task 7/14
