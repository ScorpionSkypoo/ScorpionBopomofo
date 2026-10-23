#!/usr/bin/env python3
"""
顏文字：w33ble/emoticon-data（MIT License，Copyright (c) 2014 Joe Fleming）
  https://github.com/w33ble/emoticon-data

把英文標籤對應到中文分類，每個分類一個注音讀音（不含聲調，打「ㄎㄞ ㄒㄧㄣ」就穿插開心類顏文字），
重建 kaomoji_dict 表。priority 前面幾位是分類順序（展開面板的分頁依此排），分類內照原始資料的順序，
太短（沒有臉的輪廓）或太長的排到後面。含簡體字的顏文字（用 OpenCC s2t 轉換後會變的）整個略過。

用法：build_kaomoji.py <scorpio_dict.db（解壓後，就地更新）> [emoticons.json]
  沒給 emoticons.json 就從固定版本下載
"""
import json
import re
import sqlite3
import sys
import urllib.request

import opencc

SOURCE = ("https://raw.githubusercontent.com/w33ble/emoticon-data/"
          "92b6211ec2a93e14052e0e572d697d4d06c71868/emoticons.json")

# 分類（依面板分頁順序）、讀音、對應的英文標籤
CATEGORIES = [
    ("開心", "ㄎㄞ-ㄒㄧㄣ", ["happy", "laugh", "fun", "excited"]),
    ("喜歡", "ㄒㄧ-ㄏㄨㄢ", ["love"]),
    ("親親抱抱", "ㄑㄧㄣ-ㄑㄧㄣ", ["kiss", "hug"]),
    ("打招呼", "ㄉㄚ-ㄓㄠ-ㄏㄨ", ["wave"]),
    ("害羞", "ㄏㄞ-ㄒㄧㄡ", ["embarassed", "hide"]),
    ("眨眼", "ㄓㄚ-ㄧㄢ", ["wink"]),
    ("得意", "ㄉㄜ-ㄧ", ["smug", "troll"]),
    ("哭泣", "ㄎㄨ-ㄑㄧ", ["cry", "sad", "hurt"]),
    ("生氣", "ㄕㄥ-ㄑㄧ", ["angry", "evil"]),
    ("翻桌", "ㄈㄢ-ㄓㄨㄛ", ["table flip"]),
    ("驚訝", "ㄐㄧㄥ-ㄧㄚ", ["surprised", "scared"]),
    ("疑惑", "ㄧ-ㄏㄨㄛ", ["confused", "think"]),
    ("擔心", "ㄉㄢ-ㄒㄧㄣ", ["worried"]),
    ("無奈", "ㄨ-ㄋㄞ", ["whatever"]),
    ("投降", "ㄊㄡ-ㄒㄧㄤ", ["surrender"]),
    ("抱歉", "ㄅㄠ-ㄑㄧㄢ", ["sorry"]),
    ("崩潰", "ㄅㄥ-ㄎㄨㄟ", ["crazy"]),
    ("陣亡", "ㄓㄣ-ㄨㄤ", ["dead"]),
    ("盯著", "ㄉㄧㄥ-ㄓㄜ", ["stare"]),
    ("睡覺", "ㄕㄨㄟ-ㄐㄧㄠ", ["sleep"]),
    ("跳舞", "ㄊㄧㄠ-ㄨ", ["dance", "music"]),
    ("逃跑", "ㄊㄠ-ㄆㄠ", ["run"]),
    ("寫字", "ㄒㄧㄝ-ㄗ", ["write"]),
    ("貓咪", "ㄇㄠ-ㄇㄧ", ["cat"]),
    ("熊熊", "ㄒㄩㄥ-ㄒㄩㄥ", ["bear"]),
    ("兔兔", "ㄊㄨ-ㄊㄨ", ["rabbit"]),
    ("狗狗", "ㄍㄡ-ㄍㄡ", ["dog"]),
    ("動物", "ㄉㄨㄥ-ㄨ", ["animal", "bird", "sea creature", "pig", "monkey"]),
]
MAX_LEN = 24  # 太長的放不進候選列
s2t = opencc.OpenCC("s2t")
PRIVATE_USE = re.compile("[\ue000-\uf8ff]")  # 私用區字元，手機上只會顯示方格


def rank_key(item):
    index, s = item
    return (0 if 5 <= len(s) <= 16 else 1, index)


db_path = sys.argv[1]
if len(sys.argv) > 2:
    data = json.load(open(sys.argv[2], encoding="utf-8"))
else:
    data = json.load(urllib.request.urlopen(SOURCE, timeout=60))

rows = []
for ci, (name, reading, tags) in enumerate(CATEGORIES):
    items = []
    seen = set()
    for index, e in enumerate(data["emoticons"]):
        s = e["string"].strip()
        if not s or "\n" in s or len(s) > MAX_LEN or s in seen:
            continue
        if s2t.convert(s) != s or PRIVATE_USE.search(s):
            continue
        if any(t in tags for t in e["tags"]):
            seen.add(s)
            items.append((index, s))
    items.sort(key=rank_key)
    for rank, (_, s) in enumerate(items):
        rows.append((reading, s, name, ci * 10000 + rank))

db = sqlite3.connect(db_path)
db.execute("DELETE FROM kaomoji_dict")
db.executemany("INSERT INTO kaomoji_dict (reading, kaomoji, category, priority) VALUES (?,?,?,?)", rows)
db.commit()
for name, n in db.execute("SELECT category, count(*) FROM kaomoji_dict GROUP BY category ORDER BY MIN(priority)"):
    print(name, n)
print("共", len(rows), "筆，", len({r[1] for r in rows}), "個不重複顏文字")
db.execute("VACUUM")
db.close()
