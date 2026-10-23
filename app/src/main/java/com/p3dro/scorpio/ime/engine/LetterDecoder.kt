package com.p3dro.scorpio.ime.engine

import kotlin.math.ln
import kotlin.math.log10

/**
 * QWERTY 字母鍵 → 注音音節解碼（漢語拼音、許氏、倚天 26 鍵、大千 26 鍵）
 *
 * 解出來的注音音節直接交給現有的整句選字（同一份詞庫、學習、前後文），只是按鍵的意義不同：
 * - 漢語拼音：zhong → ㄓㄨㄥ；可只打聲母縮寫（kbd → ㄎ ㄅ ㄉ）；最後一段打到一半時補成最常見的音節
 * - 26 鍵注音：一個鍵可能代表兩三個注音或聲調（許氏 d = ㄉ／ˊ），依合法音節與音節字頻挑最可能的切分
 *
 * 鍵位表與 libchewing（key2pho.c）相同：41 個符號依序為 ㄅ…ㄙ、ㄧㄨㄩ、ㄚ…ㄦ、˙ˊˇˋ
 */
class LetterDecoder(val kind: Kind) {

    enum class Kind { PINYIN, HSU, ETEN26, DACHEN26 }

    /** 解出的一個音節：注音（可能含聲調）、消耗的按鍵數、是否只是聲母縮寫 */
    data class Unit(val zhuyin: String, val keyCount: Int, val letters: String)

    data class Segmentation(val units: List<Unit>, val score: Double)

    companion object {
        private const val SYMBOLS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ˙ˊˇˋ"
        private val LAYOUTS = mapOf(
            Kind.HSU to "bpmfdtnlgkhjvcjvcrzasexuyhgeiawomnkllsdfj",
            Kind.ETEN26 to "bpmfdtnlvkhgvcgycjqwsexuaorwiqzpmntlhdfjk",
            Kind.DACHEN26 to "qqazwwsxedcrfvttgbyhnujmuikbiolmoplnpyerd"
        )

        /** 各佈局：字母 → 可能代表的注音符號 */
        fun keyMap(kind: Kind): Map<Char, List<Char>> {
            val keys = LAYOUTS[kind] ?: return emptyMap()
            val map = LinkedHashMap<Char, MutableList<Char>>()
            for (i in SYMBOLS.indices) map.getOrPut(keys[i]) { ArrayList() }.add(SYMBOLS[i])
            return map
        }

        private val PINYIN_INITIALS = linkedMapOf(
            "zh" to 'ㄓ', "ch" to 'ㄔ', "sh" to 'ㄕ',
            "b" to 'ㄅ', "p" to 'ㄆ', "m" to 'ㄇ', "f" to 'ㄈ', "d" to 'ㄉ', "t" to 'ㄊ', "n" to 'ㄋ', "l" to 'ㄌ',
            "g" to 'ㄍ', "k" to 'ㄎ', "h" to 'ㄏ', "j" to 'ㄐ', "q" to 'ㄑ', "x" to 'ㄒ', "r" to 'ㄖ',
            "z" to 'ㄗ', "c" to 'ㄘ', "s" to 'ㄙ'
        )

        private val FINAL_PINYIN = mapOf(
            "ㄚ" to "a", "ㄛ" to "o", "ㄜ" to "e", "ㄝ" to "e", "ㄞ" to "ai", "ㄟ" to "ei", "ㄠ" to "ao", "ㄡ" to "ou",
            "ㄢ" to "an", "ㄣ" to "en", "ㄤ" to "ang", "ㄥ" to "eng", "ㄦ" to "er",
            "ㄧ" to "i", "ㄧㄚ" to "ia", "ㄧㄛ" to "io", "ㄧㄝ" to "ie", "ㄧㄞ" to "iai", "ㄧㄠ" to "iao", "ㄧㄡ" to "iu",
            "ㄧㄢ" to "ian", "ㄧㄣ" to "in", "ㄧㄤ" to "iang", "ㄧㄥ" to "ing",
            "ㄨ" to "u", "ㄨㄚ" to "ua", "ㄨㄛ" to "uo", "ㄨㄞ" to "uai", "ㄨㄟ" to "ui", "ㄨㄢ" to "uan", "ㄨㄣ" to "un",
            "ㄨㄤ" to "uang", "ㄨㄥ" to "ong",
            "ㄩ" to "v", "ㄩㄝ" to "ve", "ㄩㄢ" to "van", "ㄩㄣ" to "vn", "ㄩㄥ" to "iong"
        )

        private val ZERO_INITIAL_PINYIN = mapOf(
            "ㄧ" to "yi", "ㄧㄚ" to "ya", "ㄧㄛ" to "yo", "ㄧㄝ" to "ye", "ㄧㄞ" to "yai", "ㄧㄠ" to "yao", "ㄧㄡ" to "you",
            "ㄧㄢ" to "yan", "ㄧㄣ" to "yin", "ㄧㄤ" to "yang", "ㄧㄥ" to "ying",
            "ㄨ" to "wu", "ㄨㄚ" to "wa", "ㄨㄛ" to "wo", "ㄨㄞ" to "wai", "ㄨㄟ" to "wei", "ㄨㄢ" to "wan", "ㄨㄣ" to "wen",
            "ㄨㄤ" to "wang", "ㄨㄥ" to "weng",
            "ㄩ" to "yu", "ㄩㄝ" to "yue", "ㄩㄢ" to "yuan", "ㄩㄣ" to "yun", "ㄩㄥ" to "yong"
        )

        /** 注音（不含聲調）→ 漢語拼音拼法（可能有多種寫法，例如 ㄋㄩㄝ = nve／nue） */
        fun pinyinSpellings(zhuyin: String): List<String> {
            if (zhuyin.isEmpty()) return emptyList()
            val first = zhuyin[0]
            val initial = PINYIN_INITIALS.entries.firstOrNull { it.value == first }?.key
            if (initial == null) {
                ZERO_INITIAL_PINYIN[zhuyin]?.let { return listOf(it) }
                return listOfNotNull(FINAL_PINYIN[zhuyin]?.takeIf { zhuyin.length == 1 || zhuyin[0] !in "ㄧㄨㄩ" })
            }
            val rest = zhuyin.substring(1)
            if (rest.isEmpty()) {
                // ㄓㄔㄕㄖㄗㄘㄙ 單獨成音節 = zhi chi shi ri zi ci si
                return if (first in BopomofoComposer.STANDALONE_INITIALS) listOf(initial + "i") else emptyList()
            }
            val fin = FINAL_PINYIN[rest] ?: return emptyList()
            return when {
                // ㄐㄑㄒ 後的 ㄩ 寫成 u
                first in "ㄐㄑㄒ" && rest.startsWith("ㄩ") -> listOf(initial + fin.replaceFirst("v", "u"))
                // ㄋㄌ + ㄩㄝ 常寫成 nue／lue
                rest == "ㄩㄝ" -> listOf(initial + fin, initial + "ue")
                // ㄅㄆㄇㄈ + ㄛ = bo po mo fo；ㄨㄥ 前有聲母寫 ong
                else -> listOf(initial + fin)
            }
        }
    }

    /** 合法音節（不含聲調）與字頻 */
    @Volatile
    private var freq: Map<String, Double> = emptyMap()
    private var total = 1.0
    private var initialMass: Map<Char, Double> = emptyMap()
    // 漢語拼音：拼法 → 注音
    private var pinyinTable: Map<String, String> = emptyMap()
    private var pinyinPrefixBest: Map<String, String> = emptyMap()
    private val keys: Map<Char, List<Char>> = keyMap(kind)

    fun setSyllableFrequencies(all: Map<String, Double>) {
        // 詞庫裡只有聲調、沒有注音的項目會變成空音節，略過
        val f = all.filterKeys { it.isNotEmpty() }
        total = f.values.sum().coerceAtLeast(1e-9)
        val im = HashMap<Char, Double>()
        for ((s, v) in f) im[s[0]] = (im[s[0]] ?: 0.0) + v
        initialMass = im
        if (kind == Kind.PINYIN) {
            val table = HashMap<String, String>()
            for (s in f.keys) for (py in pinyinSpellings(s)) {
                val cur = table[py]
                if (cur == null || (f[s] ?: 0.0) > (f[cur] ?: 0.0)) table[py] = s
            }
            pinyinTable = table
            // 打到一半的最後一段（zhon）→ 最常見的完整音節（zhong）
            val best = HashMap<String, String>()
            for ((py, z) in table) for (i in 1 until py.length) {
                val p = py.substring(0, i)
                val cur = best[p]
                if (cur == null || (f[z] ?: 0.0) > (f[cur] ?: 0.0)) best[p] = z
            }
            pinyinPrefixBest = best
        }
        freq = f
    }

    val isReady: Boolean get() = freq.isNotEmpty()

    private fun logP(zhuyin: String): Double = log10(((freq[zhuyin] ?: 0.0) / total).coerceAtLeast(1e-9))

    // 只打聲母（簡拼）的代價：以這個聲母開頭的音節總使用量，再扣固定懲罰，讓完整音節優先
    private fun abbrevLogP(initial: Char): Double = log10(((initialMass[initial] ?: 0.0) / total).coerceAtLeast(1e-9)) - 1.2

    /**
     * 字母序列 → 最可能的幾種音節切分（分數高的在前）
     * 'ˉ'（空白鍵的一聲）與 '\''（拼音分隔）視為音節邊界
     */
    fun decode(letters: List<Char>, maxResults: Int = 4): List<Segmentation> {
        if (letters.isEmpty() || freq.isEmpty()) return emptyList()
        val n = letters.size
        // beam[i] = 解到第 i 個字母為止的前幾名部分切分
        val beam = Array(n + 1) { ArrayList<Segmentation>() }
        beam[0].add(Segmentation(emptyList(), 0.0))
        val width = 8
        for (i in 0 until n) {
            if (beam[i].isEmpty()) continue
            val c = letters[i]
            if (c == BopomofoComposer.FIRST_TONE || c == '\'') {
                // 一聲／分隔：接在前一個音節後面，不另成音節
                for (seg in beam[i]) {
                    val units = seg.units
                    val merged = if (c == BopomofoComposer.FIRST_TONE && units.isNotEmpty() &&
                        units.last().zhuyin.none { it in BopomofoComposer.TONES }
                    ) {
                        units.dropLast(1) + units.last().copy(zhuyin = units.last().zhuyin + c, keyCount = units.last().keyCount + 1)
                    } else if (units.isNotEmpty()) {
                        units.dropLast(1) + units.last().copy(keyCount = units.last().keyCount + 1)
                    } else units
                    push(beam[i + 1], Segmentation(merged, seg.score), width)
                }
                continue
            }
            for ((unit, score) in unitsAt(letters, i)) {
                for (seg in beam[i]) push(beam[i + unit.keyCount], Segmentation(seg.units + unit, seg.score + score), width)
            }
        }
        return beam[n].sortedByDescending { it.score }.take(maxResults)
    }

    private fun push(list: ArrayList<Segmentation>, s: Segmentation, width: Int) {
        if (list.size < width) {
            list.add(s)
            return
        }
        val worst = list.indices.minByOrNull { list[it].score } ?: return
        if (s.score > list[worst].score) list[worst] = s
    }

    /** 從位置 i 開始的所有可能音節與其分數 */
    private fun unitsAt(letters: List<Char>, i: Int): List<Pair<Unit, Double>> =
        if (kind == Kind.PINYIN) pinyinUnits(letters, i) else zhuyinUnits(letters, i)

    private fun pinyinUnits(letters: List<Char>, i: Int): List<Pair<Unit, Double>> {
        val out = ArrayList<Pair<Unit, Double>>()
        val sb = StringBuilder()
        val atEnd = { j: Int -> j == letters.size }
        for (j in i until minOf(letters.size, i + 6)) {
            val c = letters[j]
            if (c !in 'a'..'z') break
            sb.append(c)
            val s = sb.toString()
            pinyinTable[s]?.let { z -> out.add(Unit(z, s.length, s) to logP(z)) }
            // 聲母縮寫（b、zh…）
            PINYIN_INITIALS[s]?.let { ini -> out.add(Unit(ini.toString(), s.length, s) to abbrevLogP(ini)) }
            if (s == "y") out.add(Unit("ㄧ", 1, s) to abbrevLogP('ㄧ'))
            if (s == "w") out.add(Unit("ㄨ", 1, s) to abbrevLogP('ㄨ'))
            // 最後一段打到一半（zhon）；只剩聲母（d、zh）時當簡拼，不硬補成某個音節
            if (atEnd(j + 1) && pinyinTable[s] == null && s !in PINYIN_INITIALS && s != "y" && s != "w") {
                pinyinPrefixBest[s]?.let { z -> out.add(Unit(z, s.length, s) to logP(z) - 0.5) }
            }
        }
        return out
    }

    private fun zhuyinUnits(letters: List<Char>, i: Int): List<Pair<Unit, Double>> {
        val out = ArrayList<Pair<Unit, Double>>()
        // 先組出 1~4 個符號的音節（不含聲調），每個鍵可能代表多個符號
        fun extend(j: Int, cur: String) {
            if (cur.isNotEmpty()) {
                val valid = freq.containsKey(cur)
                val abbrev = cur.length == 1 && cur[0] in BopomofoComposer.INITIALS
                if (valid || abbrev) {
                    val base = if (valid) logP(cur) else abbrevLogP(cur[0])
                    val keyCount = j - i
                    out.add(Unit(cur, keyCount, "") to base)
                    // 後面接聲調鍵
                    if (j < letters.size) {
                        for (sym in keys[letters[j]].orEmpty()) {
                            if (sym in BopomofoComposer.TONES) out.add(Unit(cur + sym, keyCount + 1, "") to base + 0.1)
                        }
                    }
                }
            }
            if (j >= letters.size || cur.length >= 4) return
            for (sym in keys[letters[j]].orEmpty()) {
                if (sym in BopomofoComposer.TONES) continue
                val next = cur + sym
                // 只往合法音節的開頭延伸
                if (prefixValid(next)) extend(j + 1, next)
            }
        }
        extend(i, "")
        return out
    }

    @Volatile
    private var prefixes: Set<String>? = null

    private fun prefixValid(p: String): Boolean {
        val set = prefixes ?: HashSet<String>().also { s ->
            for (z in freq.keys) for (k in 1..z.length) s.add(z.substring(0, k))
            prefixes = s
        }
        return p in set
    }

    /**
     * 按下當下的字母先驗：接上 c 之後最好的切分分數（自然對數），越高越可能；完全解不出來回傳 null
     */
    fun bestScoreWith(letters: List<Char>, c: Char): Double? =
        decode(letters + c, 1).firstOrNull()?.score?.times(ln(10.0))
}
