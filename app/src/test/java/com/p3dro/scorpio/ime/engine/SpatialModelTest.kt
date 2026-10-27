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

    // ---------- 移動造成的偏差 ----------

    /** 從 from 鍵中央移到 key、落在 key 中心往移動方向多衝 overshoot 倍位移的地方 */
    private fun SpatialModel.teachOvershoot(fromKey: Char, key: Char, overshoot: Double, times: Int) {
        val fr = SpatialModel.GRID.indexOfFirst { fromKey in it }
        val fc = SpatialModel.GRID[fr].indexOf(fromKey)
        val r = SpatialModel.GRID.indexOfFirst { key in it }
        val c = SpatialModel.GRID[r].indexOf(key)
        val dx = (c - fc) * overshoot
        val dy = (r - fr) * overshoot
        repeat(times) {
            learn(key, TouchPoint(key, (dx * SpatialModel.HALF_KEYS_PER_PITCH_X).toFloat(), (dy * SpatialModel.HALF_KEYS_PER_PITCH_Y).toFloat(),
                fromX = fc.toFloat(), fromY = fr.toFloat()))
        }
    }

    @Test
    fun noMotionDataChangesNothing() {
        val m = model()
        assertEquals(0.0 to 0.0, m.motion.shift(5.0, 3.0))
        val with = m.neighborPenalties(TouchPoint('ㄨ', fromX = 0f, fromY = 0f))
        val without = m.neighborPenalties(TouchPoint('ㄨ'))
        assertEquals(without, with)
    }

    @Test
    fun learnsOvershootAlongMovement() {
        val m = model()
        // 從左上的 ㄅ 移到各個鍵都多衝 10%
        for (k in listOf('ㄒ', 'ㄨ', 'ㄜ', 'ㄑ', 'ㄤ', 'ㄙ')) m.teachOvershoot('ㄅ', k, 0.10, 60)
        val (sx, sy) = m.motion.shift(3.0, 3.0)
        assertTrue("往右下移 3 鍵距要學到往右下多衝：$sx $sy", sx > 0.1 && sy > 0.1)
        // 從 ㄅ 移過來、落在 ㄒ 右下方（多衝的地方）：有移動偏差時，ㄒ 右邊的 ㄖ 比沒有時貴
        val r = SpatialModel.GRID.indexOfFirst { 'ㄒ' in it }
        val over = TouchPoint('ㄒ', (0.3 * SpatialModel.HALF_KEYS_PER_PITCH_X).toFloat(), (0.3 * SpatialModel.HALF_KEYS_PER_PITCH_Y).toFloat(), fromX = 0f, fromY = 0f)
        val plain = over.copy(fromX = Float.NaN, fromY = Float.NaN)
        assertEquals(3, r)
        assertTrue(m.neighborPenalties(over).penaltyOf('ㄖ') > m.neighborPenalties(plain).penaltyOf('ㄖ'))
    }

    @Test
    fun motionSurvivesSerializeRestore() {
        val m = model()
        m.teachOvershoot('ㄅ', 'ㄒ', 0.10, 80)
        val back = model().apply { motion.restore(m.motion.serialize()) }
        assertEquals(m.motion.shift(3.0, 3.0).first, back.motion.shift(3.0, 3.0).first, 1e-4)
    }

    @Test
    fun postureHabitsBackOffToGeneralAndKeepTheirOwnOffset() {
        val m = model()
        // 單手時每次都按在 ㄨ 偏右 0.6 個半鍵；雙手拇指時按在中央
        repeat(60) { m.learn('ㄨ', TouchPoint('ㄨ', 0.6f, 0f, posture = PostureTracker.ONE_HAND)) }
        repeat(60) { m.learn('ㄨ', TouchPoint('ㄨ', 0f, 0f, posture = PostureTracker.TWO_THUMBS)) }
        val one = m.effectiveBias('ㄨ', PostureTracker.ONE_HAND).first
        val two = m.effectiveBias('ㄨ', PostureTracker.TWO_THUMBS).first
        val general = m.effectiveBias('ㄨ').first
        assertTrue("單手偏右 $one > 不分 $general", one > general + 0.03)
        assertTrue("雙手偏回中央 $two < 不分 $general", two < general - 0.03)
        // 不知道握法：跟不分握法一樣
        assertEquals(general, m.effectiveBias('ㄨ', PostureTracker.UNKNOWN).first, 1e-9)
        // 存起來再讀回來一樣；舊版（沒有分握法）的資料也讀得進來
        val back = model().apply { restore(m.serialize()) }
        assertEquals(one, back.effectiveBias('ㄨ', PostureTracker.ONE_HAND).first, 1e-3)
        val old = model().apply { restore(m.serialize().substringBefore("||")) }
        assertEquals(general, old.effectiveBias('ㄨ').first, 1e-3)
    }

    @Test
    fun erSitsBelowTheBottomRow() {
        val m = model()
        val er = m.neighborPenalties(TouchPoint('ㄦ')).map { it.first }.toSet()
        assertEquals(setOf('ㄙ', 'ㄩ', 'ㄝ'), er)
        assertTrue(m.neighborPenalties(TouchPoint('ㄩ')).any { it.first == 'ㄦ' })
        assertTrue(m.physicalPoint(TouchPoint('ㄦ')) != null)
    }

    @Test
    fun tapLeaningOntoToneKeyStaysALetter() {
        val m = model("ㄉㄨ", "ㄉㄨㄛ")
        val history = listOf(TouchPoint('ㄉ'), TouchPoint('ㄨ'))
        // ㄓ 的左緣外（往 ˋ 那邊）也不改判成聲調
        assertEquals('ㄓ', m.resolveTap('ㄓ', -1.4f, 0f, history).char)
    }

    @Test
    fun postureWithoutSamplesUsesGeneralHabit() {
        val m = model()
        // 只學不分握法的：每次都按在 ㄨ 的右邊 0.3 鍵距
        repeat(60) { m.learn('ㄨ', TouchPoint('ㄨ', (0.3 * SpatialModel.HALF_KEYS_PER_PITCH_X).toFloat(), 0f)) }
        val general = m.effectiveBias('ㄨ')
        assertTrue(general.first > 0.2)
        // 雙手還沒有任何樣本：照全部的習慣，不會把整體偏移扣掉
        val two = m.effectiveBias('ㄨ', PostureTracker.TWO_THUMBS)
        assertEquals(general.first, two.first, 1e-9)
        assertEquals(general.second, two.second, 1e-9)
    }

    private val samsungLayout = ChineseMethod.parse(
        """{"schema":2,"id":"dachen_samsung","name":"注音（大千・三星鍵位）","spaceLabel":"注音·三星","engine":"dachen","minAppVersion":174,"sort":5,"keys":{"columns":11,"rows":["ㄅㄉˇˋㄓˊ˙ㄚㄞㄢㄦ","ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ","ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ",["ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ",{"special":"backspace"}]]}}"""
    )!!.zhuyinLayout

    @Test
    fun builtInNeighboursAreTheOriginalGrid() {
        // 內建 10×4：鄰鍵跟原本寫死的格子八方向一樣（順序也一樣：上一排、同一排、下一排，各由左到右；第 4 排 ㄙㄩㄝ 下面多最底排的 ㄦ）
        val grid = listOf("ㄅㄉˇˋㄓˊ˙ㄚㄞㄢ", "ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ", "ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ", "ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ")
        val m = model()
        for (r in grid.indices) for (c in grid[r].indices) {
            val expected = ArrayList<Char>()
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                grid.getOrNull(r + dr)?.getOrNull(c + dc)?.let { expected.add(it) }
            }
            if (r == 3 && c in 5..7) expected.add('ㄦ')
            assertEquals(expected, m.neighborPenalties(TouchPoint(grid[r][c])).map { it.first })
        }
    }

    @Test
    fun samsungRowsAreStaggered() {
        val m = model()
        assertTrue(m.useLayout(samsungLayout, null))
        assertEquals(false, m.useLayout(samsungLayout, null))
        // ㄧ（第 2 排，錯開半鍵）：上面是 ˙ 和 ㄚ，下面是 ㄘ ㄨ ㄜ（第 3 排跟第 2 排對齊）
        val p = m.neighborPenalties(TouchPoint('ㄧ'))
        assertEquals(listOf('˙', 'ㄚ', 'ㄗ', 'ㄛ', 'ㄘ', 'ㄨ', 'ㄜ'), p.map { it.first })
        // 斜上方差半鍵，比內建 10×4 的斜角便宜
        assertTrue(p.penaltyOf('ㄚ') < 2.2)
        assertEquals(p.penaltyOf('˙'), p.penaltyOf('ㄚ'), 1e-9)
        // ㄦ 在字鍵區，有落點、鄰鍵
        assertEquals(listOf('ㄢ', 'ㄣ'), m.neighborPenalties(TouchPoint('ㄦ')).map { it.first })
        // 第 4 排：ㄩ 上面是 ㄘ 和 ㄨ
        assertTrue(m.neighborPenalties(TouchPoint('ㄩ')).map { it.first }.containsAll(listOf('ㄘ', 'ㄨ')))
        // 偏向右上就判成 ㄚ
        assertEquals('ㄚ', m.neighborPenalties(TouchPoint('ㄧ', 0.6f, -1.0f)).minByOrNull { it.second }!!.first)
    }

    @Test
    fun switchingLayoutMovesHabitsByPosition() {
        val m = model()
        repeat(30) { m.learn('ㄨ', TouchPoint('ㄨ', 0f, 0.5f)) }
        val before = m.effectiveBias('ㄨ').second
        assertTrue(before > 0.1)
        // 第一次用三星鍵位：ㄨ 搬到同一個字的新位置（最近的舊鍵），樣本減半但偏移一樣
        m.useLayout(samsungLayout, null)
        assertEquals(before, m.effectiveBias('ㄨ').second, 0.05)
        val samsungData = m.serialize()
        // 切回內建：用內建那一組存的資料（這裡模擬沒學過）
        m.useLayout(KeyboardLayout.DACHEN, "")
        assertEquals(0.0, m.effectiveBias('ㄨ').second, 1e-9)
        // 再切到三星：用三星那一組存的
        m.useLayout(samsungLayout, samsungData)
        assertEquals(before, m.effectiveBias('ㄨ').second, 0.05)
    }
}
