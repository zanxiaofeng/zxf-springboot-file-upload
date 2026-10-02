#!/usr/bin/env python3
"""Mechanical lint for CLAUDE.md / AGENTS.md context files.

Checks structure (required/recommended/forbidden sections), length cap,
weak wording on rule lines, dead paths & @imports, and CLAUDE.md<->AGENTS.md
section drift. Pure stdlib — no third-party dependencies.

Usage:
    python3 context_lint.py [file ...]    # defaults: ./CLAUDE.md ./AGENTS.md

Exit code: 0 = clean or warnings only, 1 = errors found.
"""
import os
import re
import sys

WARN_LINES = 200   # official target (Anthropic memory docs)
ERROR_LINES = 250  # hard cap (AgentLint community practice)

REQUIRED_SECTIONS = [
    (("tech stack", "技术栈", "stack"), "Tech Stack (exact versions)"),
    (("command", "命令", "build", "构建"), "Commands (precise build/test commands)"),
    (("hard rule", "禁令", "never", "prohibition", "don't"),
     "Hard Rules (Don't/Never, each with its replacement)"),
]
RECOMMENDED_SECTIONS = [
    (("architect", "架构"), "Architecture (short, details in .wiki/)"),
    (("decision", "决策"), "Decisions (one line each WITH reason)"),
    (("convention", "约定", "etiquette", "规范"), "Conventions (branch/PR/language)"),
    (("principle", "原则"), "Principles (3-7, one-line rationale each)"),
]
FORBIDDEN_PATTERNS = [
    (re.compile(r"^#{1,3}\s+.*(philosoph|价值|history|历史|origin|由来)", re.I),
     "philosophy/history section"),
    (re.compile(r"├──|└──"), "file-by-file directory tree"),
]
WEAK_WORDS = re.compile(
    r"(\b(?:try to|consider|when possible|if possible|preferably|feel free|"
    r"should probably)\b|尽量|建议|可以考虑|最好|可能的话|原则上|一般来说)")
PATH_RE = re.compile(r"(?<![\w.])(?:src|docs|scripts|\.claude|\.github|\.wiki)/[\w./-]+")
IMPORT_RE = re.compile(r"@(\.[\w./-]+|[\w-]+(?:/[\w.-]+)+)")
HEADING_RE = re.compile(r"^#{1,6}\s+(.*?)\s*#*\s*$")
RULE_LINE_RE = re.compile(r"^(?:[-*#]|\d+[.)])")
URL_RE = re.compile(r"https?://\S+")

errors = []
warnings = []


def err(f, line, msg):
    errors.append(f"ERROR   {f}:{line}: {msg}")


def warn(f, line, msg):
    warnings.append(f"WARNING {f}:{line}: {msg}")


def lint_file(name, lines):
    total = len(lines)
    if total > ERROR_LINES:
        err(name, total, f"file has {total} lines (hard cap {ERROR_LINES})")
    elif total > WARN_LINES:
        warn(name, total, f"file has {total} lines (target under {WARN_LINES})")

    headings = []
    for i, raw in enumerate(lines, 1):
        match = HEADING_RE.match(raw)
        if match:
            headings.append(match.group(1).lower())
        for pattern, label in FORBIDDEN_PATTERNS:
            if pattern.search(raw):
                warn(name, i, f"forbidden content: {label}")
        if RULE_LINE_RE.match(raw.lstrip()):
            weak = WEAK_WORDS.search(raw)
            if weak:
                warn(name, i,
                     f"weak wording '{weak.group(0)}' on a rule line — "
                     "use MUST/禁止 or delete the line")
        cleaned = URL_RE.sub("", raw)
        refs = set(PATH_RE.findall(cleaned)) | set(IMPORT_RE.findall(cleaned))
        for ref in refs:
            if not os.path.exists(ref):
                err(name, i, f"dead reference: {ref}")

    heading_text = "\n".join(headings)
    for keys, label in REQUIRED_SECTIONS:
        if not any(key in heading_text for key in keys):
            warn(name, 0, f"missing required section: {label}")
    missing = [label for keys, label in RECOMMENDED_SECTIONS
               if not any(key in heading_text for key in keys)]
    if missing:
        warn(name, 0, "missing recommended sections: " + "; ".join(missing))
    return headings


def drift(name_a, heads_a, name_b, heads_b):
    only_a = sorted(set(heads_a) - set(heads_b))
    only_b = sorted(set(heads_b) - set(heads_a))
    if only_a or only_b:
        warn(f"{name_a}<->{name_b}", 0,
             f"section drift — only in {name_a}: {only_a}; "
             f"only in {name_b}: {only_b} (designate one source of truth)")


def main():
    files = sys.argv[1:] or ["CLAUDE.md", "AGENTS.md"]
    audited = {}
    for name in files:
        if not os.path.isfile(name):
            continue
        with open(name, encoding="utf-8") as handle:
            audited[name] = lint_file(name, handle.read().splitlines())
    if "CLAUDE.md" in audited and "AGENTS.md" in audited:
        drift("CLAUDE.md", audited["CLAUDE.md"], "AGENTS.md", audited["AGENTS.md"])

    for line in errors:
        print(line)
    for line in warnings:
        print(line)
    print(f"\n{len(audited)} files, {len(errors)} errors, {len(warnings)} warnings")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
