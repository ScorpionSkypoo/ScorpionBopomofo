package com.p3dro.scorpio.ime.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 鍵盤版面的字鍵區（宣告式，格式見 docs/keyboard-layouts.md）：最下排固定由 App 畫，這裡只描述上面的數字列與字鍵排。
 *
 * 位置以「欄」為單位（一欄＝排寬 ÷ columns）：每排的鍵由左到右依序排，寬度加起來不足 columns 的部分左右平分留空。
 * 按鍵落點學習的座標也從這裡算（鍵距單位）：內建的大千算出來跟原本寫死的 10×4 一樣；
 * QWERTY 第 1、2 排一樣，第 3 排照實際畫面（Shift 占 1.4 欄，z 的中心比原本寫死的左 0.1 鍵）
 */
class KeyboardLayout private constructor(
    val columns: Int,
    /** 數字列的鍵（null 是沒有數字列） */
    val numberRow: List<Char>?,
    val rows: List<List<Key>>
) {

    enum class Type { CHAR, SHIFT, BACKSPACE }

    /** start、width 單位是欄；Shift、刪除的 char 是 null */
    data class Key(val type: Type, val char: Char?, val start: Double, val width: Double)

    // 內容一樣就相等（版面檔每次讀進來都是新的物件，輸入法比較時不必重畫鍵盤）
    override fun equals(other: Any?) =
        other is KeyboardLayout && columns == other.columns && numberRow == other.numberRow && rows == other.rows

    override fun hashCode() = (columns * 31 + (numberRow?.hashCode() ?: 0)) * 31 + rows.hashCode()

    /** 字鍵的中心（鍵距單位）：x 以「第一欄的鍵」中心為 0，y 是第幾排（不含數字列） */
    val centers: Map<Char, Pair<Double, Double>> by lazy {
        buildMap {
            for ((r, row) in rows.withIndex()) for (k in row) {
                if (k.char != null) put(k.char, (k.start + k.width / 2 - 0.5) to r.toDouble())
            }
        }
    }

    /** 字鍵寬度（欄）：觸控偏移以半個鍵寬為 1，寬的鍵換算成鍵距要乘上寬度 */
    val widths: Map<Char, Double> by lazy {
        buildMap { for (row in rows) for (k in row) if (k.char != null) put(k.char, k.width) }
    }

    /**
     * 按鍵落點學習的群組：字鍵位置一樣的版面共用一份習慣（同一個人按同一個位置的習慣一樣）。
     * 跟內建 QWERTY 一樣位置的是 "qwerty"（英文、26 鍵注音、拼音、倉頡、速成原本就共用的那一份），其他由位置算一個指紋
     */
    val geometryId: String by lazy { if (sameGeometry(QWERTY)) QWERTY_GEOMETRY else "g" + fingerprint() }

    fun sameGeometry(other: KeyboardLayout): Boolean {
        if (centers.keys != other.centers.keys) return false
        return centers.all { (c, p) ->
            val q = other.centers[c]!!
            kotlin.math.abs(p.first - q.first) < 1e-9 && kotlin.math.abs(p.second - q.second) < 1e-9 &&
                kotlin.math.abs(widths[c]!! - other.widths[c]!!) < 1e-9
        }
    }

    private fun fingerprint(): String {
        val s = centers.keys.sorted().joinToString(";") { c ->
            val (x, y) = centers[c]!!
            "%s:%.3f,%.3f,%.3f".format(java.util.Locale.ROOT, c, x, y, widths[c]!!)
        }
        val crc = java.util.zip.CRC32().apply { update(s.toByteArray(Charsets.UTF_8)) }
        return "%08x".format(crc.value)
    }

    companion object {
        const val MIN_COLUMNS = 10
        const val MAX_COLUMNS = 12
        const val MAX_ROWS = 4
        const val MAX_CHAR_WIDTH = 2.0
        const val QWERTY_GEOMETRY = "qwerty"

        private class Spec(val type: Type, val char: Char?, val width: Double?)

        /**
         * 解析版面檔的 keys；不合規則回傳 null（跟版面檔其他欄位一樣，裝不上去也不會讓 App 出錯）：
         * - columns 10～12（預設 10）；字鍵排 1～4 排；數字列只能是數字、不超過 columns 個
         * - 每排寫成字串（一個字一鍵）或陣列：字串是一串字鍵，{"key":"ー","width":1} 是一個字鍵，
         *   {"special":"shift"|"backspace","width":1.4} 是功能鍵（沒寫寬度就平分這排剩下的寬度）
         * - 字鍵寬 1～2 欄、功能鍵至少 1 欄，每排加起來不超過 columns；同一個字只能出現一次
         * - 刪除鍵剛好一個、Shift 最多一個（QWERTY 鍵盤的最下排沒有刪除鍵）
         */
        fun parse(o: JSONObject): KeyboardLayout? = try {
            val columns = o.optInt("columns", MIN_COLUMNS)
            val numberRow = o.optString("numberRow").ifEmpty { null }?.toList()
            val rowsJson = o.getJSONArray("rows")
            val rows = (0 until rowsJson.length()).map { i ->
                when (val r = rowsJson.get(i)) {
                    is String -> r.map { Spec(Type.CHAR, it, null) }
                    is JSONArray -> (0 until r.length()).flatMap { j ->
                        when (val e = r.get(j)) {
                            is String -> e.map { Spec(Type.CHAR, it, null) }
                            is JSONObject -> listOf(
                                when {
                                    e.has("special") -> Spec(
                                        when (e.getString("special")) {
                                            "shift" -> Type.SHIFT
                                            "backspace" -> Type.BACKSPACE
                                            else -> return null
                                        }, null, if (e.has("width")) e.getDouble("width") else null
                                    )
                                    else -> e.getString("key").let { k ->
                                        if (k.length != 1) return null
                                        Spec(Type.CHAR, k[0], if (e.has("width")) e.getDouble("width") else null)
                                    }
                                }
                            )
                            else -> return null
                        }
                    }
                    else -> return null
                }
            }
            build(columns, numberRow, rows)
        } catch (e: Exception) {
            null
        }

        private fun build(columns: Int, numberRow: List<Char>?, specs: List<List<Spec>>): KeyboardLayout? {
            if (columns !in MIN_COLUMNS..MAX_COLUMNS) return null
            if (specs.isEmpty() || specs.size > MAX_ROWS) return null
            if (numberRow != null && (numberRow.isEmpty() || numberRow.size > columns || !numberRow.all { it in '0'..'9' })) return null
            val all = specs.flatten()
            if (all.count { it.type == Type.BACKSPACE } != 1 || all.count { it.type == Type.SHIFT } > 1) return null
            val chars = all.mapNotNull { it.char } + (numberRow ?: emptyList())
            // * : ; 是落點學習存檔的分隔符號（KeyHabits.serialize），不能當字鍵
            if (chars.size != chars.toSet().size || chars.any { it.isWhitespace() || it.isISOControl() || it in "*:;" }) return null

            val rows = specs.map { row ->
                if (row.isEmpty()) return null
                for (s in row) {
                    val w = s.width ?: continue
                    if (s.type == Type.CHAR && (w < 1.0 || w > MAX_CHAR_WIDTH)) return null
                    if (s.type != Type.CHAR && w < 1.0) return null
                }
                // 沒寫寬度的字鍵是 1 欄；沒寫寬度的功能鍵平分剩下的寬度
                val fixed = row.sumOf { it.width ?: if (it.type == Type.CHAR) 1.0 else 0.0 }
                val flexible = row.count { it.width == null && it.type != Type.CHAR }
                val flexWidth = if (flexible > 0) (columns - fixed) / flexible else 0.0
                if (flexible > 0 && flexWidth < 1.0) return null
                val widths = row.map { it.width ?: if (it.type == Type.CHAR) 1.0 else flexWidth }
                val total = widths.sum()
                if (total > columns + 1e-6) return null
                var x = (columns - total) / 2
                row.mapIndexed { i, s -> Key(s.type, s.char, x, widths[i]).also { x += widths[i] } }
            }
            return KeyboardLayout(columns, numberRow, rows)
        }

        private fun builtIn(json: String) = parse(JSONObject(json)) ?: error("內建版面不合規則")

        /** 大千注音 10×4（第 1 排含聲調；ㄦ 與刪除鍵在最下排，所以這裡沒有刪除鍵，不經過 parse 的規則） */
        val DACHEN: KeyboardLayout = KeyboardLayout(
            10, null,
            listOf("ㄅㄉˇˋㄓˊ˙ㄚㄞㄢ", "ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ", "ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ", "ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ").map { r ->
                r.mapIndexed { i, c -> Key(Type.CHAR, c, i.toDouble(), 1.0) }
            }
        )

        /** 英文 QWERTY（數字列＋三排，第 3 排 Shift 1.4、刪除 1.6，跟原本的版面檔一樣） */
        val QWERTY: KeyboardLayout = builtIn(
            """{"columns":10,"numberRow":"1234567890","rows":["qwertyuiop","asdfghjkl",
            [{"special":"shift","width":1.4},"zxcvbnm",{"special":"backspace","width":1.6}]]}"""
        )
    }
}
