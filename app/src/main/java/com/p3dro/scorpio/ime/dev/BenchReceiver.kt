package com.p3dro.scorpio.ime.dev

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.util.Log
import com.p3dro.scorpio.ime.engine.BopomofoComposer
import com.p3dro.scorpio.ime.engine.ScorpioDictManager
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 選字基準測試（開發用）：把 assets/bench/inputs.txt 的合成按鍵逐鍵餵給選字引擎，結果寫進 App 外部資料夾的 bench/。
 * 改了詞庫或選字程式後，同一支手機上「改之前跑 baseline、改之後跑 compare」，比對每一題的候選有沒有變、耗時差多少。
 *
 * 只有 shell（adb／Shizuku）能觸發：receiver 要求 DUMP 權限，一般 App 拿不到
 *   am broadcast -n com.p3dro.scorpio.ime/.dev.BenchReceiver --es mode baseline|compare [--ez nouser true] [--ez probe true]
 *   am broadcast -n com.p3dro.scorpio.ime/.dev.BenchReceiver --es mode explain --es keys ㄋㄚㄒㄧㄝ（說明某組按鍵的排序）
 * 輸入是 tools/perf/make_bench_inputs.py 從詞庫隨機挑詞產生的，不含任何人打過的字；log 只記數量與耗時
 */
class BenchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val mode = intent.getStringExtra("mode") ?: "compare"
        // nouser：不看個人學習（跟沒有學習紀錄的舊版比詞庫查詢）
        val noUser = intent.getBooleanExtra("nouser", false)
        // probe：用精確配置計數量各段配置了多少記憶體（會讓配置變慢，這時的耗時不準）
        val probe = intent.getBooleanExtra("probe", false)
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                when {
                    mode == "explain" -> explain(app, intent.getStringExtra("keys").orEmpty(), intent.getStringExtra("prev"))
                    noUser -> ScorpioDictManager.getInstance(app).withoutUserData { run(app, mode, probe) }
                    else -> run(app, mode, probe)
                }
            } catch (e: Exception) {
                Log.e(TAG, "基準測試失敗", e)
                File(outDir(app), "summary.txt").writeText("error ${e.javaClass.simpleName}\n")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun outDir(context: Context) = File(context.getExternalFilesDir(null), "bench").apply { mkdirs() }

    /**
     * explain：說明一組按鍵為什麼排出這些候選（--es keys ㄋㄚㄒㄧㄝ [--es prev 前一個詞]）：
     * 整句的每一段怎麼來、幾分，前幾名候選的分數，每個鍵的鄰鍵懲罰（按在鍵中央時）。寫到 bench/explain.txt
     */
    private fun explain(context: Context, keys: String, prev: String?) {
        val dict = ScorpioDictManager.getInstance(context)
        val composer = BopomofoComposer()
        for (c in keys) composer.append(c)
        val trace = ArrayList<String>()
        val acc = dict.tapAccuracy()
        trace.add("連續打字時按下當下判對（確定選字時對答案）：有移動偏差 ${acc[1]}/${acc[0]}、沒有 ${acc[2]}/${acc[0]}；移動偏差 ${dict.spatial.motion.describe()}")
        for (c in keys.toSet()) {
            val pen = dict.spatial.neighborPenalties(BopomofoComposer.TouchPoint(c))
            trace.add("鄰鍵 $c：" + pen.sortedBy { it.second }.take(4).joinToString(" ") { "${it.first}%.2f".format(it.second) })
        }
        runBlocking { dict.getCandidateGroups(composer.getAllSegmentations(), composer.hasExplicitTone(), prev, composer.getTouchPoints(), trace) }
        File(outDir(context), "explain.txt").writeText(trace.joinToString("\n") + "\n")
    }

    private fun run(context: Context, mode: String, probe: Boolean) {
        val dict = ScorpioDictManager.getInstance(context)
        val dir = outDir(context)
        File(dir, "summary.txt").delete()
        val inputs = context.assets.open("bench/inputs.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        // 從冷的快取開始，耗時才比得出詞庫查詢本身的差別
        dict.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        val results = ArrayList<String>(inputs.size * 4)
        val zTimes = ArrayList<Long>()
        // 選字配置了多少記憶體（GC 的壓力）：ART 的累計配置量，整個行程的（測試時鍵盤沒在打字）
        fun allocated() = android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0L
        fun gcStat(name: String) = android.os.Debug.getRuntimeStat(name)?.toLongOrNull() ?: 0L
        var zBytes = 0L
        if (probe) com.p3dro.scorpio.ime.engine.AllocProbe.start()
        val gc0 = gcStat("art.gc.gc-count")
        val gcTime0 = gcStat("art.gc.gc-time")
        val t0 = System.nanoTime()
        runBlocking {
            for (line in inputs) {
                val f = line.split('\t')
                when (f[0]) {
                    "Z" -> {
                        val prev = f.getOrNull(1).orEmpty().ifEmpty { null }
                        val keys = f[2]
                        val offs = f.getOrNull(3)?.split(';')?.map { p -> p.split(',').let { it[0].toFloat() to it[1].toFloat() } }
                        val composer = BopomofoComposer()
                        for ((i, c) in keys.withIndex()) {
                            val o = offs?.getOrNull(i)
                            composer.append(c, o?.first ?: 0f, o?.second ?: 0f)
                            val a = allocated()
                            val s = System.nanoTime()
                            val g = com.p3dro.scorpio.ime.engine.AllocProbe.measure("z.total") {
                                dict.getCandidateGroups(composer.getAllSegmentations(), composer.hasExplicitTone(), prev, composer.getTouchPoints())
                            }
                            zTimes.add(System.nanoTime() - s)
                            zBytes += allocated() - a
                            results.add(g.inline.joinToString(" ") + " | " + g.emoji.joinToString(" ") + " | " + g.kaomoji.joinToString(" "))
                        }
                    }
                    "N" -> results.add(dict.getNextWordPredictions(f[1]).joinToString(" "))
                    "E" -> results.add(dict.getEnglishCandidates(f[1]).joinToString(" "))
                    "EP" -> results.add(dict.getEnglishPredictions(f[1]).joinToString(" "))
                    "C" -> {
                        val quick = f[2] == "1"
                        results.add(dict.codeCandidates(f[1], quick).joinToString(" ") + " | " +
                            dict.codePrefixLogMass(f[1], quick)?.let { "%.6f".format(it) })
                    }
                    "M" -> {
                        results.add(dict.getEmojiCategories().joinToString(" "))
                        for (cat in dict.getEmojiCategories()) results.add(dict.getEmojisByCategory(cat).joinToString(" "))
                        results.add(dict.getKaomojiCategories().joinToString(" "))
                        for (cat in dict.getKaomojiCategories()) results.add(dict.getKaomojisByCategory(cat).joinToString(" "))
                        results.add(dict.getAllEmojis().size.toString())
                        val freq = dict.syllableFrequencyMap()
                        results.add("${freq.size} " + freq.keys.sorted().take(50).joinToString(" ") { "$it=%.6e".format(freq[it]) })
                        results.add(dict.dictionaries().joinToString(" ") { "${it.id}:${it.count}:${it.enabled}" })
                        results.add(dict.browseDictionary("core", "", 0, 30).joinToString(" ") { "${it.first}/${it.second}" })
                        results.add(dict.browseDictionary("core", "台灣", 0, 30).joinToString(" ") { "${it.first}/${it.second}" })
                        results.add(dict.browseDictionary("emoji", "", 0, 30).joinToString(" ") { "${it.first}/${it.second}" })
                        results.add(dict.browseDictionary("kaomoji", "", 0, 30).joinToString(" ") { "${it.first}/${it.second}" })
                    }
                }
            }
        }
        if (probe) com.p3dro.scorpio.ime.engine.AllocProbe.stop()
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        val sorted = zTimes.sorted()
        fun pct(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()] / 1e6
        val timing = "選字 ${sorted.size} 次：中位數 %.2f ms、p90 %.2f ms、p99 %.2f ms、最慢 %.2f ms；全部 $totalMs ms"
            .format(pct(0.5), pct(0.9), pct(0.99), pct(1.0))

        val out = File(dir, if (mode == "baseline") "baseline.txt" else "current.txt")
        out.writeText(results.joinToString("\n") + "\n")
        val gcCount = gcStat("art.gc.gc-count") - gc0
        val gcMs = gcStat("art.gc.gc-time") - gcTime0
        val alloc = "選字配置記憶體：每次平均 %.1f KB（共 %.1f MB）；整個測試 GC $gcCount 次、$gcMs ms"
            .format(if (sorted.isEmpty()) 0.0 else zBytes / 1024.0 / sorted.size, zBytes / 1048576.0)
        val probes = com.p3dro.scorpio.ime.engine.AllocProbe.totals.entries.sortedBy { it.key }
            .joinToString("、") { "${it.key} %.1f MB".format(it.value / 1048576.0) }
        val summary = StringBuilder("$mode：${results.size} 筆結果\n$timing\n$alloc\n各段配置：$probes\n")
        // 命中率：每個測項最後一鍵的第一候選（前三）是不是本來要打的詞（assets/bench/expected.txt：結果行號<Tab>詞）
        try {
            val expected = context.assets.open("bench/expected.txt").bufferedReader().readLines().mapNotNull { l ->
                l.split('\t').takeIf { it.size == 2 }?.let { it[0].toInt() to it[1] }
            }
            var top1 = 0
            var top3 = 0
            for ((row, word) in expected) {
                val cands = results.getOrNull(row - 1)?.substringBefore(" | ")?.split(' ') ?: continue
                if (cands.firstOrNull() == word) top1++
                if (cands.take(3).contains(word)) top3++
            }
            summary.append("命中率：第一候選 $top1/${expected.size}、前三 $top3/${expected.size}\n")
        } catch (e: java.io.IOException) {
            // 沒有預期答案檔
        }
        val base = File(dir, "baseline.txt")
        if (mode != "baseline" && base.exists()) {
            val expected = base.readLines()
            var diff = 0
            val diffIdx = ArrayList<Int>()
            for (i in 0 until maxOf(expected.size, results.size)) {
                if (expected.getOrNull(i) != results.getOrNull(i)) {
                    diff++
                    if (diffIdx.size < 50) diffIdx.add(i)
                }
            }
            summary.append("跟 baseline 比：${expected.size} 筆中 $diff 筆不同\n")
            if (diffIdx.isNotEmpty()) summary.append("不同的題號：${diffIdx.joinToString(",")}\n")
        }
        File(dir, "summary.txt").writeText(summary.toString())
        Log.i(TAG, "基準測試完成：$mode ${results.size} 筆，$timing")
    }

    companion object {
        private const val TAG = "ScorpioBench"
    }
}
