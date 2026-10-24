package com.p3dro.scorpio.ime.engine

import kotlin.math.sqrt

/**
 * 移動造成的落點偏差：從上一下的落點移到這個鍵，手指常沿著移動方向多衝一點或沒移到位
 * （例如從最左上的 ㄅ 移到右下方的 ㄒ）。偏差 d ≈ A·v，v＝從上一下落點到目標鍵中心的位移（鍵距單位），A 是 2×2。
 *
 * 用帶遺忘的最小平方法線上學 A（每筆舊資料的權重乘 DECAY，大約跟著最近幾百下走），並向 0 收斂（RIDGE）：
 * 資料少或移動跟偏差沒關係時 A 接近 0，跟沒有這個功能一樣。只學 4 個數字，所有鍵的組合共用，學得快
 */
class MotionBias {

    companion object {
        private const val DECAY = 0.995
        // 收斂強度：相當於幾筆「移動 1 鍵距、偏差 0」的資料
        private const val RIDGE = 40.0
        // 移動造成的校正最多幾個鍵距（跟每鍵習慣偏移的上限同一個量級）
        private const val MAX_SHIFT = 0.3
        /** 跟上一下隔太久（手離開、想事情）就不算連續移動 */
        const val MAX_GAP_MS = 1000L
    }

    // Σ v vᵀ（對稱）、Σ d vᵀ、樣本數，都帶遺忘權重
    private var vxx = 0.0
    private var vxy = 0.0
    private var vyy = 0.0
    private var dxvx = 0.0
    private var dxvy = 0.0
    private var dyvx = 0.0
    private var dyvy = 0.0
    private var n = 0.0

    // 目前的 A（a11 a12 / a21 a22），每次學完重算
    @Volatile
    private var a = DoubleArray(4)

    /** 一筆樣本：位移 v、落點相對（目標鍵中心＋該鍵習慣偏移）的偏差 d */
    @Synchronized
    fun add(vx: Double, vy: Double, dx: Double, dy: Double) {
        vxx = vxx * DECAY + vx * vx
        vxy = vxy * DECAY + vx * vy
        vyy = vyy * DECAY + vy * vy
        dxvx = dxvx * DECAY + dx * vx
        dxvy = dxvy * DECAY + dx * vy
        dyvx = dyvx * DECAY + dy * vx
        dyvy = dyvy * DECAY + dy * vy
        n = n * DECAY + 1.0
        recompute()
    }

    /** A = (Σ d vᵀ)(Σ v vᵀ + RIDGE·I)⁻¹ */
    private fun recompute() {
        val m11 = vxx + RIDGE
        val m12 = vxy
        val m22 = vyy + RIDGE
        val det = m11 * m22 - m12 * m12
        if (det <= 1e-9) return
        val i11 = m22 / det
        val i12 = -m12 / det
        val i22 = m11 / det
        a = doubleArrayOf(
            dxvx * i11 + dxvy * i12, dxvx * i12 + dxvy * i22,
            dyvx * i11 + dyvy * i12, dyvx * i12 + dyvy * i22
        )
    }

    /** 從上一下移過來（位移 v）時，瞄準的鍵中心要再加多少（鍵距單位，最多 MAX_SHIFT） */
    fun shift(vx: Double, vy: Double): Pair<Double, Double> {
        val m = a
        val sx = m[0] * vx + m[1] * vy
        val sy = m[2] * vx + m[3] * vy
        val len = sqrt(sx * sx + sy * sy)
        if (len <= MAX_SHIFT) return sx to sy
        return (sx / len * MAX_SHIFT) to (sy / len * MAX_SHIFT)
    }

    @Synchronized
    fun sampleCount(): Int = n.toInt()

    /** 開發用（explain）：目前的 A */
    fun describe(): String = a.let { "[%.3f %.3f; %.3f %.3f]（%d 筆）".format(it[0], it[1], it[2], it[3], sampleCount()) }

    @Synchronized
    fun serialize(): String = listOf(vxx, vxy, vyy, dxvx, dxvy, dyvx, dyvy, n)
        .joinToString(",") { "%.6f".format(java.util.Locale.ROOT, it) }

    @Synchronized
    fun restore(data: String?) {
        val v = data?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 8 } ?: List(8) { 0.0 }
        vxx = v[0]; vxy = v[1]; vyy = v[2]; dxvx = v[3]; dxvy = v[4]; dyvx = v[5]; dyvy = v[6]; n = v[7]
        a = DoubleArray(4)
        recompute()
    }
}
