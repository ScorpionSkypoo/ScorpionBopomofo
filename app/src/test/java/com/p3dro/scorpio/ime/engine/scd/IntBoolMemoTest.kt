package com.p3dro.scorpio.ime.engine.scd

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class IntBoolMemoTest {
    @Test
    fun matchesHashMap() {
        val rnd = Random(7)
        repeat(50) {
            // 重複使用的那一個：每輪清空後要跟新的一樣
            val memo = IntBoolMemo.local()
            val ref = HashMap<Int, Boolean>()
            repeat(rnd.nextInt(1, 3000)) {
                val k = rnd.nextInt(0, 200_000)
                val expected = ref[k]
                val got = memo.get(k)
                assertEquals(expected?.let { if (it) IntBoolMemo.YES else IntBoolMemo.NO } ?: IntBoolMemo.UNKNOWN, got)
                if (expected == null) {
                    val v = rnd.nextBoolean()
                    ref[k] = v
                    memo.put(k, v)
                }
            }
        }
    }
}
