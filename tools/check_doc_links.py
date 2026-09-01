#!/usr/bin/env python3
"""Verify every documentation link resolves -- in the markdown and in the app.

Three kinds of link have broken silently here before:

  1. Cross-file links to a page that no longer exists.
  2. Anchor links to a heading that was renamed (the anchor is derived from the
     heading text, so any edit to a heading breaks every link into it).
  3. In-app "Documentation" hyperlinks. These are QpcatDocLinks call sites in
     the Java that open GitHub in the user's browser, so a stale one is
     invisible until a user clicks it and lands on the wrong page.

Exit 1 on any break, so it can gate a push.
"""

import os
import re
import sys

DOC_DIR = "documentation"
JAVA_DIR = "src/main/java"


def github_anchor(heading):
    """GitHub's slug for a heading: strip formatting, lowercase, hyphenate."""
    text = re.sub(r"`([^`]*)`", r"\1", heading)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"[*_]", "", text)
    text = text.lower()
    text = re.sub(r"[^\w\s-]", "", text)
    return text.strip().replace(" ", "-")


_anchor_cache = {}


def anchors_of(path):
    """Every anchor a markdown file defines, or None when it is not a file."""
    real = os.path.realpath(path)
    if real in _anchor_cache:
        return _anchor_cache[real]
    if not os.path.isfile(path):
        _anchor_cache[real] = None
        return None
    found = set()
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = re.match(r"^#{1,6}\s+(.*?)\s*$", line)
            if m:
                found.add(github_anchor(m.group(1)))
            # Explicit HTML anchors. These are how the docs pin a stable target
            # for a heading whose wording is expected to change, so a checker
            # that only reads headings calls every one of them broken.
            found.update(re.findall(r"<a\s+(?:name|id)=\"([^\"]+)\"", line))
    _anchor_cache[real] = found
    return found


def markdown_sources():
    """Every markdown file whose links we check."""
    out = []
    for root, _dirs, files in os.walk(DOC_DIR):
        out += [os.path.join(root, f) for f in files if f.endswith(".md")]
    out += [f for f in ("README.md", "CHANGELOG.md") if os.path.isfile(f)]
    return sorted(out)


LINK = re.compile(r"\]\(\s*([^)\s#]+)?(?:#([^)\s]+))?\s*\)")
SKIP_SCHEMES = ("http://", "https://", "mailto:", "ftp://")


def check_markdown():
    problems = []
    for path in markdown_sources():
        base = os.path.dirname(path) or "."
        with open(path, encoding="utf-8", errors="replace") as fh:
            for num, line in enumerate(fh, 1):
                for m in LINK.finditer(line):
                    target, anchor = m.group(1), m.group(2)
                    if target and target.startswith(SKIP_SCHEMES):
                        continue
                    # Resolve against the file that contains the link.
                    resolved = path if not target else os.path.normpath(
                        os.path.join(base, target))
                    if not os.path.exists(resolved):
                        problems.append((path, num, target or path, anchor, "file not found"))
                        continue
                    if not anchor:
                        continue
                    anchors = anchors_of(resolved)
                    if anchors is None:
                        continue  # anchor into a non-markdown file: nothing to check
                    if anchor not in anchors:
                        problems.append((path, num, target or os.path.basename(path),
                                         anchor, "anchor not found"))
    return problems


# Whole-file scan: these calls routinely wrap across lines, so a line-at-a-time
# regex sees only the link TEXT and reports it as a bogus anchor.
JAVA_CALL = re.compile(
    r"QpcatDocLinks\.(?P<helper>page|linkBar|pageUrl)\s*\(\s*"
    r"(?P<args>(?:\"(?:[^\"\\]|\\.)*\"\s*,?\s*)+)\)",
    re.DOTALL,
)
STRING = re.compile(r"\"((?:[^\"\\]|\\.)*)\"")


def check_java():
    """In-app links. Every call names its page, so find the .md argument and take
    whatever follows it as the anchor -- that holds for page(file, anchor),
    page(text, file, anchor), linkBar(file, anchor) and pageUrl(file) alike."""
    problems = []
    if not os.path.isdir(JAVA_DIR):
        return problems
    for root, _dirs, files in os.walk(JAVA_DIR):
        for name in sorted(files):
            if not name.endswith(".java") or name == "QpcatDocLinks.java":
                continue
            path = os.path.join(root, name)
            text = open(path, encoding="utf-8", errors="replace").read()
            for m in JAVA_CALL.finditer(text):
                args = STRING.findall(m.group("args"))
                md = [i for i, a in enumerate(args) if a.endswith(".md")]
                if not md:
                    continue
                i = md[0]
                page = os.path.join(DOC_DIR, args[i])
                anchor = args[i + 1] if i + 1 < len(args) else None
                line = text.count("\n", 0, m.start()) + 1
                anchors = anchors_of(page)
                if anchors is None:
                    problems.append((path, line, page, anchor, "in-app page not found"))
                elif anchor and anchor not in anchors:
                    problems.append((path, line, page, anchor, "in-app anchor not found"))
    return problems


# Anchors the results dialog appends to DOCS_BASE at runtime
# (`DOCS_BASE + "#" + docAnchor`). Concatenated, so the call-site scan above
# cannot see them -- but they are the links every results tab shows, so they get
# their own check rather than being silently uncovered.
CONCAT_PAGES = {
    "DOCS_BASE": "results.md",
    "BEST_PRACTICES_BASE": "clustering.md",
}
CONCAT_ANCHOR = re.compile(r"\"([a-z0-9]+(?:-[a-z0-9]+)+)\"")


def check_concatenated_anchors():
    """Verify the anchor literals the results dialog appends to a base URL."""
    problems = []
    path = os.path.join(JAVA_DIR, "qupath/ext/qpcat/ui/ClusteringDialog.java")
    if not os.path.isfile(path):
        return problems
    text = open(path, encoding="utf-8", errors="replace").read()
    for const, page_name in CONCAT_PAGES.items():
        if const + " + \"#\"" not in text:
            continue
        page = os.path.join(DOC_DIR, page_name)
        anchors = anchors_of(page)
        if anchors is None:
            problems.append((path, 1, page, None, "concatenated-link page not found"))
            continue
        # Any anchor-shaped literal that names a tab or an algorithm caution.
        for m in CONCAT_ANCHOR.finditer(text):
            anchor = m.group(1)
            if not (anchor.endswith("-tab") or anchor.endswith("-tabs")
                    or anchor.startswith("caution-")):
                continue
            target = "clustering.md" if anchor.startswith("caution-") else page_name
            target_anchors = anchors_of(os.path.join(DOC_DIR, target))
            if target_anchors is not None and anchor not in target_anchors:
                line = text.count("\n", 0, m.start()) + 1
                problems.append((path, line, target, anchor, "results-tab anchor not found"))
        break
    return problems


def main():
    problems = check_markdown() + check_java() + check_concatenated_anchors()
    if not problems:
        print("[doc-links] OK -- every documentation link resolves")
        return 0
    print("[doc-links] %d broken link(s):" % len(problems))
    for path, num, page, anchor, why in problems:
        suffix = "#" + anchor if anchor else ""
        print("  %s:%d -> %s%s  (%s)" % (path, num, page, suffix, why))
    return 1


if __name__ == "__main__":
    sys.exit(main())
