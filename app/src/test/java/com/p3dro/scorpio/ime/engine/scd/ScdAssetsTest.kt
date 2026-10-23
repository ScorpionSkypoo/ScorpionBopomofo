package com.p3dro.scorpio.ime.engine.scd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 內建的 SCD 詞庫（assets/scd）能開、各表的查詢有東西、同分順序穩定 */
class ScdAssetsTest {

    private val dir = File("src/main/assets/scd")

    private fun open(id: String) = ScdFile.open(File(dir, "$id.scd"))

    @Test
    fun everyBundledFileOpens() {
        val files = dir.listFiles { f -> f.name.endsWith(".scd") }!!.sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val scd = ScdFile.open(f)
            assertEquals(f.nameWithoutExtension, scd.id)
            assertTrue("${f.name} 沒有版本", scd.version.isNotEmpty())
        }
    }

    @Test
    fun coreWordQueries() {
        val core = open("core")
        val store = WordStore(listOf(WordTable(core)))
        assertTrue(core.rows("w") > 100_000)
        val hits = store.byToneless("ㄊㄞ-ㄨㄢ", 10)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertTrue(hits.any { it.word == "台灣" })
        assertTrue(store.byTonelessPrefix("ㄊ", 8).size == 8)
        assertTrue(store.hasWord("台灣"))
        assertTrue(store.syllableFrequencies().isNotEmpty())
        assertEquals(5, store.byInitialsLike(listOf("ㄊ-_"), "ㄊ%-%", 5).size)
    }

    @Test
    fun otherTables() {
        val en = EnglishTable(open("en"))
        assertTrue(en.range("hel").count() > 0)
        assertTrue(en.next("i", 5).isNotEmpty())
        val cj = CangjieTable(open("cangjie"))
        assertTrue(cj.range("a").count() > 0)
        assertTrue(cj.quick.isNotEmpty())
        val emoji = EmojiTable(open("emoji"))
        assertTrue(emoji.categories().isNotEmpty())
        assertTrue(emoji.byCategory(emoji.categories().first()).isNotEmpty())
        assertTrue(emoji.all().isNotEmpty())
        val kao = KaomojiTable(open("kaomoji"))
        assertTrue(kao.categories().isNotEmpty())
        assertTrue(kao.byCategory(kao.categories().first()).isNotEmpty())
        val bg = BigramTable(open("bg_wiki"))
        assertTrue(bg.size > 0)
        val (prev, next) = bg.byScore(0)
        var found = false
        bg.forEach(prev) { n, _ -> if (n == next) found = true }
        assertTrue(found)
    }
}
