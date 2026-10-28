package com.p3dro.scorpio.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FuzzySoundsTest {

    private val priors = mapOf("ㄓㄗ" to 0.2, "ㄣㄥ" to 0.3)

    /** serialize 的結果拆成 規則 → [這樣選的次數, 打出 x 的次數] */
    private fun counts(f: FuzzySounds): Map<String, List<Double>> =
        f.serialize().split(';').filter { it.isNotEmpty() }.associate { e ->
            val p = e.split(':')
            p[0] to listOf(p[1].toDouble(), p[2].toDouble())
        }

    @Test
    fun syllableMatchingSeveralRulesDecaysOnce() {
        val f = FuzzySounds { priors }
        // ㄓㄣ 同時符合 ㄓ→ㄗ 與 ㄣ→ㄥ：同一個音節裡先記的那條不能被後面那條再衰減一次
        f.observe("ㄓㄣ", "ㄓㄣ")
        val c = counts(f)
        assertEquals(1.0, c.getValue("ㄓㄗ")[1], 1e-12)
        assertEquals(1.0, c.getValue("ㄣㄥ")[1], 1e-12)
    }

    @Test
    fun eachSyllableDecaysOldCountsOnce() {
        val f = FuzzySounds { priors }
        f.observe("ㄓㄣ", "ㄗㄣ")
        f.observe("ㄓㄣ", "ㄓㄣ")
        val c = counts(f)
        // 第二個音節：舊的計數衰減一次（×0.998），再加這次的
        assertEquals(0.998, c.getValue("ㄓㄗ")[0], 1e-12)
        assertEquals(0.998 + 1.0, c.getValue("ㄓㄗ")[1], 1e-12)
        assertEquals(0.998 + 1.0, c.getValue("ㄣㄥ")[1], 1e-12)
    }

    @Test
    fun syllableWithoutRulesChangesNothing() {
        val f = FuzzySounds { priors }
        f.observe("ㄓㄣ", "ㄓㄣ")
        val before = f.serialize()
        assertEquals(false, f.observe("ㄅㄚ", "ㄅㄚ"))
        assertEquals(before, f.serialize())
    }
}
