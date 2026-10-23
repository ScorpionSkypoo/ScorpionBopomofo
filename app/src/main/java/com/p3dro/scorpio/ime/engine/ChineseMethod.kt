package com.p3dro.scorpio.ime.engine

/**
 * 中文輸入法：大千注音用 10×4 注音鍵盤，其餘用 QWERTY 鍵盤（按鍵標上該輸入法的符號）
 * 設定存 id；啟用的輸入法以地球鍵依序切換（最後是英文）
 */
enum class ChineseMethod(val id: String, val label: String, val spaceLabel: String, val decoderKind: LetterDecoder.Kind?) {
    DACHEN("dachen", "注音（大千）", "注音", null),
    HSU("hsu", "注音（許氏）", "注音·許氏", LetterDecoder.Kind.HSU),
    ETEN26("eten26", "注音（倚天 26 鍵）", "注音·倚天", LetterDecoder.Kind.ETEN26),
    DACHEN26("dachen26", "注音（大千 26 鍵）", "注音·大千26", LetterDecoder.Kind.DACHEN26),
    PINYIN("pinyin", "漢語拼音", "拼音", LetterDecoder.Kind.PINYIN),
    CANGJIE("cangjie", "倉頡", "倉頡", null),
    QUICK("quick", "速成", "速成", null);

    val usesQwerty: Boolean get() = this != DACHEN

    /** 倉頡、速成：查碼表，不經注音 */
    val isCodeTable: Boolean get() = this == CANGJIE || this == QUICK
    val maxCodeLength: Int get() = if (this == QUICK) 2 else 5

    companion object {
        const val PREF_ENABLED = "chinese_methods"
        const val PREF_CURRENT = "chinese_method"

        /** 倉頡字根（a = 日 … z = 重） */
        val CANGJIE_RADICALS: Map<Char, Char> = "日月金木水火土竹戈十大中一弓人心手口尸廿山女田難卜重"
            .mapIndexed { i, r -> ('a' + i) to r }.toMap()

        fun byId(id: String?): ChineseMethod? = entries.firstOrNull { it.id == id }

        /** 已啟用的輸入法（依清單順序；至少一個） */
        fun enabled(prefs: android.content.SharedPreferences): List<ChineseMethod> {
            val ids = prefs.getString(PREF_ENABLED, DACHEN.id).orEmpty().split(',')
            return entries.filter { it.id in ids }.ifEmpty { listOf(DACHEN) }
        }
    }
}
