#!/usr/bin/env python3
"""Validate the static structure of an investigate-endpoint report.

This checker does not verify source-code facts. It intentionally uses only the
Python standard library so it can run in an offline investigation environment.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


CARD_HEADING = re.compile(r"^###\s+([IO])(\d+)\.\s+(\S.*)$")
CARD_REFERENCE = re.compile(r"(?<![A-Za-z0-9])([IO]\d+)(?![A-Za-z0-9])")
SECTION_HEADING = re.compile(r"^##\s+(?!#)(.+?)\s*$")
REQUIRED_SECTIONS = ("Executive Summary", "Inbound", "Operations")
SUMMARY_FIELDS = ("调查范围", "功能", "外部依赖", "注意")


@dataclass(frozen=True)
class Issue:
    line: int
    message: str

    def __str__(self) -> str:
        location = f"第 {self.line} 行" if self.line > 0 else "文件级"
        return f"{location}：{self.message}"


@dataclass(frozen=True)
class Card:
    kind: str
    number: int
    title: str
    line: int
    body: str

    @property
    def identifier(self) -> str:
        return f"{self.kind}{self.number}"


def _section_name(title: str) -> str | None:
    for name in REQUIRED_SECTIONS:
        if title == name or title.startswith(name + "（") or title.startswith(name + "("):
            return name
    return None


def _structural_lines(lines: list[str]) -> tuple[list[str], list[Issue]]:
    """Blank fenced-code contents while preserving physical line numbers."""
    result = list(lines)
    issues: list[Issue] = []
    fence: str | None = None
    fence_line = 0
    for index, line in enumerate(lines):
        match = re.match(r"^\s*(`{3,}|~{3,})", line)
        if fence is None:
            if match:
                fence = match.group(1)[0] * 3
                fence_line = index + 1
            continue
        if match and match.group(1).startswith(fence):
            fence = None
            continue
        result[index] = ""
    if fence is not None:
        issues.append(Issue(fence_line, "代码围栏未闭合"))
    return result, issues


def _section_bounds(
    structural: list[str],
) -> tuple[dict[str, tuple[int, int, int]], list[Issue]]:
    headings: list[tuple[int, str, str | None]] = []
    issues: list[Issue] = []
    for index, line in enumerate(structural):
        match = SECTION_HEADING.match(line)
        if match:
            title = match.group(1)
            headings.append((index, title, _section_name(title)))

    found: dict[str, tuple[int, int, int]] = {}
    for position, (index, _title, name) in enumerate(headings):
        if name is None:
            continue
        end = headings[position + 1][0] if position + 1 < len(headings) else len(structural)
        if name in found:
            issues.append(Issue(index + 1, f"必需区块 {name} 重复"))
            continue
        found[name] = (index, end, index + 1)

    for name in REQUIRED_SECTIONS:
        if name not in found:
            issues.append(Issue(0, f"缺少必需区块 ## {name}"))

    if all(name in found for name in REQUIRED_SECTIONS):
        positions = [found[name][0] for name in REQUIRED_SECTIONS]
        if positions != sorted(positions):
            issues.append(Issue(0, "必需区块顺序应为 Executive Summary → Inbound → Operations"))
    return found, issues


def _cards_for_section(
    structural: list[str],
    start: int,
    end: int,
    expected_kind: str,
) -> tuple[list[Card], list[Issue]]:
    headings: list[tuple[int, re.Match[str]]] = []
    issues: list[Issue] = []
    for index in range(start + 1, end):
        match = CARD_HEADING.match(structural[index])
        if match:
            headings.append((index, match))

    cards: list[Card] = []
    for position, (index, match) in enumerate(headings):
        next_heading = headings[position + 1][0] if position + 1 < len(headings) else end
        kind = match.group(1)
        if kind != expected_kind:
            issues.append(Issue(index + 1, f"{kind} 卡位于错误的必需区块"))
        cards.append(
            Card(
                kind=kind,
                number=int(match.group(2)),
                title=match.group(3),
                line=index + 1,
                body="\n".join(structural[index + 1 : next_heading]),
            )
        )
    if not cards:
        issues.append(Issue(start + 1, f"{expected_kind} 区块至少需要一张编号卡"))
    return cards, issues


def _validate_cards(cards: Iterable[Card]) -> list[Issue]:
    issues: list[Issue] = []
    by_kind: dict[str, list[Card]] = {"I": [], "O": []}
    seen: dict[str, Card] = {}
    for card in cards:
        by_kind[card.kind].append(card)
        if card.identifier in seen:
            issues.append(Issue(card.line, f"编号 {card.identifier} 重复"))
        else:
            seen[card.identifier] = card

        if "→" not in card.title or not card.title.rsplit("→", 1)[-1].strip():
            issues.append(Issue(card.line, f"{card.identifier} 标题必须包含类型 → 目标"))
        if "主坐标" not in card.body or not re.search(r"`[^`\n]+`", card.body):
            issues.append(Issue(card.line, f"{card.identifier} 缺少带坐标值的主坐标"))
        if not re.search(r"(?m)^>\s*\S", card.body):
            issues.append(Issue(card.line, f"{card.identifier} 缺少一句话结果摘要"))
        if "进程内" in card.title and "无外部系统" not in card.title:
            issues.append(Issue(card.line, f"{card.identifier} 进程内卡必须标注“无外部系统”"))

    for kind, group in by_kind.items():
        numbers = sorted(card.number for card in group)
        expected = list(range(1, len(group) + 1))
        if numbers and numbers != expected:
            issues.append(Issue(group[0].line, f"{kind} 编号应从 1 开始连续分配，当前为 {numbers}"))
    return issues


def _validate_summary(
    structural: list[str],
    start: int,
    end: int,
    operation_ids: Iterable[str],
) -> list[Issue]:
    issues: list[Issue] = []
    summary_lines = structural[start + 1 : end]
    summary = "\n".join(summary_lines)
    for field in SUMMARY_FIELDS:
        pattern = rf"(?m)^-\s+(?:\*\*)?{re.escape(field)}(?:\*\*)?：\s*\S"
        if not re.search(pattern, summary):
            issues.append(Issue(start + 1, f"Executive Summary 缺少非空字段“{field}”"))
    summary_refs = set(CARD_REFERENCE.findall(summary))
    for identifier in sorted(set(operation_ids) - summary_refs):
        issues.append(Issue(start + 1, f"Executive Summary 未引用已定义的 {identifier}"))
    return issues


def _validate_references(structural: list[str], cards: list[Card]) -> list[Issue]:
    defined = {card.identifier for card in cards}
    issues: list[Issue] = []
    reported: set[tuple[int, str]] = set()
    for index, line in enumerate(structural):
        if CARD_HEADING.match(line):
            continue
        for identifier in CARD_REFERENCE.findall(line):
            key = (index + 1, identifier)
            if identifier not in defined and key not in reported:
                issues.append(Issue(index + 1, f"引用了未定义的 {identifier}"))
                reported.add(key)
    return issues


def _validate_json_blocks(lines: list[str]) -> list[Issue]:
    issues: list[Issue] = []
    index = 0
    while index < len(lines):
        if re.match(r"^\s*```json\s*$", lines[index], flags=re.IGNORECASE):
            start = index
            index += 1
            payload: list[str] = []
            while index < len(lines) and not re.match(r"^\s*```\s*$", lines[index]):
                payload.append(lines[index])
                index += 1
            if index == len(lines):
                break
            try:
                json.loads("\n".join(payload))
            except json.JSONDecodeError as exc:
                issues.append(Issue(start + 1, f"json 代码块语法无效：{exc.msg}（块内第 {exc.lineno} 行）"))
        index += 1
    return issues


def _validate_details(lines: list[str]) -> list[Issue]:
    issues: list[Issue] = []
    depth = 0
    for index, line in enumerate(lines):
        for token in re.findall(r"</?details\b[^>]*>", line, flags=re.IGNORECASE):
            if token.startswith("</"):
                depth -= 1
                if depth < 0:
                    issues.append(Issue(index + 1, "存在多余的 </details>"))
                    depth = 0
            else:
                depth += 1
    if depth:
        issues.append(Issue(0, f"有 {depth} 个 <details> 未闭合"))
    return issues


def validate_text(text: str) -> list[Issue]:
    if not text.strip():
        return [Issue(0, "报告为空")]
    lines = text.splitlines()
    structural, issues = _structural_lines(lines)

    h1_lines = [(index, line) for index, line in enumerate(structural) if re.match(r"^#\s+(?!#)", line)]
    if len(h1_lines) != 1:
        issues.append(Issue(0, f"报告必须恰有一个一级标题，当前为 {len(h1_lines)} 个"))
    elif not h1_lines[0][1].rstrip().endswith("调查报告"):
        issues.append(Issue(h1_lines[0][0] + 1, "一级标题必须以“调查报告”结尾"))

    sections, section_issues = _section_bounds(structural)
    issues.extend(section_issues)
    cards: list[Card] = []
    if "Inbound" in sections:
        start, end, _ = sections["Inbound"]
        found, card_issues = _cards_for_section(structural, start, end, "I")
        cards.extend(found)
        issues.extend(card_issues)
    if "Operations" in sections:
        start, end, _ = sections["Operations"]
        found, card_issues = _cards_for_section(structural, start, end, "O")
        cards.extend(found)
        issues.extend(card_issues)

    issues.extend(_validate_cards(cards))
    issues.extend(_validate_references(structural, cards))
    if "Executive Summary" in sections:
        start, end, _ = sections["Executive Summary"]
        operation_ids = [card.identifier for card in cards if card.kind == "O"]
        issues.extend(_validate_summary(structural, start, end, operation_ids))

    for index, line in enumerate(structural):
        match = CARD_HEADING.match(line)
        if not match:
            continue
        in_inbound = "Inbound" in sections and sections["Inbound"][0] < index < sections["Inbound"][1]
        in_operations = "Operations" in sections and sections["Operations"][0] < index < sections["Operations"][1]
        if not in_inbound and not in_operations:
            issues.append(Issue(index + 1, "I／O 卡只能位于 Inbound 或 Operations 区块"))

    issues.extend(_validate_json_blocks(lines))
    issues.extend(_validate_details(lines))
    return sorted(set(issues), key=lambda issue: (issue.line, issue.message))


def validate_file(path: Path) -> list[Issue]:
    try:
        text = path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError) as exc:
        return [Issue(0, f"无法读取 UTF-8 报告：{exc}")]
    return validate_text(text)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="校验 investigate-endpoint 报告的静态结构")
    parser.add_argument("reports", nargs="+", type=Path, help="一个或多个 Markdown 报告")
    args = parser.parse_args(argv)
    failed = False
    for path in args.reports:
        issues = validate_file(path)
        if issues:
            failed = True
            print(f"FAIL {path}")
            for issue in issues:
                print(f"  - {issue}")
        else:
            print(f"PASS {path}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
