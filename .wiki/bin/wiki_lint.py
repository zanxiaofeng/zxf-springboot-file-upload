#!/usr/bin/env python3
"""Mechanical lint for the .wiki/ knowledge base.

Checks structure, frontmatter, naming, wikilinks, orphans, staleness,
and source-path existence. Pure stdlib — no third-party dependencies.

Usage:
    python3 .wiki/bin/wiki_lint.py [wiki-root] [repo-root]

Exit code: 0 = clean or warnings only, 1 = errors found.
"""
import datetime
import re
import sys
from pathlib import Path

PAGE_TYPES = {"concept", "module", "decision", "system", "playbook"}
STATUSES = {"draft", "accepted", "deprecated"}
REQUIRED_FIELDS = ("type", "status", "owner", "stale_after")
SPECIAL_FILES = ("index.md", "log.md", "_template.md")

NAME_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*\.md$")
ADR_RE = re.compile(r"^adr-\d{4}-[a-z0-9-]+\.md$")
STATUS_IN_NAME_RE = re.compile(r"-(old|deprecated|v\d+)\.md$")
FRONTMATTER_RE = re.compile(r"\A---\s*\n(.*?)\n---\s*\n", re.DOTALL)
WIKILINK_RE = re.compile(r"\[\[([^\]|#]+?)(?:[|#][^\]]*)?\]\]")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
SOURCE_LINE_RE = re.compile(r"^\s*-\s+(\S+)")

errors = []
warnings = []


def err(rel, msg):
    errors.append(f"ERROR   {rel}: {msg}")


def warn(rel, msg):
    warnings.append(f"WARNING {rel}: {msg}")


def split_frontmatter(text):
    """Return (flat-dict of scalar fields, raw frontmatter block, body)."""
    match = FRONTMATTER_RE.match(text)
    if not match:
        return {}, "", text
    block = match.group(1)
    fields = {}
    for line in block.splitlines():
        if ":" in line and not line.startswith((" ", "-", "\t")):
            key, value = line.split(":", 1)
            fields[key.strip()] = value.strip()
    return fields, block, text[match.end():]


def main():
    wiki = Path(sys.argv[1] if len(sys.argv) > 1 else ".wiki").resolve()
    repo = Path(sys.argv[2] if len(sys.argv) > 2 else ".").resolve()
    if not wiki.is_dir():
        print(f"ERROR   {wiki}: wiki root not found (run init first)")
        return 1

    for special in SPECIAL_FILES:
        if not (wiki / special).is_file():
            err(special, f"missing required file {special}")

    pages = {}  # slug -> relative path
    for path in sorted(wiki.rglob("*.md")):
        if path.name in SPECIAL_FILES:
            continue
        rel = path.relative_to(wiki).as_posix()
        pages[path.stem] = rel

        if not NAME_RE.match(path.name):
            err(rel, "file name must be kebab-case ASCII")
        if STATUS_IN_NAME_RE.search(path.name):
            err(rel, "file name must not encode status (-old/-v2/-deprecated)")
        if path.parent.name == "decisions" and not ADR_RE.match(path.name):
            err(rel, "ADR pages must be named adr-NNNN-{slug}.md")

        fields, block, body = split_frontmatter(
            path.read_text(encoding="utf-8"))
        if not fields and not block:
            err(rel, "missing frontmatter")
        if fields.get("type") not in PAGE_TYPES:
            err(rel, f"invalid/missing type: {fields.get('type')!r}")
        if fields.get("status") not in STATUSES:
            err(rel, f"invalid/missing status: {fields.get('status')!r}")
        for field in ("owner", "stale_after"):
            if not fields.get(field):
                err(rel, f"missing required field: {field}")

        stale_after = fields.get("stale_after", "")
        if stale_after:
            if not DATE_RE.match(stale_after):
                err(rel, f"stale_after must be YYYY-MM-DD: {stale_after}")
            else:
                try:
                    expired = datetime.date.fromisoformat(stale_after) < datetime.date.today()
                except ValueError:
                    expired = False
                    err(rel, f"stale_after is not a valid date: {stale_after}")
                if expired and fields.get("status") == "accepted":
                    warn(rel, f"page is stale (stale_after {stale_after})")

        # Sources: local paths (not URLs) must exist relative to the repo root.
        for line in block.splitlines():
            source = SOURCE_LINE_RE.match(line)
            if not source:
                continue
            item = source.group(1)
            if item.startswith(("http://", "https://")):
                continue
            if not (repo / item).exists():
                warn(rel, f"source path does not exist: {item}")

    # Wikilinks, orphans, index coverage.
    incoming = {}
    for slug, rel in pages.items():
        _, _, body = split_frontmatter(
            (wiki / rel).read_text(encoding="utf-8"))
        for target in WIKILINK_RE.findall(body):
            target = target.strip()
            incoming[target] = incoming.get(target, 0) + 1
            if target not in pages:
                err(rel, f"broken wikilink [[{target}]]")

    for slug, rel in pages.items():
        if not incoming.get(slug):
            warn(rel, "orphan page (no inbound wikilinks)")

    index_path = wiki / "index.md"
    if index_path.is_file():
        index_text = index_path.read_text(encoding="utf-8")
        for slug, rel in pages.items():
            if slug not in index_text:
                warn(rel, "not listed in index.md")

    for line in errors:
        print(line)
    for line in warnings:
        print(line)
    print(f"\n{len(pages)} pages, {len(errors)} errors, {len(warnings)} warnings")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
