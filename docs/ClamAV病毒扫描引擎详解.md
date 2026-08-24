# ClamAV 病毒扫描引擎详解

> 定位：本项目扫描管道的第二站 —— 开源杀毒引擎，凭每日更新的百万级病毒签名库识别**已知恶意文件**。
> 对应代码：`ClamAvScanner`（capybara 客户端 + Resilience4j 熔断）、`ClamAvHealthIndicator`（健康检查）、`docker/docker-compose.yml`

## 目录

- [一、核心结论](#一核心结论)
- [二、ClamAV 是什么](#二clamav-是什么)
- [三、架构与组件](#三架构与组件)
- [四、病毒签名库与检测原理](#四病毒签名库与检测原理)
- [五、clamd 网络协议：PING / SCAN / INSTREAM](#五clamd-网络协议ping--scan--instream)
- [六、Java 客户端：capybara clamav-client 与熔断器](#六java-客户端capybara-clamav-client-与熔断器)
- [七、Docker 部署与运维](#七docker-部署与运维)
- [八、EICAR：安全地测试杀毒管道](#八eicar安全地测试杀毒管道)
- [九、动手实验](#九动手实验)
- [十、常见坑与最佳实践](#十常见坑与最佳实践)
- [十一、延伸阅读](#十一延伸阅读)

---

## 一、核心结论

1. **ClamAV 是老牌开源杀毒引擎**（1996 年发起，现由 Cisco Talos 维护签名库），定位是网关/服务器侧的文件扫描，不是桌面实时防护。
2. 本方案以 **clamd 守护进程**形态运行在独立 Docker 容器中，Java 应用通过 TCP 3310 用 **INSTREAM 协议**把文件流式推过去扫描 —— 应用与引擎进程隔离，引擎崩溃不影响业务进程。
3. ClamAV 本质是**签名引擎**：对已知样本识别率高、库每日更新；但对新型/定向样本（APT）有滞后性 —— 这正是管道还要叠加 YARA 与文档威胁检测的原因。
4. 客户端库 `xyz.capybara:clamav-client` 已停更且**无 socket 超时**，引擎挂起会拖死扫描线程 → 本项目用 **Resilience4j 熔断器**快速失败，防止故障级联耗尽并发许可。

---

## 二、ClamAV 是什么

| | |
|---|---|
| 诞生 | 1996 年，Tomasz Kojm 发起的开源 Unix 杀毒项目 |
| 现状 | 2007 年被 Sourcefire 收购，2013 年随 Sourcefire 并入 Cisco；签名库由 Cisco Talos 威胁情报团队维护 |
| 协议 | GPLv2 开源 |
| 定位 | **邮件网关 / 文件服务器 / 上传通道**的批量扫描；低误报优先 |
| 检出能力 | 百万级签名覆盖常见病毒、蠕虫、木马、钓鱼站点特征；含字节码"行为类"签名 |
| 弱点 | 以签名匹配为主，启发式/ML 能力弱于商业引擎；对加壳变形、0day 有滞后 |

一句话定位：**ClamAV 回答"这个文件是否匹配已知恶意样本特征"，不回答"这个文件行为是否可疑"**。后者靠 YARA 定向规则和文档语义检测补位。

---

## 三、架构与组件

```
┌───────────────────────────── clamav 容器 ─────────────────────────────┐
│                                                                        │
│   freshclam ──定时──▶ https://database.clamav.net                      │
│      │                  （下载 main/daily/bytecode 签名库）             │
│      ▼                                                                 │
│   /var/lib/clamav/          clamd（守护进程，加载全量签名库到内存）      │
│   main.cvd                     │ TCP :3310 / Unix socket               │
│   daily.cvd                    │ 文本行协议：PING/VERSION/SCAN/…        │
│   bytecode.cvd                 ▼                                       │
│                            INSTREAM：客户端推流，clamd 内存扫描          │
└────────────────────────────────────────────────────────────────────────┘
                                     ▲
                                     │ TCP
                        ┌────────────┴───────────┐
                        │ Spring Boot 应用        │
                        │ ClamAvScanner           │
                        └────────────────────────┘
```

| 组件 | 作用 | 本方案是否使用 |
|---|---|---|
| `clamd` | 常驻守护进程，签名库常驻内存，监听 3310 | ✅ 核心依赖 |
| `freshclam` | 签名库自动更新（每天多次检查官方源） | ✅ 容器内置自动运行 |
| `clamscan` | 一次性 CLI 扫描（每次冷加载库，秒级起步） | ❌ 仅本地调试用 |
| `clamdscan` | 走 clamd 的命令行客户端 | ❌ 调试用 |
| `sigtool` | 查看/制作签名 | ❌ |
| `clamconf` | 查看配置与版本 | ❌ 调试用 |

---

## 四、病毒签名库与检测原理

### 4.1 三大签名库

| 库文件 | 内容 | 更新频率 |
|---|---|---|
| `main.cvd` | 基础稳定签名 | 随版本发布 |
| `daily.cvd` | 新增签名（含新病毒、钓鱼特征） | 每天多次 |
| `bytecode.cvd` | 字节码签名 | 随 daily |

`bytecode` 值得一提：ClamAV 内置一个小型**字节码虚拟机**，复杂签名可以写成 ClamAV 字节码程序，在引擎内模拟执行检测逻辑（可表达"解压后再匹配""多段组合判断"等），这是纯静态签名做不到的。

### 4.2 签名类型

| 类型 | 扩展名 | 匹配方式 |
|---|---|---|
| 哈希签名 | `.hdb` / `.hsb` / `.mdb` | 整文件或 PE section 的 MD5/SHA1/SHA256 |
| 扩展签名 | `.ndb` | `病毒名:目标类型:偏移:十六进制模式`（支持 `??` 通配与 `*` 跳变） |
| 逻辑签名 | `.ldb` | 多个子签名的布尔组合 |
| 钓鱼签名 | `.pdb` / `.wdb` | URL 模式匹配 |
| **YARA 规则** | `.yara` | **ClamAV 0.99+ 引擎直接支持 YARA 规则文件** |

最后一行冷知识：clamav 引擎本身也吃 YARA 规则（放进数据库目录即可）。本方案仍保留独立 YARA 层，因为规则可以独立热更（不依赖病毒库目录与 RELOAD）、独立超时控制、以子进程隔离故障 —— 两者是互补而非重复。

---

## 五、clamd 网络协议：PING / SCAN / INSTREAM

clamd 用**文本行命令**协议（连接后发命令行、读响应）。命令可用 `\n` 结尾发送，也可用 `z` 前缀 + `\0` 结尾（便于程序区分命令边界）。

### 5.1 常用命令

```
→ PING            ← PONG                       （探活，本项目健康检查用它）
→ VERSION         ← ClamAV 1.4.5/...
→ SHUTDOWN        （关闭 clamd）
→ SCAN /path/file ← /path/file: OK
                   /path/file: Win.Test.EICAR_HDB-1 FOUND
→ CONTSCAN /dir   （扫描目录，继续后续文件）
→ INSTREAM        （流式上传扫描，见下）
```

### 5.2 INSTREAM 详解（本项目使用的命令）

`SCAN` 要求 clamd 自己去读本地路径 —— 适合同机部署；应用与引擎分容器/分机时用 **INSTREAM**：客户端把文件内容当字节流推过去，clamd 在内存中扫描，不落盘。

协议格式：

```
→ "zINSTREAM\0"                        # 开始会话
→ [4 字节大端长度 N][N 字节数据块]      # 重复任意多次，分块大小自定
→ [4 字节 0]                           # 零长度块 = 流结束
← "stream: OK\0"                       # 干净
← "stream: Win.Test.EICAR_HDB-1 FOUND\0"   # 命中：签名名
← "INSTREAM size limit exceeded. (Requested: x, Max: y)"  # 超过限制
```

关键配置项（`clamd.conf`）：

| 配置 | 含义 | 与本项目关系 |
|---|---|---|
| `StreamMaxLength` | INSTREAM 接受的最大字节数 | 默认 100MB，与 `maxFileSize` 对齐；超过直接报错而非静默放行 |
| `MaxChunkSize` | 单块上限 | 客户端分块须 ≤ 此值 |
| `MaxThreads` | clamd 并发扫描线程数 | 默认 10 量级 —— 应用侧信号量（`maxConcurrentScans=16`）限流保护引擎 |
| `MaxFileSize` / `MaxScanSize` | 单文件扫描大小上限 | 超限部分可能被跳过（仍回 OK），配置必须覆盖业务最大文件 |

---

## 六、Java 客户端：capybara clamav-client 与熔断器

### 6.1 库的用法与短板

`xyz.capybara:clamav-client`（2.1.2，2022 年后停更）是纯 Java 的 INSTREAM 实现，API 极简：

```java
ClamavClient client = new ClamavClient("localhost", 3310, Platform.JVM_PLATFORM);

client.ping();                       // PING/PONG，健康检查用

ScanResult result = client.scan(inputStream);   // INSTREAM 推流
if (result instanceof ScanResult.VirusFound vf) {
    // Map<病毒签名名, List<命中的流/文件名>>
    vf.getFoundViruses();
}
```

短板：**不支持配置 socket 超时**。ClamAV 进程假死（TCP 连接在、但不回数据）时，`scan()` 会无限期阻塞 —— 扫描线程被钉死，信号量许可被耗尽，最终整个上传服务不可用。

### 6.2 对策：Resilience4j 熔断器

熔断器是微服务韧性经典模式，三个状态：

```
            失败率超阈值
  CLOSED ───────────────▶ OPEN ──等待 30s──▶ HALF_OPEN
     ▲   （正常放行调用）  （直接快速失败，  （放行少量试探调用）
     │                      不再触碰引擎）        │
     └─────────────────────────────────────────┘
                试探调用全部成功 → 恢复 CLOSED
```

本项目 `ClamAvScanner` 的参数逐项解读：

| 参数 | 值 | 含义 |
|---|---|---|
| `slidingWindowSize` | 10 | 统计最近 10 次调用 |
| `minimumNumberOfCalls` | 5 | 至少 5 次调用后才开始计算失败率（防小样本误判） |
| `failureRateThreshold` | 50% | 失败率过半即熔断 |
| `waitDurationInOpenState` | 30s | OPEN 状态持续 30s 后进入半开试探 |
| `permittedNumberOfCallsInHalfOpenState` | 3 | 半开状态放行 3 次试探调用 |

```java
// 调用侧：circuitBreaker.executeSupplier(() -> doScan(file))
// 熔断打开时抛 CallNotPermittedException，包装为 ScanFailedException
// → 由 VirusScanService 按 fail-strategy 决定拒绝（CLOSED，默认）或降级放行（OPEN）
```

选择熔断而非"给客户端加超时"的原因：库没有暴露超时配置点，自己实现 INSTREAM 协议才能收回超时能力（方案文档已注明这是可替换路径）。熔断的优势是**故障期完全不触碰引擎**，比单纯超时更能保护下游。

---

## 七、Docker 部署与运维

`docker/docker-compose.yml` 要点解读：

```yaml
services:
  clamav:
    image: clamav/clamav:1.4.5
    environment:
      CLAMD_STARTUP_TIMEOUT: "600"     # 首次启动下载病毒库可达数分钟，放宽就绪超时
    ports: ["3310:3310"]
    volumes:
      - clamav-db:/var/lib/clamav      # 签名库持久化：容器重建不重新下载
    healthcheck:
      test: ["CMD", "/health.sh"]      # 官方镜像自带脚本，真实 PING clamd
      interval: 30s
      timeout: 10s
      retries: 10
      start_period: 120s               # 启动宽限期，期间失败不计入 retries
```

运维要点：

- **内存**：clamd 把全量签名库加载进内存，常驻几百 MB 量级；K8s/容器内存限制要给足，否则 OOM 后反复重启。
- **冷启动**：新环境首次拉库慢（网络环境差时更久），依赖 `CLAMD_STARTUP_TIMEOUT` + `start_period`；应用侧有熔断 + 健康检查兜底，clamd 未就绪时上传会快速失败而不是挂死。
- **网络拓扑**：INSTREAM 要把文件字节完整推给 clamd，扫描吞吐受网络带宽影响 —— clamd 与应用同宿主机/同集群部署。
- **应用侧健康检查**：`ClamAvHealthIndicator` 定期 PING，结果汇总到 `/actuator/health`，可接告警。

---

## 八、EICAR：安全地测试杀毒管道

**EICAR 测试文件**是行业约定的人造"病毒"：一段 68 字节的标准字符串，所有合规杀毒引擎都必须报毒，而它本身**不是可执行代码、完全无害**，专门用于验证杀毒链路通不通。

```
X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*
```

注意两点：

1. 字符串必须**逐字节精确**（用单引号 echo 或直接复制到文件，避免 shell 转义破坏内容）；末尾可追加任意数量的空白字符/换行，检测依然有效。
2. 不同版本的签名库对它的**报告名不同**：老版本报 `Eicar-Test-Signature`，新版本常报 `Win.Test.EICAR_HDB-1` —— 自动化断言建议用"包含 EICAR（忽略大小写）"的宽匹配，不要绑死具体签名名。

本项目 `EicarScanIT`（Testcontainers + failsafe，`mvn verify` 阶段执行）即用它做端到端验证：上传 EICAR 断言 422 + 命中报告名；上传干净文件断言 200。

---

## 九、动手实验

### 9.1 手工体验 clamd 协议（不写代码）

```bash
docker compose -f docker/docker-compose.yml up -d clamav
docker logs -f clamav          # 等 "clamd started" / healthcheck 变 healthy

# PING / VERSION
echo -e 'PING' | nc localhost 3310
echo -e 'VERSION' | nc localhost 3310
```

### 9.2 用 Python 直接实现 INSTREAM（理解协议本质）

```python
# clam_instream.py —— 与 ClamAvScanner 干的同一件事
import socket

data = open("eicar.txt", "rb").read()
s = socket.create_connection(("localhost", 3310))
s.sendall(b"zINSTREAM\0")
for i in range(0, len(data), 1024):
    chunk = data[i:i+1024]
    s.sendall(len(chunk).to_bytes(4, "big") + chunk)
s.sendall((0).to_bytes(4, "big"))          # 零长度块结束
print(s.recv(1024).decode())               # stream: Win.Test.EICAR_HDB-1 FOUND
```

### 9.3 走完整链路

```bash
# 准备 EICAR（注意必须单引号，防止 bash 处理 \P \Z）
echo 'X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*' > eicar.txt

mvn spring-boot:run
curl -F "file=@eicar.txt" http://localhost:8080/api/files/upload     # 期望 422 VIRUS_DETECTED
curl http://localhost:8080/actuator/health                            # 看 clamav 健康项
ls data/upload-quarantine/                                            # 感染文件已进隔离区
```

### 9.4 观察熔断（可选）

停掉 clamav 容器后连续上传多个文件：前几次报"ClamAV 扫描失败"（真实连接失败），失败率过半后变为"ClamAV 熔断中"（快速失败，`docker logs` 里 clamd 无新请求）—— 即熔断器打开。重启容器等 30s 后自动恢复。

---

## 十、常见坑与最佳实践

| 坑 | 说明 / 对策 |
|---|---|
| 大文件 INSTREAM 慢 | 全量字节要推过网络；限制上传大小（本项目 100MB）并让 clamd 就近部署 |
| clamd 默认线程数有限 | 并发上传会排队甚至拖垮引擎；应用侧限流（本项目 Semaphore=16）不可省 |
| 库文件大小限制不显式报错 | 文件超过 `MaxFileSize`/`MaxScanSize` 时 clamd 可能跳过部分内容仍回 OK —— 配置必须 ≥ 业务最大文件，否则存在"以为扫了其实没扫"的假安全 |
| 首次启动"卡死" | 不是卡死，是在下载病毒库；`start_period` / `CLAMD_STARTUP_TIMEOUT` 放宽 + 应用侧健康检查 |
| 误报处理 | 白名单签名很麻烦；先靠分层（Tika 白名单已挡掉大量非业务格式），个别误报联系 Talos 反馈或用 ClamAV 排除配置 |
| 只报签名名 | ClamAV 不给威胁分类/处置建议，业务语义（隔离 or 拒绝）由应用层决定 |
| 客户端无超时 | capybara 库已知短板；熔断兜底是本方案选择，吞吐上来后可自实现 INSTREAM 协议（协议不过十几行，见 9.2） |
| clamscan 每次扫描都慢 | 库要冷加载；常驻用 clamd，不要在生产起 clamscan 进程 |

---

## 十一、延伸阅读

- 官方文档与 clamd.conf 配置说明：<https://docs.clamav.net/>
- 官方 Docker 镜像说明：<https://hub.docker.com/r/clamav/clamav>
- clamd 协议（man clamd）：PING/INSTREAM 全量命令清单
- EICAR 官网：<https://www.eicar.org/>（抗恶意软件测试组织）
- 熔断器模式（Martin Fowler）：<https://martinfowler.com/bliki/CircuitBreaker.html>
- 本仓库相关文档：[病毒扫描技术栈总览](病毒扫描技术栈总览.md) · [YARA恶意软件规则匹配详解](YARA恶意软件规则匹配详解.md) · 实现方案见 `virus-scan-solution-v3.md` Task 6/15/16
