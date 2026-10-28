package com.p3dro.scorpio.ime.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 長按副鍵（比照 Gboard）：每個字鍵長按可以輸入的字，鍵的右上角顯示第一個當提示。
 *
 * 版面檔的 "long"（選填）寫在預設值上面：{"ㄅ":"1", "e":"éèê", "q":["1","!"], "a":""}
 * - 字串：每個字是一個選項；陣列：每個元素是一個選項（可以是好幾個字）；空字串或空陣列是這個鍵不要副鍵
 * - 只有一個選項：長按放開就輸入它；好幾個：長按跳出一排，手指左右滑選，放開輸入
 * 沒寫的鍵用預設值：大千第一排數字、其他排是實體鍵盤上的英文字母（跟 Gboard 一樣），QWERTY 第一排數字、其他排常用符號。
 * 舊版 App 不認得 "long"，照舊沒有副鍵，所以版面檔加這個欄位不必提高 minAppVersion
 */
object LongPressKeys {

    const val MAX_OPTIONS = 8
    const val MAX_OPTION_LENGTH = 8

    private fun spread(keys: String, values: List<String>): Map<Char, List<String>> =
        keys.toList().zip(values).associate { (k, v) -> k to listOf(v) }

    private fun chars(s: String) = s.map { it.toString() }

    /** 內建大千 10×4（也是宣告的大千版面的預設）：第一排 1–0；其他排是標準鍵盤上同位置的字母、標點（全形） */
    val DACHEN: Map<Char, List<String>> =
        spread("ㄅㄉˇˋㄓˊ˙ㄚㄞㄢ", chars("1234567890")) +
            spread("ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ", chars("qwertyuiop")) +
            spread("ㄇㄋㄎㄑㄕㄘㄨㄜㄠ", chars("asdfghjkl")) +
            spread("ㄈㄌㄏㄒㄖㄙㄩ", chars("zxcvbnm")) +
            mapOf('ㄤ' to listOf("：", "；"), 'ㄝ' to listOf("…", "、"), 'ㄡ' to listOf("！"), 'ㄥ' to listOf("？"))

    /** 英文 QWERTY：第一排 1–0、其他排常用符號（跟 Gboard 一樣） */
    val QWERTY: Map<Char, List<String>> =
        spread("qwertyuiop", chars("1234567890")) +
            spread("asdfghjkl", chars("@#\$_&-+()")) +
            spread("zxcvbnm", chars("*\"':;!?"))

    /** 中文的 QWERTY 版面（26 鍵注音、拼音、倉頡…）：數字一樣，標點用全形 */
    val CHINESE_QWERTY: Map<Char, List<String>> =
        QWERTY + spread("klzxcvbnm", chars("（）＊「」：；！？"))

    /**
     * 解析版面檔的 "long"；null（沒寫）回傳空的覆寫。格式不對回傳 null（跟版面檔其他欄位一樣，整個版面不收）。
     * keys：這個版面有的字鍵，long 只能寫這些鍵
     */
    fun parse(o: JSONObject?, keys: Set<Char>): Map<Char, List<String>>? {
        if (o == null) return emptyMap()
        return try {
            buildMap {
                for (name in o.keys()) {
                    if (name.length != 1 || name[0] !in keys) return null
                    val options = when (val v = o.get(name)) {
                        is String -> codePoints(v)
                        is JSONArray -> (0 until v.length()).map { v.getString(it) }
                        else -> return null
                    }
                    if (options.size > MAX_OPTIONS || options.any { !validOption(it) }) return null
                    put(name[0], options)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 預設值加上版面的覆寫（覆寫成空的就是不要副鍵） */
    fun merge(defaults: Map<Char, List<String>>, overrides: Map<Char, List<String>>): Map<Char, List<String>> =
        (defaults + overrides).filterValues { it.isNotEmpty() }

    private fun validOption(s: String) =
        s.isNotEmpty() && s.codePointCount(0, s.length) <= MAX_OPTION_LENGTH && s.none { it.isISOControl() || it.isWhitespace() }

    /** 一個字一個選項（補充平面的字不拆開） */
    private fun codePoints(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val n = Character.charCount(s.codePointAt(i))
            out.add(s.substring(i, i + n))
            i += n
        }
        return out
    }
}
