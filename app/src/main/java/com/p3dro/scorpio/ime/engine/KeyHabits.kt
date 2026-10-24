package com.p3dro.scorpio.ime.engine

import kotlin.math.sqrt

/**
 * 每個鍵的個人化落點習慣（大千注音 SpatialModel 與 QWERTY 的 QwertyModel 共用）
 * - 偏移：每鍵的平均偏移與樣本數，樣本少的鍵向全體平均收斂，全體平均再向 0 收斂，最多校正 MAX_BIAS
 * - 散佈：每鍵落點的變異數，相對於所有鍵的平均變異數放寬或收窄（樣本少向 1 收斂）
 * 座標單位由使用的模型決定（都是鍵距）；沒有任何資料時偏移為 0、散佈比例為 1
 */
class KeyHabits {

    companion object {
        // 每鍵樣本向全體平均收斂的強度、全體平均向 0 收斂的強度、最大校正量（鍵距）
        private const val KEY_PRIOR = 20.0
        private const val GLOBAL_PRIOR = 30.0
        private const val MAX_BIAS = 0.35
        private const val MAX_SAMPLES = 300.0

        // 個人化散佈：每鍵變異數向「所有鍵的平均變異數」收斂的強度（樣本少的鍵維持原本寬度），以及放寬／收窄的上下限
        private const val SPREAD_PRIOR = 40.0
        private const val MIN_SPREAD = 0.6
        private const val MAX_SPREAD = 1.6
    }

    /**
     * 偏移統計：平均偏移（鍵距單位）與樣本數；vx/vy 是落點對平均的變異數，m 是算變異數用的樣本數
     * （舊版存的資料沒有變異數，m 從 0 開始累積）。超過 MAX_SAMPLES 之後都變成移動平均，一直跟著使用者的習慣走
     */
    class Bias(var dx: Double = 0.0, var dy: Double = 0.0, var n: Double = 0.0, var vx: Double = 0.0, var vy: Double = 0.0, var m: Double = 0.0) {
        fun add(x: Double, y: Double) {
            n = minOf(n + 1.0, MAX_SAMPLES)
            m = minOf(m + 1.0, MAX_SAMPLES)
            val ox = dx
            val oy = dy
            dx += (x - dx) / n
            dy += (y - dy) / n
            vx += ((x - ox) * (x - dx) - vx) / m
            vy += ((y - oy) * (y - dy) - vy) / m
        }
    }

    /** 一個鍵的落點分佈（鍵距單位）：平均偏移、標準差與樣本數；sdX/sdY 為 null 表示散佈樣本還太少 */
    class KeySpread(val dx: Double, val dy: Double, val sdX: Double?, val sdY: Double?, val n: Double)

    private val keyBias = HashMap<Char, Bias>()
    private val globalBias = Bias()

    /** 所有鍵落點變異數的平均（依樣本數加權），散佈比例的基準；學習、還原時重算 */
    private var refVar: Pair<Double, Double>? = null

    /** 個人化偏移（鍵距單位）：樣本少的鍵以全體平均為準 */
    @Synchronized
    fun effectiveBias(key: Char): Pair<Double, Double> {
        val g = globalBias
        val gw = g.n / (g.n + GLOBAL_PRIOR)
        val gx = g.dx * gw
        val gy = g.dy * gw
        val k = keyBias[key] ?: return clampBias(gx, gy)
        val x = (k.n * k.dx + KEY_PRIOR * gx) / (k.n + KEY_PRIOR)
        val y = (k.n * k.dy + KEY_PRIOR * gy) / (k.n + KEY_PRIOR)
        return clampBias(x, y)
    }

    private fun clampBias(x: Double, y: Double): Pair<Double, Double> {
        val len = sqrt(x * x + y * y)
        if (len <= MAX_BIAS) return x to y
        return (x / len * MAX_BIAS) to (y / len * MAX_BIAS)
    }

    /**
     * 這個鍵的散佈相對於使用者所有鍵的平均（x, y 各一個比例，1 = 跟平均一樣）：
     * 樣本少的鍵向 1 收斂；沒有任何散佈資料時都是 1（跟沒有這個功能時完全一樣）
     */
    @Synchronized
    fun spreadRatio(key: Char): Pair<Double, Double> {
        val ref = refVar ?: computeRefVar().also { refVar = it }
        val k = keyBias[key]
        if (k == null || k.m <= 0.0) return 1.0 to 1.0
        fun ratio(v: Double, r: Double) = if (r <= 0.0) 1.0 else ((k.m * v + SPREAD_PRIOR * r) / (k.m + SPREAD_PRIOR) / r).coerceIn(MIN_SPREAD, MAX_SPREAD)
        return ratio(k.vx, ref.first) to ratio(k.vy, ref.second)
    }

    private fun computeRefVar(): Pair<Double, Double> {
        var w = 0.0
        var sx = 0.0
        var sy = 0.0
        for (b in keyBias.values) {
            if (b.m <= 0.0) continue
            w += b.m
            sx += b.m * b.vx
            sy += b.m * b.vy
        }
        return if (w > 0) (sx / w) to (sy / w) else 0.0 to 0.0
    }

    /** 一筆「想按 key、落點相對該鍵中心 (dx, dy)」的樣本（鍵距單位） */
    @Synchronized
    fun add(key: Char, dx: Double, dy: Double) {
        keyBias.getOrPut(key) { Bias() }.add(dx, dy)
        globalBias.add(dx, dy)
        refVar = null
    }

    fun sampleCount(): Int = globalBias.n.toInt()

    /** 設定頁的落點分佈圖用：每個學過的鍵的原始統計（不含向全體平均的收斂） */
    @Synchronized
    fun keySpreads(): Map<Char, KeySpread> = keyBias.mapValues { (_, b) ->
        val hasSpread = b.m >= 5.0
        KeySpread(b.dx, b.dy, if (hasSpread) sqrt(b.vx) else null, if (hasSpread) sqrt(b.vy) else null, b.n)
    }

    /** 序列化：「鍵:dx,dy,n,vx,vy,m;…」，全體平均以 * 為鍵 */
    @Synchronized
    fun serialize(): String {
        val sb = StringBuilder()
        fun put(k: Char, b: Bias) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(k).append(':').append("%.4f,%.4f,%.1f,%.5f,%.5f,%.1f".format(java.util.Locale.ROOT, b.dx, b.dy, b.n, b.vx, b.vy, b.m))
        }
        put('*', globalBias)
        for ((k, b) in keyBias) put(k, b)
        return sb.toString()
    }

    /** 「鍵:dx,dy,n[,vx,vy,m]」；舊版只有前三個數字（沒有散佈資料，m 從 0 開始累積） */
    @Synchronized
    fun restore(data: String?) {
        keyBias.clear()
        globalBias.dx = 0.0; globalBias.dy = 0.0; globalBias.n = 0.0
        globalBias.vx = 0.0; globalBias.vy = 0.0; globalBias.m = 0.0
        refVar = null
        if (data.isNullOrBlank()) return
        for (part in data.split(';')) {
            val idx = part.indexOf(':')
            if (idx != 1) continue
            val nums = part.substring(2).split(',').mapNotNull { it.toDoubleOrNull() }
            if (nums.size != 3 && nums.size != 6) continue
            val b = if (part[0] == '*') globalBias else keyBias.getOrPut(part[0]) { Bias() }
            b.dx = nums[0]; b.dy = nums[1]; b.n = nums[2]
            if (nums.size == 6) {
                b.vx = nums[3]; b.vy = nums[4]; b.m = nums[5]
            }
        }
    }
}
