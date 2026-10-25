package com.p3dro.scorpio.ime.engine

import kotlin.math.log10

/**
 * 讀音混用（模糊音）每個人不一樣：前後鼻音（ㄣ／ㄥ、ㄧㄣ／ㄧㄥ、ㄢ／ㄤ）、平捲舌（ㄓㄗ、ㄔㄘ、ㄕㄙ）、ㄛ／ㄜ 等。
 * 每一種「打了 x、其實想打 y」各記一個比例：打出 x 的時候，有多少次選的是讀 y 的字。
 * 從預設比例開始（相當於 PRIOR_WEIGHT 次的經驗），照你實際選的字更新；換成懲罰時跟鄰鍵懲罰同一個尺度（×1.8 是 log10）。
 *
 * 預設比例在鍵盤設定（ImeConfig 的 fuzzyPriors，跟著資料 repo 更新），參考台灣華語的研究：ㄧㄣ／ㄧㄥ、ㄣ／ㄥ 混得多，
 * ㄢ／ㄤ 少很多（中研院 Chiu）；平捲舌從完全不分到分得很清楚都有（Chuang 等 2019）。所以預設保守，你常這樣選才變便宜，
 * 從來不這樣選的會越來越貴。鍵是「打的注音＋想打的注音」，ㄣ／ㄥ 前面是 ㄧ（ㄧㄣ／ㄧㄥ）時另外算、加 i
 */
class FuzzySounds(private val priors: () -> Map<String, Double>) {

    companion object {
        // 預設比例相當於幾次經驗：選過十來次就跟著你的習慣走
        private const val PRIOR_WEIGHT = 10.0
        // 每次更新舊資料的權重（大約跟著最近幾百個音節走）
        private const val DECAY = 0.998
        private const val MIN_PENALTY = 0.2
        private const val MAX_PENALTY = 2.5

        private fun ruleKey(typed: String, i: Int, intended: Char): String =
            "${typed[i]}$intended" + if ((typed[i] == 'ㄣ' || typed[i] == 'ㄥ') && i > 0 && typed[i - 1] == 'ㄧ') "i" else ""
    }

    // 每種混用：[這樣選的次數, 打出 x 的次數]，都帶遺忘權重
    private val counts = HashMap<String, DoubleArray>()

    /** 音節 typed（無聲調）可以換成哪些讀音、各自的懲罰 */
    @Synchronized
    fun variants(typed: String): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        for (i in typed.indices) {
            for ((rule, prior) in priors()) {
                if (rule[0] != typed[i]) continue
                val key = ruleKey(typed, i, rule[1])
                if (key != rule) continue
                out.add(typed.substring(0, i) + rule[1] + typed.substring(i + 1) to penalty(key, prior))
            }
        }
        return out
    }

    private fun penalty(key: String, prior: Double): Double {
        val c = counts[key]
        val p = if (c == null) prior else (PRIOR_WEIGHT * prior + c[0]) / (PRIOR_WEIGHT + c[1])
        return (-log10(p) / 1.8).coerceIn(MIN_PENALTY, MAX_PENALTY)
    }

    /**
     * 選字確定後的一個音節：打的是 typed、字的讀音是 intended（無聲調，同長度）。
     * 打出來的每個可以混用的注音都算一次「打出 x」；跟 intended 不同、而且正好是某種混用的，再算一次「這樣選」
     */
    @Synchronized
    fun observe(typed: String, intended: String): Boolean {
        if (typed.length != intended.length) return false
        var changed = false
        for (i in typed.indices) {
            for (rule in priors().keys) {
                if (rule[0] != typed[i]) continue
                val key = ruleKey(typed, i, rule[1])
                if (key != rule) continue
                for (c in counts.values) { c[0] *= DECAY; c[1] *= DECAY }
                val c = counts.getOrPut(key) { DoubleArray(2) }
                c[1] += 1.0
                if (intended[i] == rule[1]) c[0] += 1.0
                changed = true
            }
        }
        return changed
    }

    @Synchronized
    fun serialize(): String = counts.entries.joinToString(";") { (k, c) -> "$k:${c[0]}:${c[1]}" }

    @Synchronized
    fun restore(s: String?) {
        counts.clear()
        s?.split(';')?.forEach { e ->
            val p = e.split(':')
            if (p.size == 3) {
                val a = p[1].toDoubleOrNull() ?: return@forEach
                val b = p[2].toDoubleOrNull() ?: return@forEach
                counts[p[0]] = doubleArrayOf(a, b)
            }
        }
    }
}
