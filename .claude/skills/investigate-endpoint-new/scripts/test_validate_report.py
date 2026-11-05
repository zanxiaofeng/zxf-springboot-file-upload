#!/usr/bin/env python3
"""Regression tests for validate_report.py using only the standard library."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import validate_report as validator


VALID_REPORT = """# POST /api/demo 调查报告

## Executive Summary
- 调查范围：固定版本静态调查。
- 功能：接收请求并写入数据库。
- 外部依赖：O1（Database → demo 表）。
- 注意：无显式超时。

## Inbound
### I1. 同步 HTTP → POST /api/demo

| 鉴权 | 主坐标 |
|---|---|
| 无 | `src/Demo.java#handle` |

> 接收请求并调用 O1。

```json
{
  "name": "{name}"
}
```

## Operations
### O1. Database → demo 表

主坐标：`src/DemoRepository.java#save`

> 保存 name；失败向 I1 传播。
"""


class ValidatorTests(unittest.TestCase):
    def assert_has_error(self, text: str, fragment: str) -> None:
        messages = [issue.message for issue in validator.validate_text(text)]
        self.assertTrue(
            any(fragment in message for message in messages),
            msg=f"未找到错误片段 {fragment!r}；实际错误：{messages}",
        )

    def test_valid_minimal_report(self) -> None:
        self.assertEqual([], validator.validate_text(VALID_REPORT))

    def test_bundled_examples(self) -> None:
        references = Path(__file__).resolve().parents[1] / "references"
        for name in ("example-crud.md", "example-distributed.md", "example-async.md"):
            with self.subTest(name=name):
                self.assertEqual([], validator.validate_file(references / name))

    def test_missing_summary_field(self) -> None:
        self.assert_has_error(VALID_REPORT.replace("- 注意：无显式超时。\n", ""), "缺少非空字段“注意”")

    def test_missing_main_coordinate(self) -> None:
        self.assert_has_error(VALID_REPORT.replace("主坐标：`src/DemoRepository.java#save`", "主坐标：待补充"), "缺少带坐标值的主坐标")

    def test_duplicate_and_noncontiguous_numbers(self) -> None:
        duplicate = VALID_REPORT.replace("### O1. Database → demo 表", "### O1. Database → demo 表\n\n### O1. Database → other 表\n\n主坐标：`src/Other.java#save`\n\n> 保存其他数据。")
        self.assert_has_error(duplicate, "编号 O1 重复")
        noncontiguous = VALID_REPORT.replace("### O1. Database → demo 表", "### O2. Database → demo 表")
        self.assert_has_error(noncontiguous, "编号应从 1 开始连续分配")

    def test_undefined_reference(self) -> None:
        self.assert_has_error(VALID_REPORT.replace("失败向 I1 传播", "失败向 I9 传播"), "引用了未定义的 I9")

    def test_operation_missing_from_summary(self) -> None:
        self.assert_has_error(VALID_REPORT.replace("O1（Database → demo 表）", "Database → demo 表"), "Executive Summary 未引用已定义的 O1")

    def test_invalid_json(self) -> None:
        self.assert_has_error(VALID_REPORT.replace('"name": "{name}"', '202 { "name": "{name}" }'), "json 代码块语法无效")

    def test_unclosed_details_and_fence(self) -> None:
        self.assert_has_error(VALID_REPORT + "\n<details><summary>详情</summary>\n", "<details> 未闭合")
        self.assert_has_error(VALID_REPORT + "\n```text\nunclosed\n", "代码围栏未闭合")

    def test_process_internal_label(self) -> None:
        text = VALID_REPORT.replace("### O1. Database → demo 表", "### O1. 进程内 → 计算")
        self.assert_has_error(text, "进程内卡必须标注“无外部系统”")


if __name__ == "__main__":
    unittest.main(verbosity=2)
