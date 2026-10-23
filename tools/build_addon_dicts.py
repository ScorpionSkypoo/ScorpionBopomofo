#!/usr/bin/env python3
"""
附加詞庫：流行與口語用語、台灣地名與路名、台灣景點地標、世界國家與城市、世界地標與地理

資料來源（皆為開放授權，名稱、來源與授權記在 tools/data/packs.json，會寫進 dict_packs 表給 App 顯示）：
  - 人工整理的流行與口語用語 tools/data/trendy.txt
  - 內政部戶政司「全國路名資料」（政府資料開放授權條款第 1 版）roads.csv
  - 交通部觀光署「景點 - 觀光資訊資料庫」AttractionList.json（政府資料開放授權條款第 1 版）
  - Unicode CLDR zh-Hant 地區名與時區城市名 terr.json、tz.json（Unicode License）
  - Wikidata 人口 40 萬以上城市、地標與地理 cities.csv、landmarks.csv（CC0）

讀音以主詞庫推得：詞內能對到主詞庫的多字詞就用它的讀音（破音字跟著詞走），其餘單字取最常用讀音；
有主詞庫沒有的字就整個略過。主詞庫已有同字同讀音的詞不重複收錄。

用法：build_addon_dicts.py <raw 目錄> <scorpio_dict.db（解壓後，就地更新 addon_dict 與 dict_packs 表）>
raw 目錄裡缺檔的來源不重建，保留資料庫裡原本的詞（只更新流行用語時不必重抓全部資料）。
"""
import csv
import json
import math
import os
import re
import sqlite3
import sys

raw, db_path = sys.argv[1], sys.argv[2]
tools = os.path.dirname(os.path.abspath(__file__))
db = sqlite3.connect(db_path)

INITIALS = set("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ")
TONES = "ˊˇˋ˙ˉ"
HAN = re.compile(r"^[㐀-鿿豈-﫿]+$")

word_reading = {}
for word, reading, score in db.execute("SELECT word, reading, score FROM dict"):
    cur = word_reading.get(word)
    if cur is None or score > cur[1]:
        word_reading[word] = (reading, score)
core_pairs = set(db.execute("SELECT word, reading FROM dict").fetchall())


def guess_reading(word):
    """最少切段（長詞優先）；同段數取詞頻總和高的"""
    n = len(word)
    best = [None] * (n + 1)
    best[0] = (0, 0.0, [])
    for i in range(n):
        if best[i] is None:
            continue
        for j in range(min(n, i + 6), i, -1):
            hit = word_reading.get(word[i:j])
            if not hit:
                continue
            cand = (best[i][0] + 1, best[i][1] + hit[1], best[i][2] + [hit[0]])
            cur = best[j]
            if cur is None or cand[0] < cur[0] or (cand[0] == cur[0] and cand[1] > cur[1]):
                best[j] = cand
    return "-".join(best[n][2]) if best[n] else None


def split_syllable(s):
    t = s.rstrip(TONES)
    ini = t[0] if t and t[0] in INITIALS else ""
    fin = t[len(ini):]
    return t, ini or "_", fin or "_"


def make_row(word, score, source, reading=None):
    if not HAN.match(word) or not (2 <= len(word) <= 10):
        return None
    reading = reading or guess_reading(word)
    if not reading or reading.count("-") + 1 != len(word):
        return None
    if (word, reading) in core_pairs:
        return None
    parts = [split_syllable(s) for s in reading.split("-")]
    return (reading, "-".join(p[0] for p in parts), word, round(score, 4),
            "-".join(p[1] for p in parts), "-".join(p[2] for p in parts), source)


rows = {}
built = set()


def add(word, score, source, reading=None):
    word = word.strip()
    r = make_row(word, score, source, reading)
    if r and (r[2], r[0]) not in rows:
        rows[(r[2], r[0])] = r


def have(*names):
    return all(os.path.exists(f"{raw}/{n}") for n in names)


# 0. 流行與口語用語
section = None
for line in open(f"{tools}/data/trendy.txt", encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    if line.startswith("["):
        section = line.strip("[]")
        continue
    if section == "words":
        f = line.split()
        add(f[0], -4.8, "trendy", f[1] if len(f) > 1 else None)
built.add("trendy")

# 1. 台灣地名與路名
sites = set()
counties = set()
if have("roads.csv"):
    built.add("tw_places")
    with open(f"{raw}/roads.csv", encoding="utf-8-sig") as f:
        for x in csv.DictReader(f):
            counties.add(x["city"])
            sites.add(x["site_id"][len(x["city"]):])
            add(x["road"], -6.6, "tw_places")
for c in counties:
    add(c, -5.2, "tw_places")
for s in sites:
    add(s, -5.6, "tw_places")
    if len(s) >= 3 and s[-1] in "鄉鎮市區":
        add(s[:-1], -5.9, "tw_places")

# 2. 台灣景點地標
attrs = json.load(open(f"{raw}/attr/AttractionList.json", encoding="utf-8-sig"))["Attractions"] if have("attr/AttractionList.json") else []
if attrs:
    built.add("tw_landmarks")
for a in attrs:
    name = a["AttractionName"]
    name = re.sub(r"[（(].*?[)）]", "", name)
    name = name.split("：")[-1].split(":")[-1].strip()
    add(name, -6.2, "tw_landmarks")
    for alt in a.get("AlternateNames") or []:
        add(re.sub(r"[（(].*?[)）]", "", alt), -6.3, "tw_landmarks")

# 3. 世界國家與城市
terr = {}
if have("terr.json", "tz.json", "cities.csv"):
    built.add("world_places")
    terr = json.load(open(f"{raw}/terr.json"))["main"]["zh-Hant"]["localeDisplayNames"]["territories"]
for k, v in terr.items():
    if "-alt-" in k:
        continue
    add(v, -5.0, "world_places")


def walk(o):
    for v in o.values():
        if isinstance(v, dict):
            if "exemplarCity" in v:
                add(v["exemplarCity"], -5.6, "world_places")
            walk(v)


if "world_places" in built:
    walk(json.load(open(f"{raw}/tz.json"))["main"]["zh-Hant"]["dates"]["timeZoneNames"]["zone"])


def links_score(links, base, top):
    return min(top, base + 0.6 * math.log10(max(1, links) / 30))


for x in (csv.DictReader(open(f"{raw}/cities.csv")) if "world_places" in built else []):
    name = x["tw"] or x["hant"]
    add(name, links_score(int(x["links"] or 0), -6.2, -5.2), "world_places")

# 4. 世界地標與地理
if have("landmarks.csv"):
    built.add("world_landmarks")
for line in (csv.reader(open(f"{raw}/landmarks.csv")) if "world_landmarks" in built else []):
    if len(line) < 5:
        continue
    _, _, tw, hant, links = line[:5]
    name = tw or hant
    if name:
        add(name, links_score(int(links or 0), -6.4, -5.0), "world_landmarks")

db.execute("CREATE TABLE IF NOT EXISTS addon_dict (reading TEXT NOT NULL, toneless TEXT NOT NULL, word TEXT NOT NULL, score REAL NOT NULL, "
           "initials TEXT NOT NULL, finals TEXT NOT NULL, source TEXT NOT NULL)")
for src in built:
    db.execute("DELETE FROM addon_dict WHERE source = ?", (src,))
db.executemany("INSERT INTO addon_dict VALUES (?,?,?,?,?,?,?)", rows.values())
db.execute("CREATE INDEX IF NOT EXISTS idx_addon_source ON addon_dict(source)")
print("重建：", ", ".join(sorted(built)))

# 詞庫資訊（名稱、說明、來源、授權、版本）：App 依這張表列出詞庫，版本變了就重新套用
db.execute("DROP TABLE IF EXISTS dict_packs")
db.execute("CREATE TABLE dict_packs (id TEXT PRIMARY KEY, grp TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, "
           "source TEXT NOT NULL, license TEXT NOT NULL, url TEXT NOT NULL, version TEXT NOT NULL, sort INTEGER NOT NULL)")
packs = json.load(open(f"{tools}/data/packs.json", encoding="utf-8"))["packs"]
db.executemany("INSERT INTO dict_packs VALUES (?,?,?,?,?,?,?,?,?)",
               [(p["id"], p["group"], p["name"], p["description"], p["source"], p["license"], p["url"], p["version"], i)
                for i, p in enumerate(packs)])
# 主詞庫標記來源：啟用的附加／匯入詞庫會插進 dict（source 標名稱），停用時依 source 刪掉
cols = [r[1] for r in db.execute("PRAGMA table_info(dict)")]
if "source" not in cols:
    db.execute("ALTER TABLE dict ADD COLUMN source TEXT")
db.execute("CREATE INDEX IF NOT EXISTS idx_dict_source ON dict(source)")
db.execute("CREATE INDEX IF NOT EXISTS idx_dict_word ON dict(word)")
db.commit()
for src, n in db.execute("SELECT source, count(*) FROM addon_dict GROUP BY source"):
    print(src, n)
db.execute("VACUUM")
db.close()
