package com.p3dro.scorpio.ime.engine

import kotlin.math.abs

/**
 * QWERTY 鍵盤（英文、26 鍵注音、漢語拼音、倉頡、速成共用的那個面板）字鍵的空間模型
 * - 每個鍵是一個二元高斯分佈（鍵寬單位，同排鄰鍵懲罰約 1.2、上下約 1.0）
 * - 個人化：跟大千的 SpatialModel 一樣學每鍵的平均偏移（KeyHabits）；沒有資料時跟沒有這個功能完全一樣
 * - 鍵的位置來自版面宣告（KeyboardLayout）：同樣位置的版面共用一份習慣（geometryId），預設是內建 QWERTY
 *
 * 座標：一欄為 1（x）、排距為 1（y），原點為第一欄的鍵中心（QWERTY 是 q）；觸控偏移沿用 SpatialKeyLayout 的單位（半個鍵寬／鍵高為 1）
 */
class QwertyModel(val layout: KeyboardLayout = KeyboardLayout.QWERTY) {

    companion object {
        private const val VAR_X = 0.5 / 1.2
        private const val VAR_Y = 0.5
    }

    /** 字鍵中心座標 */
    val pos: Map<Char, Pair<Double, Double>> = layout.centers
    private val widths = layout.widths

    /** 兩個鍵是否相鄰（含斜角）：鍵心距離² ≤ 1.6 */
    fun adjacent(a: Char, b: Char): Boolean {
        val p = pos[a] ?: return false
        val q = pos[b] ?: return false
        val dx = p.first - q.first
        val dy = p.second - q.second
        return dx * dx + dy * dy <= 1.6
    }

    private val habits = KeyHabits()

    /** 分握法的偏移（PostureTracker 的 TWO_THUMBS、ONE_HAND）：樣本少時退回不分握法的 habits */
    private val postureHabits = arrayOf(KeyHabits(), KeyHabits())

    /** 目前判斷的握法（每按一下由 ScorpioDictManager 更新）；0 是不確定 */
    @Volatile
    var posture = 0

    private fun bias(key: Char): Pair<Double, Double> {
        val general = habits.effectiveBias(key)
        val ph = postureHabits.getOrNull(posture - 1) ?: return general
        val g = habits.globalEffective()
        val (pgx, pgy) = ph.globalToward(g)
        return ph.biasToward(key, (general.first + pgx - g.first) to (general.second + pgy - g.second))
    }

    /** 落點的鍵盤座標；不是字母鍵（數字、符號）回傳 null */
    fun point(touch: BopomofoComposer.TouchPoint): Pair<Double, Double>? = point(touch.rawChar, touch.offsetX, touch.offsetY)

    fun point(raw: Char, offsetX: Float, offsetY: Float): Pair<Double, Double>? {
        val c = raw.lowercaseChar()
        val p = pos[c] ?: return null
        return (p.first + offsetX * 0.5 * (widths[c] ?: 1.0)) to (p.second + offsetY * 0.5)
    }

    /** 瞄準這個鍵時的落點中心（鍵中心＋個人化偏移） */
    fun center(key: Char, useHabits: Boolean = true): Pair<Double, Double>? {
        val p = pos[key] ?: return null
        if (!useHabits) return p
        val (bx, by) = bias(key)
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
        for (k in pos.keys) {
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
        val target = pos[key] ?: return false
        val (px, py) = point(touch) ?: return false
        val dx = px - target.first
        val dy = py - target.second
        if (abs(dx) > 1.5 || abs(dy) > 1.5) return false
        habits.add(key, dx, dy)
        postureHabits.getOrNull(posture - 1)?.add(key, dx, dy)
        return true
    }

    fun sampleCount(): Int = habits.sampleCount()

    /** 設定頁的落點分佈圖：posture 0 是不分握法的，TWO_THUMBS、ONE_HAND 是那個握法自己學到的 */
    fun keySpreads(posture: Int = 0): Map<Char, KeyHabits.KeySpread> =
        (postureHabits.getOrNull(posture - 1) ?: habits).keySpreads()

    /** 各握法學了幾筆（設定頁用） */
    fun postureSampleCounts(): List<Int> = postureHabits.map { it.sampleCount() }

    /** 不分握法的習慣，接著是各握法的（以 || 隔開；舊版只有第一段） */
    fun serialize(): String = (listOf(habits) + postureHabits).joinToString("||") { it.serialize() }

    fun restore(data: String?) {
        val parts = data?.split("||").orEmpty()
        habits.restore(parts.getOrNull(0))
        for ((i, h) in postureHabits.withIndex()) h.restore(parts.getOrNull(i + 1))
    }

    /** 版面改版：從舊版面的習慣（serialize 的內容）與鍵位搬過來（見 KeyHabits.migrateFrom；分握法的不搬，重新學） */
    fun migrateFrom(oldData: String, oldCenters: Map<Char, Pair<Double, Double>>) =
        habits.migrateFrom(KeyHabits().apply { restore(oldData.substringBefore("||")) }, oldCenters, pos)
}
