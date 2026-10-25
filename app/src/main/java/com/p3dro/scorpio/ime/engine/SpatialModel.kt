package com.p3dro.scorpio.ime.engine

import kotlin.math.abs

/**
 * 注音鍵盤空間模型（噪聲通道模型的觸控端 P(落點 | 按鍵)）
 *
 * 1. 每個鍵是一個二元高斯分佈：落點到鍵中心的距離（以鍵距為單位、x/y 各自的標準差）決定機率，
 *    懲罰 = 負對數機率差，與解碼器的 log 機率同一尺度
 * 2. 個人化偏移校正：每次確定選字後，用「想按的鍵」與實際落點更新該鍵的系統性偏移（例如習慣按在鍵的下緣），
 *    樣本少的鍵向全體平均收斂；連續打字時再加上移動造成的偏差（MotionBias：從上一下移過來，常沿著移動方向多衝或沒到位）
 * 3. 逐鍵音節合法性：按下當下若最近的鍵會讓目前音節拼不起來（ㄉㄨ + ㄠ），而旁邊的鍵可以（ㄛ），
 *    且落點本來就靠近兩鍵交界，就改判為旁邊的鍵；落點在鍵中央時尊重實際按下的鍵
 *
 * 觸控偏移單位沿用 ZhuyinGridLayout：相對「實際按下鍵」中心、以半個鍵寬／鍵高為 1
 */
class SpatialModel {

    companion object {
        // 大千注音 10x4 按鍵位置：來自版面宣告（KeyboardLayout.DACHEN），鍵盤畫面也照它排
        val GRID: List<List<Char>> = KeyboardLayout.DACHEN.rows.map { row -> row.mapNotNull { it.char } }

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

        private val POS: Map<Char, Pair<Int, Int>> = buildMap {
            for (r in GRID.indices) for (c in GRID[r].indices) put(GRID[r][c], r to c)
        }

        fun isTone(c: Char) = BopomofoComposer.TONES.contains(c)
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

    /** 每鍵的個人化偏移（鍵距單位） */
    private val habits = KeyHabits()

    /** 分握法的偏移（PostureTracker 的 TWO_THUMBS、ONE_HAND 各一份）：樣本少時退回不分握法的 habits */
    private val postureHabits = arrayOf(KeyHabits(), KeyHabits())

    /** 移動造成的偏差（連續打字時） */
    val motion = MotionBias()

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
    fun physicalPoint(touch: BopomofoComposer.TouchPoint): Pair<Double, Double>? {
        val (r, c) = POS[touch.rawChar] ?: return null
        return Pair(c + touch.offsetX / pitchX, r + touch.offsetY / pitchY)
    }

    /** 個人化偏移（鍵距單位）：樣本少的鍵以全體平均為準 */
    fun effectiveBias(key: Char): Pair<Double, Double> = habits.effectiveBias(key)

    /**
     * 某個握法時的偏移：先拿不分握法的這個鍵，加上這個握法整體比平常偏多少，再向這個握法這個鍵自己的樣本靠
     * （樣本越多越像自己）；不知道握法時就是不分握法的
     */
    fun effectiveBias(key: Char, posture: Int): Pair<Double, Double> {
        val general = habits.effectiveBias(key)
        val ph = postureHabits.getOrNull(posture - 1) ?: return general
        val (pgx, pgy) = ph.globalEffective()
        val (gx, gy) = habits.globalEffective()
        return ph.biasToward(key, (general.first + pgx - gx) to (general.second + pgy - gy))
    }

    /** 從 from 移到 key 時，移動造成的偏差（沒有上一下時是 0） */
    private fun motionShift(key: Char, from: Pair<Double, Double>?): Pair<Double, Double> {
        if (from == null) return 0.0 to 0.0
        val (r, c) = POS[key] ?: return 0.0 to 0.0
        return motion.shift(c - from.first, r - from.second)
    }

    /**
     * 落點相對某鍵的高斯代價（負對數機率）：先扣掉使用者瞄準該鍵時的習慣偏移、從上一下移過來的偏差（鍵中央的代價是 0）。
     * 不加常態分佈的正規化項：這裡的高斯寬度是調過的懲罰尺度（比手指實際的散佈寬很多）
     */
    private fun cost(point: Pair<Double, Double>, key: Char, from: Pair<Double, Double>? = null, useMotion: Boolean = true, posture: Int = 0): Double {
        val (r, c) = POS[key] ?: return Double.MAX_VALUE
        val (bx, by) = effectiveBias(key, posture)
        val (mx, my) = if (useMotion) motionShift(key, from) else 0.0 to 0.0
        val dx = point.first - (c + bx + mx)
        val dy = point.second - (r + by + my)
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
        val from = touch.from
        val base = cost(point, touch.char, from, posture = touch.posture)
        val keys = LinkedHashSet(gridNeighbors(touch.char))
        // 被改判過的鍵，原本按到的鍵一定要列為候選
        if (touch.rawChar != touch.char && POS.containsKey(touch.rawChar)) keys.add(touch.rawChar)
        return keys.map { k -> k to (cost(point, k, from, posture = touch.posture) - base).coerceIn(MIN_PENALTY, MAX_PENALTY) }
    }

    // ---------- 按下當下：判定這一鍵 ----------

    /**
     * 依落點、個人化偏移與音節合法性決定這一鍵實際算哪個鍵
     * - 不會把一般按鍵改判成聲調鍵（聲調會直接結束音節，改錯代價太高）；聲調鍵可改判回一般鍵
     * - 回傳的 TouchPoint 保留原始按鍵與落點（rawChar / offset），解碼時仍能回頭考慮原本的鍵；
     *   連續打字時也記下上一下的落點（from），解碼與學習時用來算移動造成的偏差
     */
    fun resolveTap(
        rawChar: Char,
        offsetX: Float,
        offsetY: Float,
        history: List<BopomofoComposer.TouchPoint>,
        /** 上一下的落點（大千鍵盤座標）；剛開始打、停頓太久時是 null */
        from: Pair<Double, Double>? = null,
        /** false：不看移動造成的偏差（只用來跟有的版本比判對率） */
        useMotion: Boolean = true,
        time: Long = 0L,
        /** 目前判斷的握法（PostureTracker） */
        posture: Int = 0
    ): BopomofoComposer.TouchPoint {
        val raw = BopomofoComposer.TouchPoint(
            rawChar, offsetX, offsetY,
            fromX = from?.first?.toFloat() ?: Float.NaN, fromY = from?.second?.toFloat() ?: Float.NaN,
            time = time, posture = posture
        )
        if (!POS.containsKey(rawChar)) return raw
        val point = physicalPoint(raw) ?: return raw

        val options = listOf(rawChar) + gridNeighbors(rawChar).filter { !isTone(it) }
        val priors = priorPenalties(history, options)
        fun score(k: Char) = cost(point, k, from, useMotion, posture) + (priors[k] ?: 0.0)

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

    /**
     * 記錄一筆「想按 intended、實際落在 touch」的樣本。
     * 連續打字（有上一下的落點）時拆成兩部分學：扣掉移動偏差的部分學進這個鍵的習慣偏移，扣掉這個鍵習慣偏移的部分學進移動偏差
     */
    fun learn(intended: Char, touch: BopomofoComposer.TouchPoint) {
        val (r, c) = POS[intended] ?: return
        val point = physicalPoint(touch) ?: return
        val dx = point.first - c
        val dy = point.second - r
        // 離目標超過一個半鍵距多半是解析錯位而非手滑，不納入
        if (abs(dx) > 1.5 || abs(dy) > 1.5) return
        val from = touch.from
        val ph = postureHabits.getOrNull(touch.posture - 1)
        if (from == null) {
            habits.add(intended, dx, dy)
            ph?.add(intended, dx, dy)
            return
        }
        val (mx, my) = motion.shift(c - from.first, r - from.second)
        val (bx, by) = effectiveBias(intended, touch.posture)
        habits.add(intended, dx - mx, dy - my)
        ph?.add(intended, dx - mx, dy - my)
        motion.add(c - from.first, r - from.second, dx - bx, dy - by)
    }

    fun sampleCount(): Int = habits.sampleCount()

    /** 設定頁的落點分佈圖：posture 0 是不分握法的，TWO_THUMBS、ONE_HAND 是那個握法自己學到的 */
    fun keySpreads(posture: Int = 0): Map<Char, KeyHabits.KeySpread> =
        (postureHabits.getOrNull(posture - 1) ?: habits).keySpreads()

    /** 不分握法的習慣，接著是各握法的（以 || 隔開；舊版只有第一段） */
    fun serialize(): String = (listOf(habits) + postureHabits).joinToString("||") { it.serialize() }

    fun restore(data: String?) {
        val parts = data?.split("||").orEmpty()
        habits.restore(parts.getOrNull(0))
        for ((i, h) in postureHabits.withIndex()) h.restore(parts.getOrNull(i + 1))
    }

    /** 各握法學了幾筆（設定頁用） */
    fun postureSampleCounts(): List<Int> = postureHabits.map { it.sampleCount() }
}
