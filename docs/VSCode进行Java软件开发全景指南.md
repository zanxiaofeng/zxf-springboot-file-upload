# 在 VS Code 中进行 Java 软件开发全景指南

> 适用对象：以 Java 21 / Spring Boot 为主的后端工程师
> 覆盖范围：环境搭建、编码开发、构建、调试、测试、质量保障、远程开发与配置管理

---

## 目录

1. [总体架构：VS Code 的 Java 支持是如何工作的](#1-总体架构)
2. [环境准备：JDK、构建工具与扩展安装](#2-环境准备)
3. [核心配置：settings.json 详解](#3-核心配置)
4. [项目创建与导入](#4-项目创建与导入)
5. [编码开发：导航、补全与重构](#5-编码开发)
6. [调试：launch.json 与高级调试技巧](#6-调试)
7. [构建与依赖管理：Maven / Gradle](#7-构建与依赖管理)
8. [测试：JUnit 5、测试视图与覆盖率](#8-测试)
9. [Spring Boot 开发专项](#9-spring-boot-开发专项)
10. [代码质量：SonarLint、Checkstyle 与格式化](#10-代码质量)
11. [版本控制与协作](#11-版本控制与协作)
12. [远程开发与容器化：Dev Containers / WSL / SSH](#12-远程开发与容器化)
13. [性能调优与常见问题排查](#13-性能调优与常见问题)
14. [团队级统一配置建议](#14-团队级统一配置建议)
15. [VS Code vs IntelliJ IDEA：适用边界](#15-vs-code-vs-intellij-idea)

---

## 1. 总体架构

VS Code 本身只是编辑器，Java 能力由**语言服务器协议（LSP）**提供：

```
VS Code 前端
    │  LSP
    ▼
Eclipse JDT Language Server (jdtls)   ← Red Hat 维护，Language Support for Java 扩展的核心
    │
    ├─ 编译（ECJ 增量编译，非 javac 全量）
    ├─ 索引 / 符号解析 / 重构
    └─ 诊断（错误提示）
```

要点：

- **jdtls 自身运行在 JDK 17+**（旧版要求 JDK 11+），项目本身可以用更低或更高的 JDK，两者解耦。
- 代码导航、补全、重构都由 jdtls 完成；调试（Debugger for Java）、测试（Test Runner for Java）、构建（Maven/Gradle for Java）是独立扩展。
- 理解这一架构有助于排查问题：报错先看 **Output 面板 → Language Support for Java** 的日志，而不是猜。

---

## 2. 环境准备

### 2.1 JDK 安装与多版本管理

- 推荐 JDK 21（LTS），Spring Boot 3.x / 4.x 均以 JDK 17 为最低基线，JDK 21 是虚拟线程等特性的推荐版本。
- macOS / Linux 推荐用 **SDKMAN!** 管理多版本：

```bash
sdk install java 21.0.5-tem
sdk default java 21.0.5-tem
sdk use java 17.0.13-tem   # 仅当前 shell
```

- Windows 可用 [Microsoft OpenJDK](https://learn.microsoft.com/java/openjdk/download) 或 winget：

```powershell
winget install Microsoft.OpenJDK.21
```

- 配置 `JAVA_HOME` 并让 `java -version` 在终端可验证。

### 2.2 构建工具

- **Maven 3.9+**：配置国内镜像（阿里云）加速依赖下载；企业环境配置 Nexus 私服 `settings.xml`。
- **Gradle**：优先使用项目自带的 Gradle Wrapper（`./gradlew`），不要依赖全局安装。
- 建议项目内始终携带 **Maven Wrapper**（`mvnw`），保证团队与 CI 使用同一版本：

```bash
mvn wrapper:wrapper -Dmaven=3.9.9
```

### 2.3 必装扩展

**Extension Pack for Java**（Microsoft 官方包，一次装齐）：

| 扩展 | 作用 |
|---|---|
| Language Support for Java™ by Red Hat | 语言服务器：补全、导航、重构、诊断 |
| Debugger for Java | 断点调试 |
| Test Runner for Java | 运行/调试 JUnit、TestNG |
| Maven for Java | POM 支持、项目脚手架、自定义 goal |
| Gradle for Java | Gradle 任务视图、依赖查看 |
| Project Manager for Java | Java Projects 视图、包/类/库管理 |
| Visual Studio IntelliCode | AI 辅助补全 |

**Spring Boot Extension Pack**（Spring 项目必装）：

| 扩展 | 作用 |
|---|---|
| Spring Boot Tools | application.yml/properties 智能提示、Bean 导航、实时应用信息 |
| Spring Initializr Java Support | 编辑器内生成 Spring Boot 项目、编辑 starter 依赖 |
| Spring Boot Dashboard | 统一管理多个 Boot 应用的启动/停止/调试 |

**按需加装**：

- `Lombok`：新版 Java 扩展已内置支持，旧项目确认 `settings.json` 无遗留的 `java.jdt.ls.lombokSupport` 配置冲突即可。
- `SonarLint`：实时代码质量/安全问题检测，可绑定 SonarQube/SonarCloud。
- `Checkstyle for Java`：团队编码规范强制检查。
- `Error Lens`：把诊断信息内联显示在行尾，强烈推荐。
- `GitLens`：Git 增强。
- `EditorConfig for VS Code`：跨编辑器缩进/换行统一。

---

## 3. 核心配置

### 3.1 多 JDK 运行时（最常用配置）

jdtls 允许为不同项目指定不同 JDK（**User 级** settings.json）：

```json
{
  "java.configuration.runtimes": [
    { "name": "JavaSE-17", "path": "/opt/jdk/jdk-17" },
    { "name": "JavaSE-21", "path": "/opt/jdk/jdk-21", "default": true }
  ]
}
```

项目 `pom.xml` 中的 `maven.compiler.release` 决定使用哪个 runtime。

### 3.2 Maven 相关

```json
{
  "maven.executable.path": "/opt/maven/bin/mvn",
  "java.configuration.maven.userSettings": "/opt/maven/conf/settings.xml",
  "maven.terminal.useJavaHome": true
}
```

注意：`maven.executable.path` 只能配置在 **User 级**，不能放 Workspace。

### 3.3 jdtls 内存与性能

大项目给语言服务器加内存：

```json
{
  "java.jdt.ls.vmargs": "-XX:+UseParallelGC -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=90 -Dsun.zip.disableMemoryMapping=true -Xmx4G"
}
```

### 3.4 编辑体验

```json
{
  "java.saveActions.organizeImports": true,
  "editor.formatOnSave": true,
  "java.completion.importOrder": ["java", "javax", "org", "com", ""],
  "java.inlayHints.parameterNames.enabled": "literals"
}
```

### 3.5 Workspace vs User 的边界

- **User 级**：JDK 路径、Maven 路径——因机器而异，不要提交。
- **Workspace 级**（`.vscode/settings.json`，提交到 Git）：格式化规则、import 顺序、检查开关——团队应一致的内容。

---

## 4. 项目创建与导入

### 4.1 创建 Spring Boot 项目

`Ctrl+Shift+P` → `Spring Initializr: Generate a Maven Project` → 依次选择 Boot 版本、语言、Group/Artifact、打包方式、Java 版本、依赖（Spring Web、Lombok、Validation…）。

已有项目追加依赖：在 `pom.xml` 上右键 → **Add starters...**，勾选即改 POM，比手写好搜。

### 4.2 导入现有项目

直接打开含 `pom.xml` / `build.gradle` 的目录即可，jdtls 自动识别为 Java 项目并解析依赖。首次导入慢属正常（在建索引）。

多模块 Maven 项目：打开根 POM 目录，所有模块会出现在 **Java Projects** 视图中；可在该视图里查看每个模块的 classpath、source path 与实际生效的 JDK。

---

## 5. 编码开发

### 5.1 高频能力清单

| 能力 | 快捷键 / 方式 |
|---|---|
| 跳转定义 / 引用 | F12 / Shift+F12 |
| 全局符号搜索 | Ctrl+T（类）、Ctrl+Shift+O（文件内符号） |
| 重命名重构 | F2 |
| 提取方法/变量/常量 | 选中代码 → 灯泡（Ctrl+.） |
| 快速修复 | Ctrl+. |
| 组织 import | Shift+Alt+O |
| 生成构造器/getter/toString | 右键 → Source Action… |
| 实现接口方法 / Override | Source Action… |
| 查看类型层级 | F4 / 右键 Type Hierarchy |
| 查看调用层级 | Shift+Alt+H |

### 5.2 Source Action 是灵魂

右键 → **Source Action…** 菜单集中了 Java 专属批量操作：生成代码、组织 import、生成 hashCode/equals、覆写方法、生成 Lombok 相关注解切换等。把它当成 IDEA 的 `Alt+Insert`。

### 5.3 重构覆盖度

支持 Rename、Move、Extract Method/Variable/Constant/Interface、Inline、Change Signature、Convert to Static Import 等。复杂重构（如 Safe Delete 级联分析、大规模结构迁移）仍是 IDEA 的强项。

### 5.4 实用细节

- **折叠 Javadoc / 注释模板**：`java.foldingRange` 相关设置。
- **Semantic Highlighting**：字段、静态成员、类型参数着色区分，默认开启。
- **Inlay Hints**：参数名内联提示，读长方法调用链时非常好用。
- **未解析 classpath 报错**：`"java.errors.incompleteClasspath.severity": "ignore"` 可压制非 Maven/Gradle 的散落 Java 文件告警。

---

## 6. 调试

### 6.1 零配置调试

打开主类，`F5` 直接调试；编辑器顶部有 **Run | Debug** CodeLens。

### 6.2 launch.json（推荐团队化）

`.vscode/launch.json`（提交到 Git）：

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "App (dev)",
      "request": "launch",
      "mainClass": "com.example.Application",
      "projectName": "my-app",
      "vmArgs": "-Xmx1g -Dspring.profiles.active=dev",
      "env": { "DB_HOST": "localhost" },
      "envFile": "${workspaceFolder}/.env"
    },
    {
      "type": "java",
      "name": "Attach 5005",
      "request": "attach",
      "hostName": "localhost",
      "port": 5005
    }
  ]
}
```

### 6.3 高级技巧

- **Hot Code Replace**：调试中改方法体代码，保存后即时生效，无需重启（等价于 IDEA 的 HCR，不是 JRebel 级热部署）。
- **条件断点 / 命中次数断点 / 日志断点（Logpoint）**：断点上右键 → Edit Breakpoint。
- **表达式求值**：调试时在 WATCH 或 DEBUG CONSOLE 中执行任意 Java 表达式。
- **远程调试**：远端加 `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`，本地用 Attach 配置。
- **调试单测**：测试方法上方的 `Debug Test` CodeLens，或在 Testing 视图右键。

---

## 7. 构建与依赖管理

### 7.1 Maven

- **Maven 视图**：左侧边栏可浏览 lifecycle 与 plugins，点击即执行。
- **依赖树**：`mvn dependency:tree` 或在 POM 上的依赖冲突提示查看；排查版本冲突推荐命令行：

```bash
mvn dependency:tree -Dverbose | grep -B2 conflict
```

- **离线/私服**：确保 `settings.xml` 镜像配置正确，jdtls 解析依赖失败时 90% 是网络/仓库问题。
- 强制刷新：`Ctrl+Shift+P` → `Java: Clean Java Language Server Workspace` → Restart and delete（解决索引损坏的万能第一步）。

### 7.2 Gradle

- 始终用 Wrapper：`./gradlew build`，VS Code 的 Gradle 视图自动识别。
- Gradle 扩展通过 Build Server Protocol 与 jdtls 协作导入项目；修改 `build.gradle` 后手动触发 `Java: Force Java Compilation` 或重新加载窗口。

### 7.3 任务集成

`.vscode/tasks.json` 定义常用命令并绑定快捷键：

```json
{
  "version": "2.0.0",
  "tasks": [
    {
      "label": "mvn verify",
      "type": "shell",
      "command": "./mvnw -q verify",
      "group": { "kind": "build", "isDefault": true },
      "problemMatcher": []
    }
  ]
}
```

`Ctrl+Shift+B` 一键执行。

---

## 8. 测试

### 8.1 Test Runner

- **Testing 视图**（烧杯图标）：树形展示所有测试类/方法，支持运行、调试、按结果筛选。
- 支持 **JUnit 4 / JUnit 5 / TestNG**。
- 测试方法上的 `Run Test | Debug Test` CodeLens 是最快入口。

### 8.2 JUnit 5 工程实践

- 参数化测试、嵌套测试（`@Nested`）在 Testing 视图中均有良好展示。
- 标签过滤：`@Tag("slow")` 配合 Maven profile（`mvn test -Dgroups=fast`）区分快慢测试。
- Spring Boot 测试：`@SpringBootTest`、`@WebMvcTest`、`@DataJpaTest` 切片测试都能正常调试；集成测试建议引入 **Testcontainers**。

### 8.3 覆盖率

VS Code 原生 Test Runner 的覆盖率支持有限，推荐两条路：

1. **JaCoCo + 报告**：`mvn test jacoco:report`，浏览器打开 `target/site/jacoco/index.html`；CI 侧配阈值校验。
2. **Coverage Gutters** 扩展：读取 `lcov.info` / `jacoco.xml`，在编辑器行号槽显示覆盖标记：

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <configuration>
    <reports>
      <report>xml</report>
    </reports>
  </configuration>
</plugin>
```

```bash
mvn test jacoco:report   # 生成 target/site/jacoco/jacoco.xml
```

然后在 Coverage Gutters 中 **Watch** 该文件。

### 8.4 测试调试技巧

- 调试时条件断点定位偶发失败（flaky test）。
- `Debug Test` 时 jdtls 会自动带上测试 classpath，无需手动配。
- 失败测试的堆栈在 Test Results 面板可直接点击跳转到源码行。

---

## 9. Spring Boot 开发专项

### 9.1 Spring Boot Tools 能力

- `application.yml` / `application.properties`：key 补全、类型校验、值提示（如 `server.port`、`logging.level.*`）。
- `@Value` / `@ConfigurationProperties` 与配置文件的**双向跳转**。
- **Live Process**：应用运行时显示激活的 profile、Bean、RequestMapping 端点列表（依赖 Actuator + `management.endpoint.*.enabled`）。
- `@Autowired` Bean 的注入来源导航、循环依赖警告。

### 9.2 Spring Boot Dashboard

- 统一列出工作区所有 Boot 应用，一键 Run/Debug/Stop。
- 直接打开应用的 Actuator 端点（`/actuator/health`、`/actuator/metrics`）。
- 多模块微服务仓库里管理多个服务特别方便。

### 9.3 推荐组合配置

`.vscode/launch.json` 中为不同 profile 建多个配置（dev/test），配合 `envFile` 管理本地密钥；提交到 Git 时注意 `.env` 加 `.gitignore`。

### 9.4 热重载

- `spring-boot-devtools` 依赖 + VS Code 保存触发编译 → 自动重启（restart），比 HCR 更彻底。
- 配置项：

```properties
spring.devtools.restart.enabled=true
spring.devtools.livereload.enabled=false
```

---

## 10. 代码质量

### 10.1 静态检查

- **SonarLint**：实时的 Bug/异味/安全热点提示，团队可绑定 SonarQube 项目同步规则（Connected Mode）。
- **Checkstyle for Java**：指定团队的 `checkstyle.xml`，保存即检查。

```json
{
  "java.checkstyle.configuration": "${workspaceFolder}/config/checkstyle/checkstyle.xml",
  "java.checkstyle.version": "13.8.0"
}
```

### 10.2 格式化统一

推荐 **Spotless（Maven/Gradle 插件）** 作为唯一格式化标准，VS Code 侧用 google-java-format 或对齐 Spotless 规则，保证"IDE 格式化 = CI 校验"：

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <configuration>
    <java>
      <googleJavaFormat><version>1.35.0</version></googleJavaFormat>
      <importOrder><order>javax,java,org,com,\#</order></importOrder>
      <removeUnusedImports/>
    </java>
  </configuration>
</plugin>
```

CI 中跑 `mvn spotless:check`，本地跑 `mvn spotless:apply`。

### 10.3 提交前拦截

`pre-commit` 框架或 Maven 插件挂 git hook，把 `spotless:check`、`checkstyle:check` 放到 commit 阶段，避免烂代码进仓库。

---

## 11. 版本控制与协作

- 内置 Git 覆盖日常提交/变基/冲突解决；三栏合并编辑器体验良好。
- **GitLens**：行级 blame、文件历史、提交图、CodeLens 作者提示。
- **Git Graph**：可视化分支拓扑，适合梳理复杂分支策略。
- `.vscode/` 目录的提交策略：`settings.json`（团队统一项）、`launch.json`、`tasks.json`、`extensions.json` 提交；`*` 其他忽略。

`.vscode/extensions.json` 声明团队推荐扩展，新人打开项目即提示安装：

```json
{
  "recommendations": [
    "vscjava.vscode-java-pack",
    "vmware.vscode-boot-dev-pack",
    "sonarsource.sonarlint-vscode",
    "editorconfig.editorconfig"
  ]
}
```

---

## 12. 远程开发与容器化

### 12.1 Dev Containers（最推荐的团队方案）

`.devcontainer/devcontainer.json` 把 JDK、Maven、扩展、端口转发全部固化：

```json
{
  "image": "mcr.microsoft.com/devcontainers/java:21",
  "features": {
    "ghcr.io/devcontainers/features/java:1": { "version": "21" }
  },
  "customizations": {
    "vscode": {
      "extensions": ["vscjava.vscode-java-pack", "vmware.vscode-boot-dev-pack"]
    }
  },
  "forwardPorts": [8080],
  "postCreateCommand": "./mvnw -q dependency:go-offline"
}
```

价值：环境零差异、"在我机器上能跑"问题消失、新人 5 分钟开工。

### 12.2 WSL / Remote-SSH

- Windows 用户：代码放 WSL 文件系统内（`\\wsl$` 路径下性能差），用 **WSL 扩展**连接开发。
- Remote-SSH：在服务器上跑 jdtls，本地只做 UI；大项目、内网依赖场景适用。

### 12.3 容器化调试

Docker 扩展 + 远端 JDWP attach（见 6.3），可调试容器内 Java 进程；k8s 环境配合 `kubectl port-forward`。

---

## 13. 性能调优与常见问题

| 症状 | 排查 |
|---|---|
| 打开项目后满屏红错 | 等索引建完；`Java: Clean Java Language Server Workspace` 重启 |
| 依赖下载不动 | 检查 Maven `settings.xml` 镜像；企业代理配 `http.proxy` |
| 扩展报 `UnsupportedClassVersionError` | jdtls 需要 JDK 17+ 运行，检查 `java.jdt.ls.java.home` 指向的 JDK |
| 大项目卡顿 | 加 jdtls 内存（3.3）；关闭不需要的多模块项目；排除 `target/` 目录索引 |
| Lombok 不生效 | 确认依赖存在且 annotation processing 正常；执行 Clean Workspace |
| 保存后热重启失效 | 确认 `spring-boot-devtools` 在 classpath，且 VS Code 实际触发了编译（Build Automatically 开启） |
| 调试不进断点 | 检查断点是实心红（已绑定）；多模块项目 `projectName` 是否写对 |
| yml 无提示 | 确认 Spring Boot Tools 扩展已激活，文件在 `src/main/resources` |

常用诊断命令：`Ctrl+Shift+P` → `Java: Open Java Language Server Log File`、`Developer: Show Running Extensions`。

---

## 14. 团队级统一配置建议

落地清单（均可提交 Git）：

```
repo/
├── .vscode/
│   ├── settings.json        # 格式化、import 顺序、检查开关
│   ├── launch.json          # 常用启动/调试配置
│   ├── tasks.json           # mvn verify 等任务
│   └── extensions.json      # 推荐扩展
├── .editorconfig            # 跨 IDE 缩进统一
├── .devcontainer/           # 开发容器（可选但推荐）
├── config/checkstyle/       # 团队规范
└── pom.xml                  # spotless/jacoco 等质量插件
```

再加一份 `CONTRIBUTING.md` 写明：JDK 版本、首次导入步骤、格式化命令（`mvn spotless:apply`）、测试命令，即完成闭环。

---

## 15. VS Code vs IntelliJ IDEA

| 维度 | VS Code | IntelliJ IDEA Ultimate |
|---|---|---|
| 启动/资源占用 | 轻 | 重 |
| Java 核心体验（补全/导航/调试/测试） | 良好，足够日常开发 | 优秀 |
| 深度重构 / 结构搜索 / 数据流分析 | 一般 | 业内最强 |
| Spring/JPA/SQL 框架级智能 | 够用（Spring Boot Tools） | 深度集成 |
| 数据库工具 | 需装扩展 | 内置 Database 工具 |
| 免费 | 是 | 收费（Community 版功能受限） |
| 多语言混编（前端+后端一窗口） | 天然优势 | 需多 IDE |

结论：Java 后端 + 前端/脚本混编、轻量诉求、免费诉求 → VS Code 完全可胜任主力；纯大型 Java 单体深度重构 → IDEA 更省力。两者并不互斥，按项目切换。

---

## 参考资料

- VS Code 官方 Java 文档：https://code.visualstudio.com/docs/java/extensions
- Extension Pack for Java：https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack
- Spring Boot in VS Code：https://code.visualstudio.com/docs/java/spring-boot
- vscode-java-debug：https://github.com/microsoft/vscode-java-debug
- Eclipse JDT Language Server：https://github.com/eclipse-jdtls/eclipse.jdt.ls
