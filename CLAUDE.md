# CLAUDE.md

Spring Boot 4.1 文件上传安全扫描服务（Java 21 / Maven / 虚拟线程）。上传文件经「类型校验 → 病毒扫描 → YARA 规则 → 文档威胁检测」四阶段管道，详见 README.md。

## 编码规范

规范位于 `.claude/rules/`（19 个文件，移植自 Copilot `.github/instructions/`），按 `paths` glob 在读取匹配文件时自动注入，文件名保留 `*.instructions.md` 后缀。规范面向 Java 21 + Spring Boot 4.1，与本项目一致。

## 常用命令

- 编译：`mvn compile -q`
- 测试：`mvn test -q`（约 7s；判真以 `mvn clean test` 为准）

## Hooks（`.claude/settings.json`）

- `SessionStart`：输出 Java / Maven 版本（注意 PATH 默认 JDK 可能是 22，项目要求 21）
- `PreToolUse`（`^Bash$`）：拦截 `rm -rf` 及变体（`-fr`、`-r -f` 等），命中 exit 2 阻断
- `PostToolUse`（`^(Edit|Write)$`）：自动 `mvn compile -q` 快速编译验证
- `Stop`：停止前自动 `mvn test -q`，失败以 `{"decision":"block"}` 阻止停止并要求修复（连续 8 次阻断后 Claude Code 强制放行）

## 子代理 / 技能 / 命令

- `.claude/agents/`：`code-reviewer`、`security-auditor`、`tdd-guide`、`build-error-resolver`
- `.claude/skills/`：`add-endpoint`、`implement-feature`、`refactor-module`
- `.claude/commands/`：`/code-review`、`/security-audit`（经 `context: fork` 路由到对应子代理）

注：agents 内引用的规范路径已改写为 `.claude/rules/`；原 Copilot 的 `excludeAgent`（code-review 排除契约测试等规则）无 Claude Code 对应机制，已移除——code-reviewer 子代理现在会看到全部规范。
