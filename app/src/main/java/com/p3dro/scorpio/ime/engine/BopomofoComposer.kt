package com.p3dro.scorpio.ime.engine

/**
 * 台灣注音符號狀態機與緩衝區管理
 * 支援精準單鍵退格刪除、免聲調極速盲打與連續音節解析
 */
class BopomofoComposer {

    companion object {
        val INITIALS = setOf(
            'ㄅ', 'ㄆ', 'ㄇ', 'ㄈ', 'ㄉ', 'ㄊ', 'ㄋ', 'ㄌ',
            'ㄍ', 'ㄎ', 'ㄏ', 'ㄐ', 'ㄑ', 'ㄒ', 'ㄓ', 'ㄔ',
            'ㄕ', 'ㄖ', 'ㄗ', 'ㄘ', 'ㄙ'
        )

        val MEDIALS = setOf('ㄧ', 'ㄨ', 'ㄩ')

        val FINALS = setOf(
            'ㄚ', 'ㄛ', 'ㄜ', 'ㄝ', 'ㄞ', 'ㄟ', 'ㄠ', 'ㄡ',
            'ㄢ', 'ㄣ', 'ㄤ', 'ㄥ', 'ㄦ'
        )

        // 'ˉ' 為一聲（空白鍵輸入，字典中一聲無符號）
        val TONES = setOf('ˊ', 'ˇ', 'ˋ', '˙', 'ˉ')
        const val FIRST_TONE = 'ˉ'

        val STANDALONE_INITIALS = setOf('ㄓ', 'ㄔ', 'ㄕ', 'ㄖ', 'ㄗ', 'ㄘ', 'ㄙ')

        // 國語語音規則中不能與「ㄧ」拼合的聲母（無 ㄍㄧ, ㄎㄧ, ㄏㄧ, ㄓㄧ, ㄔㄧ, ㄕㄧ, ㄖㄧ, ㄗㄧ, ㄘㄧ, ㄙㄧ）
        val INCOMPATIBLE_WITH_I = setOf(
            'ㄍ', 'ㄎ', 'ㄏ', 'ㄓ', 'ㄔ', 'ㄕ', 'ㄖ', 'ㄗ', 'ㄘ', 'ㄙ'
        )

        // 國語語音規則中不能與「ㄩ」拼合的聲母（僅 ㄋ, ㄌ, ㄐ, ㄑ, ㄒ 可與 ㄩ 拼合）
        val INCOMPATIBLE_WITH_U = setOf(
            'ㄅ', 'ㄆ', 'ㄇ', 'ㄈ', 'ㄉ', 'ㄊ',
            'ㄍ', 'ㄎ', 'ㄏ', 'ㄓ', 'ㄔ', 'ㄕ', 'ㄖ', 'ㄗ', 'ㄘ', 'ㄙ'
        )

        /** keyCount：此音節實際消耗的按鍵數（補上的介音不算），部分選字時據此移除按鍵 */
        data class ParsedSyllable(val text: String, val touches: Map<Char, TouchPoint>, val keyCount: Int)

        private fun repairMissingMedial(init: Char?, med: Char?, fin: Char?): Char? {
            if (init == null || med != null || fin == null) return med
            if (init == 'ㄐ' || init == 'ㄑ' || init == 'ㄒ') {
                return if (fin == 'ㄛ' || fin == 'ㄜ') 'ㄩ' else 'ㄧ'
            }
            if (fin == 'ㄛ' && !setOf('ㄅ', 'ㄆ', 'ㄇ', 'ㄈ').contains(init)) {
                return 'ㄨ'
            }
            if (fin == 'ㄟ' && setOf('ㄊ', 'ㄘ', 'ㄙ', 'ㄓ', 'ㄔ', 'ㄖ', 'ㄎ').contains(init)) {
                return 'ㄨ'
            }
            return med
        }

        /**
         * 將按鍵序列解析為注音音節（附各音節的觸控點）；供 composer 與鄰鍵修正共用
         */
        /**
         * keepIncompatible：聲母後面接了拼不起來的介音（ㄏ＋ㄩ）也不拆開，整個當一個（拼不起來的）音節。
         * 一般組字會拆成「ㄏ（簡拼）＋ㄩㄢ」；按錯鄰鍵的修正與學習要用不拆開的看（ㄏㄩㄢ 其實是 ㄒㄩㄢ）
         */
        fun parseSyllables(points: List<TouchPoint>, allowSwap: Boolean = false, keepIncompatible: Boolean = false): List<ParsedSyllable> {
            val syllables = mutableListOf<ParsedSyllable>()
            var curInit: Char? = null
            var curMed: Char? = null
            var curFin: Char? = null
            var curTone: Char? = null
            var curTouches = HashMap<Char, TouchPoint>()
            var curKeys = 0

            fun commitSyllable() {
                val repairedMed = repairMissingMedial(curInit, curMed, curFin)
                val sb = StringBuilder()
                curInit?.let { sb.append(it) }
                repairedMed?.let { sb.append(it) }
                curFin?.let { sb.append(it) }
                curTone?.let { sb.append(it) }
                val s = sb.toString()
                if (s.isNotEmpty()) {
                    syllables.add(ParsedSyllable(s, curTouches, curKeys))
                }
                curKeys = 0
                curInit = null
                curMed = null
                curFin = null
                curTone = null
                curTouches = HashMap()
            }

            for (p in points) {
                val c = p.char
                when {
                    INITIALS.contains(c) -> {
                        // 左右手搶拍順序顛倒：先按韻母才按聲母（如 ㄠㄏ -> ㄏㄠ）
                        if (allowSwap && curInit == null && curMed == null && curTone == null && curFin != null) {
                            curInit = c
                        } else {
                            if (curInit != null || curMed != null || curFin != null) {
                                commitSyllable()
                            }
                            curInit = c
                        }
                    }
                    MEDIALS.contains(c) -> {
                        // 若當前聲母與該介母不相容（如 ㄎ與ㄧ、ㄍ與ㄧ、ㄓ與ㄧ、ㄅ與ㄩ等），聲母必須獨立成音節，介母開展新音節
                        val isIncompatible = !keepIncompatible && (curInit != null) && (
                            (c == 'ㄧ' && INCOMPATIBLE_WITH_I.contains(curInit)) ||
                            (c == 'ㄩ' && INCOMPATIBLE_WITH_U.contains(curInit))
                        )
                        // 左右手搶拍順序顛倒：先按韻母才補按相容介音（如 ㄒㄠㄧ -> ㄒㄧㄠ）
                        if (allowSwap && curMed == null && curFin != null && !isIncompatible) {
                            curMed = c
                        } else {
                            if (curMed != null || curFin != null || isIncompatible) {
                                commitSyllable()
                            }
                            curMed = c
                        }
                    }
                    FINALS.contains(c) -> {
                        if (curFin != null) {
                            commitSyllable()
                        }
                        curFin = c
                    }
                    TONES.contains(c) -> {
                        curTone = c
                        curTouches[c] = p
                        curKeys++
                        commitSyllable()
                        continue
                    }
                }
                curTouches[c] = p
                curKeys++
            }
            commitSyllable()
            return syllables
        }
    }

    /**
     * 一次按鍵：char 為判定後的鍵；rawChar 為手指實際落在的鍵，offset 是相對 rawChar 中心的落點（半鍵為 1）
     * 空間模型可能依落點、個人偏移或音節合法性把 rawChar 改判成鄰鍵
     */
    data class TouchPoint(
        val char: Char,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val rawChar: Char = char,
        /** 上一下的落點（大千鍵盤座標，鍵距單位）；連續打字才有，沒有是 NaN（剛開始打、停頓太久、中間按了別的鍵） */
        val fromX: Float = Float.NaN,
        val fromY: Float = Float.NaN
    ) {
        val from: Pair<Double, Double>? get() = if (fromX.isNaN()) null else fromX.toDouble() to fromY.toDouble()
    }

    // 儲存原始輸入的注音符號歷史堆疊與 (x, y) 座標偏移，保證按一下 Backspace 絕對只刪除一個鍵！
    private val touchHistory = mutableListOf<TouchPoint>()

    /**
     * 字母鍵輸入法（漢語拼音、26 鍵注音）：按鍵存的是字母，由解碼器轉成注音音節；null 為大千注音（按鍵即注音）
     */
    var decoder: LetterDecoder? = null
        set(value) {
            field = value
            touchHistory.clear()
            decodeCache = null
        }

    private var decodeCache: Pair<List<Char>, List<LetterDecoder.Segmentation>>? = null

    private fun decoded(): List<LetterDecoder.Segmentation> {
        val d = decoder ?: return emptyList()
        val keys = keyHistory
        decodeCache?.let { if (it.first == keys) return it.second }
        return d.decode(keys, 4).also { decodeCache = keys to it }
    }

    /** 主要切分：大千直接解析注音；字母鍵輸入法取解碼最佳切分（解不出來時整串當一個音節，不會有候選） */
    private fun parsed(): List<ParsedSyllable> {
        if (decoder == null) return parseSyllables(touchHistory)
        val best = decoded().firstOrNull()
            ?: return if (touchHistory.isEmpty()) emptyList() else listOf(ParsedSyllable(rawInputText, emptyMap(), touchHistory.size))
        return best.units.map { ParsedSyllable(it.zhuyin, emptyMap(), it.keyCount) }
    }

    val isPinyin: Boolean get() = decoder?.kind == LetterDecoder.Kind.PINYIN

    val keyHistory: List<Char>
        get() = touchHistory.map { it.char }

    val isComposing: Boolean
        get() = touchHistory.isNotEmpty()

    val rawInputText: String
        get() = touchHistory.map { it.char }.joinToString("")

    fun append(c: Char, offsetX: Float = 0f, offsetY: Float = 0f) {
        touchHistory.add(TouchPoint(c, offsetX, offsetY))
    }

    fun append(touch: TouchPoint) {
        touchHistory.add(touch)
    }

    fun getTouchPoints(): List<TouchPoint> = touchHistory.toList()

    /** 不把拼不起來的聲母＋介音拆開時的音節數（ㄏ＋ㄩㄢ 算一個 ㄏㄩㄢ）；字母鍵輸入法照解碼結果 */
    fun mergedSyllableCount(): Int =
        if (decoder != null) parsed().size else parseSyllables(touchHistory, keepIncompatible = true).size

    /**
     * 退格刪除：保證一次只精準刪除最後輸入的一個注音符號
     * @return true 若還有剩餘未完成拼音；false 若已經全部刪空
     */
    fun backspace(): Boolean {
        if (touchHistory.isNotEmpty()) {
            touchHistory.removeAt(touchHistory.size - 1)
        }
        return touchHistory.isNotEmpty()
    }

    fun clear() {
        touchHistory.clear()
    }

    /**
     * 消費指定數量的注音音節（當使用者在多音節輸入中選擇部分字詞時使用）
     * 例如輸入「ㄏㄠˇ ㄉㄚ」，選了「好」(1個音節)，則移除「ㄏㄠˇ」，保留「ㄉㄚ」
     * @return 剩餘未消費的音節數
     */
    fun consumeSyllables(count: Int): Int {
        if (count <= 0) return getAllSyllables().size
        val syllables = parsed()
        if (count >= syllables.size) {
            clear()
            return 0
        }
        // 依實際按鍵數移除（ㄐㄢ 會補成 ㄐㄧㄢ，但只按了兩個鍵）
        val keysToDrop = syllables.take(count).sumOf { it.keyCount }
        repeat(keysToDrop) {
            if (touchHistory.isNotEmpty()) {
                touchHistory.removeAt(0)
            }
        }
        return getAllSyllables().size
    }

    /** 前 count 個音節實際按下的鍵（含落點），供個人化偏移學習 */
    fun touchPointsForSyllables(count: Int): List<TouchPoint> {
        val keys = parsed().take(count).sumOf { it.keyCount }
        return touchHistory.take(keys)
    }

    fun hasExplicitTone(): Boolean {
        if (decoder != null) return parsed().any { syl -> syl.text.any { TONES.contains(it) } }
        return keyHistory.any { TONES.contains(it) }
    }

    /**
     * 解析目前的 keyHistory 為注音音節列表
     */
    fun getAllSyllables(allowSwap: Boolean = false): List<String> =
        if (decoder != null) parsed().map { it.text } else parseSyllables(touchHistory, allowSwap).map { it.text }

    /** 主要切分中每個音節各自的觸控點（同一符號在不同音節各自保留，不互相覆蓋） */
    fun getSyllableTouches(): List<Map<Char, TouchPoint>> =
        parsed().map { it.touches }

    /**
     * 取得當前尚未完成聲調的音節中已輸入的符號（供 iOS 聲母動態按鍵引導使用）
     */
    fun getCurrentSyllablePart(): String {
        // 漢語拼音沒有空白一聲：空白直接選字
        if (isPinyin) return ""
        val syllables = getAllSyllables()
        if (syllables.isEmpty()) return ""
        if (keyHistory.lastOrNull()?.let { TONES.contains(it) } == true) return ""
        val last = syllables.last()
        if (last.any { TONES.contains(it) }) return ""
        return last
    }

    /**
     * 動態音節切分：支援同時解析貪婪音節與多重可能音節
     * 例如輸入 ㄎㄨㄛ 時，可同時解析出 [ㄎㄨㄛ] (擴、闊) 與 [ㄎㄨ, ㄛ] (酷喔、苦喔)；
     * 輸入 ㄏㄞㄎㄧ 時，自動解析出 [ㄏㄞ, ㄎ, ㄧ] (還可以)
     */
    fun getAllSegmentations(): List<List<String>> {
        if (decoder != null) return decoded().map { seg -> seg.units.map { it.zhuyin } }.ifEmpty { listOf(getAllSyllables()) }
        val primary = getAllSyllables()
        if (primary.isEmpty()) return emptyList()

        val results = mutableListOf<List<String>>()
        results.add(primary)
        // 左右手搶拍顛倒（ㄠㄏ → ㄏㄠ）只作為次要切分，絕不覆蓋主要切分（避免 ㄢ+ㄐㄧㄢ 被誤併成 ㄐㄧㄢ+ㄢ）
        val swapped = getAllSyllables(allowSwap = true)
        if (swapped != primary) results.add(swapped)

        val tonelessRaw = keyHistory.filter { !TONES.contains(it) }.joinToString("")
        if (tonelessRaw.isEmpty() || tonelessRaw.length > 12) {
            return results
        }

        fun isValidToneless(s: String): Boolean {
            return when (s.length) {
                1 -> {
                    val c = s[0]
                    FINALS.contains(c) || MEDIALS.contains(c) || INITIALS.contains(c)
                }
                2 -> {
                    val c0 = s[0]
                    val c1 = s[1]
                    val isInitMed = (INITIALS.contains(c0) && MEDIALS.contains(c1)) &&
                        !(c1 == 'ㄧ' && INCOMPATIBLE_WITH_I.contains(c0)) &&
                        !(c1 == 'ㄩ' && INCOMPATIBLE_WITH_U.contains(c0))
                    isInitMed ||
                    (INITIALS.contains(c0) && FINALS.contains(c1)) ||
                    (MEDIALS.contains(c0) && FINALS.contains(c1))
                }
                3 -> {
                    val c0 = s[0]
                    val c1 = s[1]
                    val c2 = s[2]
                    val isCompatible = !(c1 == 'ㄧ' && INCOMPATIBLE_WITH_I.contains(c0)) &&
                        !(c1 == 'ㄩ' && INCOMPATIBLE_WITH_U.contains(c0))
                    isCompatible && INITIALS.contains(c0) && MEDIALS.contains(c1) && FINALS.contains(c2)
                }
                else -> false
            }
        }

        fun dfs(start: Int, path: MutableList<String>) {
            if (results.size >= 6) return
            if (start == tonelessRaw.length) {
                if (path != primary && !results.contains(path)) {
                    results.add(ArrayList(path))
                }
                return
            }
            val maxLen = minOf(3, tonelessRaw.length - start)
            for (len in 1..maxLen) {
                val sub = tonelessRaw.substring(start, start + len)
                if (isValidToneless(sub)) {
                    path.add(sub)
                    dfs(start + len, path)
                    path.removeAt(path.size - 1)
                }
            }
        }

        dfs(0, mutableListOf())
        return results
    }

    /**
     * 取得給輸入框呈現的底線組合文字（例如：ㄐㄧㄣ ㄊㄧㄢ）
     */
    fun getDisplayComposingText(): String {
        if (isPinyin) {
            // 拼音顯示原本打的字母，依切分加上空格（ni hao）
            val units = decoded().firstOrNull()?.units ?: return rawInputText
            val letters = keyHistory
            val sb = StringBuilder()
            var pos = 0
            for (u in units) {
                if (sb.isNotEmpty()) sb.append(' ')
                for (k in 0 until u.keyCount) {
                    val ch = letters.getOrNull(pos + k) ?: break
                    if (ch != '\'' && ch != FIRST_TONE) sb.append(ch)
                }
                pos += u.keyCount
            }
            return sb.toString()
        }
        val list = getAllSyllables()
        if (list.isEmpty()) return rawInputText
        return list.joinToString(" ").replace("ˉ", "")
    }

    /**
     * 取得搜尋字典用的鍵（例如：ㄐㄧㄣ-ㄊㄧㄢ）
     */
    fun getDictLookupKey(): String {
        val list = getAllSyllables()
        if (list.isEmpty()) return rawInputText
        return list.joinToString("-").replace("ˉ", "")
    }
}
