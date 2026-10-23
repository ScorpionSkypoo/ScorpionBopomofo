package com.p3dro.scorpio.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterDecoderTest {

    private val freq = mapOf(
        "ㄋㄧ" to 50.0, "ㄏㄠ" to 60.0, "ㄓㄨㄥ" to 40.0, "ㄍㄨㄛ" to 30.0, "ㄒㄧㄢ" to 20.0, "ㄒㄧ" to 25.0,
        "ㄢ" to 5.0, "ㄎㄢ" to 15.0, "ㄅㄨ" to 45.0, "ㄉㄠ" to 30.0, "ㄐㄩ" to 10.0, "ㄌㄩ" to 8.0, "ㄓ" to 12.0,
        "ㄋㄩㄝ" to 3.0, "ㄉㄜ" to 80.0, "ㄉㄚ" to 20.0, "ㄕ" to 30.0
    )

    private fun pinyin() = LetterDecoder(LetterDecoder.Kind.PINYIN).apply { setSyllableFrequencies(freq) }

    private fun best(d: LetterDecoder, s: String) = d.decode(s.toList()).first().units.map { it.zhuyin }

    @Test
    fun pinyinSpellings() {
        assertEquals(listOf("zhong"), LetterDecoder.pinyinSpellings("ㄓㄨㄥ"))
        assertEquals(listOf("ju"), LetterDecoder.pinyinSpellings("ㄐㄩ"))
        assertEquals(listOf("lv"), LetterDecoder.pinyinSpellings("ㄌㄩ"))
        assertEquals(listOf("zhi"), LetterDecoder.pinyinSpellings("ㄓ"))
        assertTrue(LetterDecoder.pinyinSpellings("ㄋㄩㄝ").contains("nue"))
        assertEquals(listOf("yi"), LetterDecoder.pinyinSpellings("ㄧ"))
    }

    @Test
    fun pinyinFullSyllables() {
        assertEquals(listOf("ㄋㄧ", "ㄏㄠ"), best(pinyin(), "nihao"))
        assertEquals(listOf("ㄓㄨㄥ", "ㄍㄨㄛ"), best(pinyin(), "zhongguo"))
    }

    @Test
    fun pinyinAbbreviation() {
        // kbd → 看不到的簡拼
        assertEquals(listOf("ㄎ", "ㄅ", "ㄉ"), best(pinyin(), "kbd"))
    }

    @Test
    fun pinyinPartialLastSyllable() {
        // 最後一段打到一半（zhon）補成 zhong
        assertEquals(listOf("ㄓㄨㄥ"), best(pinyin(), "zhon"))
    }

    @Test
    fun pinyinPrefersWholeSyllable() {
        assertEquals(listOf("ㄒㄧㄢ"), best(pinyin(), "xian"))
        // 分隔號強制切開
        assertEquals(listOf("ㄒㄧ", "ㄢ"), best(pinyin(), "xi'an"))
    }

    @Test
    fun hsuAmbiguousKeys() {
        // 許氏：d = ㄉ／ˊ（聲調），k = ㄎ／ㄤ；e = ㄧ／ㄝ；w = ㄠ
        val d = LetterDecoder(LetterDecoder.Kind.HSU).apply { setSyllableFrequencies(freq) }
        val map = LetterDecoder.keyMap(LetterDecoder.Kind.HSU)
        assertTrue(map['d']!!.contains('ㄉ') && map['d']!!.contains('ˊ'))
        // dw = ㄉㄠ
        assertEquals(listOf("ㄉㄠ"), best(d, "dw"))
        // dwd = ㄉㄠˊ（第二個 d 是聲調）
        assertEquals(listOf("ㄉㄠˊ"), best(d, "dwd"))
    }

    @Test
    fun firstToneMarkerJoinsSyllable() {
        val d = LetterDecoder(LetterDecoder.Kind.HSU).apply { setSyllableFrequencies(freq) }
        assertEquals(listOf("ㄉㄠˉ"), best(d, "dwˉ"))
    }
}
