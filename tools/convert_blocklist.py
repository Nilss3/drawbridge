#!/usr/bin/env python3
"""Converts site-src/block-list.md into an HTML fragment for the why-blocked
page. A mechanical converter rather than hand-transcription, because the
source has ~200 rows of citation links and a mistyped URL is invisible until
someone clicks it.

Handles exactly the subset of markdown this one document uses: '#'/'##'/'###'
headings, '---' rules (dropped — the surrounding headings already separate
sections), pipe tables with a header row, '-'/'1.' lists, and inline
'**bold**', '[text](url)' and bare 'https://...' links within table cells,
list items and paragraphs. Not a general markdown parser.
"""

from __future__ import annotations

import html
import pathlib
import re

SOURCE = pathlib.Path(__file__).resolve().parent.parent / "site-src" / "block-list.md"


def inline(text: str) -> str:
    text = html.escape(text.strip(), quote=False)
    # Code spans, before every other pass. The document names about a hundred
    # and thirty Android packages, and a package id is full of dots and
    # underscores that must not be re-punctuated on the way to the page; doing
    # this first also means nothing inside a span can be read as emphasis.
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    # Bare URLs first, and only ones not already the target of a markdown
    # link — otherwise the second pass would re-wrap the href value itself.
    text = re.sub(
        r"(?<!\]\()(https?://\S+)",
        lambda m: f'<a href="{m.group(1)}">{m.group(1)}</a>',
        text,
    )
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"\[([^\]]+)\]\((https?://[^\s)]+)\)", r'<a href="\2">\1</a>', text)
    # Single-asterisk italics, after bold so **x** has already lost its
    # asterisks and can't be mistaken for one.
    text = re.sub(r"\*([^*]+?)\*", r"<em>\1</em>", text)
    return text


def split_row(line: str) -> list[str]:
    line = line.strip()
    if line.startswith("|"):
        line = line[1:]
    if line.endswith("|"):
        line = line[:-1]
    cells: list[str] = []
    current = []
    depth = 0  # bracket depth, so a ']' inside a markdown link doesn't split early
    i = 0
    while i < len(line):
        ch = line[i]
        if ch == "[":
            depth += 1
        elif ch == "]":
            depth = max(0, depth - 1)
        if ch == "|" and depth == 0:
            cells.append("".join(current))
            current = []
        else:
            current.append(ch)
        i += 1
    cells.append("".join(current))
    return [c.strip() for c in cells]


def is_separator_row(cells: list[str]) -> bool:
    return all(re.fullmatch(r":?-{2,}:?", c.strip()) for c in cells if c.strip())


def render_table(header: list[str], rows: list[list[str]], caption: str | None) -> str:
    out = ['<div class="table-scroll">', "<table>"]
    if caption:
        out.append(f"<caption>{inline(caption)}</caption>")
    out.append("<thead><tr>")
    for h in header:
        out.append(f"<th>{inline(h)}</th>")
    out.append("</tr></thead>")
    out.append("<tbody>")
    for row in rows:
        out.append("<tr>")
        for cell in row:
            out.append(f"<td>{inline(cell)}</td>")
        out.append("</tr>")
    out.append("</tbody></table></div>")
    return "\n".join(out)


def build_fragment() -> str:
    lines = SOURCE.read_text(encoding="utf-8").splitlines()
    out: list[str] = []
    toc: list[tuple[str, str]] = []  # (anchor, label) for h2s

    i = 0
    pending_caption: str | None = None

    def slugify(text: str) -> str:
        s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
        return s

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if stripped == "---":
            i += 1
            continue

        if stripped.startswith("### "):
            heading = stripped[4:].strip()
            out.append(f"<h3>{inline(heading)}</h3>")
            i += 1
            continue

        if stripped.startswith("## "):
            heading = stripped[3:].strip()
            anchor = slugify(heading)
            out.append(f'<h2 id="{anchor}">{inline(heading)}</h2>')
            toc.append((anchor, heading))
            i += 1
            continue

        if stripped.startswith("# "):
            i += 1
            continue

        if stripped.startswith("|"):
            # Table: header row, separator row, then data rows.
            header = split_row(lines[i])
            i += 1
            if i < len(lines) and lines[i].strip().startswith("|") and is_separator_row(split_row(lines[i])):
                i += 1
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(split_row(lines[i]))
                i += 1
            out.append(render_table(header, rows, pending_caption))
            pending_caption = None
            continue

        if stripped == "":
            i += 1
            continue

        bullet_re = re.compile(r"^-\s+(.*)")
        numbered_re = re.compile(r"^\d+\.\s+(.*)")
        marker = bullet_re.match(stripped) or numbered_re.match(stripped)
        if marker:
            tag = "ul" if bullet_re.match(stripped) else "ol"
            pattern = bullet_re if tag == "ul" else numbered_re
            items: list[str] = []
            while True:
                m = pattern.match(lines[i].strip()) if i < len(lines) else None
                if not m:
                    break
                item_lines = [m.group(1)]
                i += 1
                # Continuation lines belonging to this item: non-blank, not a
                # new list item, not the start of a new block.
                while (
                    i < len(lines)
                    and lines[i].strip()
                    and not pattern.match(lines[i].strip())
                    and not lines[i].strip().startswith(("#", "|", "---"))
                ):
                    item_lines.append(lines[i].strip())
                    i += 1
                items.append(" ".join(item_lines))
                # A single blank line between items still belongs to the same
                # list, provided another item follows; two blank lines (or a
                # new block) end it.
                if i < len(lines) and lines[i].strip() == "":
                    j = i + 1
                    if j < len(lines) and pattern.match(lines[j].strip()):
                        i = j
                        continue
                break
            lis = "\n".join(f"<li>{inline(item)}</li>" for item in items)
            out.append(f"<{tag}>\n{lis}\n</{tag}>")
            continue

        # Plain paragraph (possibly spanning to the next blank line).
        para_lines = [stripped]
        i += 1
        while i < len(lines) and lines[i].strip() and not lines[i].strip().startswith(("#", "|", "---")):
            para_lines.append(lines[i].strip())
            i += 1
        out.append(f"<p>{inline(' '.join(para_lines))}</p>")

    # The heading and the summary above it are page chrome, and the same
    # summary is shown in three languages, so build-site.py owns them. This
    # returns only the table of contents and the converted document.
    toc_html = "\n".join(f'<a href="#{a}">{html.escape(label)}</a>' for a, label in toc)
    return f'<nav class="toc">{toc_html}</nav>\n' + "\n".join(out)


if __name__ == "__main__":
    print(build_fragment()[:2000])
