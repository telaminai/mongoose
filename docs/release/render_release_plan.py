#!/usr/bin/env python3
"""Render the release plan through local headless Chrome, as the existing PDF was built.

Requires Python's markdown-it-py package and Chrome/Chromium. No remote renderer or browser profile is used.
Usage: python docs/release/render_release_plan.py [markdown-file] [pdf-file]
"""
import argparse
import html
from pathlib import Path
import shutil
import subprocess
import tempfile

from markdown_it import MarkdownIt


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path, default=root / "docs/release/release-plan-1.0.33.md")
    parser.add_argument("output", nargs="?", type=Path, default=root / "docs/release/release-plan-1.0.33.pdf")
    args = parser.parse_args()
    browser = next((p for name in ("google-chrome", "chromium", "chromium-browser") if (p := shutil.which(name))), None)
    if browser is None:
        browser = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    if not Path(browser).is_file():
        parser.error("Chrome/Chromium is required")
    source = args.source.read_text(encoding="utf-8")
    title = source.splitlines()[0].lstrip("# ")
    body = MarkdownIt("commonmark").enable("table").render(source)
    style = """
@page { size: A4; margin: 16mm; }
* { box-sizing: border-box; }
body { margin: 0; color: #202020; font: 10pt/1.43 'Helvetica Neue', Arial, sans-serif; }
h1 { font-size: 17pt; line-height: 1.35; border-bottom: 1.5px solid #333; padding-bottom: 6pt; }
h2 { font-size: 12pt; border-bottom: 1px solid #bbb; padding-bottom: 2pt; margin-top: 16pt; }
h3 { font-size: 10.5pt; margin-top: 9pt; }
h1, h2, h3 { break-after: avoid; }
p { margin: 7pt 0; }
a { color: inherit; text-decoration: none; overflow-wrap: anywhere; }
code { font: 8.6pt Menlo, monospace; background: #f1f1f1; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; }
table { border-collapse: collapse; width: 100%; margin: 9pt 0; font-size: 8.7pt; table-layout: fixed; }
th, td { border: 1px solid #bdbdbd; padding: 4pt; text-align: left; vertical-align: top; overflow-wrap: anywhere; }
th { background: #ececec; }
thead { display: table-header-group; }
tr { break-inside: avoid; }
table:first-of-type th:nth-child(1) { width: 14%; }
table:first-of-type th:nth-child(2) { width: 16%; }
table:first-of-type th:nth-child(3) { width: 14%; }
table:first-of-type th:nth-child(4) { width: 56%; }
table:nth-of-type(2) th:first-child { width: 35%; }
table:nth-of-type(2) th:last-child { width: 65%; }
table:nth-of-type(3) th:first-child { width: 45%; }
table:nth-of-type(3) th:nth-child(2) { width: 17%; }
table:nth-of-type(3) th:last-child { width: 38%; }
li { margin: 3pt 0; }
"""
    document = '<!doctype html><html><head><meta charset="utf-8"><title>' + html.escape(title)
    document += '</title><style>' + style + '</style></head><body>' + body + '</body></html>'
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="mongoose-release-pdf-") as temporary:
        folder = Path(temporary)
        page = folder / "release-plan.html"
        page.write_text(document, encoding="utf-8")
        subprocess.run([browser, "--headless", "--disable-gpu", "--no-pdf-header-footer",
                        "--disable-extensions", "--no-first-run", "--no-default-browser-check",
                        "--user-data-dir=" + str(folder / "profile"), "--print-to-pdf=" + str(output),
                        page.as_uri()], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=60)


if __name__ == "__main__":
    main()
