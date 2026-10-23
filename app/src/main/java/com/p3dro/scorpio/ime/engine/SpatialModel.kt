package com.p3dro.scorpio.ime.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 注音鍵盤空間模型（噪聲通道模型的觸控端 P(落點 | 按鍵)）
 *
 * 1. 每個鍵是一個二元高斯分佈：落點到鍵中心的距離（以鍵距為單位、x/y 各自的標準差）決定機率，
 *    懲罰 = 負對數機率差，與解碼器的 log 機率同一尺度
 * 2. 個人化偏移校正：每次確定選字後，用「想按的鍵」與實際落點更新該鍵的系統性偏移（例如習慣按在鍵的下緣），
 *    樣本少的鍵向全體平均收斂
 * 3. 逐鍵音節合法性：按下當下若最近的鍵會讓目前音節拼不起來（ㄉㄨ + ㄠ），而旁邊的鍵可以（ㄛ），
 *    且落點本來就靠近兩鍵交界，就改判為旁邊的鍵；落點在鍵中央時尊重實際按下的鍵
 *
 * 觸控偏移單位沿用 ZhuyinGridLayout：相對「實際按下鍵」中心、以半個鍵寬／鍵高為 1
 */
class SpatialModel {

    companion object {
        // 大千注音 10x4 按鍵位置（與 ZhuyinGridLayout 排列一致）
        val GRID: List<List<Char>> = listOf(
            listOf('ㄅ', 'ㄉ', 'ˇ', 'ˋ', 'ㄓ', 'ˊ', '˙', 'ㄚ', 'ㄞ', 'ㄢ'),
            listOf('ㄆ', 'ㄊ', 'ㄍ', 'ㄐ', 'ㄔ', 'ㄗ', 'ㄧ', 'ㄛ', 'ㄟ', 'ㄣ'),
            listOf('ㄇ', 'ㄋ', 'ㄎ', 'ㄑ', 'ㄕ', 'ㄘ', 'ㄨ', 'ㄜ', 'ㄠ', 'ㄤ'),
            listOf('ㄈ', 'ㄌ', 'ㄏ', 'ㄒ', 'ㄖ', 'ㄙ', 'ㄩ', 'ㄝ', 'ㄡ', 'ㄥ')
        )

        // 半鍵單位 → 鍵距單位（鍵寬含左右 2.5dp 間距，約 2.3 個半鍵；鍵高含排距約 2.2 個半鍵）
        const val HALF_KEYS_PER_PITCH_X = 2.3
        const val HALF_KEYS_PER_PITCH_Y = 2.2

        // 高斯標準差（鍵距單位）。點在鍵中央時：左右鄰鍵懲罰 1.2、上下鄰鍵 1.0、斜角 2.2
        private const val VAR_X = 0.5 / 1.2
        private const val VAR_Y = 0.5 / 1.0

        const val MIN_PENALTY = 0.15
        const val MAX_PENALTY = 3.0

        // 拼不起來的音節在按下當下的懲罰：只在落點已偏向鄰鍵時才改判（左右約需偏 0.3 鍵距、上下約 0.25）
        const val ILLEGAL_PENALTY = 0.5

        // 拼得起來但不常見：依「接上這個符號的音節有多常用」連續加權，常用的鍵感應區大一點
        // 每差 e 倍扣 0.12，最多扣 0.35（邊界最多偏移約 0.2 鍵距，且一定比拼不起來輕）
        const val PRIOR_WEIGHT = 0.12
        const val PRIOR_CAP = 0.35

        // 下一個符號開始新音節的機率：前一個音節已完整（可省略聲調）／只有一個聲母（簡拼）
        private const val P_NEW_AFTER_COMPLETE = 0.25
        private const val P_NEW_AFTER_PARTIAL = 0.1

        // 個人化偏移：每鍵樣本向全體平均收斂的強度、全體平均向 0 收斂的強度、最大校正量（鍵距）
        private const val KEY_PRIOR = 20.0
        private const val GLOBAL_PRIOR = 30.0
        private const val MAX_BIAS = 0.35
        private const val MAX_SAMPLES = 300.0

        private val POS: Map<Char, Pair<Int, Int>> = buildMap {
            for (r in GRID.indices) for (c in GRID[r].indices) put(GRID[r][c], r to c)
        }

        fun isTone(c: Char) = BopomofoComposer.TONES.contains(c)
    }

    /** 偏移統計：平均偏移（鍵距單位）與樣本數 */
    class Bias(var dx: Double = 0.0, var dy: Double = 0.0, var n: Double = 0.0) {
        fun add(x: Double, y: Double) {
            n = minOf(n + 1.0, MAX_SAMPLES)
            dx += (x - dx) / n
            dy += (y - dy) / n
        }
    }

    // 半鍵單位 → 鍵距單位的換算（依實際版面更新；按鍵高度可在設定調整）
    @Volatile
    private var pitchX = HALF_KEYS_PER_PITCH_X
    @Volatile
    private var pitchY = HALF_KEYS_PER_PITCH_Y

    /** 以實際鍵盤版面更新換算：相鄰兩鍵中心距離 ÷ 半個鍵寬（鍵高） */
    fun setPitch(halfKeysPerPitchX: Double, halfKeysPerPitchY: Double) {
        if (halfKeysPerPitchX in 1.5..4.0) pitchX = halfKeysPerPitchX
        if (halfKeysPerPitchY in 1.5..4.0) pitchY = halfKeysPerPitchY
    }

    private val keyBias = HashMap<Char, Bias>()
    private val globalBias = Bias()

    /** 用來判斷音節是否拼得起來（含尚未打完的開頭）；未載入前不做合法性判斷 */
    @Volatile
    var syllablePrefixes: Set<String>? = null

    /** 各音節開頭（不含聲調）的使用量總和：以這個開頭的所有音節的字頻加總 */
    @Volatile
    private var prefixMass: Map<String, Double> = emptyMap()
    @Volatile
    private var validSet: Set<String> = emptySet()
    private var totalMass = 0.0

    fun setValidSyllables(valid: Set<String>) = setSyllableFrequencies(valid.associateWith { 1.0 })

    /** 合法音節（不含聲調）與其使用量（單字字頻加總，相對值即可） */
    fun setSyllableFrequencies(freq: Map<String, Double>) {
        val mass = HashMap<String, Double>(freq.size * 3)
        for ((s, f) in freq) for (i in 1..s.length) {
            val p = s.substring(0, i)
            mass[p] = (mass[p] ?: 0.0) + f
        }
        prefixMass = mass
        validSet = freq.keys
        totalMass = freq.values.sum()
        syllablePrefixes = mass.keys
    }

    // ---------- 座標 ----------

    /** 落點的實體座標（鍵距單位，原點為左上角鍵中心）；null 表示不在注音網格上（例如 ㄦ） */
    private fun physicalPoint(touch: BopomofoComposer.TouchPoint): Pair<Double, Double>? {
        val (r, c) = POS[touch.rawChar] ?: return null
        return Pair(c + touch.offsetX / pitchX, r + touch.offsetY / pitchY)
    }

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

    /** 落點相對某鍵的高斯代價（負對數機率，未正規化）：先扣掉使用者瞄準該鍵時的習慣偏移 */
    private fun cost(point: Pair<Double, Double>, key: Char): Double {
        val (r, c) = POS[key] ?: return Double.MAX_VALUE
        val (bx, by) = effectiveBias(key)
        val dx = point.first - (c + bx)
        val dy = point.second - (r + by)
        return 0.5 * (dx * dx / VAR_X + dy * dy / VAR_Y)
    }

    private fun gridNeighbors(key: Char): List<Char> {
        val (r, c) = POS[key] ?: return emptyList()
        val out = ArrayList<Char>(8)
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            GRID.getOrNull(r + dr)?.getOrNull(c + dc)?.let { out.add(it) }
        }
        return out
    }

    // ---------- 解碼器：鄰鍵懲罰 ----------

    /**
     * 目前判定的鍵（touch.char）周圍各鄰鍵的懲罰 = 該鍵代價 − 目前鍵代價（夾在 0.15～3.0）
     * 落點偏向哪邊、或使用者習慣偏向哪邊，那邊的鍵就越便宜
     */
    fun neighborPenalties(touch: BopomofoComposer.TouchPoint): List<Pair<Char, Double>> {
        if (!POS.containsKey(touch.char)) return emptyList()
        val point = physicalPoint(touch) ?: return emptyList()
        val base = cost(point, touch.char)
        val keys = LinkedHashSet(gridNeighbors(touch.char))
        // 被改判過的鍵，原本按到的鍵一定要列為候選
        if (touch.rawChar != touch.char && POS.containsKey(touch.rawChar)) keys.add(touch.rawChar)
        return keys.map { k -> k to (cost(point, k) - base).coerceIn(MIN_PENALTY, MAX_PENALTY) }
    }

    // ---------- 按下當下：判定這一鍵 ----------

    /**
     * 依落點、個人化偏移與音節合法性決定這一鍵實際算哪個鍵
     * - 不會把一般按鍵改判成聲調鍵（聲調會直接結束音節，改錯代價太高）；聲調鍵可改判回一般鍵
     * - 回傳的 TouchPoint 保留原始按鍵與落點（rawChar / offset），解碼時仍能回頭考慮原本的鍵
     */
    fun resolveTap(
        rawChar: Char,
        offsetX: Float,
        offsetY: Float,
        history: List<BopomofoComposer.TouchPoint>
    ): BopomofoComposer.TouchPoint {
        val raw = BopomofoComposer.TouchPoint(rawChar, offsetX, offsetY)
        if (!POS.containsKey(rawChar)) return raw
        val point = physicalPoint(raw) ?: return raw

        val options = listOf(rawChar) + gridNeighbors(rawChar).filter { !isTone(it) }
        val priors = priorPenalties(history, options)
        fun score(k: Char) = cost(point, k) + (priors[k] ?: 0.0)

        var best = rawChar
        var bestScore = score(rawChar)
        for (k in options) {
            if (k == rawChar) continue
            val s = score(k)
            if (s < bestScore - 1e-9) {
                best = k
                bestScore = s
            }
        }
        return if (best == rawChar) raw else raw.copy(char = best)
    }

    /**
     * 按下當下的語言先驗懲罰：拼不起來 → ILLEGAL_PENALTY；拼得起來 → 依可能性連續加權（最可能的鍵 0，最多 PRIOR_CAP）
     * P(k) = 接在目前音節後面的機率 + 開始新音節的機率（簡拼、省略聲調都合法）
     */
    fun priorPenalties(history: List<BopomofoComposer.TouchPoint>, keys: List<Char>): Map<Char, Double> {
        val mass = prefixMass
        val out = HashMap<Char, Double>(keys.size)
        val probs = HashMap<Char, Double>(keys.size)
        val cur = currentSyllable(history)
        for (k in keys) {
            if (!isLegalNext(history, k)) {
                out[k] = ILLEGAL_PENALTY
                continue
            }
            probs[k] = if (mass.isEmpty() || isTone(k)) 1.0 else probabilityOf(cur, k, mass)
        }
        val best = probs.values.maxOrNull() ?: return out
        for ((k, p) in probs) {
            out[k] = if (best <= 0.0 || p >= best) 0.0
            else if (p <= 0.0) PRIOR_CAP
            else (PRIOR_WEIGHT * kotlin.math.ln(best / p)).coerceAtMost(PRIOR_CAP)
        }
        return out
    }

    /** 目前正在打的音節（不含聲調）；剛打完聲調或還沒開始則為空字串 */
    private fun currentSyllable(history: List<BopomofoComposer.TouchPoint>): String {
        val last = history.lastOrNull() ?: return ""
        if (isTone(last.char)) return ""
        return BopomofoComposer.parseSyllables(history).lastOrNull()?.text?.filter { !isTone(it) }.orEmpty()
    }

    private fun probabilityOf(cur: String, k: Char, mass: Map<String, Double>): Double {
        val total = totalMass.takeIf { it > 0 } ?: return 1.0
        val start = (mass[k.toString()] ?: 0.0) / total
        if (cur.isEmpty()) return start
        val curMass = mass[cur] ?: 0.0
        val pNew = if (cur in validSet) P_NEW_AFTER_COMPLETE else P_NEW_AFTER_PARTIAL
        val cont = if (curMass > 0) (mass[cur + k] ?: 0.0) / curMass else 0.0
        return (1 - pNew) * cont + pNew * start
    }

    /** 接上 k 之後，最後一個音節（去掉聲調）是否仍是某個合法音節或其開頭 */
    fun isLegalNext(history: List<BopomofoComposer.TouchPoint>, k: Char): Boolean {
        val prefixes = syllablePrefixes ?: return true
        val parsed = BopomofoComposer.parseSyllables(history + BopomofoComposer.TouchPoint(k))
        val last = parsed.lastOrNull()?.text ?: return false
        val tl = last.filter { !isTone(it) }
        if (tl.isEmpty()) return false // 聲調前面沒有任何注音
        if (isTone(k) && history.lastOrNull()?.let { isTone(it.char) } == true) return false
        return tl.length == 1 || prefixes.contains(tl)
    }

    // ---------- 個人化偏移學習 ----------

    /** 記錄一筆「想按 intended、實際落在 touch」的樣本 */
    @Synchronized
    fun learn(intended: Char, touch: BopomofoComposer.TouchPoint) {
        val (r, c) = POS[intended] ?: return
        val point = physicalPoint(touch) ?: return
        val dx = point.first - c
        val dy = point.second - r
        // 離目標超過一個半鍵距多半是解析錯位而非手滑，不納入
        if (abs(dx) > 1.5 || abs(dy) > 1.5) return
        keyBias.getOrPut(intended) { Bias() }.add(dx, dy)
        globalBias.add(dx, dy)
    }

    fun sampleCount(): Int = globalBias.n.toInt()

    /** 序列化：「鍵:dx,dy,n;…」，全體平均以 * 為鍵 */
    @Synchronized
    fun serialize(): String {
        val sb = StringBuilder()
        fun put(k: Char, b: Bias) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(k).append(':').append("%.4f,%.4f,%.1f".format(java.util.Locale.ROOT, b.dx, b.dy, b.n))
        }
        put('*', globalBias)
        for ((k, b) in keyBias) put(k, b)
        return sb.toString()
    }

    @Synchronized
    fun restore(data: String?) {
        keyBias.clear()
        globalBias.dx = 0.0; globalBias.dy = 0.0; globalBias.n = 0.0
        if (data.isNullOrBlank()) return
        for (part in data.split(';')) {
            val idx = part.indexOf(':')
            if (idx != 1) continue
            val nums = part.substring(2).split(',').mapNotNull { it.toDoubleOrNull() }
            if (nums.size != 3) continue
            val b = if (part[0] == '*') globalBias else keyBias.getOrPut(part[0]) { Bias() }
            b.dx = nums[0]; b.dy = nums[1]; b.n = nums[2]
        }
    }
}
