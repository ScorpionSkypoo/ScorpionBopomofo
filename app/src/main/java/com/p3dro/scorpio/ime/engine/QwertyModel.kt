package com.p3dro.scorpio.ime.engine

import kotlin.math.abs

/**
 * QWERTY 字母鍵的空間模型：英文、26 鍵注音、漢語拼音、倉頡、速成共用同一個實體鍵盤，所以共用一份落點習慣
 * - 每個鍵是一個二元高斯分佈（鍵寬單位，同排鄰鍵懲罰約 1.2、上下約 1.0）
 * - 個人化：跟大千的 SpatialModel 一樣學每鍵的平均偏移（KeyHabits）；沒有資料時跟沒有這個功能完全一樣
 *
 * 座標：鍵寬為 1（x）、排距為 1（y），原點為 q 的中心；觸控偏移沿用 SpatialKeyLayout 的單位（半個鍵寬／鍵高為 1）
 */
class QwertyModel {

    companion object {
        // 字母鍵中心座標（第 2 排右移半鍵、第 3 排右移一鍵半，對應實際版面）
        val POS: Map<Char, Pair<Double, Double>> = buildMap {
            "qwertyuiop".forEachIndexed { i, c -> put(c, i.toDouble() to 0.0) }
            "asdfghjkl".forEachIndexed { i, c -> put(c, i + 0.5 to 1.0) }
            "zxcvbnm".forEachIndexed { i, c -> put(c, i + 1.5 to 2.0) }
        }

        private const val VAR_X = 0.5 / 1.2
        private const val VAR_Y = 0.5

        /** 兩個鍵是否相鄰（含斜角）：鍵心距離² ≤ 1.6 */
        fun adjacent(a: Char, b: Char): Boolean {
            val p = POS[a] ?: return false
            val q = POS[b] ?: return false
            val dx = p.first - q.first
            val dy = p.second - q.second
            return dx * dx + dy * dy <= 1.6
        }
    }

    private val habits = KeyHabits()

    /** 落點的鍵盤座標；不是字母鍵（數字、符號）回傳 null */
    fun point(touch: BopomofoComposer.TouchPoint): Pair<Double, Double>? = point(touch.rawChar, touch.offsetX, touch.offsetY)

    fun point(raw: Char, offsetX: Float, offsetY: Float): Pair<Double, Double>? =
        POS[raw.lowercaseChar()]?.let { (it.first + offsetX * 0.5) to (it.second + offsetY * 0.5) }

    /** 瞄準這個鍵時的落點中心（鍵中心＋個人化偏移） */
    fun center(key: Char, useHabits: Boolean = true): Pair<Double, Double>? {
        val p = POS[key] ?: return null
        if (!useHabits) return p
        val (bx, by) = habits.effectiveBias(key)
        return (p.first + bx) to (p.second + by)
    }

    /** 落點相對某鍵的高斯代價（負對數機率）：扣掉習慣偏移；鍵中央的代價是 0 */
    fun cost(px: Double, py: Double, key: Char, useHabits: Boolean = true): Double {
        val (cx, cy) = center(key, useHabits) ?: return Double.MAX_VALUE
        val dx = px - cx
        val dy = py - cy
        return 0.5 * (dx * dx / VAR_X + dy * dy / VAR_Y)
    }

    /** 只看落點（不看字母先驗）時最像的鍵：用來比有／沒有個人化時各判對幾次 */
    fun nearest(touch: BopomofoComposer.TouchPoint, useHabits: Boolean): Char? {
        val raw = touch.rawChar.lowercaseChar()
        val (px, py) = point(touch) ?: return null
        var best = raw
        var bestCost = cost(px, py, raw, useHabits)
        for (k in POS.keys) {
            if (k == raw || !adjacent(k, raw)) continue
            val c = cost(px, py, k, useHabits)
            if (c < bestCost - 1e-9) {
                best = k
                bestCost = c
            }
        }
        return best
    }

    /** 記錄一筆「想按 intended、實際落在 touch」的樣本；離目標超過一個半鍵距多半是對錯位置，不納入 */
    fun learn(intended: Char, touch: BopomofoComposer.TouchPoint): Boolean {
        val key = intended.lowercaseChar()
        val target = POS[key] ?: return false
        val (px, py) = point(touch) ?: return false
        val dx = px - target.first
        val dy = py - target.second
        if (abs(dx) > 1.5 || abs(dy) > 1.5) return false
        habits.add(key, dx, dy)
        return true
    }

    fun sampleCount(): Int = habits.sampleCount()

    fun keySpreads(): Map<Char, KeyHabits.KeySpread> = habits.keySpreads()

    fun serialize(): String = habits.serialize()

    fun restore(data: String?) = habits.restore(data)
}
