package com.p3dro.scorpio.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BopomofoComposerTest {

    private fun composer(keys: String) = BopomofoComposer().apply { for (c in keys) append(c) }

    @Test
    fun replaceSyllableSwapsAbbreviatedInitial() {
        val c = composer("ㄒㄙ")
        assertTrue(c.replaceSyllable(0, "ㄒㄧㄠ"))
        assertEquals(listOf("ㄒㄧㄠ", "ㄙ"), c.getAllSyllables())
        // 換進來的鍵標成 synthetic，原本按的不動
        assertEquals(listOf(true, true, true, false), c.getTouchPoints().map { it.synthetic })
    }

    @Test
    fun replaceSyllableKeepsTypedTone() {
        val c = composer("ㄒㄧㄠˋㄙˇ")
        assertTrue(c.replaceSyllable(1, "ㄙㄨㄛ"))
        assertEquals(listOf("ㄒㄧㄠˋ", "ㄙㄨㄛˇ"), c.getAllSyllables())
    }

    @Test
    fun replaceSyllableRevertsWhenItWouldMergeWithNext() {
        // ㄓ＋ㄧ 拼不起來會拆成兩個音節；把 ㄓ 換成 ㄒ 會跟後面的 ㄧ 黏成 ㄒㄧ，不換
        val c = composer("ㄓㄧ")
        assertEquals(listOf("ㄓ", "ㄧ"), c.getAllSyllables())
        assertFalse(c.replaceSyllable(0, "ㄒ"))
        assertEquals(listOf("ㄓ", "ㄧ"), c.getAllSyllables())
        assertEquals(listOf(false, false), c.getTouchPoints().map { it.synthetic })
    }

    @Test
    fun jqxBeforeWSplitsOnlyForAbbrevReadings() {
        val c = composer("ㄒㄨㄟㄔ")
        // 一般組字不拆（ㄒㄨ 多半是 ㄕㄨ 按歪，交給鄰鍵修正）
        assertEquals(listOf("ㄒㄨㄟ", "ㄔ"), c.getAllSyllables())
        assertEquals(listOf("ㄒ", "ㄨㄟ", "ㄔ"), c.getAbbrevSyllables())
        assertTrue(c.replaceSyllable(0, "ㄒㄧㄢ"))
        assertEquals(listOf("ㄒㄧㄢ", "ㄨㄟ", "ㄔ"), c.getAllSyllables())
    }

    @Test
    fun backspaceAfterReplaceRemovesOneKey() {
        val c = composer("ㄒㄙ")
        c.replaceSyllable(0, "ㄒㄧㄠ")
        c.backspace()
        assertEquals(listOf("ㄒㄧㄠ"), c.getAllSyllables())
    }
}
