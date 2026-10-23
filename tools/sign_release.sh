#!/usr/bin/env bash
# 用正式金鑰簽 APK，並附上「debug 金鑰 → 正式金鑰」的金鑰輪替證明（APK Signature Scheme v3）
#   Android 9 以上：認得輪替證明，舊版（debug 簽章）可以直接覆蓋更新成正式簽章，資料保留
#   Android 8.x：v1／v2 簽章仍是 debug 金鑰，一樣可以覆蓋更新
#
# 用法：tools/sign_release.sh <輸入.apk> <輸出.apk>
# 環境變數：
#   DEBUG_KEYSTORE           舊金鑰（預設 ~/.android/debug.keystore，密碼 android）
#   RELEASE_KEYSTORE         正式金鑰（PKCS12，別名 scorpio）
#   RELEASE_STORE_PASSWORD   正式金鑰密碼
#   APKSIGNER                apksigner 指令（預設找 Android SDK build-tools 最新版）
# 輪替證明 signing/lineage.bin 只含兩把金鑰的公開證書，可以放在 repo 裡
set -euo pipefail
IN=$1; OUT=$2
R=$(dirname "$(dirname "$(realpath "$0")")")
DEBUG_KEYSTORE=${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}
: "${RELEASE_KEYSTORE:?要設定 RELEASE_KEYSTORE}" "${RELEASE_STORE_PASSWORD:?要設定 RELEASE_STORE_PASSWORD}"
if [ -z "${APKSIGNER:-}" ]; then
  SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(grep -s '^sdk.dir=' "$R/local.properties" | cut -d= -f2)}}
  JAR=$(ls -d "$SDK"/build-tools/*/lib/apksigner.jar | sort -V | tail -1)
  APKSIGNER="java -jar $JAR"
fi
export RELEASE_STORE_PASSWORD
$APKSIGNER sign \
  --ks "$DEBUG_KEYSTORE" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --next-signer --ks "$RELEASE_KEYSTORE" --ks-key-alias scorpio --ks-pass env:RELEASE_STORE_PASSWORD \
  --lineage "$R/signing/lineage.bin" --rotation-min-sdk-version 28 \
  --out "$OUT" "$IN"
$APKSIGNER verify --print-certs "$OUT" | grep -E "Signer #|certificate DN" | head -4
