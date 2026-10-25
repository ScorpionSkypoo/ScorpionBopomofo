package com.p3dro.scorpio.ime.engine

/**
 * 判斷現在是雙手拇指還是單手在打字（ContextType, Goel et al. 2013 用按鍵間隔判斷握法）：
 * 雙手拇指打字時，跨過大半個鍵盤的移動多半是換另一隻手，跟按旁邊的鍵一樣快甚至更快；
 * 單手時同一根手指要移過去，距離越遠越慢。所以比「遠距離移動」和「近距離移動」的間隔中位數。
 *
 * 只看最近幾十下（握法會換），樣本不夠或差距不明顯時回傳 UNKNOWN（落點模型退回不分握法的習慣）
 */
class PostureTracker {

    companion object {
        const val UNKNOWN = 0
        const val TWO_THUMBS = 1
        const val ONE_HAND = 2

        // 遠／近移動（鍵距）：遠是跨過鍵盤快一半，近是旁邊一兩個鍵（不含同一鍵連按）
        private const val FAR = 4.0
        private const val NEAR_MIN = 0.5
        private const val NEAR_MAX = 2.0
        // 間隔超過這個就不算連續打字
        private const val MAX_GAP_MS = 1000L
        private const val WINDOW = 16
        private const val MIN_SAMPLES = 5
        // 遠／近間隔中位數的比值：小於 TWO 是雙手、大於 ONE 是單手，中間不確定
        const val RATIO_TWO = 0.95
        const val RATIO_ONE = 1.15
    }

    private val far = ArrayDeque<Long>()
    private val near = ArrayDeque<Long>()
    private var lastX = Double.NaN
    private var lastTime = 0L

    /** 最近一次的遠／近比值（開發記錄用；樣本不夠是 NaN） */
    @Volatile
    var ratio = Double.NaN
        private set

    @Volatile
    var posture = UNKNOWN
        private set

    /** 一下按鍵：x 是水平位置（鍵距，左邊第一欄為 0），time 是 SystemClock.uptimeMillis；回傳目前判斷的握法 */
    @Synchronized
    fun onTap(x: Double, time: Long): Int {
        val gap = time - lastTime
        if (!lastX.isNaN() && gap in 1 until MAX_GAP_MS) {
            val dx = kotlin.math.abs(x - lastX)
            val list = when {
                dx >= FAR -> far
                dx in NEAR_MIN..NEAR_MAX -> near
                else -> null
            }
            list?.let {
                it.addLast(gap)
                while (it.size > WINDOW) it.removeFirst()
            }
        }
        lastX = x
        lastTime = time
        update()
        return posture
    }

    private fun update() {
        if (far.size < MIN_SAMPLES || near.size < MIN_SAMPLES) {
            ratio = Double.NaN
            posture = UNKNOWN
            return
        }
        val r = median(far) / median(near)
        ratio = r
        posture = when {
            r < RATIO_TWO -> TWO_THUMBS
            r > RATIO_ONE -> ONE_HAND
            else -> UNKNOWN
        }
    }

    private fun median(q: ArrayDeque<Long>): Double {
        val s = q.sorted()
        return if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    @Synchronized
    fun reset() {
        far.clear()
        near.clear()
        lastX = Double.NaN
        lastTime = 0L
        ratio = Double.NaN
        posture = UNKNOWN
    }
}
