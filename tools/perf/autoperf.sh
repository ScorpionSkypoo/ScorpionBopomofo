# 偵測到開始打字（log 出現「選字耗時」）就錄 30 秒：simpleperf＋每秒 CPU／記憶體
rm -f /data/local/tmp/perf.data /data/local/tmp/auto.log /data/local/tmp/sample2.txt
logcat -c
echo "waiting $(date +%T)" > /data/local/tmp/auto.log
i=0
while ! logcat -d | grep -q '選字耗時'; do sleep 1; i=$((i+1)); [ $i -gt 1800 ] && exit 0; done
P=$(pidof com.p3dro.scorpio.ime)
echo "typing detected pid=$P $(date +%T)" >> /data/local/tmp/auto.log
dumpsys gfxinfo com.p3dro.scorpio.ime reset > /dev/null
(
  T0=$(awk '{print $14+$15}' /proc/$P/stat); n=0
  while [ $n -lt 30 ]; do sleep 1; n=$((n+1)); T=$(awk '{print $14+$15}' /proc/$P/stat); R=$(awk '/VmRSS/{print int($2/1024)}' /proc/$P/status); echo "$n cpu=$((T-T0))% rss=${R}MB"; T0=$T; done
  dumpsys meminfo com.p3dro.scorpio.ime | grep -E 'TOTAL PSS|Native Heap:|Java Heap:|Graphics:'
) > /data/local/tmp/sample2.txt 2>&1 &
simpleperf record -p $P -g -f 1000 --duration 30 -o /data/local/tmp/perf.data >> /data/local/tmp/auto.log 2>&1
logcat -d | grep '選字耗時' > /data/local/tmp/lat2.txt
dumpsys gfxinfo com.p3dro.scorpio.ime | grep -E 'Total frames rendered|50th percentile|90th percentile' >> /data/local/tmp/auto.log
echo "done $(date +%T)" >> /data/local/tmp/auto.log
