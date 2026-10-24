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
        assertNull(ChineseMethod.parse("""{"schema":2,"id":"x","name":"x","engine":"pinyin"}"""))
        assertNull(ChineseMethod.parse("""{"id":"../x","name":"x","engine":"pinyin"}"""))
    }
}
