#!/usr/bin/env python3
"""
Rebuild the english_dict table in scorpio_dict.db.gz from a word-frequency list.

Source list: hermitdave/FrequencyWords en_50k.txt (OpenSubtitles 2018, CC-BY-SA 4.0)
  https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt
Words already in the old table (Google 10k web list) are kept; their rank is mapped onto a Zipf
frequency so web vocabulary missing from subtitles ("configurations", "mediawiki") still ranks sensibly.

score = round(1000 * log10(frequency)), e.g. "you" ≈ 7459, "scorpion" ≈ 3401.

Usage: python3 tools/build_english_dict.py <en_50k.txt>
"""

import gzip
import math
import os
import re
import shutil
import sqlite3
import sys
import tempfile

DB_GZ_PATH = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'scorpio_dict.db.gz')

WORD_RE = re.compile(r"^[a-z]+(?:'[a-z]+)?$")

# Subtitle tokenisation splits contractions ("don't" -> "don" + "t"); drop the orphan halves
FRAGMENTS = {
    's', 't', 'll', 've', 're', 'd', 'm', 'didn', 'doesn', 'isn', 'wasn', 'couldn', 'wouldn', 'shouldn',
    'hasn', 'haven', 'aren', 'weren', 'ain', 'mustn', 'needn', 'dont', 'im', 'ive', 'youre', 'didnt',
    'doesnt', 'isnt', 'wasnt', 'cant', 'wont', 'thats', 'whats', 'theres', 'lets',
}
SINGLE_LETTERS_OK = {'a', 'i'}

# Common contractions, ranked just under their most frequent stem
CONTRACTIONS = {
    "don't": 6.3, "i'm": 6.4, "it's": 6.4, "you're": 6.0, "that's": 6.1, "can't": 5.9, "i'll": 5.8,
    "didn't": 5.8, "what's": 5.7, "let's": 5.6, "he's": 5.6, "i've": 5.6, "there's": 5.5, "we're": 5.6,
    "won't": 5.4, "doesn't": 5.5, "isn't": 5.4, "she's": 5.3, "they're": 5.4, "wasn't": 5.2, "we'll": 5.1,
    "you'll": 5.1, "i'd": 5.2, "haven't": 5.0, "couldn't": 5.0, "wouldn't": 5.0, "aren't": 4.9,
    "you've": 4.9, "shouldn't": 4.6, "we've": 4.7, "they'll": 4.5, "who's": 4.6, "you'd": 4.6,
    "he'll": 4.4, "she'll": 4.1, "hasn't": 4.5, "weren't": 4.4, "they've": 4.3, "where's": 4.6,
    "how's": 4.1, "here's": 4.6, "ain't": 4.6, "y'all": 4.0,
}

ZIPF_C = 2.9e7  # frequency of the rank-1 word in the subtitle list


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)

    scores = {}
    with open(sys.argv[1], encoding='utf-8') as f:
        for line in f:
            parts = line.split()
            if len(parts) != 2 or not parts[1].isdigit():
                continue
            w, freq = parts[0], int(parts[1])
            if not WORD_RE.match(w) or w in FRAGMENTS:
                continue
            if len(w) == 1 and w not in SINGLE_LETTERS_OK:
                continue
            scores[w] = round(1000 * math.log10(max(freq, 1)))
    scores['don'] = min(scores.get('don', 0), 3000)  # inflated by "don't"

    for w, z in CONTRACTIONS.items():
        scores[w] = max(scores.get(w, 0), round(z * 1000))

    tmp_dir = tempfile.mkdtemp()
    db_path = os.path.join(tmp_dir, 'scorpio_dict.db')
    try:
        with gzip.open(DB_GZ_PATH, 'rb') as src, open(db_path, 'wb') as dst:
            shutil.copyfileobj(src, dst)

        conn = sqlite3.connect(db_path)
        old = conn.execute("SELECT word, score FROM english_dict ORDER BY score DESC").fetchall()
        if len(old) > 20000:
            sys.exit("english_dict already rebuilt; restore the original asset first (git checkout) to keep the web-rank source")
        for rank, (w, _) in enumerate(old, start=1):
            w = w.lower()
            if not WORD_RE.match(w) or w in FRAGMENTS:
                continue
            web = round(1000 * math.log10(ZIPF_C / rank))
            if web > scores.get(w, 0):
                scores[w] = web

        conn.execute("DELETE FROM english_dict")
        conn.executemany(
            "INSERT INTO english_dict (word, score) VALUES (?, ?)",
            sorted(scores.items(), key=lambda kv: -kv[1])
        )
        conn.commit()
        conn.execute("VACUUM")
        conn.close()

        with open(db_path, 'rb') as src, gzip.open(DB_GZ_PATH, 'wb', compresslevel=9) as dst:
            shutil.copyfileobj(src, dst)
        print(f"english_dict rebuilt: {len(scores)} words (was {len(old)})")
    finally:
        shutil.rmtree(tmp_dir)


if __name__ == '__main__':
    main()
