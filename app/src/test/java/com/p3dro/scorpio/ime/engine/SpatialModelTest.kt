package com.p3dro.scorpio.ime.engine

import com.p3dro.scorpio.ime.engine.BopomofoComposer.TouchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialModelTest {

    private fun model(vararg valid: String) = SpatialModel().apply {
        if (valid.isNotEmpty()) setValidSyllables(valid.toSet())
    }

    private fun List<Pair<Char, Double>>.penaltyOf(c: Char) = first { it.first == c }.second

    @Test
    fun centreTapNeighbourPenaltiesFollowGaussian() {
        val p = model().neighborPenalties(TouchPoint('ㄨ'))
        assertEquals(1.0, p.penaltyOf('ㄧ'), 1e-6) // 上
        assertEquals(1.0, p.penaltyOf('ㄩ'), 1e-6) // 下
        assertEquals(1.2, p.penaltyOf('ㄘ'), 1e-6) // 左
        assertEquals(1.2, p.penaltyOf('ㄜ'), 1e-6) // 右
        assertEquals(2.2, p.penaltyOf('ㄛ'), 1e-6) // 右上
    }

    @Test
    fun offsetTowardNeighbourMakesItCheaper() {
        val m = model()
        val up = m.neighborPenalties(TouchPoint('ㄨ', 0f, -1.0f))
        val down = m.neighborPenalties(TouchPoint('ㄨ', 0f, 1.0f))
        assertTrue(up.penaltyOf('ㄧ') < 0.3)
        assertTrue(down.penaltyOf('ㄧ') > 1.5)
        assertTrue(up.penaltyOf('ㄩ') > 1.5)
    }

    @Test
    fun centreTapOnIllegalKeyIsKept() {
        val m = model("ㄉㄨㄟ", "ㄉㄨㄛ", "ㄉㄧㄠ", "ㄉㄠ", "ㄉㄨ")
        val history = listOf(TouchPoint('ㄉ'), TouchPoint('ㄨ'))
        assertEquals('ㄠ', m.resolveTap('ㄠ', 0f, 0f, history).char)
    }

    @Test
    fun ambiguousTapOnIllegalKeyMovesToLegalNeighbour() {
        val m = model("ㄉㄨㄟ", "ㄉㄨㄛ", "ㄉㄧㄠ", "ㄉㄠ", "ㄉㄨ")
        val history = listOf(TouchPoint('ㄉ'), TouchPoint('ㄨ'))
        val t = m.resolveTap('ㄠ', 0f, -0.8f, history)
        assertEquals('ㄟ', t.char)
        assertEquals('ㄠ', t.rawChar)
        // 原本按到的鍵仍列為候選，而且很便宜
        assertTrue(m.neighborPenalties(t).penaltyOf('ㄠ') < 0.5)
    }

    @Test
    fun ambiguousTapOnLegalKeyIsKept() {
        val m = model("ㄉㄠ", "ㄉㄟ", "ㄉㄧㄠ")
        val t = m.resolveTap('ㄠ', 0f, -0.8f, listOf(TouchPoint('ㄉ')))
        assertEquals('ㄠ', t.char)
    }

    @Test
    fun likelySyllableWinsAmbiguousTap() {
        // ㄉ 之後 ㄧ（ㄉㄧㄠ、ㄉㄧㄢ…）遠比 ㄨ（ㄉㄨㄛ）常用
        val m = SpatialModel().apply {
            setSyllableFrequencies(mapOf("ㄉㄧㄠ" to 50.0, "ㄉㄧㄢ" to 50.0, "ㄉㄨㄛ" to 2.0, "ㄉㄨ" to 3.0, "ㄉㄚ" to 20.0))
        }
        val history = listOf(TouchPoint('ㄉ'))
        // 按在 ㄨ 上緣、靠近 ㄧ：改判成常用的 ㄧ
        assertEquals('ㄧ', m.resolveTap('ㄨ', 0f, -0.75f, history).char)
        // 按在 ㄨ 正中央：尊重實際按的鍵
        assertEquals('ㄨ', m.resolveTap('ㄨ', 0f, 0f, history).char)
    }

    @Test
    fun priorIsCappedBelowIllegal() {
        val m = SpatialModel().apply {
            setSyllableFrequencies(mapOf("ㄉㄧㄠ" to 1000.0, "ㄉㄨ" to 0.001))
        }
        val p = m.priorPenalties(listOf(TouchPoint('ㄉ')), listOf('ㄧ', 'ㄨ', 'ㄛ'))
        assertEquals(0.0, p['ㄧ']!!, 1e-9)
        assertEquals(SpatialModel.PRIOR_CAP, p['ㄨ']!!, 1e-9)
        assertEquals(SpatialModel.ILLEGAL_PENALTY, p['ㄛ']!!, 1e-9)
    }

    @Test
    fun abbreviationInitialsStayLegal() {
        // 簡拼：ㄎ 之後接另一個聲母 ㄅ 是合法的（看不到 = ㄎㄅㄉ）
        val m = SpatialModel().apply {
            setSyllableFrequencies(mapOf("ㄎㄢ" to 10.0, "ㄅㄨ" to 10.0, "ㄎㄚ" to 1.0))
        }
        val p = m.priorPenalties(listOf(TouchPoint('ㄎ')), listOf('ㄅ'))
        assertTrue(p['ㄅ']!! < SpatialModel.ILLEGAL_PENALTY)
    }

    @Test
    fun neverResolvesIntoToneKey() {
        val m = model("ㄓ")
        // ㄓ 右邊就是 ˊ，靠右按也不會被改成聲調
        assertEquals('ㄓ', m.resolveTap('ㄓ', 1.2f, 0f, emptyList()).char)
    }

    @Test
    fun toneTapAtSyllableStartFallsBackToNeighbour() {
        val m = model("ㄓ", "ㄓㄜ")
        // 音節開頭按到 ˋ（ㄓ 左邊）而且偏向 ㄓ → 判成 ㄓ
        assertEquals('ㄓ', m.resolveTap('ˋ', 0.8f, 0f, emptyList()).char)
    }

    @Test
    fun personalOffsetShiftsPenalties() {
        val m = model()
        val before = m.neighborPenalties(TouchPoint('ㄨ')).penaltyOf('ㄧ')
        // 使用者瞄準各鍵時都習慣按在下緣
        repeat(200) {
            for (k in listOf('ㄨ', 'ㄧ', 'ㄜ', 'ㄛ', 'ㄊ', 'ㄋ')) m.learn(k, TouchPoint(k, 0f, 0.6f))
        }
        val (bx, by) = m.effectiveBias('ㄧ')
        assertEquals(0.0, bx, 0.01)
        assertTrue(by > 0.2)
        // 點在 ㄨ 正中央 = 比平常偏上，更像是想按上面的 ㄧ
        val after = m.neighborPenalties(TouchPoint('ㄨ')).penaltyOf('ㄧ')
        assertTrue("before=$before after=$after", after < before - 0.3)
    }

    @Test
    fun serializeRoundTrip() {
        val m = model()
        repeat(10) { m.learn('ㄨ', TouchPoint('ㄨ', 0.4f, 0.6f)) }
        val copy = SpatialModel().apply { restore(m.serialize()) }
        assertEquals(m.effectiveBias('ㄨ').first, copy.effectiveBias('ㄨ').first, 1e-3)
        assertEquals(m.effectiveBias('ㄨ').second, copy.effectiveBias('ㄨ').second, 1e-3)
        assertEquals(10, copy.sampleCount())
    }

    @Test
    fun consumeUsesKeyCountNotRepairedLength() {
        val c = BopomofoComposer()
        // ㄐㄢ 會補成 ㄐㄧㄢ（三個字元），但只按了兩個鍵
        for (k in "ㄐㄢㄉㄚ") c.append(k)
        assertEquals(listOf("ㄐㄧㄢ", "ㄉㄚ"), c.getAllSyllables())
        c.consumeSyllables(1)
        assertEquals(listOf("ㄉㄚ"), c.getAllSyllables())
    }

    // ---------- 個人化散佈 ----------

    /** 想按 key、落點在鍵中央附近、x 方向的散佈約 spread 鍵距（固定序列，不靠亂數） */
    private fun SpatialModel.teach(key: Char, spread: Double, times: Int) {
        val r = SpatialModel.GRID.indexOfFirst { key in it }
        val c = SpatialModel.GRID[r].indexOf(key)
        for (i in 0 until times) {
            val dx = if (i % 2 == 0) spread else -spread
            // 半鍵單位：鍵距 × 每鍵距幾個半鍵
            learn(key, TouchPoint(key, (dx * SpatialModel.HALF_KEYS_PER_PITCH_X).toFloat(), 0f))
        }
        assertEquals(r, SpatialModel.GRID.indexOfFirst { key in it })
        assertEquals(c, SpatialModel.GRID[r].indexOf(key))
    }

    @Test
    fun noSpreadDataKeepsDefaultWidth() {
        val m = model()
        assertEquals(1.0 to 1.0, m.spreadRatio('ㄨ'))
        // 只有舊版格式（沒有散佈）的偏移資料：寬度不變
        m.restore("*:0.0100,0.0200,50.0;ㄨ:0.0300,0.0100,40.0")
        assertEquals(1.0 to 1.0, m.spreadRatio('ㄨ'))
    }

    @Test
    fun sloppyKeyWidensAndPreciseKeyNarrows() {
        val m = model()
        m.teach('ㄨ', 0.30, 200)
        m.teach('ㄚ', 0.10, 200)
        val sloppy = m.spreadRatio('ㄨ').first
        val precise = m.spreadRatio('ㄚ').first
        assertTrue("按得散的鍵要放寬：$sloppy", sloppy > 1.2)
        assertTrue("按得準的鍵要收窄：$precise", precise < 0.8)
        // 落在 ㄨ 右邊 0.45 鍵距：ㄨ 放寬後，右鄰 ㄜ 比沒有散佈資料時貴
        val wide = m.neighborPenalties(TouchPoint('ㄨ', (0.45 * SpatialModel.HALF_KEYS_PER_PITCH_X).toFloat(), 0f)).penaltyOf('ㄜ')
        val plain = model().neighborPenalties(TouchPoint('ㄨ', (0.45 * SpatialModel.HALF_KEYS_PER_PITCH_X).toFloat(), 0f)).penaltyOf('ㄜ')
        assertTrue("放寬的鍵附近，鄰鍵要比較貴：$wide vs $plain", wide > plain)
    }

    @Test
    fun spreadSurvivesSerializeRestore() {
        val m = model()
        m.teach('ㄨ', 0.30, 120)
        m.teach('ㄚ', 0.10, 120)
        val back = model().apply { restore(m.serialize()) }
        assertEquals(m.spreadRatio('ㄨ').first, back.spreadRatio('ㄨ').first, 1e-3)
        assertEquals(m.spreadRatio('ㄚ').first, back.spreadRatio('ㄚ').first, 1e-3)
    }
}
