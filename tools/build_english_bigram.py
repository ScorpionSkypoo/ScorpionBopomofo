#!/usr/bin/env python3
"""
Build english_bigram (prev -> next word predictions) in scorpio_dict.db.gz from English subtitle dialogue.

Source: OPUS OpenSubtitles v2018 English monolingual text (same corpus family as the english_dict word list)
  https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/en.txt.gz
Only the first N lines are needed; stream them in:

  curl -sL <url> | gunzip | head -n 12000000 | python3 tools/build_english_bigram.py

Both words must be in english_dict. For each prev word keep the TOP_N most frequent next words seen at least MIN_COUNT times.
score = round(1000 * log10(count)), higher first.
"""

import collections
import gzip
import math
import os
import re
import shutil
import sqlite3
import sys
import tempfile

DB_GZ_PATH = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'scorpio_dict.db.gz')

TOP_N = 8
MIN_COUNT = 5
TOKEN_RE = re.compile(r"[a-z]+(?:'[a-z]+)?")


def main():
    tmp_dir = tempfile.mkdtemp()
    db_path = os.path.join(tmp_dir, 'scorpio_dict.db')
    try:
        with gzip.open(DB_GZ_PATH, 'rb') as src, open(db_path, 'wb') as dst:
            shutil.copyfileobj(src, dst)
        conn = sqlite3.connect(db_path)
        vocab = {w for (w,) in conn.execute("SELECT word FROM english_dict")}

        counts = collections.Counter()
        for n, line in enumerate(sys.stdin):
            ws = TOKEN_RE.findall(line.lower())
            for a, b in zip(ws, ws[1:]):
                if a in vocab and b in vocab:
                    counts[(a, b)] += 1
            if n % 1_000_000 == 0:
                print(f"{n} lines, {len(counts)} pairs", file=sys.stderr, flush=True)

        by_prev = collections.defaultdict(list)
        for (a, b), c in counts.items():
            if c >= MIN_COUNT and a != b:
                by_prev[a].append((c, b))
        rows = []
        for a, lst in by_prev.items():
            lst.sort(reverse=True)
            for c, b in lst[:TOP_N]:
                rows.append((a, b, round(1000 * math.log10(c))))

        conn.execute("DROP TABLE IF EXISTS english_bigram")
        conn.execute("CREATE TABLE english_bigram (prev TEXT NOT NULL, next TEXT NOT NULL, score INTEGER NOT NULL)")
        conn.executemany("INSERT INTO english_bigram (prev, next, score) VALUES (?, ?, ?)", rows)
        conn.execute("CREATE INDEX idx_english_bigram_prev ON english_bigram (prev, score DESC)")
        conn.commit()
        conn.execute("VACUUM")
        conn.close()
        with open(db_path, 'rb') as src, gzip.open(DB_GZ_PATH, 'wb', compresslevel=9) as dst:
            shutil.copyfileobj(src, dst)
        print(f"english_bigram: {len(rows)} rows for {len(by_prev)} prev words", file=sys.stderr)
    finally:
        shutil.rmtree(tmp_dir)


if __name__ == '__main__':
    main()
