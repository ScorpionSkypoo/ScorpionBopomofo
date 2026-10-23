#!/usr/bin/env bash
# 重建 App 內建的詞庫資產（app/src/main/assets/scorpio_dict.db.gz、scorpio_bigram.db.gz）
#
# 用法：tools/build_packs.sh <工作目錄> [bigram 來源...]
#   工作目錄/raw     附加詞庫原始資料（roads.csv、attr/、terr.json、tz.json、cities.csv、landmarks.csv），缺的來源保留原本的詞
#   工作目錄/corpus  接續詞語料（wiki1.bz2、pttpre.parquet、pttqa.parquet、dcard.parquet），缺的來源保留原本的資料
#   沒指定 bigram 來源就全部重建（bg_wiki bg_ptt bg_dcard trendy）；只改了 trendy.txt 時傳 trendy 即可
#
# 需要 python 套件 opencc、pyarrow。改完內容記得調 tools/data/packs.json 的 version，
# 並把 ScorpioDictManager 的 CURRENT_DB_VERSION／CURRENT_BIGRAM_VERSION 加一，App 才會換上新資產。
set -euo pipefail
W=$(realpath "$1"); shift
T=$(dirname "$(realpath "$0")")
A="$T/../app/src/main/assets"
PY=${PYTHON:-python3}
mkdir -p "$W/raw" "$W/corpus" "$W/bg"

gunzip -c "$A/scorpio_dict.db.gz" > "$W/dict.db"
"$PY" -I "$T/build_addon_dicts.py" "$W/raw" "$W/dict.db"

gunzip -c "$A/scorpio_bigram.db.gz" > "$W/bigram.db"
SOURCES=${*:-bg_wiki bg_ptt bg_dcard trendy}
for s in $SOURCES; do
  rm -f "$W/bg/$s.db"
  "$PY" -I "$T/build_bigram_packs.py" "$W/dict.db" "$W/corpus" "$T/data/trendy.txt" "$W/bg/$s.db" "$s" > "$W/bg/$s.log" 2>&1 &
done
wait
tail -n 1 "$W"/bg/*.log

# 舊版（沒有 source 欄位）的 bigram 表整個換掉；新版只換掉重建的來源
"$PY" -I - "$W/bigram.db" "$W/bg" $SOURCES <<'EOF'
import os, sqlite3, sys
out, bg, sources = sys.argv[1], sys.argv[2], sys.argv[3:]
db = sqlite3.connect(out)
if "source" not in [r[1] for r in db.execute("PRAGMA table_info(bigram)")]:
    db.execute("DROP TABLE IF EXISTS bigram")
db.execute("CREATE TABLE IF NOT EXISTS bigram (prev TEXT NOT NULL, next TEXT NOT NULL, score REAL NOT NULL, source TEXT NOT NULL, "
           "PRIMARY KEY(prev, next, source)) WITHOUT ROWID")
for s in sources:
    path = f"{bg}/{s}.db"
    db.execute("ATTACH ? AS src", (path,))
    n = db.execute("SELECT count(*) FROM src.bigram WHERE source = ?", (s,)).fetchone()[0]
    if n:
        db.execute("DELETE FROM bigram WHERE source = ?", (s,))
        db.execute("INSERT INTO bigram SELECT prev, next, score, source FROM src.bigram WHERE source = ?", (s,))
    db.commit()
    db.execute("DETACH src")
for row in db.execute("SELECT source, count(*) FROM bigram GROUP BY source"):
    print(*row)
db.execute("VACUUM")
db.close()
EOF

gzip -9 -c "$W/dict.db" > "$A/scorpio_dict.db.gz"
gzip -9 -c "$W/bigram.db" > "$A/scorpio_bigram.db.gz"
ls -la "$A"
