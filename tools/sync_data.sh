#!/usr/bin/env bash
# 從資料 repo 的 Release「base」下載最新的基底詞庫，放進 App 內建資產
# （詞庫的建置、更新都在 https://github.com/ScorpionSkypoo/scorpio-ime-data）
#
# 換上新資產後，記得把 ScorpioDictManager 的 CURRENT_DB_VERSION／CURRENT_BIGRAM_VERSION 加一，
# 手機上的 App 才會換成新的內建詞庫（個人學習紀錄會保留）。
set -euo pipefail
A="$(dirname "$(realpath "$0")")/../app/src/main/assets"
for f in scorpio_dict.db.gz scorpio_bigram.db.gz; do
  curl -fsSL --retry 3 -o "$A/$f" "https://github.com/ScorpionSkypoo/scorpio-ime-data/releases/download/base/$f"
done
ls -la "$A"
