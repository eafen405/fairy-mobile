#!/usr/bin/env python3
"""Build the app title font: Noto Sans SC, wght=900, offline-subsetted.

Downloads (or accepts a path to) the Noto Sans SC variable font from
google/fonts (`ofl/notosanssc/NotoSansSC[wght].ttf`), instantiates wght=900
with fontTools.varLib.instancer, then subsets to:

  - ASCII printable (U+0020-U+007E)
  - CJK punctuation (U+3000-U+303F, U+FF00-U+FFEF)
  - GB2312 level-1 hanzi (3755 chars; decoded via the gb2312 codec,
    EUC rows 0xB0-0xD7)
  - every character appearing in app/src/main/res/values*/strings*.xml

Output: app/src/main/res/font/title_black.ttf (target <= 1.5 MB).
The script exits non-zero if any required character lacks a cmap entry.

Usage:
    python3 scripts/subset_title_font.py [--source PATH]

Requires: fontTools (`pip install fontTools`). Re-runnable.
"""

import argparse
import os
import sys
import urllib.request
import xml.etree.ElementTree as ET

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

GOOGLE_FONTS_RAW = (
    "https://raw.githubusercontent.com/google/fonts/main"
    "/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf"
)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUTPUT_FONT = os.path.join(REPO_ROOT, "app", "src", "main", "res", "font", "title_black.ttf")
RES_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "res")
SIZE_LIMIT_BYTES = 1_500_000


def fetch_source_font(path: str | None) -> str:
    if path:
        return path
    cache = os.path.join(
        os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache")),
        "fairy-fonts",
        "NotoSansSC-wght.ttf",
    )
    if not os.path.exists(cache):
        os.makedirs(os.path.dirname(cache), exist_ok=True)
        print(f"downloading {GOOGLE_FONTS_RAW}")
        urllib.request.urlretrieve(GOOGLE_FONTS_RAW, cache)
    return cache


def gb2312_level1_hanzi() -> set[str]:
    """GB2312 level-1 hanzi: EUC-CN rows 0xB0-0xD7 (rows 16-55), 3755 chars."""
    chars: set[str] = set()
    for high in range(0xB0, 0xD7 + 1):
        for low in range(0xA1, 0xFE + 1):
            try:
                chars.add(bytes([high, low]).decode("gb2312"))
            except UnicodeDecodeError:
                pass
    return chars


def string_resource_chars() -> set[str]:
    chars: set[str] = set()
    for entry in sorted(os.listdir(RES_DIR)):
        if not entry.startswith("values"):
            continue
        directory = os.path.join(RES_DIR, entry)
        if not os.path.isdir(directory):
            continue
        for name in sorted(os.listdir(directory)):
            if not name.endswith(".xml"):
                continue
            try:
                root = ET.parse(os.path.join(directory, name)).getroot()
            except ET.ParseError:
                continue
            for element in root.iter():
                for node in (element, *element.iter()):
                    if node.text:
                        chars.update(node.text)
                    if node.tail:
                        chars.update(node.tail)
    return chars


def wanted_codepoints() -> set[int]:
    cps = set(range(0x20, 0x7F))
    cps.update(range(0x3000, 0x3040))
    cps.update(range(0xFF00, 0xFFF0))
    cps.update(ord(c) for c in gb2312_level1_hanzi())
    resource_chars = string_resource_chars()
    cps.update(ord(c) for c in resource_chars if ord(c) >= 0x20)
    return cps, resource_chars


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        help="path to a local NotoSansSC[wght].ttf; downloaded when omitted",
    )
    args = parser.parse_args()

    source_path = fetch_source_font(args.source)
    print(f"source: {source_path} ({os.path.getsize(source_path)} bytes)")

    font = TTFont(source_path)
    if "fvar" in font:
        font = instancer.instantiateVariableFont(font, {"wght": 900})
        print("instantiated wght=900")
    source_cmap = font.getBestCmap()

    cps, resource_chars = wanted_codepoints()
    print(f"subset codepoints requested: {len(cps)}")

    options = subset.Options()
    options.name_IDs = ["*"]
    options.hinting = False
    options.desubroutinize = True
    options.glyph_names = False
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=sorted(cps))
    subsetter.subset(font)

    cmap = font.getBestCmap()
    # Assert coverage for every string-resource char the source font encodes.
    # Chars the SC font cannot encode (Hangul, Arabic, ...) remain uncovered and
    # fall back to the system font at render time.
    coverable = {c for c in resource_chars if ord(c) >= 0x20 and ord(c) in source_cmap}
    missing = [c for c in coverable if ord(c) not in cmap]
    if missing:
        print(f"ERROR: {len(missing)} string-resource chars missing: {missing!r}")
        return 1
    skipped = {c for c in resource_chars if ord(c) >= 0x20 and ord(c) not in source_cmap}
    print(
        f"resource chars: {len(coverable)} covered, {len(skipped)} unencodable "
        "(system-font fallback)"
    )

    os.makedirs(os.path.dirname(OUTPUT_FONT), exist_ok=True)
    font.save(OUTPUT_FONT)
    size = os.path.getsize(OUTPUT_FONT)
    print(f"wrote {OUTPUT_FONT} ({size} bytes)")
    if size > SIZE_LIMIT_BYTES:
        print(f"ERROR: exceeds {SIZE_LIMIT_BYTES} byte budget")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
