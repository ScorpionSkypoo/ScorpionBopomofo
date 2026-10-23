package com.p3dro.scorpio.ime.engine.scd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手機上寫的 SCD（ScdWriter，匯入詞庫用）要跟資料 repo 的 tools/scd.py 寫的一模一樣。
 * 測試資料由資料 repo 的 tools/scd_fixture.py 產生（words_fixture.tsv 是輸入、words_fixture.scd 是 Python 寫出的檔案）
 */
class ScdWriterTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    private fun rows(): List<ScdWriter.Word> = String(resource("words_fixture.tsv"), Charsets.UTF_8).lines().filter { it.isNotEmpty() }.map {
        val c = it.split('\t')
        ScdWriter.Word(c[0], c[1], c[2], c[3].toDouble(), c[4], c[5])
    }

    private val meta = mapOf<String, Any>(
        "id" to "fixture", "group" to "words", "name" to "測試\"詞庫\"\n\\\t\u0001",
        "version" to "2026.1.1", "bundled" to true, "sort" to 7
    )

    @Test
    fun kotlinWriterIsByteIdenticalToPython() {
        val expected = resource("words_fixture.scd")
        val actual = ScdWriter.words(meta, rows())
        assertEquals(expected.size, actual.size)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun readerFindsRowsInTieOrder() {
        val rs = rows()
        val f = ScdFile.wrap(ScdWriter.words(meta, rs))
        assertEquals("fixture", f.id)
        assertEquals(rs.size, f.rows("w"))
        val store = WordStore(listOf(WordTable(f)))
        // 熱門前綴走預先算好的前幾名：結果要跟逐筆挑出來的一樣
        val expected = rs.withIndex().filter { it.value.toneless.startsWith("ㄅㄚ") }
            .sortedWith(compareBy({ -it.value.score }, { it.value.toneless }, { it.index })).take(5).map { it.value.word }
        assertEquals(expected, store.byTonelessPrefix("ㄅㄚ", 5).map { it.word })
        assertEquals(listOf("啊"), store.byToneless("ㄚ", 5).map { it.word })
        assertTrue(store.hasWord("𠀀0") || store.hasWord("𠀀1") || rs.none { it.word.startsWith("𠀀") })
    }

    @Test
    fun sqliteLike() {
        assertTrue(Like("ㄅ%").matches("ㄅㄚ"))
        assertTrue(Like("ㄅ%-ㄇ%").matches("ㄅㄚ-ㄇㄚ"))
        assertTrue(Like("a_c").matches("ABC"))
        assertTrue(!Like("ㄅ_").matches("ㄅㄚㄚ"))
        assertTrue(Like("%").matches(""))
    }
}
