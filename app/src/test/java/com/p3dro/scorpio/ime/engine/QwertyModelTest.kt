package com.p3dro.scorpio.ime.engine

import com.p3dro.scorpio.ime.engine.BopomofoComposer.TouchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwertyModelTest {

    @Test
    fun withoutDataCostIsPlainGaussian() {
        val m = QwertyModel()
        // 點在 e 正中央：e 是 0，同排鄰鍵 w 是 0.5 × 1 / (0.5 / 1.2) = 1.2
        val (px, py) = m.point('e', 0f, 0f)!!
        assertEquals(0.0, m.cost(px, py, 'e'), 1e-9)
        assertEquals(1.2, m.cost(px, py, 'w'), 1e-9)
        assertEquals(m.cost(px, py, 'd'), m.cost(px, py, 'd', useHabits = false), 1e-12)
    }

    @Test
    fun learnedOffsetMovesKeyCentre() {
        val m = QwertyModel()
        // 想按 e 時習慣按在下緣（半個鍵高的 0.6 = 0.3 排距）
        repeat(200) { assertTrue(m.learn('e', TouchPoint('e', 0f, 0.6f))) }
        val (_, cy) = m.center('e')!!
        assertTrue(cy > 0.2)
        // 落在 e 下緣的點，有個人化時比沒有便宜
        val (px, py) = m.point('e', 0f, 0.6f)!!
        assertTrue(m.cost(px, py, 'e') < m.cost(px, py, 'e', useHabits = false))
    }

    @Test
    fun farSamplesAreIgnored() {
        val m = QwertyModel()
        // 落在 p 卻說想按 q：離太遠，不學
        assertFalse(m.learn('q', TouchPoint('p', 0f, 0f)))
        assertEquals(0, m.sampleCount())
    }

    @Test
    fun serializeRoundTrip() {
        val m = QwertyModel()
        repeat(50) { i -> m.learn('a', TouchPoint('a', (i % 5) * 0.1f, 0.2f)) }
        val copy = QwertyModel().apply { restore(m.serialize()) }
        assertEquals(m.center('a')!!.first, copy.center('a')!!.first, 1e-3)
        assertEquals(m.keySpreads()['a']!!.sdX!!, copy.keySpreads()['a']!!.sdX!!, 1e-3)
    }

    @Test
    fun adjacency() {
        val m = QwertyModel()
        assertTrue(m.adjacent('e', 'r'))
        assertTrue(m.adjacent('e', 'd'))
        assertTrue(m.adjacent('e', 's'))
        assertFalse(m.adjacent('e', 't'))
        assertFalse(m.adjacent('q', 'p'))
    }

    @Test
    fun migrateMovesHabitsByPosition() {
        // 舊版面習慣按 q 的右邊；新版面 a、q 對調（AZERTY 的 a 在 QWERTY q 的位置）：新的 a 拿到舊 q 的習慣
        val old = QwertyModel()
        repeat(100) { old.learn('q', TouchPoint('q', 0.5f, 0f)) }
        val azerty = KeyboardLayout.parse(org.json.JSONObject(
            """{"rows":["azertyuiop","qsdfghjklm",[{"special":"shift"},"wxcvbn",{"special":"backspace"}]]}"""
        ))!!
        val m = QwertyModel(azerty)
        m.migrateFrom(old.serialize(), old.pos)
        val from = old.keySpreads()['q']!!
        val to = m.keySpreads()['a']!!
        assertEquals(from.dx, to.dx, 1e-3)
        // 樣本數減半：新版面打一陣子就跟上
        assertEquals(from.n / 2, to.n, 1e-9)
        // 新的 q 在舊 a 的位置，舊 a 沒有資料
        assertNull(m.keySpreads()['q'])
    }
}
