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
 *   am broadcast -n com.p3dro.scorpio.ime/.dev.BenchReceiver --es mode baseline|compare [--ez nouser true]
 * 輸入是 tools/perf/make_bench_inputs.py 從詞庫隨機挑詞產生的，不含任何人打過的字；log 只記數量與耗時
 */
class BenchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val mode = intent.getStringExtra("mode") ?: "compare"
        // nouser：不看個人學習（跟沒有學習紀錄的舊版比詞庫查詢）
        val noUser = intent.getBooleanExtra("nouser", false)
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                if (noUser) ScorpioDictManager.getInstance(app).withoutUserData { run(app, mode) } else run(app, mode)
            } catch (e: Exception) {
                Log.e(TAG, "基準測試失敗", e)
                File(outDir(app), "summary.txt").writeText("error ${e.javaClass.simpleName}\n")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun outDir(context: Context) = File(context.getExternalFilesDir(null), "bench").apply { mkdirs() }

    private fun run(context: Context, mode: String) {
        val dict = ScorpioDictManager.getInstance(context)
        val dir = outDir(context)
        File(dir, "summary.txt").delete()
        val inputs = context.assets.open("bench/inputs.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        // 從冷的快取開始，耗時才比得出詞庫查詢本身的差別
        dict.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        val results = ArrayList<String>(inputs.size * 4)
        val zTimes = ArrayList<Long>()
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
                            val s = System.nanoTime()
                            val g = dict.getCandidateGroups(composer.getAllSegmentations(), composer.hasExplicitTone(), prev, composer.getTouchPoints())
                            zTimes.add(System.nanoTime() - s)
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
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        val sorted = zTimes.sorted()
        fun pct(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()] / 1e6
        val timing = "選字 ${sorted.size} 次：中位數 %.2f ms、p90 %.2f ms、p99 %.2f ms、最慢 %.2f ms；全部 $totalMs ms"
            .format(pct(0.5), pct(0.9), pct(0.99), pct(1.0))

        val out = File(dir, if (mode == "baseline") "baseline.txt" else "current.txt")
        out.writeText(results.joinToString("\n") + "\n")
        val summary = StringBuilder("$mode：${results.size} 筆結果\n$timing\n")
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
