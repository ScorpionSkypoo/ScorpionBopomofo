package com.p3dro.scorpio.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChineseMethodTest {

    // 資料 repo 發佈的格式（layouts/hsu.json 加上版本）
    private val hsu = """{"schema":1,"id":"hsu","name":"注音（許氏）","spaceLabel":"注音·許氏","description":"許氏 26 鍵","engine":"zhuyin26","keymap":"bpmfdtnlgkhjvcjvcrzasexuyhgeiawomnkllsdfj","sort":10,"version":"2026.10.24.1200"}"""

    @Test
    fun parsesPublishedLayout() {
        val m = ChineseMethod.parse(hsu)!!
        assertEquals("hsu", m.id)
        assertEquals(ChineseMethod.Engine.ZHUYIN26, m.engine)
        assertEquals(LetterDecoder.Kind.ZHUYIN26, m.decoderKind)
        assertEquals("注音·許氏", m.spaceLabel)
        assertEquals("2026.10.24.1200", m.version)
        assertEquals(10, m.sort)
    }

    @Test
    fun codeTableLayouts() {
        val q = ChineseMethod.parse("""{"id":"quick","name":"速成","engine":"quick"}""")!!
        assertEquals(true, q.isCodeTable)
        assertEquals(2, q.maxCodeLength)
        assertNull(q.decoderKind)
    }

    @Test
    fun rejectsWhatThisAppCannotUse() {
        assertNull(ChineseMethod.parse("""{"id":"x","name":"x","engine":"stroke"}"""))
        assertNull(ChineseMethod.parse("""{"id":"x","name":"x","engine":"zhuyin26","keymap":"abc"}"""))
        assertNull(ChineseMethod.parse("""{"id":"dachen","name":"x","engine":"pinyin"}"""))
        assertNull(ChineseMethod.parse("""{"schema":3,"id":"x","name":"x","engine":"pinyin"}"""))
        assertNull(ChineseMethod.parse("""{"id":"../x","name":"x","engine":"pinyin"}"""))
    }

    @Test
    fun declaredKeys() {
        // Dvorak 拼音：字鍵是 a–z 各一次（第 3 排 9 鍵＋刪除剛好 10 欄，放不下 Shift）
        val dvorak = """{"schema":2,"id":"pinyin_dvorak","name":"拼音（Dvorak）","engine":"pinyin",
            "keys":{"numberRow":"1234567890","rows":["pyfgcrl","aoeuidhtns",["qjkxbmwvz",{"special":"backspace"}]]}}"""
        val m = ChineseMethod.parse(dvorak)!!
        assertEquals(0.0 + 1.5, m.keys!!.centers['p']!!.first, 1e-9)
        assertEquals(null, ChineseMethod.parse(hsu)!!.keys)
        // 少了字母、多了不是字母的鍵、keys 不合規則：整個版面都不收
        assertNull(ChineseMethod.parse(dvorak.replace("qjkxbmwvz", "qjkxbmwv")))
        assertNull(ChineseMethod.parse(dvorak.replace("pyfgcrl", "pyfgcrlー")))
        assertNull(ChineseMethod.parse(dvorak.replace("\"columns\":", "").replace("{\"numberRow\"", "{\"columns\":20,\"numberRow\"")))
    }
}
