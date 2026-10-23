#!/usr/bin/env python3
"""
產生選字基準測試的輸入（app/src/main/assets/bench/inputs.txt），內容全是從詞庫隨機挑的詞組成的合成按鍵，不含任何人打過的字。
每一行一個測項，Tab 分隔：
  Z  前一個詞  按鍵序列  [每鍵觸控偏移 x,y;x,y;…]   逐鍵打字，每打一鍵就選字一次
  N  前一個詞                                        接續詞預測
  E  英文前綴                                         英文補全
  EP 前一個英文字                                     英文接續
  C  倉頡碼  0／1（1＝速成）                          倉頡候選＋碼前綴使用量
  M                                                   Emoji／顏文字分類、音節字頻、詞庫清單
用法：make_bench_inputs.py <scorpio_dict.db> <輸出檔> [預期答案檔]
"""
import random
import sqlite3
import sys

db = sqlite3.connect(sys.argv[1])
rnd = random.Random(20261023)
TONES = "ˊˇˋ˙"
INI = set("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ")
NEAR = {"ㄅ": "ㄉㄆ", "ㄉ": "ㄅㄊ", "ㄓ": "ㄔ", "ㄚ": "ㄞㄛ", "ㄞ": "ㄚㄢㄟ", "ㄢ": "ㄞㄣ", "ㄆ": "ㄅㄊㄇ", "ㄊ": "ㄉㄆㄍㄋ",
        "ㄍ": "ㄊㄐㄎ", "ㄐ": "ㄍㄔㄑ", "ㄔ": "ㄓㄐㄗㄕ", "ㄗ": "ㄔㄧㄘ", "ㄧ": "ㄗㄛㄨ", "ㄛ": "ㄚㄧㄟㄜ", "ㄟ": "ㄞㄛㄣㄠ",
        "ㄣ": "ㄢㄟㄤ", "ㄇ": "ㄆㄋㄈ", "ㄋ": "ㄊㄇㄎㄌ", "ㄎ": "ㄍㄋㄑㄏ", "ㄑ": "ㄐㄎㄕㄒ", "ㄕ": "ㄔㄑㄘㄖ", "ㄘ": "ㄗㄕㄨㄙ",
        "ㄨ": "ㄧㄘㄜㄩ", "ㄜ": "ㄛㄨㄠㄝ", "ㄠ": "ㄟㄜㄤㄡ", "ㄤ": "ㄣㄠㄥ", "ㄈ": "ㄇㄌ", "ㄌ": "ㄋㄈㄏ", "ㄏ": "ㄎㄌㄒ",
        "ㄒ": "ㄑㄏㄖ", "ㄖ": "ㄕㄒㄙ", "ㄙ": "ㄘㄖㄩ", "ㄩ": "ㄨㄙㄝ", "ㄝ": "ㄜㄩㄡ", "ㄡ": "ㄠㄝㄥ", "ㄥ": "ㄤㄡ"}

def han(w):
    return all("㐀" <= c <= "鿿" for c in w)

def pick(n_syl, k, min_score=-6.5):
    rows = db.execute("SELECT word, reading FROM dict WHERE source IS NULL AND score > ? AND length(word) = ? "
                      "AND (length(reading) - length(replace(reading, '-', ''))) = ? ORDER BY rowid", (min_score, n_syl, n_syl - 1)).fetchall()
    rows = [r for r in rows if han(r[0])]
    return rnd.sample(rows, min(k, len(rows)))

def keys_of(reading, tones):
    out = []
    for syl in reading.split("-"):
        base = "".join(c for c in syl if c not in TONES)
        out.append(base.lstrip("˙") if base.startswith("˙") else base)
        if tones:
            t = next((c for c in syl if c in TONES), "ˉ")
            out.append(t)
    return "".join(out)

prevs = [w for (w,) in db.execute("SELECT word FROM dict WHERE source IS NULL AND score > -4.2 AND length(word) BETWEEN 1 AND 2 ORDER BY rowid")]
prevs = [w for w in prevs if han(w)]
def prev():
    return rnd.choice(prevs) if rnd.random() < 0.5 else ""

lines = []
expected = []  # 每一行要打的詞（Z 才有；用來算第一候選命中率）
# 單一符號（每個詞的第一鍵）
for c in "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ":
    lines.append(f"Z\t\t{c}")
    expected.append("")
for n, k in ((1, 40), (2, 90), (3, 50), (4, 30), (5, 10)):
    for w, r in pick(n, k):
        lines.append(f"Z\t{prev()}\t{keys_of(r, False)}")
        expected.append(w)
for n, k in ((1, 20), (2, 30), (3, 10)):
    for w, r in pick(n, k):
        lines.append(f"Z\t{prev()}\t{keys_of(r, True)}")
        expected.append(w)
# 簡拼：部分音節只打聲母
for w, r in pick(3, 25) + pick(4, 15) + pick(2, 15):
    syls = [keys_of(s, False) for s in r.split("-")]
    out = [s[0] if s[0] in INI and rnd.random() < 0.6 else s for s in syls]
    if all(len(s) > 1 for s in out) and syls[0][0] in INI:
        out[0] = syls[0][0]
    lines.append(f"Z\t{prev()}\t{''.join(out)}")
    expected.append(w)
# 長句：幾個詞接起來不打聲調
for _ in range(20):
    picked = pick(rnd.choice((2, 2, 3)), 3)
    parts = [keys_of(r, False) for w, r in picked]
    lines.append(f"Z\t{prev()}\t{''.join(parts)}")
    expected.append("".join(w for w, r in picked))
# 按錯鄰鍵，附觸控偏移（偏向按錯的那一邊）
for w, r in pick(2, 40) + pick(3, 20):
    ks = list(keys_of(r, False))
    i = rnd.randrange(len(ks))
    if ks[i] in NEAR:
        ks[i] = rnd.choice(NEAR[ks[i]])
    offs = ";".join(f"{rnd.uniform(-0.6, 0.6):.2f},{rnd.uniform(-0.5, 0.5):.2f}" for _ in ks)
    lines.append(f"Z\t{prev()}\t{''.join(ks)}\t{offs}")
    expected.append(w)
# 接續詞預測
for w in rnd.sample(prevs, 80):
    lines.append(f"N\t{w}")
# 英文
en = [w for (w,) in db.execute("SELECT word FROM english_dict WHERE score > 3000 ORDER BY rowid")]
for w in rnd.sample(en, 60):
    lines.append(f"E\t{w[:rnd.randint(1, len(w))]}")
for w in rnd.sample(en, 40):
    lines.append(f"EP\t{w}")
# 倉頡／速成
cj = db.execute("SELECT code, quick FROM cangjie ORDER BY rowid").fetchall()
for code, quick in rnd.sample(cj, 60):
    lines.append(f"C\t{code[:rnd.randint(1, len(code))]}\t0")
    lines.append(f"C\t{quick[:rnd.randint(1, len(quick))]}\t1")
lines.append("M")
with open(sys.argv[2], "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
# 第三個參數：每個 Z 測項最後一鍵的結果在第幾行、要打的詞（算第一候選命中率用）
if len(sys.argv) > 3:
    with open(sys.argv[3], "w", encoding="utf-8") as f:
        row = 0
        zi = 0
        for line in lines:
            parts = line.split("\t")
            if parts[0] == "Z":
                row += len(parts[2])
                if expected[zi]:
                    f.write(f"{row}\t{expected[zi]}\n")
                zi += 1
            elif parts[0] == "M":
                break
            else:
                row += 1
print(len(lines), "lines")
