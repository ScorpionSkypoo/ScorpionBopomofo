# 效能量測

`autoperf.sh` 在手機上執行（需要 shell 權限，例如 adb shell 或 Shizuku）：等到 log 出現「選字耗時」（開始打字）
就自動錄 30 秒，產出：

- `/data/local/tmp/auto.log`：開始／結束時間、這段期間畫了幾幀（gfxinfo）
- `/data/local/tmp/sample2.txt`：每秒 CPU（佔單核 %）與 RSS，最後附 dumpsys meminfo 摘要
- `/data/local/tmp/perf.data`：simpleperf 取樣（App 已設 `profileable`，release 版也能錄）
- `/data/local/tmp/lat2.txt`：每一鍵的選字耗時

```bash
adb push tools/perf/autoperf.sh /data/local/tmp/
adb shell 'nohup sh /data/local/tmp/autoperf.sh > /dev/null 2>&1 &'
# 打字 30 秒，然後：
adb shell cat /data/local/tmp/auto.log /data/local/tmp/sample2.txt
adb shell 'cd /data/local/tmp && simpleperf report -i perf.data --sort dso'
adb shell 'cd /data/local/tmp && simpleperf report -i perf.data --children --sort symbol' | head -60
```

量之前先讓系統依 Baseline Profile 編譯，數字才接近使用者實際情況：

```bash
adb shell cmd package compile -m speed-profile -f com.p3dro.scorpio.ime
adb shell dumpsys package com.p3dro.scorpio.ime | grep status=   # 要看到 speed-profile
```
