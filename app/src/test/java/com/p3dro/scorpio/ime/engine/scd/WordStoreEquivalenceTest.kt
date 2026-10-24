package com.p3dro.scorpio.ime.engine.scd

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 新版 WordStore 的前綴、模糊音、簡拼查詢跟修改前完全一樣（詞、分數、順序） */
class WordStoreEquivalenceTest {
    private val dir = File("src/main/assets/scd")
    private fun tables() = listOf("core", "trendy", "tw_places", "tw_landmarks", "world_places", "world_landmarks")
        .map { WordTable(ScdFile.open(File(dir, "$it.scd"))) }

    private fun show(l: List<WordHit>) = l.joinToString(" ") { "${it.word}/${it.score}/${it.reading}" }

    @Test
    fun sameResults() {
        val t = tables()
        val now = WordStore(t)
        val old = OldWordStore(t)
        val core = t[0]
        val keys = (0 until core.tlIx.size step 37).map { core.f.str(core.tlIx.keySid(it)) }
        for (k in keys) {
            assertEquals(k, show(old.byTonelessPrefix(k, 15)), show(now.byTonelessPrefix(k, 15)))
            val first = k.substringBefore('-')
            assertEquals(k, show(old.byTonelessPrefix(first, 15)), show(now.byTonelessPrefix(first, 15)))
            val fuzzy = listOf(k, first, k.replace('ㄣ', 'ㄥ'), k.replace('ㄢ', 'ㄤ'))
            assertEquals(k, show(old.byTonelessIn(fuzzy, 10)), show(now.byTonelessIn(fuzzy, 10)))
            val syl = k.split('-')
            if (syl.size >= 2) {
                val init = syl.map { it.first().toString() }
                val pattern = syl.mapIndexed { i, s -> if (i % 2 == 1) "${s.first()}%" else s }.joinToString("-")
                val keys3 = listOf(init.joinToString("-"), init.joinToString("-") { if (it in "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ") it else "_" })
                assertEquals(k, show(old.byInitialsLike(keys3, pattern, 15)), show(now.byInitialsLike(keys3, pattern, 15)))
                assertEquals(k, show(old.byInitialsLike(keys3, pattern, 3)), show(now.byInitialsLike(keys3, pattern, 3)))
            }
        }
    }
}
