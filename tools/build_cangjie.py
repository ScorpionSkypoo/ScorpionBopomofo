#!/usr/bin/env python3
"""
Build the cangjie table (倉頡／速成 codes) in scorpio_dict.db.gz from Unicode Unihan kCangjie (Cangjie version 3).

Source: https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip → Unihan_DictionaryLikeData.txt (Unicode License v3)

Columns:
  code   full cangjie code, lowercase (日 = a)
  quick  速成 code: first + last letter of the cangjie code (the code itself when it is one letter)
  char   the character (BMP only; supplementary-plane characters render as tofu on phones)
  freq   log10 of the summed frequency of every dict word containing the character; -12 when unknown

Usage: python3 tools/build_cangjie.py <Unihan_DictionaryLikeData.txt>
"""

import gzip
import math
import os
import shutil
import sqlite3
import sys
import tempfile

DB_GZ_PATH = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'scorpio_dict.db.gz')


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    codes = []
    with open(sys.argv[1], encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or '\tkCangjie\t' not in line:
                continue
            cp, _, code = line.rstrip('\n').split('\t')
            n = int(cp[2:], 16)
            if n >= 0x10000:
                continue
            code = code.strip().lower()
            if code and code.isalpha():
                codes.append((chr(n), code))

    tmp_dir = tempfile.mkdtemp()
    db_path = os.path.join(tmp_dir, 'scorpio_dict.db')
    try:
        with gzip.open(DB_GZ_PATH, 'rb') as src, open(db_path, 'wb') as dst:
            shutil.copyfileobj(src, dst)
        conn = sqlite3.connect(db_path)
        # 字頻：所有含這個字的詞的頻率加總（單字項目在詞庫裡最高只到 -1，常用字會同分）
        mass = {}
        for word, score in conn.execute("SELECT word, score FROM dict"):
            p = 10 ** score
            for ch in word:
                mass[ch] = mass.get(ch, 0.0) + p
        freq = {ch: math.log10(m) for ch, m in mass.items() if m > 0}
        rows = []
        for ch, code in codes:
            quick = code if len(code) == 1 else code[0] + code[-1]
            rows.append((code, quick, ch, freq.get(ch, -12.0)))
        conn.execute("DROP TABLE IF EXISTS cangjie")
        conn.execute("CREATE TABLE cangjie (code TEXT NOT NULL, quick TEXT NOT NULL, char TEXT NOT NULL, freq REAL NOT NULL)")
        conn.executemany("INSERT INTO cangjie (code, quick, char, freq) VALUES (?, ?, ?, ?)", rows)
        conn.commit()
        conn.execute("VACUUM")
        known = sum(1 for r in rows if r[3] > -12)
        conn.close()
        with open(db_path, 'rb') as src, gzip.open(DB_GZ_PATH, 'wb', compresslevel=9) as dst:
            shutil.copyfileobj(src, dst)
        print(f"cangjie: {len(rows)} characters ({known} with dict frequency)")
    finally:
        shutil.rmtree(tmp_dir)


if __name__ == '__main__':
    main()
