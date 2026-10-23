#!/usr/bin/env python3
"""
Rebuild emoji_dict (keyword -> emoji) in scorpio_dict.db.gz from Unicode CLDR Traditional Chinese annotations.

Source: unicode-org/cldr release-46 common/annotations/zh_Hant.xml (Unicode License v3)
  https://raw.githubusercontent.com/unicode-org/cldr/release-46/common/annotations/zh_Hant.xml

Priority (lower = shown first):
  1. hand-curated rows already in the table (kept as-is, e.g. 狗 -> 🐶 🐕 🐩)
  2. the keyword is the emoji's spoken name (tts), e.g. 老鼠 -> 🐀
  3. other keywords, more specific first (a keyword shared by fewer emoji ranks higher)
Keywords shared by more than MAX_SHARED emoji (動物, 臉, 符號…) are dropped as too generic.

Usage: python3 tools/build_emoji_dict.py <zh_Hant.xml>
"""

import gzip
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import xml.etree.ElementTree as ET

DB_GZ_PATH = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'scorpio_dict.db.gz')

MAX_SHARED = 25
HAN_RE = re.compile(r'[㐀-鿿]')

# Hand-picked additions (shown first)
EXTRA = {
    '台灣': ['🇹🇼', '🧋'],
    '開心': ['😄', '😊', '🥰'],
    '高興': ['😄', '😊'],
    '難過': ['😢', '😭'],
}

# Emoji 16.0 (2024-09) — not in the One UI / Noto fonts on current phones yet, would render as tofu
TOO_NEW = {'\U0001FAE9', '\U0001FAC6', '\U0001FABE', '\U0001FADC', '\U0001FA89', '\U0001FA8F', '\U0001FADF'}


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)

    names, keywords = {}, {}
    for a in ET.parse(sys.argv[1]).getroot().iter('annotation'):
        cp, text = a.get('cp'), (a.text or '').strip()
        if not cp or not text or any(ch in TOO_NEW for ch in cp):
            continue
        # BMP symbols (☂ ☕ ✂) default to text style; FE0F asks for the colour emoji glyph
        if len(cp) == 1 and ord(cp) < 0x1F000:
            cp += '\ufe0f'

        if a.get('type') == 'tts':
            names[cp] = text
        else:
            keywords[cp] = [k.strip() for k in text.split('|') if k.strip()]

    shared = {}
    for cp, kws in keywords.items():
        for k in set(kws):
            shared[k] = shared.get(k, 0) + 1

    rows = {}  # (keyword, emoji) -> priority

    def put(k, e, p):
        if not HAN_RE.search(k):
            return
        if (k, e) not in rows or p < rows[(k, e)]:
            rows[(k, e)] = p

    for cp, name in names.items():
        put(name, cp, 10)
    for cp, kws in keywords.items():
        for k in kws:
            n = shared.get(k, 1)
            if n > MAX_SHARED:
                continue
            put(k, cp, 20 + n)

    tmp_dir = tempfile.mkdtemp()
    db_path = os.path.join(tmp_dir, 'scorpio_dict.db')
    try:
        with gzip.open(DB_GZ_PATH, 'rb') as src, open(db_path, 'wb') as dst:
            shutil.copyfileobj(src, dst)
        conn = sqlite3.connect(db_path)
        curated = conn.execute("SELECT keyword, emoji, priority FROM emoji_dict WHERE priority < 10").fetchall()
        before = conn.execute("SELECT count(*) FROM emoji_dict").fetchone()[0]
        # Curated rows win; drop CLDR duplicates that differ only by the FE0F selector (☕ vs ☕️)
        curated_bare = {(k, e.replace('\ufe0f', '')) for k, e, _ in curated}
        rows = {(k, e): p for (k, e), p in rows.items() if (k, e.replace('\ufe0f', '')) not in curated_bare}
        for k, e, p in curated:
            rows[(k, e)] = p
        for k, ems in EXTRA.items():
            for i, e in enumerate(ems):
                rows[(k, e)] = min(rows.get((k, e), 99), i)
        conn.execute("DELETE FROM emoji_dict")
        conn.executemany("INSERT INTO emoji_dict (keyword, emoji, priority) VALUES (?, ?, ?)",
                         sorted(((k, e, p) for (k, e), p in rows.items()), key=lambda r: (r[0], r[2])))
        conn.commit()
        conn.execute("VACUUM")
        n_kw = conn.execute("SELECT count(DISTINCT keyword) FROM emoji_dict").fetchone()[0]
        n_em = conn.execute("SELECT count(DISTINCT emoji) FROM emoji_dict").fetchone()[0]
        conn.close()
        with open(db_path, 'rb') as src, gzip.open(DB_GZ_PATH, 'wb', compresslevel=9) as dst:
            shutil.copyfileobj(src, dst)
        print(f"emoji_dict rebuilt: {len(rows)} rows, {n_kw} keywords, {n_em} emoji (was {before} rows; kept {len(curated)} curated)")
    finally:
        shutil.rmtree(tmp_dir)


if __name__ == '__main__':
    main()
