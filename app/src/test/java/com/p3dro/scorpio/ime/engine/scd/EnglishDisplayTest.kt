package com.p3dro.scorpio.ime.engine.scd

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 英文詞庫的顯示寫法（e.disp）：en_fixture.scd 由資料 repo 的 tools/scd_fixture.py 產生 */
class EnglishDisplayTest {

    private fun fixture(): EnglishTable {
        val tmp = File.createTempFile("en_fixture", ".scd").apply { deleteOnExit() }
        tmp.writeBytes(javaClass.classLoader!!.getResourceAsStream("en_fixture.scd")!!.readBytes())
        return EnglishTable(ScdFile.open(tmp))
    }

    @Test
    fun displayFormsByRowAndWord() {
        val en = fixture()
        val r = en.range("di")
        assertEquals(listOf("dior", "dirt"), r.map { en.word(it) })
        assertEquals(listOf("Dior", "dirt"), r.map { en.display(it) })
        assertEquals("iPhone", en.display("iphone"))
        assertEquals("ipod", en.display("ipod"))
        // 字典沒有的字、只是前綴的字照原樣
        assertEquals("ip", en.display("ip"))
        assertEquals("zebra", en.display("zebra"))
        assertEquals(listOf("iphone", "dirt"), en.next("the", 5))
    }

    @Test
    fun bundledDictionaryDisplayOnlyChangesCase() {
        // 內建的英文詞庫有沒有 e.disp 都要能用；顯示寫法只改大小寫
        val en = EnglishTable(ScdFile.open(File("src/main/assets/scd/en.scd")))
        val r = en.range("hel")
        assertEquals(r.map { en.word(it) }, r.map { en.display(it).lowercase() })
        assertEquals("hello", en.display("hello"))
    }
}
