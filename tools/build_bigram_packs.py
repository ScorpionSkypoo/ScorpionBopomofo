#!/usr/bin/env python3
"""
依來源分開建立「詞與詞搭配機率 (Bigram)」scorpio_bigram.db：每個來源各自統計、各自算機率，
存在同一張表的 source 欄位，App 只合併使用者開啟的來源。

  bg_wiki   中文維基百科（zhwiki pages-articles dump，OpenCC s2twp 轉台灣正體用語）
  bg_ptt    PTT 文章與推文（PTT-pretrain-zhtw）＋八卦版問答（PTT-Corpus-100K_Gossiping）
  bg_dcard  Dcard 討論串（dcard_conversations）
  trendy    流行與口語用語的人工搭配（tools/data/trendy.txt 的 [pairs]）

分詞用主詞庫＋附加詞庫的詞表做 Viterbi 最大機率分詞；score = ln P(next | prev)。

用法：build_bigram_packs.py <scorpio_dict.db（解壓後）> <語料目錄> <trendy.txt> <輸出 scorpio_bigram.db> [來源...]
  語料目錄需有 wiki1.bz2、pttpre.parquet、pttqa.parquet、dcard.parquet（缺的來源略過）
  指定來源時只重建那幾個，其他來源保留輸出檔裡原本的資料（更新單一來源用）
"""
import bz2
import math
import os
import re
import sqlite3
import sys
import time
from collections import Counter

import opencc
import pyarrow.parquet as pq

dict_db, corpus, trendy_path, out_db = sys.argv[1:5]
only = set(sys.argv[5:])
t0 = time.time()


def log(msg):
    print(f"[{time.time() - t0:6.0f}s] {msg}", flush=True)


# ---------- 詞表 ----------
con = sqlite3.connect(dict_db)
uni = {}
for table in ("dict", "addon_dict"):
    for w, s in con.execute(f"SELECT word, MAX(score) FROM {table} GROUP BY word"):
        if w and not w.startswith("MACRO@") and all('一' <= ch <= '鿿' for ch in w):
            uni[w] = max(s, uni.get(w, -99))
con.close()


def read_trendy(path):
    words, pairs, section = [], [], None
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("["):
            section = line.strip("[]")
            continue
        f = line.split()
        if section == "words":
            words.append(f[0])
        elif section == "pairs" and len(f) == 3:
            pairs.append((f[0], f[1], int(f[2])))
    return words, pairs


trendy_words, trendy_pairs = read_trendy(trendy_path)
for w in trendy_words:
    uni.setdefault(w, -4.8)
MAXLEN = min(max(len(w) for w in uni), 7)
log(f"詞表 {len(uni)} 詞，最長 {MAXLEN} 字")

UNKNOWN_COST = 14.0
LEN_BONUS = 1.6


def segment(run):
    n = len(run)
    best = [1e18] * (n + 1)
    back = [0] * (n + 1)
    best[0] = 0.0
    for i in range(n):
        bi = best[i]
        c = bi + UNKNOWN_COST
        if c < best[i + 1]:
            best[i + 1] = c
            back[i + 1] = i
        for l in range(1, min(MAXLEN, n - i) + 1):
            s = uni.get(run[i:i + l])
            if s is None:
                continue
            c = bi - s - LEN_BONUS * (l - 1)
            if c < best[i + l]:
                best[i + l] = c
                back[i + l] = i
    out = []
    j = n
    while j > 0:
        i = back[j]
        out.append(run[i:j])
        j = i
    out.reverse()
    return out


RE_URL = re.compile(r"https?://\S+")
RE_PTT_HEAD = re.compile(r"^(作者|看板|標題|時間|發信站|文章網址|轉錄至).*$", re.M)
RE_PTT_BODY = re.compile(r"^內文[:：]\s*", re.M)  # 內文跟「內文:」在同一行，只拿掉標頭
RE_PUSH_PREFIX = re.compile(r"^(推|→|噓)\s+[a-zA-Z0-9_-]+:\s*", re.M)
RE_RUN = re.compile(r"[一-鿿]{2,}")
RE_TEMPLATE = re.compile(r"\{\{[^{}]*\}\}")
RE_TABLE = re.compile(r"\{\|.*?\|\}", re.S)
RE_REF = re.compile(r"<ref[^>]*?>.*?</ref>|<ref[^>]*/>", re.S)
RE_TAG = re.compile(r"<[^>]+>")
RE_LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]")
RE_EXT = re.compile(r"\[https?://[^\s\]]*\s?([^\]]*)\]")
RE_TEXT = re.compile(r"<text[^>]*>(.*?)</text>", re.S)


class Counts:
    def __init__(self):
        self.uni = Counter()
        self.bi = Counter()
        self.chars = 0

    def feed(self, runs):
        for run in runs:
            if len(run) < 2:
                continue
            self.chars += len(run)
            prev = None
            for w in segment(run):
                if w in uni:
                    self.uni[w] += 1
                    if prev is not None:
                        self.bi[(prev, w)] += 1
                    prev = w
                else:
                    prev = None

    def feed_text(self, text, ptt=False):
        t = RE_URL.sub("", text)
        if ptt:
            t = RE_PTT_HEAD.sub("", t)
            t = RE_PTT_BODY.sub("", t)
            t = RE_PUSH_PREFIX.sub("", t)
        self.feed(RE_RUN.findall(t))


def build_wiki(budget=8_000_000):
    path = f"{corpus}/wiki1.bz2"
    if not os.path.exists(path):
        return None
    cc = opencc.OpenCC("s2twp")
    k = Counts()
    buf = ""
    with bz2.open(path, "rt", encoding="utf-8", errors="ignore") as f:
        while k.chars < budget:
            chunk = f.read(1 << 22)
            if not chunk:
                break
            buf += chunk
            last = buf.rfind("</text>")
            if last < 0:
                continue
            work, buf = buf[:last + 7], buf[last + 7:]
            for m in RE_TEXT.finditer(work):
                body = m.group(1)
                if body.startswith("#REDIRECT") or body.startswith("#重定向"):
                    continue
                t = RE_TABLE.sub("", RE_REF.sub("", body))
                for _ in range(2):
                    t = RE_TEMPLATE.sub("", t)
                t = RE_TAG.sub("", RE_EXT.sub(r"\1", RE_LINK.sub(r"\1", t)))
                runs = RE_RUN.findall(t)
                if runs:
                    k.feed(cc.convert("\n".join(runs)).split("\n"))
                if k.chars >= budget:
                    break
            log(f"維基 {k.chars / 1e6:.1f}M 字，詞對 {len(k.bi)}")
    return k


def build_ptt(budget=16_000_000):
    k = Counts()
    qa = f"{corpus}/pttqa.parquet"
    if os.path.exists(qa):
        t = pq.read_table(qa, columns=["question", "answer"])
        for col in ("question", "answer"):
            for text in t[col].to_pylist():
                if text:
                    k.feed_text(text)
        log(f"PTT 問答 {k.chars / 1e6:.1f}M 字，詞對 {len(k.bi)}")
    pre = f"{corpus}/pttpre.parquet"
    if os.path.exists(pre):
        for batch in pq.ParquetFile(pre).iter_batches(batch_size=2000, columns=["text"]):
            for text in batch["text"].to_pylist():
                if text:
                    k.feed_text(text, ptt=True)
            log(f"PTT {k.chars / 1e6:.1f}M 字，詞對 {len(k.bi)}")
            if k.chars >= budget:
                break
    return k if k.chars else None


def build_dcard():
    path = f"{corpus}/dcard.parquet"
    if not os.path.exists(path):
        return None
    k = Counts()
    t = pq.read_table(path)
    for col in ("instruction", "input", "output"):
        if col in t.column_names:
            for text in t[col].to_pylist():
                if text:
                    k.feed_text(text)
    log(f"Dcard {k.chars / 1e6:.2f}M 字，詞對 {len(k.bi)}")
    return k


def build_trendy():
    k = Counts()
    for a, b, c in trendy_pairs:
        k.uni[a] += c
        k.uni[b] += c
        k.bi[(a, b)] += c
    return k


def to_rows(k, source, min_count, limit):
    rows = []
    for (a, b), c in k.bi.items():
        if c >= min_count:
            rows.append((a, b, round(math.log(c / (k.uni[a] + 1.0)), 3), source, c))
    rows.sort(key=lambda r: -r[4])
    return [r[:4] for r in rows[:limit]]


# 來源 → （建置函式, 最少出現次數, 最多筆數）
SOURCES = {
    "bg_wiki": (build_wiki, 4, 150_000),
    "bg_ptt": (build_ptt, 4, 200_000),
    "bg_dcard": (build_dcard, 2, 40_000),
    "trendy": (build_trendy, 1, 10_000),
}

out = sqlite3.connect(out_db)
cols = [r[1] for r in out.execute("PRAGMA table_info(bigram)")]
if "source" not in cols:
    out.execute("DROP TABLE IF EXISTS bigram")
    out.execute("CREATE TABLE bigram (prev TEXT NOT NULL, next TEXT NOT NULL, score REAL NOT NULL, source TEXT NOT NULL, "
                "PRIMARY KEY(prev, next, source)) WITHOUT ROWID")
for source, (fn, min_count, limit) in SOURCES.items():
    if only and source not in only:
        continue
    log(f"建立 {source}…")
    k = fn()
    if k is None:
        log(f"{source}：找不到語料，保留原本的資料")
        continue
    rows = to_rows(k, source, min_count, limit)
    out.execute("DELETE FROM bigram WHERE source = ?", (source,))
    out.executemany("INSERT INTO bigram VALUES (?,?,?,?)", rows)
    out.commit()
    log(f"{source}：{k.chars / 1e6:.1f}M 字 → {len(rows)} 組")
for src, n in out.execute("SELECT source, count(*) FROM bigram GROUP BY source"):
    print(src, n)
out.execute("VACUUM")
out.close()
