package com.p3dro.scorpio.ime.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardLayoutTest {

    private fun parse(json: String) = KeyboardLayout.parse(JSONObject(json))

    /** 改成宣告式之前寫死的 QWERTY 鍵位（QwertyModel.POS） */
    private val oldQwerty: Map<Char, Pair<Double, Double>> = buildMap {
        "qwertyuiop".forEachIndexed { i, c -> put(c, i.toDouble() to 0.0) }
        "asdfghjkl".forEachIndexed { i, c -> put(c, i + 0.5 to 1.0) }
        "zxcvbnm".forEachIndexed { i, c -> put(c, i + 1.5 to 2.0) }
    }

    @Test
    fun dachenMatchesOldGrid() {
        val old = listOf("ㄅㄉˇˋㄓˊ˙ㄚㄞㄢ", "ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ", "ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ", "ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ")
        val centers = KeyboardLayout.DACHEN.centers
        assertEquals(40, centers.size)
        for ((r, row) in old.withIndex()) for ((c, ch) in row.withIndex()) {
            assertEquals(c.toDouble() to r.toDouble(), centers[ch])
        }
        assertEquals(old.map { it.toList() }, SpatialModel.GRID)
    }

    @Test
    fun qwertyMatchesOldPositionsExceptThirdRow() {
        val centers = KeyboardLayout.QWERTY.centers
        assertEquals(oldQwerty.keys, centers.keys)
        for ((c, p) in oldQwerty) {
            val q = centers[c]!!
            // 第 3 排照實際畫面：Shift 1.4 欄，比原本寫死的左 0.1 鍵
            val dx = if (p.second == 2.0) -0.1 else 0.0
            assertEquals("$c", p.first + dx, q.first, 1e-9)
            assertEquals("$c", p.second, q.second, 1e-9)
        }
        assertEquals("qwerty", KeyboardLayout.QWERTY.geometryId)
    }

    @Test
    fun sameKeysShareGeometry() {
        val copy = parse("""{"rows":["qwertyuiop","asdfghjkl",[{"special":"shift","width":1.4},"zxcvbnm",{"special":"backspace","width":1.6}]]}""")!!
        assertEquals("qwerty", copy.geometryId)
        // Shift、刪除平分（各 1.5）：z 的位置不同，是另一組
        val even = parse("""{"rows":["qwertyuiop","asdfghjkl",[{"special":"shift"},"zxcvbnm",{"special":"backspace"}]]}""")!!
        assertEquals(1.5, even.centers['z']!!.first, 1e-9)
        assertNotEquals("qwerty", even.geometryId)
        assertEquals(even.geometryId, parse("""{"rows":["qwertyuiop","asdfghjkl",[{"special":"shift","width":1.5},"zxcvbnm",{"special":"backspace","width":1.5}]]}""")!!.geometryId)
    }

    @Test
    fun indentAndWideKeys() {
        // 12 欄：9 鍵左右各留 1.5 欄
        val l = parse("""{"columns":12,"rows":["абвгдежзий","клмнопрст",[{"special":"shift"},{"key":"у","width":2},{"special":"backspace","width":1}]]}""")!!
        assertEquals(1.5 + 0.5 - 0.5, l.centers['к']!!.first, 1e-9)
        // 第 3 排：Shift 平分剩下的 12 − 2 − 1 = 9 欄
        assertEquals(9.0 + 1.0 - 0.5, l.centers['у']!!.first, 1e-9)
        assertEquals(2.0, l.widths['у']!!, 1e-9)
    }

    @Test
    fun rejectsLayoutsBreakingTheRules() {
        val ok = """{"rows":["qwertyuiop","asdfghjkl",[{"special":"shift"},"zxcvbnm",{"special":"backspace"}]]}"""
        assertNotNull(parse(ok))
        // 欄數 10～12
        assertNull(parse(ok.replace("{\"rows\"", "{\"columns\":13,\"rows\"")))
        assertNull(parse(ok.replace("{\"rows\"", "{\"columns\":9,\"rows\"")))
        // 一排超過欄數
        assertNull(parse("""{"rows":["qwertyuiopx","asdfghjkl",[{"special":"backspace"}]]}"""))
        // 最多 4 排
        assertNull(parse("""{"rows":["q","w","e","r",[{"special":"backspace"}]]}"""))
        // 重複的字、沒有刪除鍵、兩個 Shift、* : ; 不能當字鍵
        assertNull(parse("""{"rows":["qwertyuiop","asdfghjklq",[{"special":"backspace"}]]}"""))
        assertNull(parse("""{"rows":["qwertyuiop"]}"""))
        assertNull(parse("""{"rows":[[{"special":"shift"},{"special":"shift"},{"special":"backspace"}]]}"""))
        assertNull(parse("""{"rows":["qwerty;",[{"special":"backspace"}]]}"""))
        // 字鍵寬 1～2、功能鍵至少 1、平分後不到 1 欄
        assertNull(parse("""{"rows":[[{"key":"a","width":0.5},{"special":"backspace"}]]}"""))
        assertNull(parse("""{"rows":[[{"key":"a","width":2.5},{"special":"backspace"}]]}"""))
        assertNull(parse("""{"rows":["qwertyuio",{"special":"shift"},{"special":"backspace"}]}"""))
        // 數字列只能是數字
        assertNull(parse(ok.replace("{\"rows\"", "{\"numberRow\":\"12a\",\"rows\"")))
        assertNull(parse("""{"rows":[[{"special":"enter"}]]}"""))
    }
}
