#!/usr/bin/env bash
# 從資料 repo 的 Release「base」下載最新的內建詞庫（SCD），解壓放進 App 內建資產 app/src/main/assets/scd/
# （詞庫的建置、更新都在 https://github.com/ScorpionSkypoo/scorpio-ime-data）
#
# 內建詞庫不壓縮進 APK（build.gradle.kts 的 noCompress），App 執行時直接 mmap；
# 版本記在每個 .scd 檔的 meta 裡，不必再手動加版本號：手機上下載的資料包比內建的新就用下載的，
# 內建的比較新（App 改版）就用內建的，並刪掉舊的下載檔。
set -euo pipefail
REPO=ScorpionSkypoo/scorpio-ime-data
A="$(dirname "$(realpath "$0")")/../app/src/main/assets/scd"
mkdir -p "$A"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fsSL --retry 3 "https://api.github.com/repos/$REPO/releases/tags/base" \
  | python3 -c 'import json,sys; [print(a["browser_download_url"]) for a in json.load(sys.stdin)["assets"] if a["name"].endswith(".scd.gz")]' \
  > "$TMP/urls"
[ -s "$TMP/urls" ] || { echo "Release base 裡沒有 .scd.gz（資料 repo 還沒改成建置 SCD？）" >&2; exit 1; }
rm -f "$A"/*.scd
while read -r url; do
  f=$(basename "$url" .gz)
  curl -fsSL --retry 3 "$url" | gunzip -c > "$A/$f"
done < "$TMP/urls"
ls -la "$A"
