package com.p3dro.scorpio.ime.engine

import kotlin.math.sqrt

/**
 * 每個鍵的個人化落點習慣（大千注音 SpatialModel 與 QWERTY 的 QwertyModel 共用）
 * - 偏移：每鍵的平均偏移與樣本數，樣本少的鍵向全體平均收斂，全體平均再向 0 收斂，最多校正 MAX_BIAS
 * - 散佈：每鍵落點的變異數，只給設定頁的落點分佈圖看（拿來放寬／收窄感應範圍試過，判對率沒有差別，已拿掉）
 * 座標單位由使用的模型決定（都是鍵距）；沒有任何資料時偏移為 0
 */
class KeyHabits {

    companion object {
        // 每鍵樣本向全體平均收斂的強度、全體平均向 0 收斂的強度、最大校正量（鍵距）
        private const val KEY_PRIOR = 20.0
        private const val GLOBAL_PRIOR = 30.0
        private const val MAX_BIAS = 0.35
        private const val MAX_SAMPLES = 300.0
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

    /** 這一層的全體平均偏移，樣本少時向 prior（上一層的全體平均）收斂，不是向 0：例如某個握法 → 不分握法 */
    @Synchronized
    fun globalToward(prior: Pair<Double, Double>): Pair<Double, Double> {
        val g = globalBias
        return (g.n * g.dx + GLOBAL_PRIOR * prior.first) / (g.n + GLOBAL_PRIOR) to
            (g.n * g.dy + GLOBAL_PRIOR * prior.second) / (g.n + GLOBAL_PRIOR)
    }

    /** 全體平均偏移（已向 0 收斂） */
    @Synchronized
    fun globalEffective(): Pair<Double, Double> {
        val gw = globalBias.n / (globalBias.n + GLOBAL_PRIOR)
        return (globalBias.dx * gw) to (globalBias.dy * gw)
    }

    /**
     * 分層退回（Yin et al. 2013 的 spatial backoff）：這一層的樣本少時向 prior（上一層的估計）收斂，
     * 例如某個握法的某個鍵 → 不分握法的這個鍵
     */
    @Synchronized
    fun biasToward(key: Char, prior: Pair<Double, Double>): Pair<Double, Double> {
        val k = keyBias[key] ?: return clampBias(prior.first, prior.second)
        val x = (k.n * k.dx + KEY_PRIOR * prior.first) / (k.n + KEY_PRIOR)
        val y = (k.n * k.dy + KEY_PRIOR * prior.second) / (k.n + KEY_PRIOR)
        return clampBias(x, y)
    }

    private fun clampBias(x: Double, y: Double): Pair<Double, Double> {
        val len = sqrt(x * x + y * y)
        if (len <= MAX_BIAS) return x to y
        return (x / len * MAX_BIAS) to (y / len * MAX_BIAS)
    }

    /** 一筆「想按 key、落點相對該鍵中心 (dx, dy)」的樣本（鍵距單位） */
    @Synchronized
    fun add(key: Char, dx: Double, dy: Double) {
        keyBias.getOrPut(key) { Bias() }.add(dx, dy)
        globalBias.add(dx, dy)
    }

    fun sampleCount(): Int = globalBias.n.toInt()

    /** 設定頁的落點分佈圖用：每個學過的鍵的原始統計（不含向全體平均的收斂） */
    @Synchronized
    fun keySpreads(): Map<Char, KeySpread> = keyBias.mapValues { (_, b) ->
        val hasSpread = b.m >= 5.0
        KeySpread(b.dx, b.dy, if (hasSpread) sqrt(b.vx) else null, if (hasSpread) sqrt(b.vy) else null, b.n)
    }

    /**
     * 版面改版、鍵的位置變了：每個新鍵拿舊版面裡「位置最近的鍵」的習慣（習慣主要跟拇指按在螢幕哪裡有關），
     * 樣本數減半，用新版面打一陣子就會跟上；全體平均照搬（一樣減半）
     */
    fun migrateFrom(old: KeyHabits, oldCenters: Map<Char, Pair<Double, Double>>, newCenters: Map<Char, Pair<Double, Double>>) {
        val snapshot = KeyHabits().apply { restore(old.serialize()) }
        synchronized(this) {
            keyBias.clear()
            fun copy(from: Bias, to: Bias) {
                to.dx = from.dx; to.dy = from.dy; to.n = from.n / 2
                to.vx = from.vx; to.vy = from.vy; to.m = from.m / 2
            }
            copy(snapshot.globalBias, globalBias)
            for ((k, p) in newCenters) {
                val nearest = oldCenters.minByOrNull { (_, q) -> (p.first - q.first).let { it * it } + (p.second - q.second).let { it * it } }
                    ?: continue
                val b = snapshot.keyBias[nearest.key] ?: continue
                copy(b, keyBias.getOrPut(k) { Bias() })
            }
        }
    }

    /** 序列化：「鍵:dx,dy,n,vx,vy,m;…」，全體平均以 * 為鍵（所以版面的字鍵不能是 * : ;） */
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
