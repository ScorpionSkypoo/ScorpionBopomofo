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

    // 資料 repo 的 layouts/dachen_samsung.json：大千注音、三星鍵盤的鍵位
    private val samsung = """{"schema":2,"id":"dachen_samsung","name":"注音（大千・三星鍵位）","spaceLabel":"注音·三星","engine":"dachen","minAppVersion":174,"sort":5,"keys":{"columns":11,"rows":["ㄅㄉˇˋㄓˊ˙ㄚㄞㄢㄦ","ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ","ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ",["ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ",{"special":"backspace"}]]}}"""

    @Test
    fun dachenWithDeclaredKeys() {
        val m = ChineseMethod.parse(samsung)!!
        assertEquals(ChineseMethod.Engine.DACHEN, m.engine)
        assertEquals(false, m.usesQwerty)
        assertNull(m.decoderKind)
        val l = m.zhuyinLayout
        assertEquals(11, l.columns)
        assertEquals(41, l.centers.size)
        // 第 1 排 11 鍵貼滿、ㄦ 在右上角；第 2、3 排錯開半鍵；第 4 排 10 鍵加刪除鍵
        assertEquals(10.0 to 0.0, l.centers['ㄦ'])
        assertEquals(0.5 to 1.0, l.centers['ㄆ'])
        assertEquals(6.5 to 2.0, l.centers['ㄨ'])
        assertEquals(0.0 to 3.0, l.centers['ㄈ'])
        assertEquals(KeyboardLayout.Type.BACKSPACE, l.rows[3].last().type)
        // 內建的大千沒有宣告，用內建 10×4
        assertEquals(KeyboardLayout.DACHEN, ChineseMethod.DACHEN.zhuyinLayout)
    }

    @Test
    fun rejectsBadDachenLayouts() {
        // 大千一定要宣告字鍵排；字鍵要剛好是 41 個注音符號與聲調；不能有 Shift；id 不能是內建的 dachen
        assertNull(ChineseMethod.parse("""{"id":"x","name":"x","engine":"dachen"}"""))
        assertNull(ChineseMethod.parse(samsung.replace("ㄞㄢㄦ", "ㄞㄢ")))
        assertNull(ChineseMethod.parse(samsung.replace("ㄞㄢㄦ", "ㄞㄢㄦa")))
        assertNull(ChineseMethod.parse(samsung.replace("[\"ㄈ", "[{\"special\":\"shift\"},\"ㄈ")))
        assertNull(ChineseMethod.parse(samsung.replace("dachen_samsung", "dachen")))
        // 26 鍵的引擎不能用注音字鍵
        assertNull(ChineseMethod.parse(samsung.replace("\"engine\":\"dachen\"", "\"engine\":\"pinyin\"")))
    }
}
