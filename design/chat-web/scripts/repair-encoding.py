"""Repair the mojibake left by a PowerShell Get-Content/Set-Content round trip.

The files are mixed: some typographic characters were written afterwards by tools
that write UTF-8 correctly, so a whole-file reverse decode would corrupt them.
These are the exact sequences the round trip produced (UTF-8 bytes read as cp1252),
replaced one by one. A BOM added by Set-Content is stripped too.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src"

PAIRS = {
    "\u00c2\u00b7": "\u00b7",              # Â· -> ·
    "\u00e2\u20ac\u2122": "\u2019",        # â€™ -> ’
    "\u00e2\u20ac\u0153": "\u201c",        # â€œ -> “
    "\u00e2\u20ac\u009d": "\u201d",        # â€<9d> -> ”
    "\u00e2\u2020\u2019": "\u2192",        # â†’ -> →
    "\u00e2\u20ac\u201d": "\u2014",        # â€” -> —
    "\u00e2\u20ac\u201c": "\u2013",        # â€“ -> –
    "\u00e2\u20ac\u00a6": "\u2026",        # â€¦ -> …
    "\u00e2\u20ac\u00a2": "\u2022",        # â€¢ -> •
}

changed = 0
for path in sorted(ROOT.rglob("*")):
    if path.suffix not in {".ts", ".tsx", ".css"}:
        continue
    text = path.read_text(encoding="utf-8")
    original = text
    text = text.lstrip("\ufeff")
    for bad, good in PAIRS.items():
        text = text.replace(bad, good)
    if text != original:
        path.write_text(text, encoding="utf-8", newline="\n")
        changed += 1
        print(f"fixed {path}")

print(f"{changed} file(s) repaired")

# anything left of the two mojibake lead bytes is worth seeing
for path in sorted(ROOT.rglob("*")):
    if path.suffix not in {".ts", ".tsx", ".css"}:
        continue
    text = path.read_text(encoding="utf-8")
    leftovers = sorted({c for c in text if c in "\u00c2\u00e2\u00c3\ufeff"})
    if leftovers:
        print("LEFTOVER", path, [hex(ord(c)) for c in leftovers])
