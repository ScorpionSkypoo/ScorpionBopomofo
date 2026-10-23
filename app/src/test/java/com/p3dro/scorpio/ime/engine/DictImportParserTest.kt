package com.p3dro.scorpio.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DictImportParserTest {

    private fun one(text: String) = DictImportParser.parse(text).single()

    @Test
    fun plainWordList() {
        assertEquals(DictImportParser.Entry("情緒價值", emptyList(), null), one("情緒價值"))
    }

    @Test
    fun mcbopomofoUserPhrase() {
        val e = one("小麥注音 ㄒㄧㄠˇ-ㄇㄞˋ-ㄓㄨˋ-ㄧㄣ")
        assertEquals("小麥注音", e.word)
        assertEquals(listOf("ㄒㄧㄠˇ", "ㄇㄞˋ", "ㄓㄨˋ", "ㄧㄣ"), e.syllables)
    }

    @Test
    fun chewingTsiSrc() {
        val e = one("台北 120 ㄊㄞˊ ㄅㄟˇ")
        assertEquals("台北", e.word)
        assertEquals(listOf("ㄊㄞˊ", "ㄅㄟˇ"), e.syllables)
        assertEquals(120.0, e.freq!!, 0.0)
    }

    @Test
    fun mcbopomofoDataTxtReadingFirst() {
        val e = one("ㄊㄞˊ-ㄅㄟˇ 台北 -4.2")
        assertEquals("台北", e.word)
        assertEquals(listOf("ㄊㄞˊ", "ㄅㄟˇ"), e.syllables)
        assertEquals(-4.2, e.freq!!, 0.0)
        assertEquals(emptyList<DictImportParser.Entry>(), DictImportParser.parse("_punctuation_list ，"))
        assertEquals(emptyList<DictImportParser.Entry>(), DictImportParser.parse("ㄅ MACRO@DATE_TODAY 0"))
    }

    @Test
    fun rimeDictYamlSkipsHeader() {
        val text = "# Rime dictionary\n---\nname: 我的詞庫\nversion: \"1\"\n...\n情緒價值\tqing xu jia zhi\t100\n"
        val entries = DictImportParser.parse(text)
        assertEquals(1, entries.size)
        assertEquals("情緒價值", entries[0].word)
        assertEquals(100.0, entries[0].freq!!, 0.0)
    }

    @Test
    fun gboardExport() {
        val text = "# Gboard Dictionary version:1\nqxjz\t情緒價值\tzh-TW\n"
        assertEquals("情緒價值", one(text).word)
    }

    @Test
    fun skipsCommentsBlankAndNonChinese() {
        assertEquals(emptyList<DictImportParser.Entry>(), DictImportParser.parse("﻿# 註解\n\nhello world\n"))
    }
}
