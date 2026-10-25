package com.p3dro.scorpio.ime.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.util.LruCache
import com.p3dro.scorpio.ime.engine.scd.BigramTable
import com.p3dro.scorpio.ime.engine.scd.CangjieTable
import com.p3dro.scorpio.ime.engine.scd.DictFiles
import com.p3dro.scorpio.ime.engine.scd.EmojiTable
import com.p3dro.scorpio.ime.engine.scd.EnglishTable
import com.p3dro.scorpio.ime.engine.scd.KaomojiTable
import com.p3dro.scorpio.ime.engine.scd.Like
import com.p3dro.scorpio.ime.engine.scd.ScdFile
import com.p3dro.scorpio.ime.engine.scd.ScdWriter
import com.p3dro.scorpio.ime.engine.scd.WordStore
import com.p3dro.scorpio.ime.engine.scd.WordTable
import com.p3dro.scorpio.ime.engine.scd.WordHit
import com.p3dro.scorpio.ime.engine.scd.compareUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 智慧選字、Emoji 聯想、英文自動補全與顏文字核心管理器
 * 支援模糊音／易混淆按錯鍵容錯、免聲調盲打與 Viterbi 最佳路徑解算
 *
 * 資料來源：
 * - 詞庫（主詞庫、附加詞庫、接續詞、英文、倉頡、Emoji、顏文字）：唯讀的 SCD 二進位檔，mmap 直接查（見 engine/scd）
 * - 匯入的詞庫：原文存在 scorpio_imports.db，開啟時在手機上轉成 SCD
 * - 個人學習（選字、接續搭配、英文、慣性打錯）：存在 scorpio_user.db（SQLite），查詢走記憶體鏡像 [UserStore]
 */
class ScorpioDictManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ScorpioDictManager"
        private const val USER_DB_NAME = "scorpio_user.db"
        // 舊版（SQLite 詞庫）的檔案：第一次啟動時把個人學習搬到 scorpio_user.db，之後刪掉
        private const val OLD_DICT_DB_NAME = "scorpio_dict.db"
        private const val OLD_BIGRAM_DB_NAME = "scorpio_bigram.db"
        private const val PREF_USER_DICT_CLEANUP = "user_dict_cleanup_v1"

        // 整句選字「打對的注音優先」：改注音懲罰（未乘權重）合計至少這麼多，才算靠修正拼出來的整句；
        // 這種整句要比照打的整句高出 LITERAL_MARGIN 分才排第一（落點壓在兩鍵交界時鄰鍵懲罰 < 0.4，不受影響）。
        // 用打字記錄 840 次選字重播比：1.5 → 76.8%、2.5 → 77.4%、3.5 → 77.7%、5 → 77.5%、8 → 77.4%（第一候選命中）
        private const val STRONG_CORRECTION = 0.4
        private const val LITERAL_MARGIN = 3.5
        // 句尾只打一個聲母時，「前面那句」放在第幾個候選（0 起算），見 getCandidateGroups
        private const val TRAIL_HEAD_POSITION = 1
        // 慣性打錯至少選過這麼多次（而且反方向很少）才算穩定的個人習慣，見 isTypoHabit
        private const val TYPO_HABIT_MIN = 5
        // 按歪了的鄰鍵候選：鄰鍵懲罰在這以下才列（按在鍵中央時鄰鍵約 0.65～1.3（上下的鄰鍵比較便宜，個人習慣也會讓某個方向便宜一點），偏向某鄰鍵三、四成鍵寬就會低於這個）、最多幾個詞
        private const val NEIGHBOR_ALT_MAX = 0.6
        // 整句網格（全部選字的共同打分，log10）：
        // 打滿整個輸入的那一段列幾個詞；個人化比例的混合強度（見 spanLookup、contextBonus）；聲調打錯扣分；
        // 字典沒有這個讀音、只有自己學過的詞時，當作這個讀音的字頻總和
        private const val FULL_SPAN_WORDS = 8
        private const val MU_READING = 2.0
        private const val MU_CONTEXT = 2.0
        private const val TONE_MISMATCH = 6.0
        private const val USER_READING_MASS = 1e-4
        // 鄰鍵修正幾種讀法的整句放進統一分數；排在最前面的統一分數最多比第一名低多少（再低的照原本的順序排在後面）
        private const val REPAIRS_POOLED = 4
        private const val POOL_MARGIN = 5.0
        // 簡拼（只打聲母）查到的詞比打完整讀音的少多少分（log10）
        private const val ABBREV_COST = 1.5
        // 注音組合依懲罰排不進前幾名、但拼得成詞的另外留幾個：懲罰上限（約兩個鄰鍵／慣性打錯）、最多幾個
        private const val WORD_KEEP_MAX_PENALTY = 2.0
        private const val WORD_KEEP_EXTRA = 8
        private const val NEIGHBOR_ALT_WORDS = 4
        private const val PREF_USER_DICT_CANONICAL = "user_dict_canonical_v1"
        private const val PREF_SPATIAL_BIAS = "spatial_bias_v1"
        // v2：判對統計改成比「有／沒有移動偏差」（v1 比的是散佈，已拿掉）
        private const val PREF_TAP_STATS = "tap_stats_v2"
        private const val PREF_FUZZY = "fuzzy_sounds_v1"
        private const val PREF_MOTION = "motion_bias_v1"
        private const val PREF_QWERTY_BIAS = "qwerty_bias_v1"
        private const val PREF_QWERTY_STATS = "qwerty_tap_stats_v1"
        // 其他鍵位的 QWERTY 面板版面：「qwerty_bias_v1:<geometryId>」存習慣、「qwerty_centers_v1:<geometryId>」存鍵位（版面改版時搬習慣用），
        // 「layout_geometry_v1:<輸入法 id>」記這個輸入法上次用的是哪一組
        private const val PREF_QWERTY_CENTERS = "qwerty_centers_v1"
        private const val PREF_LAYOUT_GEOMETRY = "layout_geometry_v1"

        // 英文學習詞（key 為小寫，word 保留最近一次的大小寫，例如 iPhone）
        // 個人慣性打錯：打了 typed 音節、實際選的是 intended 讀音的字（例如 ㄉㄨㄠ → ㄉㄧㄠ）
        private const val USER_TYPO_DDL = "CREATE TABLE IF NOT EXISTS user_typo (typed TEXT NOT NULL, intended TEXT NOT NULL, count INTEGER NOT NULL DEFAULT 1, last_used INTEGER NOT NULL, PRIMARY KEY(typed, intended))"
        private const val USER_TRIGRAM_DDL = "CREATE TABLE IF NOT EXISTS user_trigram (prev2 TEXT NOT NULL, prev1 TEXT NOT NULL, next_word TEXT NOT NULL, count INTEGER NOT NULL DEFAULT 1, last_used INTEGER NOT NULL, PRIMARY KEY(prev2, prev1, next_word))"
        private const val USER_ENGLISH_DDL = "CREATE TABLE IF NOT EXISTS user_english (key TEXT PRIMARY KEY, word TEXT NOT NULL, count INTEGER NOT NULL DEFAULT 1, last_used INTEGER NOT NULL)"


        @Volatile
        private var instance: ScorpioDictManager? = null

        fun getInstance(context: Context): ScorpioDictManager {
            return instance ?: synchronized(this) {
                instance ?: ScorpioDictManager(context.applicationContext).also { instance = it }
            }
        }

        /** 去掉聲調符號（每按一鍵會呼叫上百次，不用正規表達式：原本每次都重新編譯，佔打字時 CPU 約 5%） */
        fun toToneless(s: String): String {
            var i = 0
            while (i < s.length && "ˊˇˋ˙ˉ".indexOf(s[i]) < 0) i++
            if (i == s.length) return s
            val sb = StringBuilder(s.length)
            sb.append(s, 0, i)
            for (j in i until s.length) if ("ˊˇˋ˙ˉ".indexOf(s[j]) < 0) sb.append(s[j])
            return sb.toString()
        }

        private const val TONE_CHARS = "ˊˇˋ˙ˉ"
        private const val INITIAL_CHARS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ"

        /** 使用者實際輸入音節上的聲調（無則 null，代表萬用） */
        fun typedToneOf(syl: String): Char? = syl.lastOrNull()?.takeIf { TONE_CHARS.indexOf(it) >= 0 }

        /** 字典讀音音節的聲調（無符號即一聲 ˉ） */
        fun dictToneOf(syl: String): Char = syl.lastOrNull()?.takeIf { TONE_CHARS.indexOf(it) >= 0 } ?: 'ˉ'

        /** 使用者已明確輸入聲調的音節，與字典讀音聲調不符的數量 */
        fun toneMismatchCount(typed: List<String>, reading: String): Int {
            val rs = reading.split("-")
            if (rs.size != typed.size) return 0
            var n = 0
            for (i in typed.indices) {
                val t = typedToneOf(typed[i]) ?: continue
                if (t != dictToneOf(rs[i])) n++
            }
            return n
        }

        /** 學習紀錄讀音與使用者輸入的聲調衝突數（兩邊都有明確聲調且不同才算；學習時未打聲調的音節視為萬用） */
        fun toneConflictCount(typed: List<String>, learnedReading: String): Int {
            val ls = learnedReading.split("-")
            if (ls.size != typed.size) return 0
            var n = 0
            for (i in typed.indices) {
                val t = typedToneOf(typed[i]) ?: continue
                val l = typedToneOf(ls[i]) ?: continue
                if (t != l) n++
            }
            return n
        }

        /** 全為漢字（含擴充區）的詞 */
        fun isHanWord(w: String): Boolean {
            if (w.isEmpty()) return false
            var i = 0
            while (i < w.length) {
                val cp = w.codePointAt(i)
                if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return false
                i += Character.charCount(cp)
            }
            return true
        }

        fun hanLength(w: String): Int = w.codePointCount(0, w.length)

        /** 讀音的有效音節數（忽略只剩聲調或空白的音節） */
        fun syllableCount(reading: String): Int =
            reading.split("-").count { toToneless(it).isNotEmpty() }

        /** 可學習的選字：漢字詞且字數與讀音音節數一致（避免「ㄖㄤ → 然後我想要」這類錯位讀音污染學習庫） */
        fun isLearnable(reading: String, word: String): Boolean {
            if (!isHanWord(word)) return false
            val syls = reading.split("-")
            if (syls.any { toToneless(it).isEmpty() }) return false
            return syls.size == hanLength(word)
        }

        /** 學習加分（學過的長詞補全、簡拼）：疊加在字典分數上；count 是淡出後的次數 */
        fun learnBonus(count: Double): Double = minOf(3.0 + count * 1.5, 12.0)

        /** 學習的證據隨時間淡出：大約一個月沒用，次數剩一半 */
        const val LEARN_HALF_LIFE_DAYS = 30.0

        /**
         * user_dict、user_bigram 的 count：第一次學就是 2（新增時預設 1，接著又加 1），所以實際選過的次數是 count − 1
         * （user_trigram 從 0 開始加，count 就是次數）
         */
        fun timesChosen(count: Int) = (count - 1).coerceAtLeast(0)

        fun decayedCount(count: Int, lastUsed: Long, now: Long): Double {
            val days = (now - lastUsed).coerceAtLeast(0L) / 86_400_000.0
            return count * Math.pow(0.5, days / LEARN_HALF_LIFE_DAYS)
        }

        /** 多音節輸入時，只蓋到前幾個音節的詞加完上文分數的上限：低於整句（115）與整句備選（108、107） */
        const val PARTIAL_CAP = 106.0

    

        fun getInitialsFromReading(reading: String): String {
            if (reading.isBlank()) return ""
            return reading.split("-").mapNotNull { syl ->
                syl.firstOrNull()?.toString()
            }.joinToString("-")
        }

        // 大千注音 10x4 實體按鍵二維空間矩陣（用於 (X, Y) 觸控座標高斯分佈鄰鍵解算）
        val BOPOMOFO_GRID = SpatialModel.GRID

        // 英文按下當下判定：落點高斯（QwertyModel）與字母先驗的權重、上限（同注音 SpatialModel）
        private const val EN_PRIOR_WEIGHT = 0.12
        private const val EN_PRIOR_CAP = 0.35

        // 大千注音鍵盤實體相鄰按鍵對應表（針對手機觸控打錯字容錯）
        val NEAR_KEYS = mapOf(
            'ㄅ' to listOf('ㄉ', 'ㄆ'),
            'ㄉ' to listOf('ㄅ', 'ˇ', 'ㄊ'),
            'ㄓ' to listOf('ˋ', 'ˊ', 'ㄔ'),
            'ㄚ' to listOf('˙', 'ㄞ', 'ㄛ'),
            'ㄞ' to listOf('ㄚ', 'ㄢ', 'ㄟ'),
            'ㄢ' to listOf('ㄞ', 'ㄣ'),
            'ㄆ' to listOf('ㄅ', 'ㄊ', 'ㄇ'),
            'ㄊ' to listOf('ㄉ', 'ㄆ', 'ㄍ', 'ㄋ'),
            'ㄍ' to listOf('ㄊ', 'ㄐ', 'ㄎ'),
            'ㄐ' to listOf('ㄍ', 'ㄔ', 'ㄑ'),
            'ㄔ' to listOf('ㄓ', 'ㄐ', 'ㄗ', 'ㄕ'),
            'ㄗ' to listOf('ㄔ', 'ㄧ', 'ㄘ'),
            'ㄧ' to listOf('ㄗ', 'ㄛ', 'ㄨ'),
            'ㄛ' to listOf('ㄚ', 'ㄧ', 'ㄟ', 'ㄜ'),
            'ㄟ' to listOf('ㄞ', 'ㄛ', 'ㄣ', 'ㄠ'),
            'ㄣ' to listOf('ㄢ', 'ㄟ', 'ㄤ'),
            'ㄇ' to listOf('ㄆ', 'ㄋ', 'ㄈ'),
            'ㄋ' to listOf('ㄊ', 'ㄇ', 'ㄎ', 'ㄌ'),
            'ㄎ' to listOf('ㄍ', 'ㄋ', 'ㄑ', 'ㄏ'),
            'ㄑ' to listOf('ㄐ', 'ㄎ', 'ㄕ', 'ㄒ'),
            'ㄕ' to listOf('ㄔ', 'ㄑ', 'ㄘ', 'ㄖ'),
            'ㄘ' to listOf('ㄗ', 'ㄕ', 'ㄨ', 'ㄙ'),
            'ㄨ' to listOf('ㄧ', 'ㄘ', 'ㄜ', 'ㄩ'),
            'ㄜ' to listOf('ㄛ', 'ㄨ', 'ㄠ', 'ㄝ'),
            'ㄠ' to listOf('ㄟ', 'ㄜ', 'ㄤ', 'ㄡ'),
            'ㄤ' to listOf('ㄣ', 'ㄠ', 'ㄥ'),
            'ㄈ' to listOf('ㄇ', 'ㄌ'),
            'ㄌ' to listOf('ㄋ', 'ㄈ', 'ㄏ'),
            'ㄏ' to listOf('ㄎ', 'ㄌ', 'ㄒ'),
            'ㄒ' to listOf('ㄑ', 'ㄏ', 'ㄖ'),
            'ㄖ' to listOf('ㄕ', 'ㄒ', 'ㄙ'),
            'ㄙ' to listOf('ㄘ', 'ㄖ', 'ㄩ'),
            'ㄩ' to listOf('ㄨ', 'ㄙ', 'ㄝ'),
            'ㄝ' to listOf('ㄜ', 'ㄩ', 'ㄡ'),
            'ㄡ' to listOf('ㄠ', 'ㄝ', 'ㄥ'),
            'ㄥ' to listOf('ㄤ', 'ㄡ')
        )
    }

    /** 個人學習資料庫（scorpio_user.db） */
    private var db: SQLiteDatabase? = null

    /** 個人學習的記憶體鏡像：每按一鍵的查詢走這裡 */
    @Volatile
    private var user = UserStore()

    // 計算結果的快取（詞庫查詢本身直接讀 mmap，不必快取）
    private val candidatesCache = LruCache<String, CandidateGroups>(300)
    // 網格每段讀音的候選與個人化分數（還沒扣聲調，見 spanLookup）；學習資料一變就清掉（同 candidatesCache）
    private val spanCache = LruCache<String, List<SpanHit>>(2000)
    /** 片段 → 拆成的詞庫詞（不是片段存空的），見 chunkParts */
    private val chunkCache = LruCache<String, List<String>>(4000)

    // ---------- 詞庫檔（SCD） ----------

    private val files = DictFiles(context)

    /** 生效中的檔（id → 檔）；資料包安裝、移除時重新載入 */
    @Volatile
    private var loaded: Map<String, ScdFile> = emptyMap()

    /** 依開關組好的查詢對象；開關、安裝、匯入變動時整份換掉（查詢中的執行緒繼續用舊的那份） */
    private class Data(
        /** 主詞庫＋開著的附加詞庫＋開著的匯入詞庫（等於舊版的 dict 表） */
        val words: WordStore,
        val core: WordTable?,
        /** 選字加分用的接續詞：開著的接續詞資料包與流行用語 */
        val scoreBigrams: List<BigramTable>,
        /** 接續詞預測用：所有裝著的接續詞（id, 表），同舊版整個 bigram 表 */
        val allBigrams: List<Pair<String, BigramTable>>,
        val english: EnglishTable?,
        val cangjie: CangjieTable?,
        val emoji: EmojiTable?,
        val kaomoji: KaomojiTable?
    )

    @Volatile
    private var data = Data(WordStore(emptyList()), null, emptyList(), emptyList(), null, null, null, null)

    private val words: WordStore get() = data.words

    /**
     * 候選結果分組：inline 為候選列預設排法（文字中穿插 emoji／顏文字）；
     * words／emoji／kaomoji 供「emoji 與顏文字只放展開面板」模式分頁顯示
     */
    data class CandidateGroups(
        val inline: List<String>,
        val words: List<String>,
        val emoji: List<String>,
        val kaomoji: List<String>
    ) {
        companion object {
            val EMPTY = CandidateGroups(emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    /** 注音鍵盤空間模型（高斯落點機率、個人化偏移、逐鍵音節合法性） */
    val spatial = SpatialModel()
    // 每個人自己的讀音混用比例（前後鼻音、平捲舌…），見 FuzzySounds
    val fuzzy = FuzzySounds { ImeConfig.get(context).fuzzyPriors }
    private var fuzzyDirty = 0
    private val spatialPrefs = context.getSharedPreferences("scorpio_spatial", Context.MODE_PRIVATE)
    private var spatialDirty = 0

    /**
     * QWERTY 面板的空間模型（英文、26 鍵注音、拼音、倉頡、速成）：依版面的鍵位分組（KeyboardLayout.geometryId），
     * 每組一份習慣；鍵位跟內建 QWERTY 一樣的都用原本那一份（qwerty_bias_v1）。目前畫在面板上的版面用哪一組，這裡就是哪一組
     */
    @Volatile
    var qwerty = QwertyModel()
        private set
    private val qwertyGroups = HashMap<String, QwertyModel>()
    private var qwertyDirty = 0

    // 以下在 init 之前宣告：init 開的背景執行緒會用到
    private val sourcePrefs = context.getSharedPreferences("scorpio_dict_sources", Context.MODE_PRIVATE)

    @Volatile
    var emojiEnabled = sourcePrefs.getBoolean("emoji", true)
        private set

    @Volatile
    var kaomojiEnabled = sourcePrefs.getBoolean("kaomoji", true)
        private set

    /** 詞庫資訊（名稱、來源、授權、版本），來自各 SCD 檔的 meta（資料 repo 的 packs.json） */
    data class Pack(
        val id: String,
        /** words＝增加打得出的詞；bigram＝只影響詞與詞的接續；kaomoji＝顏文字 */
        val group: String,
        val name: String,
        val description: String,
        val source: String,
        val license: String,
        val url: String,
        val version: String
    )

    /** 裝著的資料包（主詞庫、附加詞庫、接續詞、顏文字），依 packs.json 的順序 */
    fun packs(): List<Pack> = packFiles().map { f ->
        val m = f.meta
        Pack(f.id, f.group, m.optString("name", f.id), m.optString("description"), m.optString("source"),
            m.optString("license"), m.optString("url"), f.version)
    }

    private fun packFiles(): List<ScdFile> = loaded.values
        .filter { it.group == "words" || it.group == "bigram" || it.group == "kaomoji" }
        .sortedWith(compareBy({ it.meta.optInt("sort", 99) }, { it.id }))

    /** 可開關的附加詞庫（詞會併進主詞庫） */
    private fun addonPacks() = packs().filter { it.group == "words" && it.id != "core" }

    /**
     * 依開關組出查詢對象：主詞庫在前，再來是開著的附加詞庫（依 packs.json 順序）、開著的匯入詞庫（依匯入先後），
     * 同分時的先後跟舊版依序插進 dict 表的順序一樣
     */
    private fun buildData(): Data {
        val files = loaded
        val core = files["core"]?.takeIf { it.has("w.word") }?.let { WordTable(it) }
        val tables = ArrayList<WordTable>()
        core?.let { tables.add(it) }
        for (f in packFiles()) {
            if (f.id != "core" && f.group == "words" && f.has("w.word") && addonEnabled(f.id)) tables.add(WordTable(f))
        }
        for ((id, enabled) in importList()) {
            if (enabled) files["import:$id"]?.let { tables.add(WordTable(it)) }
        }
        val bigramFiles = packFiles().filter { it.has("b.prev") }
        // 流行用語同時帶詞和搭配，跟著同一個開關
        val scoring = bigramFiles.filter { (it.group == "bigram" || it.id == "trendy") && addonEnabled(it.id) }.map { BigramTable(it) }
        return Data(
            WordStore(tables), core, scoring, bigramFiles.map { it.id to BigramTable(it) },
            files["en"]?.let { EnglishTable(it) }, files["cangjie"]?.let { CangjieTable(it) },
            files["emoji"]?.let { EmojiTable(it) }, files["kaomoji"]?.let { KaomojiTable(it) }
        )
    }

    /** 重新載入詞庫檔（reload＝檔案有增減）並依開關重組；清掉跟詞庫有關的快取 */
    @Synchronized
    private fun remount(reload: Boolean, updateSpatial: Boolean = true) {
        // 匯入詞庫先編好檔，再一起載入
        val compiled = compileMissingImports()
        if (reload || compiled || loaded.isEmpty()) loaded = files.load()
        data = buildData()
        bigramCache.evictAll()
        invalidateCandidateCaches()
        validSyllableSet = null
        if (updateSpatial) spatial.setSyllableFrequencies(syllableFrequencies())
    }

    /** 匯入的詞庫存在獨立的檔案，主詞庫改版覆蓋時不會不見 */
    private val importDb: SQLiteDatabase? by lazy {
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("scorpio_imports.db"), null).apply {
                execSQL("CREATE TABLE IF NOT EXISTS import_dicts (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, created INTEGER NOT NULL)")
                execSQL("CREATE TABLE IF NOT EXISTS import_entries (dict_id INTEGER NOT NULL, word TEXT NOT NULL, reading TEXT NOT NULL, score REAL NOT NULL)")
                execSQL("CREATE INDEX IF NOT EXISTS idx_import_dict ON import_entries(dict_id)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "開啟匯入詞庫失敗", e)
            null
        }
    }


    /**
     * 開個人學習資料庫（scorpio_user.db）。從舊版（SQLite 詞庫）升級上來時，把學習紀錄從舊的 scorpio_dict.db 搬過來，
     * 再刪掉舊的詞庫檔（scorpio_dict.db 約 48 MB、scorpio_bigram.db 約 13 MB）與舊格式的下載資料包
     */
    private fun initUserDatabase() {
        try {
            val userFile = context.getDatabasePath(USER_DB_NAME)
            userFile.parentFile?.mkdirs()
            val fresh = !userFile.exists()
            val database = SQLiteDatabase.openOrCreateDatabase(userFile, null)
            try {
                database.enableWriteAheadLogging()
                database.execSQL("PRAGMA synchronous = NORMAL")
            } catch (e: Exception) {
                // Ignore
            }
            createUserTables(database)
            db = database

            val oldFile = context.getDatabasePath(OLD_DICT_DB_NAME)
            if (fresh && oldFile.exists()) migrateFromOldDictDb(database, oldFile)
            if (oldFile.exists() || context.getDatabasePath(OLD_BIGRAM_DB_NAME).exists()) deleteOldDictFiles()

            loadTypoMemory()
            val prefs = context.getSharedPreferences("scorpio_dict_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_USER_DICT_CLEANUP, false)) {
                purgeUnlearnableUserWords()
                prefs.edit().putBoolean(PREF_USER_DICT_CLEANUP, true).apply()
            }
            if (!prefs.getBoolean(PREF_USER_DICT_CANONICAL, false)) {
                canonicalizeUserWords()
                prefs.edit().putBoolean(PREF_USER_DICT_CANONICAL, true).apply()
            }
            user.load(database)
            Log.i(TAG, "個人學習資料庫就緒")
        } catch (e: Exception) {
            Log.e(TAG, "個人學習資料庫初始化失敗", e)
        }
    }

    private fun createUserTables(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_dict (
                reading TEXT NOT NULL,
                toneless TEXT NOT NULL DEFAULT '',
                initials TEXT NOT NULL DEFAULT '',
                word TEXT NOT NULL,
                count INTEGER NOT NULL DEFAULT 1,
                last_used INTEGER NOT NULL,
                PRIMARY KEY(reading, word)
            )
            """.trimIndent()
        )
        // 寫入時依詞讀回記憶體鏡像、userOnlyWords 依詞查
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_user_dict_word ON user_dict(word)")
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_bigram (
                prev_word TEXT NOT NULL,
                next_word TEXT NOT NULL,
                count INTEGER NOT NULL DEFAULT 1,
                last_used INTEGER NOT NULL,
                PRIMARY KEY(prev_word, next_word)
            )
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_user_bigram_prev ON user_bigram(prev_word)")
        database.execSQL(USER_ENGLISH_DDL)
        database.execSQL(USER_TYPO_DDL)
        database.execSQL(USER_TRIGRAM_DDL)
    }

    /** 舊版的學習紀錄（在 scorpio_dict.db 裡）依原本的順序搬到 scorpio_user.db */
    private fun migrateFromOldDictDb(database: SQLiteDatabase, oldFile: File) {
        try {
            database.execSQL("ATTACH DATABASE ? AS old", arrayOf(oldFile.absolutePath))
            try {
                val copies = listOf(
                    "INSERT OR IGNORE INTO user_dict (reading, toneless, initials, word, count, last_used) SELECT reading, toneless, initials, word, count, last_used FROM old.user_dict ORDER BY rowid",
                    "INSERT OR IGNORE INTO user_bigram (prev_word, next_word, count, last_used) SELECT prev_word, next_word, count, last_used FROM old.user_bigram ORDER BY rowid",
                    "INSERT OR IGNORE INTO user_english (key, word, count, last_used) SELECT key, word, count, last_used FROM old.user_english ORDER BY rowid",
                    "INSERT OR IGNORE INTO user_typo (typed, intended, count, last_used) SELECT typed, intended, count, last_used FROM old.user_typo ORDER BY rowid"
                )
                database.beginTransaction()
                try {
                    for (sql in copies) {
                        try {
                            database.execSQL(sql)
                        } catch (e: Exception) {
                            // 很舊的版本可能還沒有這張表
                        }
                    }
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            } finally {
                database.execSQL("DETACH DATABASE old")
            }
            // 舊版下載的資料包是 SQLite 格式，用不到了；開著的會由線上更新重新下載新格式
            context.getSharedPreferences(PackUpdater.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PackUpdater.PREF_REFETCH, true).apply()
            Log.i(TAG, "已把學習紀錄搬到新的個人資料庫")
        } catch (e: Exception) {
            Log.e(TAG, "搬移學習紀錄失敗", e)
        }
    }

    private fun deleteOldDictFiles() {
        for (name in listOf(OLD_DICT_DB_NAME, OLD_BIGRAM_DB_NAME)) {
            val f = context.getDatabasePath(name)
            SQLiteDatabase.deleteDatabase(f)
            for (suffix in listOf("-wal", "-shm", "-journal")) File(f.path + suffix).delete()
        }
        File(context.filesDir, "packs").deleteRecursively()
        val e = sourcePrefs.edit()
        for (k in sourcePrefs.all.keys) if (k.startsWith("ver:") || k.startsWith("file:") || k.startsWith("filegrp:")) e.remove(k)
        e.apply()
        context.getSharedPreferences("scorpio_dict_prefs", Context.MODE_PRIVATE).edit()
            .remove("scorpio_dict_db_version").remove("scorpio_bigram_db_version").apply()
    }

    /** 一次性清除舊版寫入的錯位學習紀錄（例如「ㄖㄤ → 然後我想要」、「ㄐㄧㄢˋ → 鍵盤」、顏文字） */
    private fun purgeUnlearnableUserWords() {
        val database = db ?: return
        try {
            val bad = mutableListOf<Pair<String, String>>()
            database.rawQuery("SELECT reading, word FROM user_dict", null).use { cur ->
                while (cur.moveToNext()) {
                    val r = cur.getString(0)
                    val w = cur.getString(1)
                    if (!isLearnable(r, w)) bad.add(r to w)
                }
            }
            if (bad.isEmpty()) return
            database.beginTransaction()
            try {
                for ((r, w) in bad) database.delete("user_dict", "reading = ? AND word = ?", arrayOf(r, w))
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            Log.i(TAG, "已清除 ${bad.size} 筆錯位學習紀錄")
        } catch (e: Exception) {
            Log.e(TAG, "清除錯位學習紀錄失敗", e)
        }
    }

    /**
     * 校正學習讀音：鄰鍵猜字／模糊音選出的字，要記在它真正的讀音底下，不能記在按錯的讀音
     * 例如打 ㄕㄨ-ㄅㄨ 選了「速度」→ 記成 ㄙㄨˋ-ㄉㄨˋ，否則之後打 ㄗㄨˇ-ㄅㄨ（組不）會經鄰鍵連到「速度」
     * 打得對的音節（含簡拼）保留原樣；字典查不到的字（自造字）也保留原樣
     */
    private fun canonicalReading(reading: String, word: String): String {
        val syls = reading.split("-")
        val chars = ArrayList<String>()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            chars.add(String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        if (chars.size != syls.size) return reading
        val out = ArrayList<String>(syls.size)
        for (k in syls.indices) {
            val typed = syls[k]
            val tl = toToneless(typed)
            val readings = words.byWord(chars[k], 8).map { it.reading }
            // 打的是正確讀音或其開頭（聲母簡拼 ㄐ、懶人音 ㄨ→我）都算打對
            val compatible = readings.isEmpty() || readings.any { r -> toToneless(r).startsWith(tl) }
            out.add(if (compatible) typed else readings.minByOrNull { editDistance(toToneless(it), tl) } ?: typed)
        }
        return out.joinToString("-")
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    // 個人慣性打錯記憶（typed 無聲調音節 → intended → 次數），量小，常駐記憶體
    private val typoMemory = HashMap<String, HashMap<String, Int>>()

    private fun loadTypoMemory() {
        synchronized(typoMemory) {
            typoMemory.clear()
            try {
                db?.rawQuery("SELECT typed, intended, count FROM user_typo", null)?.use { c ->
                    while (c.moveToNext()) typoMemory.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = c.getInt(2)
                }
            } catch (e: Exception) {
                Log.w(TAG, "載入慣性打錯記憶失敗: ${e.message}")
            }
        }
    }

    private fun typoFixes(tonelessTyped: String): Map<String, Int> =
        synchronized(typoMemory) { typoMemory[tonelessTyped]?.toMap() } ?: emptyMap()

    /** 慣性打錯的懲罰：選過越多次越便宜；打出來的音節本身合法時保守一點，避免蓋掉本來就想打的字 */
    private fun typoPenalty(count: Int, typedIsValid: Boolean): Double =
        if (typedIsValid) maxOf(0.5, 1.2 - 0.3 * count) else maxOf(0.15, 1.0 - 0.35 * count)

    /**
     * 穩定的慣性打錯：打 typed 想打 intended 至少 TYPO_HABIT_MIN 次，反方向不到三分之一（ㄒㄧㄣ 想打 ㄒㄧㄢ）。
     * 這是固定的個人習慣，修正它不算「改了你打的注音」，不受打對優先（LITERAL_MARGIN）限制；
     * 兩個方向都常發生的（ㄧㄠ／ㄧㄡ）是隨手按錯，照打的優先
     */
    private fun isTypoHabit(typed: String, intended: String, count: Int): Boolean {
        if (count < TYPO_HABIT_MIN) return false
        val reverse = typoFixes(intended)[typed] ?: 0
        return reverse * 3 < count
    }

    private fun recordTypo(typed: String, intended: String) {
        val database = db ?: return
        try {
            val now = System.currentTimeMillis()
            database.execSQL(
                "INSERT OR IGNORE INTO user_typo (typed, intended, count, last_used) VALUES (?, ?, 0, ?)",
                arrayOf(typed, intended, now)
            )
            database.execSQL(
                "UPDATE user_typo SET count = count + 1, last_used = ? WHERE typed = ? AND intended = ?",
                arrayOf(now, typed, intended)
            )
            synchronized(typoMemory) {
                val m = typoMemory.getOrPut(typed) { HashMap() }
                m[intended] = (m[intended] ?: 0) + 1
            }
            candidatesCache.evictAll()
            spanCache.evictAll()
            Log.i(TAG, "記住一筆慣性打錯")
        } catch (e: Exception) {
            Log.e(TAG, "更新 user_typo 失敗", e)
        }
    }

    // 詞是否為使用者自建（user_dict 有、系統詞典沒有）的快取；學習或刪除時清空
    private val userOnlyCache = LruCache<String, Boolean>(3000)

    /**
     * 畫面上游標前文字的最後一個詞（選字的上文）：只看結尾連著的漢字，取主詞庫或學過的詞裡最長的結尾（最多 4 字），
     * 都不是詞就取最後一個字（接續詞本來就會退回用最後一個字查）
     */
    fun lastWordOf(text: CharSequence): String? {
        val cps = ArrayList<Int>()
        var i = text.length
        while (i > 0 && cps.size < 4) {
            val cp = Character.codePointBefore(text, i)
            if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) break
            cps.add(0, cp)
            i -= Character.charCount(cp)
        }
        if (cps.isEmpty()) return null
        for (len in cps.size downTo 2) {
            val w = cps.subList(cps.size - len, cps.size).joinToString("") { String(Character.toChars(it)) }
            if (words.hasWord(w) || user.hasWord(w)) return w
        }
        return String(Character.toChars(cps.last()))
    }

    /** 從候選中挑出使用者自建詞（系統詞典沒有、後天選字學到的），供候選列標示小鉛筆、開放長按刪除 */
    suspend fun userOnlyWords(words: Collection<String>): Set<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>()
        for (w in words) {
            if (!isHanWord(w)) continue
            val isUser = userOnlyCache.get(w) ?: (user.hasWord(w) && !this@ScorpioDictManager.words.hasWord(w)).also { userOnlyCache.put(w, it) }
            if (isUser) out.add(w)
        }
        out
    }

    /** 按下注音鍵當下判定實際的鍵（空間模型 + 音節合法性）；previous：連續打字時的上一下（算移動造成的偏差） */
    fun resolveZhuyinTap(
        rawChar: Char,
        offsetX: Float,
        offsetY: Float,
        history: List<BopomofoComposer.TouchPoint>,
        previous: BopomofoComposer.TouchPoint? = null,
        /** 按下的時間（SystemClock.uptimeMillis）：判斷握法用；0 是不知道（不判斷） */
        time: Long = 0L
    ): BopomofoComposer.TouchPoint {
        val p = when {
            time <= 0 -> 0
            postureFromKeyboard -> currentPosture()
            else -> currentPosture(spatial.physicalPoint(BopomofoComposer.TouchPoint(rawChar, offsetX, offsetY))?.let { postureTracker.onTap(it.first, time) } ?: postureTracker.posture)
        }
        return spatial.resolveTap(rawChar, offsetX, offsetY, history, previous?.let { spatial.physicalPoint(it) }, time = time, posture = p)
    }

    /** 判斷握法（雙手拇指／單手）：每按一下鍵更新，大千與 QWERTY 共用 */
    val postureTracker = PostureTracker()

    /** 握法設定（設定頁）：0＝自動判斷，PostureTracker.TWO_THUMBS／ONE_HAND＝一律當成這個握法 */
    @Volatile
    var postureMode = 0

    /** 自動判斷但還看不出來時當成哪個握法：TWO_THUMBS、ONE_HAND，或 0（不分握法） */
    @Volatile
    var postureFallback = PostureTracker.TWO_THUMBS

    /** 落點校正與學習用的握法：照設定，自動判斷時看不出來就用 postureFallback */
    fun currentPosture(detected: Int = postureTracker.posture) = when {
        postureMode != 0 -> postureMode
        detected != PostureTracker.UNKNOWN -> detected
        else -> postureFallback
    }

    /**
     * 鍵盤畫面把每一下（注音、英文、聲調、ㄦ、空白、刪除、符號鍵…）都交給 noteKeyboardTap 判斷握法時為 true：
     * 這時按注音／英文鍵不再另外算一次，只拿目前的判斷
     */
    @Volatile
    var postureFromKeyboard = false

    /** 鍵盤上按下一下：column 是水平位置（以注音鍵的欄為單位，最左邊一欄的中心是 0），time 是 SystemClock.uptimeMillis */
    fun noteKeyboardTap(column: Double, time: Long) {
        postureTracker.onTap(column, time)
    }

    private fun noteQwertyTap(raw: Char, offsetX: Float, offsetY: Float) {
        val q = qwerty
        if (postureFromKeyboard) {
            q.posture = currentPosture()
            return
        }
        val p = q.point(raw, offsetX, offsetY) ?: return
        q.posture = currentPosture(postureTracker.onTap(p.first, android.os.SystemClock.uptimeMillis()))
    }

    /**
     * 個人化偏移學習：確定選字後，比對每個音節的按鍵與該字的正確讀音，
     * 打對的鍵與只錯一個鍵的音節都是「想按某鍵、實際落在某處」的樣本
     */
    suspend fun recordTouchSamples(points: List<BopomofoComposer.TouchPoint>, word: String) = withContext(Dispatchers.IO) {
        val chars = ArrayList<String>()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            chars.add(String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        // 音節數對不上時，看看是不是聲母＋拼不起來的介音被拆開了（ㄏ＋ㄩㄢ 其實是想打 ㄒㄩㄢ）
        val parsed = BopomofoComposer.parseSyllables(points).takeIf { it.size == chars.size }
            ?: BopomofoComposer.parseSyllables(points, keepIncompatible = true)
        if (parsed.isEmpty() || parsed.size != chars.size) return@withContext
        // 每個音節想打的讀音：打對、打了開頭（簡拼），或同長度只差一個注音（按歪）。
        // 有任何一個音節對不上（多打、少打、差兩個以上）就整個詞都不學：音節可能錯開，落點會學到別的鍵上
        val intendedOf = ArrayList<String>(parsed.size)
        for (k in parsed.indices) {
            val typed = toToneless(parsed[k].text)
            val readings = words.tonelessOfWord(chars[k])
            intendedOf.add(when {
                readings.any { it.startsWith(typed) } -> typed
                else -> readings.filter { r -> r.length == typed.length && r.indices.count { r[it] != typed[it] } == 1 }
                    .singleOrNull() ?: return@withContext
            })
        }
        var added = 0
        for (k in parsed.indices) {
            val typed = toToneless(parsed[k].text)
            val intended = intendedOf[k]
            if (intended != typed) recordTypo(typed, intended)
            if (fuzzy.observe(typed, intended)) fuzzyDirty++
            for (j in typed.indices) {
                val touch = parsed[k].touches[typed[j]] ?: continue
                if (SpatialModel.isTone(touch.rawChar)) continue
                // 換掉的那一鍵：落點要真的靠近想打的鍵（同鄰鍵候選的門檻）才算按歪；按在別的鍵中央多半是讀音記錯（ㄓ／ㄗ），
                // 只記慣性打錯，不把落點學到想打的鍵上
                if (intended[j] != typed[j] &&
                    (spatial.neighborPenalties(touch).firstOrNull { it.first == intended[j] }?.second ?: Double.MAX_VALUE) > NEIGHBOR_ALT_MAX
                ) continue
                // 學之前先拿這一下來比：有／沒有移動偏差，按下當下各會判成哪個鍵（只算連續打字的那幾下，只記次數）
                val idx = points.indexOfFirst { it === touch }
                if (idx >= 0 && touch.from != null) {
                    val history = points.subList(0, idx)
                    val withMotion = spatial.resolveTap(touch.rawChar, touch.offsetX, touch.offsetY, history, touch.from).char == intended[j]
                    val without = spatial.resolveTap(touch.rawChar, touch.offsetX, touch.offsetY, history, touch.from, useMotion = false).char == intended[j]
                    tapStats[0]++
                    if (withMotion) tapStats[1]++
                    if (without) tapStats[2]++
                }
                spatial.learn(intended[j], touch)
                added++
            }
            // 聲調鍵：這個字的讀音（想打的那個）有這個聲調，才算確定按對；按到旁邊的聲調而這個讀音只有一種聲調、
            // 落點又真的靠近它，就當成按歪學到那個聲調。免聲調（沒按）或猜不出想按哪個聲調時不學
            val toneTouch = parsed[k].touches.values.firstOrNull { SpatialModel.isTone(it.rawChar) && it.rawChar != BopomofoComposer.FIRST_TONE }
            if (toneTouch != null) {
                val tones = words.readingsOfWord(chars[k]).filter { toToneless(it) == intended }
                    .map { r -> r.lastOrNull()?.takeIf { SpatialModel.isTone(it) } ?: BopomofoComposer.FIRST_TONE }.toSet()
                val want = when {
                    toneTouch.rawChar in tones -> toneTouch.rawChar
                    tones.size == 1 && tones.first() != BopomofoComposer.FIRST_TONE &&
                        (spatial.neighborPenalties(toneTouch).firstOrNull { it.first == tones.first() }?.second ?: Double.MAX_VALUE) <= NEIGHBOR_ALT_MAX -> tones.first()
                    else -> null
                }
                if (want != null) {
                    spatial.learn(want, toneTouch)
                    added++
                }
            }
        }
        if (fuzzyDirty > 0) candidatesCache.evictAll()
        if (fuzzyDirty >= 8) {
            spatialPrefs.edit().putString(PREF_FUZZY, fuzzy.serialize()).apply()
            fuzzyDirty = 0
        }
        if (added > 0) {
            spatialDirty += added
            if (spatialDirty >= 8) {
                spatialPrefs.edit().putString(PREF_SPATIAL_BIAS, spatial.serialize())
                    .putString(PREF_MOTION, spatial.motion.serialize())
                    .putString(PREF_TAP_STATS, tapStats.joinToString(",")).apply()
                spatialDirty = 0
            }
        }
    }

    /**
     * 連續打字時按下當下判對的次數（確定選字時拿真的想按的鍵來對）：[總數, 有移動偏差時判對, 沒有時判對]。
     * 用來看移動偏差的學習有沒有讓按鍵判斷更準；只記次數，不記打了什麼
     */
    private val tapStats: LongArray = spatialPrefs.getString(PREF_TAP_STATS, null)?.split(',')?.mapNotNull { it.toLongOrNull() }
        ?.takeIf { it.size == 3 }?.toLongArray() ?: LongArray(3)

    fun tapAccuracy(): LongArray = tapStats.copyOf()

    /** 刪掉一個鍵後改按別的鍵：被刪的落點當作「想按 intended」的樣本 */
    fun learnRetypeCorrection(intended: Char, wrong: BopomofoComposer.TouchPoint) {
        if (SpatialModel.isTone(intended) || SpatialModel.isTone(wrong.rawChar)) return
        spatial.learn(intended, wrong)
        if (++spatialDirty >= 8) {
            spatialPrefs.edit().putString(PREF_SPATIAL_BIAS, spatial.serialize())
                .putString(PREF_MOTION, spatial.motion.serialize()).apply()
            spatialDirty = 0
        }
    }

    /**
     * QWERTY 落點學習：每一筆是「想按 intended、實際落在 touch」（英文、26 鍵注音、拼音、倉頡、速成共用）。
     * 學之前先拿這一下來比：只看落點時，有／沒有個人化各會判成哪個鍵（只記次數）
     */
    fun recordQwertySamples(samples: List<Pair<Char, BopomofoComposer.TouchPoint>>) {
        var added = 0
        val qwerty = qwerty
        for ((intended, touch) in samples) {
            val key = intended.lowercaseChar()
            if (key !in qwerty.pos) continue
            val withHabits = qwerty.nearest(touch, useHabits = true)
            val without = qwerty.nearest(touch, useHabits = false)
            if (!qwerty.learn(key, touch)) continue
            qwertyStats[0]++
            if (withHabits == key) qwertyStats[1]++
            if (without == key) qwertyStats[2]++
            added++
        }
        if (added > 0) saveQwerty(added)
    }

    /** QWERTY 刪掉一個鍵後改按別的鍵：被刪的落點當作「想按 intended」的樣本 */
    fun learnQwertyRetype(intended: Char, wrong: BopomofoComposer.TouchPoint) {
        if (qwerty.learn(intended, wrong)) saveQwerty(1)
    }

    @Synchronized
    private fun saveQwerty(added: Int) {
        qwertyDirty += added
        if (qwertyDirty < 8) return
        flushQwerty()
    }

    @Synchronized
    private fun flushQwerty() {
        if (qwertyDirty == 0) return
        spatialPrefs.edit().putString(qwertyPrefKey(qwerty.layout.geometryId), qwerty.serialize())
            .putString(PREF_QWERTY_STATS, qwertyStats.joinToString(",")).apply()
        qwertyDirty = 0
    }

    private fun qwertyPrefKey(group: String) =
        if (group == KeyboardLayout.QWERTY_GEOMETRY) PREF_QWERTY_BIAS else "$PREF_QWERTY_BIAS:$group"

    /**
     * QWERTY 面板換了版面（切到有宣告字鍵的輸入法、或回到英文）：落點模型換成那個版面的鍵位與那一組的習慣。
     * 某個輸入法的版面改版、鍵位變了（新的一組還沒有資料），從它上一組的習慣依位置搬過來（KeyHabits.migrateFrom）
     */
    @Synchronized
    fun useQwertyLayout(layout: KeyboardLayout, methodId: String?) {
        val group = layout.geometryId
        val lastKey = methodId?.let { "$PREF_LAYOUT_GEOMETRY:$it" }
        val lastGroup = lastKey?.let { spatialPrefs.getString(it, null) }
        if (qwerty.layout.geometryId != group) {
            flushQwerty()
            qwertyGroups[qwerty.layout.geometryId] = qwerty
            qwerty = qwertyGroups.getOrPut(group) { loadQwertyGroup(layout, lastGroup) }
            candidatesCache.evictAll()
            spanCache.evictAll()
        }
        val saveGroup = lastKey != null && lastGroup != group
        val saveCenters = group != KeyboardLayout.QWERTY_GEOMETRY && !spatialPrefs.contains("$PREF_QWERTY_CENTERS:$group")
        if (saveGroup || saveCenters) spatialPrefs.edit().apply {
            if (saveGroup) putString(lastKey, group)
            if (saveCenters) putString("$PREF_QWERTY_CENTERS:$group", serializeCenters(layout.centers))
        }.apply()
    }

    private fun loadQwertyGroup(layout: KeyboardLayout, lastGroup: String?): QwertyModel {
        val model = QwertyModel(layout)
        val saved = spatialPrefs.getString(qwertyPrefKey(layout.geometryId), null)
        if (saved != null) {
            model.restore(saved)
            return model
        }
        if (lastGroup == null || lastGroup == layout.geometryId) return model
        val oldData = qwertyGroups[lastGroup]?.serialize() ?: spatialPrefs.getString(qwertyPrefKey(lastGroup), null) ?: return model
        val oldCenters = if (lastGroup == KeyboardLayout.QWERTY_GEOMETRY) KeyboardLayout.QWERTY.centers
        else parseCenters(spatialPrefs.getString("$PREF_QWERTY_CENTERS:$lastGroup", null)) ?: return model
        model.migrateFrom(oldData, oldCenters)
        Log.i(TAG, "版面鍵位改了：落點習慣依位置搬到新的一組")
        return model
    }

    /** 鍵位：「字:x,y;…」（版面的字鍵不會是 * : ;） */
    private fun serializeCenters(centers: Map<Char, Pair<Double, Double>>) =
        centers.entries.joinToString(";") { (c, p) -> "$c:%.4f,%.4f".format(java.util.Locale.ROOT, p.first, p.second) }

    private fun parseCenters(data: String?): Map<Char, Pair<Double, Double>>? {
        if (data.isNullOrBlank()) return null
        return data.split(';').mapNotNull { part ->
            if (part.length < 3 || part[1] != ':') return@mapNotNull null
            val nums = part.substring(2).split(',').mapNotNull { it.toDoubleOrNull() }
            if (nums.size != 2) null else part[0] to (nums[0] to nums[1])
        }.toMap()
    }

    /** QWERTY 只看落點時判對的次數：[總數, 有個人化時判對, 沒有個人化時判對] */
    private val qwertyStats: LongArray = spatialPrefs.getString(PREF_QWERTY_STATS, null)?.split(',')?.mapNotNull { it.toLongOrNull() }
        ?.takeIf { it.size == 3 }?.toLongArray() ?: LongArray(3)

    fun qwertyAccuracy(): LongArray = qwertyStats.copyOf()

    /** 一次性把舊版記在按錯讀音底下的學習紀錄搬到正確讀音（例如「ㄕㄨ-ㄅㄨ → 速度」、「ㄉㄧㄤ → 屌」） */
    private fun canonicalizeUserWords() {
        val database = db ?: return
        try {
            data class Row(val reading: String, val word: String, val count: Int, val lastUsed: Long)
            val rows = ArrayList<Row>()
            database.rawQuery("SELECT reading, word, count, last_used FROM user_dict", null).use { c ->
                while (c.moveToNext()) rows.add(Row(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3)))
            }
            var moved = 0
            database.beginTransaction()
            try {
                for (r in rows) {
                    val fixed = canonicalReading(r.reading, r.word)
                    if (fixed == r.reading) continue
                    database.delete("user_dict", "reading = ? AND word = ?", arrayOf(r.reading, r.word))
                    val cv = ContentValues().apply {
                        put("reading", fixed)
                        put("toneless", toToneless(fixed))
                        put("initials", getInitialsFromReading(fixed))
                        put("word", r.word)
                        put("count", r.count)
                        put("last_used", r.lastUsed)
                    }
                    if (database.insertWithOnConflict("user_dict", null, cv, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                        database.execSQL(
                            "UPDATE user_dict SET count = max(count, ?), last_used = max(last_used, ?) WHERE reading = ? AND word = ?",
                            arrayOf(r.count, r.lastUsed, fixed, r.word)
                        )
                    }
                    moved++
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            Log.i(TAG, "已校正 $moved 筆學習紀錄的讀音")
        } catch (e: Exception) {
            Log.e(TAG, "校正學習紀錄讀音失敗", e)
        }
    }

    /** 不含任何文字（漢字、字母、數字）的候選，即 emoji 或符號 */
    private fun isSymbolOnly(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) return false
            i += Character.charCount(cp)
        }
        return true
    }

    // ---------- 英文補全：英文詞庫（SCD，依字母排）前綴查詢 + QWERTY 鄰鍵容錯 + 個人學習 ----------

    /**
     * QWERTY 鄰鍵與懲罰：以按鍵寬為 1 的座標估計手指落點，懲罰隨「落點到鄰鍵距離²−落點到目前判定鍵距離²」增加。
     * 點在中心時同排鄰鍵約 1.5；偏向某鄰鍵半個鍵寬時降到 0.3。
     * 落點以實際按到的鍵（rawChar）為準；按下當下被改判過的，原本按到的鍵一定列為候選
     */
    private fun englishNeighborKeys(touch: BopomofoComposer.TouchPoint): List<Pair<Char, Double>> {
        val qwerty = qwerty
        val c = touch.char.lowercaseChar()
        val pos = qwerty.pos[c] ?: return emptyList()
        val (px, py) = qwerty.point(touch) ?: (pos.first + touch.offsetX * 0.5 to pos.second + touch.offsetY * 0.5)
        // 距離以「瞄準該鍵時的習慣落點」為準（沒有個人化資料時就是鍵中心）
        val c0 = qwerty.center(c)!!
        val d0 = (px - c0.first) * (px - c0.first) + (py - c0.second) * (py - c0.second)
        val out = ArrayList<Pair<Char, Double>>(8)
        for ((k, p) in qwerty.pos) {
            if (k == c) continue
            val dx = p.first - pos.first
            val dy = p.second - pos.second
            if (dx * dx + dy * dy > 1.6 && k != touch.rawChar.lowercaseChar()) continue
            val ck = qwerty.center(k)!!
            val d = (px - ck.first) * (px - ck.first) + (py - ck.second) * (py - ck.second)
            out.add(k to ((d - d0) * 1.2 + 0.3).coerceIn(0.3, 3.0))
        }
        return out
    }

    /**
     * 中文 QWERTY 輸入法（拼音、26 鍵注音）按下當下判定：落點高斯代價 ＋ 解碼先驗
     * logScore(c)：接上 c 後最佳切分的分數（自然對數），null 為解不出來；與英文相同的權重與上限
     */
    fun resolveQwertyTap(raw: Char, offsetX: Float, offsetY: Float, logScore: (Char) -> Double?): Char {
        noteQwertyTap(raw, offsetX, offsetY)
        val qwerty = qwerty
        val rawPos = qwerty.pos[raw] ?: return raw
        val (px, py) = qwerty.point(raw, offsetX, offsetY)!!
        val options = qwerty.pos.filter { (k, p) ->
            k == raw || (p.first - rawPos.first).let { it * it } + (p.second - rawPos.second).let { it * it } <= 1.6
        }
        // 只評估落點夠近、可能翻盤的鍵（其餘鍵的落點代價已超過先驗上限，不必解碼）
        val scores = HashMap<Char, Double?>()
        for (k in options.keys) {
            val geo = qwerty.cost(px, py, k)
            if (k == raw || geo < 2.0) scores[k] = logScore(k)
        }
        val best = scores.values.filterNotNull().maxOrNull() ?: return raw
        var bestKey = raw
        var bestTotal = Double.MAX_VALUE
        for (k in options.keys) {
            if (!scores.containsKey(k)) continue
            val geo = qwerty.cost(px, py, k)
            val sc = scores[k]
            val prior = if (sc == null) EN_PRIOR_CAP + 0.15 else (EN_PRIOR_WEIGHT * (best - sc)).coerceIn(0.0, EN_PRIOR_CAP)
            val total = geo + prior
            if (total < bestTotal - 1e-9 || (k == raw && total <= bestTotal + 1e-9)) {
                bestTotal = total
                bestKey = k
            }
        }
        return bestKey
    }

    /**
     * 英文按下當下判定（動態感應區）：落點高斯代價 ＋ 字母先驗（前面的字母接上它，字典裡有多少常用字）
     * - 先驗差每 e 倍扣 0.12、最多 0.35：邊界最多偏移約 0.2 個鍵寬，點在鍵中央一定照按的鍵
     * - 前綴已經不是任何字的開頭（人名、縮寫、密碼）：不加先驗，純看落點
     * - 字典還沒載入：照按的鍵
     */
    fun resolveEnglishTap(raw: Char, offsetX: Float, offsetY: Float, prefix: String): Char {
        noteQwertyTap(raw, offsetX, offsetY)
        val index = data.english ?: return raw
        val qwerty = qwerty
        val rawPos = qwerty.pos[raw] ?: return raw
        if (!prefix.all { it in 'a'..'z' }) return raw
        val (px, py) = qwerty.point(raw, offsetX, offsetY)!!
        val options = qwerty.pos.filter { (k, p) ->
            k == raw || (p.first - rawPos.first).let { it * it } + (p.second - rawPos.second).let { it * it } <= 1.6
        }
        val mass = HashMap<Char, Double>(options.size)
        for (k in options.keys) {
            var m = 0.0
            for (i in index.range(prefix + k)) m += Math.pow(10.0, index.score(i) / 1000.0)
            mass[k] = m
        }
        val best = mass.values.maxOrNull() ?: 0.0
        var bestKey = raw
        var bestScore = Double.MAX_VALUE
        for (k in options.keys) {
            val geo = qwerty.cost(px, py, k)
            val m = mass[k] ?: 0.0
            val prior = when {
                best <= 0.0 -> 0.0
                m <= 0.0 -> EN_PRIOR_CAP
                else -> (EN_PRIOR_WEIGHT * kotlin.math.ln(best / m)).coerceAtMost(EN_PRIOR_CAP)
            }
            val score = geo + prior
            if (score < bestScore - 1e-9 || (k == raw && score <= bestScore + 1e-9)) {
                bestScore = score
                bestKey = k
            }
        }
        return bestKey
    }

    /**
     * 英文單字預測補全
     * - 前綴完全吻合者依詞頻排序；學過的字加分
     * - 打到第 2 個字母起，每個字母都可能是按到鄰鍵（依觸控落點加權），例如 scirp → scorpion
     */
    suspend fun getEnglishCandidates(
        prefix: String,
        touches: List<BopomofoComposer.TouchPoint>? = null
    ): List<String> = withContext(Dispatchers.IO) {
        if (prefix.isBlank()) return@withContext emptyList()
        val lower = prefix.lowercase()
        val index = data.english
        val scored = HashMap<String, Double>()
        val display = HashMap<String, String>()

        fun add(word: String, score: Double) {
            val cur = scored[word]
            if (cur == null || score > cur) scored[word] = score
        }
        fun learnedScore(count: Int) = 2000.0 + minOf(800.0 + count * 400.0, 2800.0)
        // 改錯字也要找學過的字（gboard、人名、專有名詞不在內建字典裡）：整個字吻合才算
        fun addLearnedFix(variant: String, penalty: Double) {
            val e = user.englishWord(variant) ?: return
            display[variant] = e.word
            add(variant, learnedScore(e.count) - penalty)
        }

        // 1. 個人學習的英文字（前綴吻合）
        val learned = HashMap<String, Int>()
        for (e in user.englishPrefix(lower, lower + "\uffff", 20)) {
            learned[e.key] = e.count
            display[e.key] = e.word
        }
        // 打出來的就是一個正確的字（字典有或學過）：放第一個，跟中文一樣「打對就是第一候選」
        var exact = learned.containsKey(lower)
        for ((k, cnt) in learned) {
            if (k == lower) continue
            add(k, learnedScore(cnt))
        }

        if (index != null) {
            // 2. 前綴完全吻合
            val r = index.range(lower)
            for (i in r) {
                val w = index.word(i)
                if (w == lower) {
                    exact = true
                    continue
                }
                add(w, index.score(i) + minOf(800.0 + (learned[w] ?: -2) * 400.0, 2800.0).coerceAtLeast(0.0))
            }

            // 3. 鄰鍵容錯：每次替換一個字母，懲罰 1 單位約等於詞頻差 10 倍
            if (lower.length >= 2 && lower.all { it in 'a'..'z' }) {
                for (i in lower.indices) {
                    val tp = touches?.getOrNull(i)?.takeIf { it.char.lowercaseChar() == lower[i] }
                        ?: BopomofoComposer.TouchPoint(lower[i])
                    for ((nb, pen) in englishNeighborKeys(tp)) {
                        val variant = lower.substring(0, i) + nb + lower.substring(i + 1)
                        addLearnedFix(variant, pen * 1000.0)
                        val vr = index.range(variant)
                        var taken = 0
                        // 區間內依詞頻取前幾名
                        val top = vr.sortedByDescending { index.score(it) }
                        for (j in top) {
                            if (taken >= 4) break
                            add(index.word(j), index.score(j) - pen * 1000.0)
                            taken++
                        }
                    }
                }
            }
        }

        if (exact) {
            scored.remove(lower)
            scored[lower] = Double.MAX_VALUE
        }

        // 4. 打錯字（比照 Gboard）：打的不是一個字時，除了上面的鄰鍵替換，再找少打、多打一個字母、
        //    相鄰兩個字母對調的整個字（懲罰 1.2，同鄰鍵替換的尺度；teh → the、recieve → receive）
        if (!exact && lower.length >= 3 && lower.all { it in 'a'..'z' }) {
            val variants = HashSet<String>()
            for (i in lower.indices) variants.add(lower.removeRange(i, i + 1))
            for (i in 0 until lower.length - 1) {
                variants.add(lower.substring(0, i) + lower[i + 1] + lower[i] + lower.substring(i + 2))
            }
            for (i in 0..lower.length) for (c in 'a'..'z') variants.add(lower.substring(0, i) + c + lower.substring(i))
            variants.remove(lower)
            for (v in variants) {
                addLearnedFix(v, 1200.0)
                val r = index?.range(v) ?: continue
                if (!r.isEmpty() && index.word(r.first) == v) add(v, index.score(r.first) - 1200.0)
            }
        }

        // 最像的不是「打的字再打下去」（是修正過的字，或根本沒有候選）：第一個候選留著打的原樣，點它就照打的送出
        val sorted = scored.entries.sortedByDescending { it.value }
        val typo = !exact && (sorted.isEmpty() || !sorted.first().key.startsWith(lower))
        val upperFirst = prefix[0].isUpperCase()
        val allUpper = prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() }
        val list = sorted.take(if (typo) 11 else 12).map { (k, _) ->
            val base = display[k] ?: k
            when {
                allUpper -> base.uppercase()
                upperFirst -> base.replaceFirstChar { it.uppercase() }
                k == "i" || k.startsWith("i'") -> base.replaceFirstChar { it.uppercase() }
                else -> base
            }
        }
        return@withContext if (typo) listOf(prefix) + list.filter { it != prefix } else list
    }

    /**
     * 英文接續詞：打完一個字（空白或選字）後預測下一個字
     * 1. 個人學過的搭配（user_bigram，英文一律小寫）
     * 2. 內建語料搭配（english_bigram，OpenSubtitles 口語對白統計）
     */
    suspend fun getEnglishPredictions(prevWord: String): List<String> = withContext(Dispatchers.IO) {
        val prev = prevWord.lowercase()
        if (prev.isBlank() || !prev.all { it in 'a'..'z' || it == '\'' }) return@withContext emptyList()
        val list = LinkedHashSet<String>()
        list.addAll(user.nextRecent(prev, 4) { it.next != it.prev })
        data.english?.let { list.addAll(it.next(prev, 12)) }
        return@withContext list.take(10).map { if (it == "i" || it.startsWith("i'")) it.replaceFirstChar { c -> c.uppercase() } else it }
    }

    /** 學習使用者打完或選用的英文字 */
    suspend fun recordEnglishWord(word: String) = withContext(Dispatchers.IO) {
        if (word.length < 2 || !word.all { it.isLetter() || it == '\'' } || !word.first().isLetter()) return@withContext
        val database = db ?: return@withContext
        try {
            val key = word.lowercase()
            val now = System.currentTimeMillis()
            // 全大寫只是打法，記小寫；其餘保留使用者的大小寫（iPhone、Scorpio）
            val stored = if (word.length > 1 && word.all { it.isUpperCase() || it == '\'' }) key else word
            database.execSQL(
                "INSERT INTO user_english (key, word, count, last_used) VALUES (?, ?, 1, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET count = count + 1, word = excluded.word, last_used = excluded.last_used",
                arrayOf(key, stored, now)
            )
            user.reloadEnglish(database, key)
        } catch (e: Exception) {
            Log.e(TAG, "更新 user_english 失敗", e)
        }
    }

    /**
     * 取得所有收錄的 Emoji 清單（供 Emoji 面板瀏覽）
     */
    suspend fun getAllEmojis(): List<String> = withContext(Dispatchers.IO) {
        data.emoji?.all() ?: emptyList()
    }

    /**
     * 取得注音候選字清單（支援多重音節動態切分、文字 + Emoji + 顏文字綜合流）
     * 支援易混淆按錯鍵容錯、免按聲調快速連打！
     */
    @JvmName("getCandidatesForSegmentations")
    suspend fun getCandidates(
        allSegmentations: List<List<String>>,
        hasTone: Boolean,
        prevWord: String? = null,
        touchPoints: List<BopomofoComposer.TouchPoint>? = null,
        inlineSymbols: Boolean = true,
        /** 再前一個詞（連續打字時）：前兩個詞都一樣的搭配學過的話，下一個詞往前排 */
        prevWord2: String? = null
    ): List<String> {
        val groups = getCandidateGroups(allSegmentations, hasTone, prevWord, touchPoints, prevWord2 = prevWord2)
        return if (inlineSymbols) groups.inline else groups.words
    }

    suspend fun getCandidateGroups(
        allSegmentations: List<List<String>>,
        hasTone: Boolean,
        prevWord: String? = null,
        touchPoints: List<BopomofoComposer.TouchPoint>? = null,
        prevWord2: String? = null
    ): CandidateGroups = withContext(Dispatchers.IO) {
        if (allSegmentations.isEmpty()) return@withContext CandidateGroups.EMPTY
        val d = data
        val words = d.words
        if (words.tables.isEmpty()) return@withContext CandidateGroups.EMPTY
        val startNs = System.nanoTime()

        // 每個音節各自的觸控點（同一符號重複出現時不互相覆蓋）
        val primaryInput = allSegmentations.first()
        var primaryTouches: List<Map<Char, BopomofoComposer.TouchPoint>> = emptyList()
        if (touchPoints != null) {
            val parsed = BopomofoComposer.parseSyllables(touchPoints)
            if (parsed.map { it.text } == primaryInput) primaryTouches = parsed.map { it.touches }
        }

        // 1. 高速記憶體快取秒回 (0.01ms)；觸控落點會影響鄰鍵懲罰，以 0.25 鍵寬粗分納入快取鍵
        val touchSig = touchPoints?.joinToString("") {
            "${it.rawChar}${(it.offsetX * 4).roundToInt()},${(it.offsetY * 4).roundToInt()};"
        }.orEmpty()
        val cacheKey = "${prevWord2.orEmpty()}_${prevWord.orEmpty()}_${hasTone}_" + allSegmentations.joinToString(";") { it.joinToString("-") } + "|" + touchSig
        candidatesCache.get(cacheKey)?.let { return@withContext it }

        if (spatial.syllablePrefixes == null) spatial.setSyllableFrequencies(syllableFrequencies())

        // 主要切分中有拼不起來的音節：改用鄰鍵修正後最可能的切分當主要切分，原輸入退為次要
        // 按下當下被改判過的鍵（例如 ㄉㄨ+ㄠ 判成 ㄛ），以原本按到的鍵重新評估，回頭修正前面的鍵（ㄨ→ㄧ）的可能性才不會消失
        var segmentationsToUse = allSegmentations
        var precomputedLattice: LatticeResult? = null
        var repairAlternatives: List<String> = emptyList()
        val rawPoints = touchPoints?.map { if (it.rawChar != it.char) it.copy(char = it.rawChar) else it }
        val rawInvalid = rawPoints != null && rawPoints != touchPoints &&
            BopomofoComposer.parseSyllables(rawPoints).let { p -> p.size == primaryInput.size && p.any { !isValidSyllable(it.text) } }
        // 打滿整個輸入的候選（整句、整個詞）共同的分數：網格的分數（落點、字頻、上文、個人習慣同一個尺度），
        // 鄰鍵修正的再扣修正懲罰；排在候選最前面（見下面「依統一分數排序」）
        val pool = HashMap<String, Double>()
        fun poolLattice(lattice: LatticeResult, offset: Double) {
            for ((text, score) in lattice.scored) {
                val v = score - offset
                if (text.isNotEmpty() && v > (pool[text] ?: Double.NEGATIVE_INFINITY)) pool[text] = v
            }
        }
        if (touchPoints != null && primaryInput.isNotEmpty() && (rawInvalid || primaryInput.any { !isValidSyllable(it) })) {
            val repairs = repairByNeighborKeys(if (rawInvalid) rawPoints!! else touchPoints, primaryInput.size)
            if (repairs.isNotEmpty()) {
                // 同樣懲罰的修正可能有好幾種（ㄐ→ㄍ／ㄔ／聲調鍵），每種都走網格（單一音節也是），扣掉修正的懲罰後一起排
                val scored = repairs.map { r ->
                    val lattice = solveLatticeViterbi(r.syllables, r.touches, prevWord, prevWord2 = prevWord2)
                    Triple(r, lattice, lattice.bestScore - r.penalty * 1.8)
                }.sortedByDescending { it.third }
                for ((r, lattice, _) in scored.take(REPAIRS_POOLED)) poolLattice(lattice, r.penalty * 1.8)
                val best = scored.first()
                primaryTouches = best.first.touches
                precomputedLattice = best.second.takeIf { best.first.syllables.size > 1 }
                segmentationsToUse = buildList {
                    add(best.first.syllables)
                    addAll(allSegmentations)
                    scored.drop(1).take(2).forEach { if (!contains(it.first.syllables)) add(it.first.syllables) }
                }
                // 其他修正方式的整句也列在前排（ㄏㄣㄉㄨㄠ 最可能是「很多」，但「很屌」也要看得到，選過一次就會學起來）
                repairAlternatives = scored.drop(1).take(4).mapNotNull { it.second.fullSentence.takeIf { s -> s.isNotEmpty() && best.first.syllables.size > 1 } }
                Log.d(TAG, "鄰鍵修正：${primaryInput.size} 個音節 (懲罰 ${best.first.penalty})")
            }
        }

        // 聲母後面接了拼不起來的介音（ㄏ＋ㄩㄢ）：組字會拆成「ㄏ（簡拼）＋ㄩㄢ」，每段都合法，上面的修正不會啟動。
        // 不拆開再看一次：拼不起來就試鄰鍵與慣性打錯（ㄏ→ㄒ）；修好的整句比拆開的更像一句話才排第一，否則當備選
        if (precomputedLattice == null && touchPoints != null && primaryInput.size > 1) {
            val mergedParse = BopomofoComposer.parseSyllables(touchPoints, keepIncompatible = true)
            if (mergedParse.size in 2 until primaryInput.size && mergedParse.any { !isValidSyllable(it.text) }) {
                val repairs = repairByNeighborKeys(touchPoints, mergedParse.size, merged = true)
                if (repairs.isNotEmpty()) {
                    val scored = repairs.map { r ->
                        val lattice = solveLatticeViterbi(r.syllables, r.touches, prevWord, prevWord2 = prevWord2)
                        Triple(r, lattice, lattice.bestScore - r.penalty * 1.8)
                    }.sortedByDescending { it.third }
                    val best = scored.first()
                    val split = solveLatticeViterbi(primaryInput, primaryTouches, prevWord, prevWord2 = prevWord2)
                    poolLattice(split, 0.0)
                    for ((r, lattice, _) in scored.take(REPAIRS_POOLED)) poolLattice(lattice, r.penalty * 1.8)
                    if (best.third > split.bestScore) {
                        primaryTouches = best.first.touches
                        precomputedLattice = best.second
                        segmentationsToUse = buildList {
                            add(best.first.syllables)
                            addAll(allSegmentations)
                        }
                        repairAlternatives = listOfNotNull(split.fullSentence.takeIf { it.isNotEmpty() }) +
                            scored.drop(1).take(3).mapNotNull { it.second.fullSentence.takeIf { s -> s.isNotEmpty() } }
                    } else {
                        precomputedLattice = split
                        segmentationsToUse = allSegmentations + listOf(best.first.syllables)
                        repairAlternatives = listOfNotNull(best.second.fullSentence.takeIf { it.isNotEmpty() })
                    }
                }
            }
        }
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun addCandidate(word: String) {
            if (word.isNotBlank() && isRenderable(word) && seen.add(word)) {
                result.add(word)
            }
        }

        // 收集所有候選詞及其優先評分
        data class ScoredCandidate(val word: String, val score: Double)
        val bestWordScores = LinkedHashMap<String, Double>()

        // 查詢 user_bigram 上下文：打滿整個輸入的詞由網格的統一分數處理（見 contextBonus），
        // 這裡只給只蓋到前幾個音節的詞加分（加完一樣受 PARTIAL_CAP 限制）
        val now = System.currentTimeMillis()
        val bigramBoostMap = mutableMapOf<String, Double>()
        if (!prevWord.isNullOrBlank()) {
            for (p in user.next(prevWord, 20)) {
                bigramBoostMap[p.next] = minOf(25.0 + p.count * 4.0, 45.0)
            }
        }

        // 靜態 Bigram：前一個已上屏詞對下一個詞的搭配加權（例如「載具」→「發票」），跟個人搭配取大的。
        // 用到哪個詞才查（常用的前詞後面接的詞有幾千個，不必每按一鍵都抄一份）
        val staticBigrams = if (prevWord.isNullOrBlank()) null else bigramsFor(prevWord)
        fun contextBoostOf(word: String, partial: Boolean): Double {
            val personal = if (partial) bigramBoostMap[word] ?: 0.0 else 0.0
            val b = staticBigrams?.get(word) ?: return personal
            val boost = (6.0 + (b + 5.0).coerceIn(0.0, 5.0) * 2.0)
            return if (personal < boost) boost else personal
        }

        // 只蓋到前幾個音節的詞（打 ㄉㄜㄈㄨㄏㄠ 的「的」）：上文搭配加分最多加到 PARTIAL_CAP，
        // 不會因為常在「鍵盤」後面接「的」就排到整句「的符號」前面；本來就比這高的不受影響
        val fullLength = segmentationsToUse.firstOrNull()?.size ?: 0
        fun addScoredWord(word: String, baseScore: Double) {
            if (word.isNotBlank() && !word.startsWith("MACRO@")) {
                val partial = fullLength > 1 && hanLength(word) in 1 until fullLength
                var finalScore = baseScore + contextBoostOf(word, partial)
                if (partial && finalScore > PARTIAL_CAP) {
                    finalScore = maxOf(PARTIAL_CAP, baseScore)
                }
                val cur = bestWordScores[word]
                if (cur == null || finalScore > cur) {
                    bestWordScores[word] = finalScore
                }
            }
        }

        val allReadingsForKaomoji = mutableListOf<String>()

        // 智慧簡拼/懶人注音展開：例如輸入 [ㄨ, ㄐㄩㄝ, ㄉㄜ] 自動展開出 [ㄨㄛ, ㄐㄩㄝ, ㄉㄜ] (我覺得)；展開表在鍵盤設定（ImeConfig）
        val lazySyllables = ImeConfig.get(context).lazySyllables
        val augmentedSegmentations = mutableListOf<List<String>>()
        augmentedSegmentations.addAll(segmentationsToUse)
        for (syllables in segmentationsToUse) {
            val hasLazy = syllables.any { lazySyllables.containsKey(it) }
            if (hasLazy) {
                val variantLists = syllables.map { syl ->
                    val list = mutableListOf(SyllableVariant(syl, 0.0))
                    lazySyllables[syl]?.forEachIndexed { idx, exp ->
                        list.add(SyllableVariant(exp, 0.45 + idx * 0.05))
                    }
                    list
                }
                val combos = generateWeightedSubCombos(variantLists, beamWidth = 16)
                for ((combo, _) in combos) {
                    if (!augmentedSegmentations.contains(combo)) {
                        augmentedSegmentations.add(combo)
                    }
                }
            }
        }

        for ((segIndex, syllables) in augmentedSegmentations.withIndex()) {
            if (syllables.isEmpty()) continue
            val fullRawKey = syllables.joinToString("-").replace("ˉ", "")
            val fullTonelessKey = toToneless(fullRawKey)
            allReadingsForKaomoji.add(fullRawKey)
            allReadingsForKaomoji.add(fullTonelessKey)

            val isPrimary = (segIndex == 0)

            // Step 0: 單一音節也走網格，結果放進統一分數（鄰鍵修正過的已經在修正那邊放了）
            if (isPrimary && syllables.size == 1 && pool.isEmpty()) {
                poolLattice(solveLatticeViterbi(syllables, primaryTouches, prevWord, prevWord2 = prevWord2), 0.0)
            }
            // Step 1: 多音節時，Lattice Viterbi 網格全句最佳組合（只在主要切分計算一次）
            if (isPrimary && syllables.size > 1) {
                val lattice = precomputedLattice ?: solveLatticeViterbi(syllables, primaryTouches, prevWord, prevWord2 = prevWord2).also { if (pool.isEmpty()) poolLattice(it, 0.0) }
                if (lattice.fullSentence.isNotEmpty()) {
                    addScoredWord(lattice.fullSentence, 115.0) // 全句最佳路徑置頂 (例如：好像還可以、我覺得、為什麼、沒問題)
                }
                for ((idx, w) in lattice.alternatives.withIndex()) {
                    addScoredWord(w, 108.0 - idx) // 次佳整句（Bigram 搭配競爭）
                }
                for ((idx, w) in repairAlternatives.withIndex()) {
                    addScoredWord(w, 101.0 - idx) // 其他鄰鍵修正的整句
                }
                for ((idx, w) in lattice.dpWords.withIndex()) {
                    addScoredWord(w, 98.0 - idx) // 次長前綴詞 (例如：好像)
                }
                for (w in lattice.spanPrefixWords) {
                    addScoredWord(w, 92.0) // 詞首切分詞
                }
            }

            // Step 1b: 全聲母 / 混合簡拼匹配（任一音節為單聲母時啟動）
            val isAbbrev = syllables.size > 1 && syllables.any { val tl = toToneless(it); tl.length == 1 && BopomofoComposer.INITIALS.contains(tl[0]) }
            if (isAbbrev) {
                val subSyls = syllables.map { toToneless(it) }
                val initKey1 = subSyls.map { syl -> if (syl.isNotEmpty() && BopomofoComposer.INITIALS.contains(syl[0])) syl[0].toString() else "_" }.joinToString("-")
                val initKey2 = subSyls.map { syl -> if (syl.isNotEmpty() && BopomofoComposer.INITIALS.contains(syl[0])) syl[0].toString() else "" }.joinToString("-")
                val initKey3 = subSyls.mapNotNull { it.firstOrNull()?.toString() }.joinToString("-")
                val likePattern = subSyls.joinToString("-") { syl ->
                    if (syl.length == 1 && BopomofoComposer.INITIALS.contains(syl[0])) "$syl%" else syl
                }
                val hits = words.byInitialsLike(listOf(initKey1, initKey2, initKey3), likePattern, 15)
                for ((rank, h) in hits.withIndex()) {
                    addScoredWord(h.word, h.score + 96.0 - (rank * 0.5))
                }
            }

            // 1c. 韻母/母音簡拼匹配 (例如打 ㄢㄩㄝ -> 感覺、三月、判決...)
            val isAllFinals = syllables.all { it.all { c -> BopomofoComposer.FINALS.contains(c) || BopomofoComposer.MEDIALS.contains(c) } }
            if (isAllFinals) {
                val finKey = syllables.joinToString("-")
                for (h in words.byFinals(finKey, 15)) {
                    addScoredWord(h.word, h.score + 60.0)
                }
            }

            // Step 2: 個人學習詞庫 —— 只當加分，不再以固定高分蓋過字典
            // (a) 讀音完全吻合、字數一致、聲調不衝突：字典已收錄者只加學習分（交由 Step 3 依聲調評分），自創詞直接放入精準層
            for (u in user.matches(fullTonelessKey, fullRawKey, 12)) {
                val word = u.word
                if (!isHanWord(word) || hanLength(word) != syllables.size) continue
                if (toneConflictCount(syllables, u.reading) > 0) continue
                val inDict = words.hasTonelessWord(fullTonelessKey, word)
                if (!inDict) addScoredWord(word, (if (isPrimary) 104.0 else 94.0) - 4.5)
            }
            // (b) 學過的較長詞作為前綴補全（與字典前綴補全同層級）
            for (u in user.tonelessBetween("$fullTonelessKey-", "$fullTonelessKey-\uffff", 5)) {
                val word = u.word
                if (isHanWord(word) && hanLength(word) > syllables.size) {
                    addScoredWord(word, 68.0 - 4.5 + learnBonus(decayedCount(u.count, u.lastUsed, now)))
                }
            }
            // (c) 簡拼輸入（含單聲母音節）時，比對學過的同聲母、同字數詞
            if (isAbbrev) {
                val likeUser = syllables.joinToString("-") { syl ->
                    val tl = toToneless(syl)
                    if (tl.length == 1 && BopomofoComposer.INITIALS.contains(tl[0])) "$tl%" else tl
                }
                for (u in user.initialsLike(getInitialsFromReading(fullTonelessKey), likeUser, 5)) {
                    val word = u.word
                    if (isHanWord(word) && hanLength(word) == syllables.size) {
                        addScoredWord(word, 96.0 - 4.5 + learnBonus(decayedCount(u.count, u.lastUsed, now)))
                    }
                }
            }

            // Step 3a: 精準匹配（若使用者有打聲調，精確匹配平滑遞減加權）
            if (hasTone) {
                for ((rank, h) in words.byReading(fullRawKey, 20).withIndex()) {
                    val baseBoost = if (isPrimary) 112.0 else 100.0
                    val boost = maxOf(baseBoost - (rank * 0.8), 88.0)
                    addScoredWord(h.word, h.score + boost)
                }
            }

            // Step 3b: 無聲調匹配 + 聲調一致性重排（使用者已輸入聲調的音節若與字典讀音不符則大幅扣分；未輸入聲調的音節視為萬用）
            for ((rank, h) in words.byToneless(fullTonelessKey, 40).withIndex()) {
                val baseBoost = if (isPrimary) 104.0 else 94.0
                val boost = maxOf(baseBoost - (rank * 0.8), 85.0)
                val mismatch = toneMismatchCount(syllables, h.reading)
                addScoredWord(h.word, h.score + boost - mismatch * 14.0)
            }

            // Step 3c: 前綴預測匹配（例如輸入 ㄏㄠ-ㄏㄠ-ㄒ 補全「好好笑」；同字數 90.0，長詞 68.0）
            for (h in words.byTonelessPrefix(fullTonelessKey, 15)) {
                val boost = if (h.word.length == syllables.size) 90.0 else 68.0
                addScoredWord(h.word, h.score + boost)
            }

            // Step 3d: 模糊音與鄰鍵容錯 toneless IN (...) (多字詞 84.0，單字 50.0)
            if (isPrimary) {
                val fuzzyKeys = generateFuzzyVariants(fullTonelessKey)
                if (fuzzyKeys.isNotEmpty()) {
                    for (h in words.byTonelessIn(fuzzyKeys, 10)) {
                        val boost = if (h.word.length >= 2) 84.0 else 50.0
                        addScoredWord(h.word, h.score + boost)
                    }
                }
            }
        }

        // 3e. 多音節輸入時，務必在多字詞組後完整列出首音節（及雙字前綴）的所有單獨候選字！
        // 解決使用者反饋：「打 ㄏㄠㄏㄠㄎㄢ 或 ㄏㄠㄉㄚ 後面沒有單獨字可選，無法逐字選字」
        val primarySyllables = segmentationsToUse.firstOrNull() ?: emptyList()
        if (primarySyllables.size > 1) {
            val firstSyllable = primarySyllables.first()
            val tonelessFirst = toToneless(firstSyllable)
            val firstReading = firstSyllable.replace("ˉ", "")
            val firstHasTone = firstSyllable.any { BopomofoComposer.TONES.contains(it) }

            // 1. 先查個人學習庫中首音節的單字 (若曾自選過高頻單字優先加權)
            for (u in user.matches(tonelessFirst, firstReading, 5, singleChar = true)) {
                if (!isHanWord(u.word)) continue
                addScoredWord(u.word, 78.0 + minOf(decayedCount(u.count, u.lastUsed, now) * 1.5, 12.0))
            }

            // 2. 精確聲調單字 (若首音節有標聲調，如 ㄏㄠˇ 出 好、郝)
            if (firstHasTone) {
                for (h in words.byReading(firstReading, 20, singleChar = true)) {
                    addScoredWord(h.word, h.score + 75.0)
                }
            }

            // 3. 完整列出該首音節的所有單字候選字（如 ㄏㄠ 列出 好、號、豪、毫、耗、浩、郝、皓、壕、鎬...）
            for (h in words.byToneless(tonelessFirst, 30, singleChar = true)) {
                val boost = if (firstHasTone) 65.0 else 70.0
                addScoredWord(h.word, h.score + boost)
            }

            // 4. 若輸入 3 音節以上（例如 ㄏㄠ-ㄏㄠ-ㄎㄢ），額外補上前 2 音節的雙字前綴詞（例如「好好」）
            if (primarySyllables.size >= 3) {
                val prefix2Toneless = toToneless(primarySyllables.take(2).joinToString("-"))
                for (h in words.byToneless(prefix2Toneless, 10)) {
                    addScoredWord(h.word, h.score + 82.0)
                }
            }
        }

        // 依統一分數排序：打滿整個輸入的候選（網格分數：落點、字頻、上文、個人習慣同一個尺度）在前，
        // 跟第一名差 POOL_MARGIN 以內的照分數排；其他（只蓋到前幾個音節的詞、前綴補全、差很多的）照原本的分層順序接在後面
        // 只打了一個聲母（ㄅ）：多半還在打，第一個照原本放符號本身，不照網格的字頻排
        if (primaryInput.size == 1 && toToneless(primaryInput[0]).let { it.length == 1 && it[0] in BopomofoComposer.INITIALS && it[0] !in BopomofoComposer.STANDALONE_INITIALS }) pool.clear()
        // 按歪了（落點靠近鄰鍵）時鄰鍵拼出來的詞：照落點懲罰放進統一分數一起排，不另外插隊到第三個
        val neighborAlts = if (touchPoints != null && precomputedLattice == null && (pool.isNotEmpty() || bestWordScores.isNotEmpty())) neighborAlternatives(touchPoints, primaryInput.size) else emptyList()
        val altsPooled = pool.isNotEmpty()
        if (altsPooled) for ((w, v) in neighborAlts) if (v > (pool[w] ?: Double.NEGATIVE_INFINITY)) pool[w] = v
        val poolBest = pool.values.maxOrNull() ?: Double.NEGATIVE_INFINITY
        val ranked = pool.entries.filter { it.value >= poolBest - POOL_MARGIN && !it.key.startsWith("MACRO@") }
            .sortedByDescending { it.value }.mapTo(ArrayList()) { it.key }
        val front = ranked.toHashSet()
        bestWordScores.entries.sortedByDescending { it.value }.forEach { if (it.key !in front) ranked.add(it.key) }
        // 句尾只打了一個聲母（ㄓ、ㄅ）：可能是簡拼的字（之、吧），也可能是下一個詞剛開始打。整句照樣排第一，
        // 「前面那句」排第二：打字途中隨時可以先選前面，句尾的聲母留著繼續打、繼續選。
        // 重播比過：第一候選不變，前三命中 90.0% → 89.4%（第三名被擠到第四）；放第一會從 77.7% 掉到 66～73%（送出時句尾多半就是簡拼的字）
        val lastTyped = primaryInput.lastOrNull()?.let { toToneless(it) }.orEmpty()
        if (primaryInput.size >= 2 && lastTyped.length == 1 && lastTyped[0] in BopomofoComposer.INITIALS && ranked.isNotEmpty()) {
            val head = solveLatticeViterbi(primaryInput.dropLast(1), primaryTouches.take(primaryInput.size - 1), prevWord, prevWord2 = prevWord2).fullSentence
            if (head.isNotEmpty() && head != ranked.first()) {
                ranked.remove(head)
                ranked.add(minOf(TRAIL_HEAD_POSITION, ranked.size), head)
                trace?.add("句尾單打聲母：前面那句「$head」排第 ${minOf(TRAIL_HEAD_POSITION, ranked.size - 1) + 1} 個")
            }
        }
        // 排第一的是學過的詞：沒選它時扣回一次（見 noteSkippedLearned）
        val primaryKey = segmentationsToUse.first().joinToString("-").replace("ˉ", "")
        val learnedTop = ranked.firstOrNull()?.takeIf { top ->
            user.matches(toToneless(primaryKey), primaryKey, 24).any { it.word == top && timesChosen(it.count) > 0 } ||
                (!prevWord.isNullOrBlank() && user.nextAll(prevWord).any { it.next == top && timesChosen(it.count) > 0 })
        }
        promotedCache.put(promotedKey(prevWord, primaryInput), setOfNotNull(learnedTop))
        val textCandidates = ranked.map { ScoredCandidate(it, pool[it] ?: bestWordScores[it] ?: 0.0) }

        // 4. 查顏文字快捷 (Kaomoji)
        val kaomojis = mutableListOf<String>()
        val distinctReadings = if (kaomojiEnabled) allReadingsForKaomoji.distinct().take(3) else emptyList()
        for (rKey in distinctReadings) {
            for (km in d.kaomoji?.byReading(rKey, 3).orEmpty()) {
                if (!kaomojis.contains(km)) kaomojis.add(km)
            }
        }

        // 4b. 依候選詞語意關鍵字穿插顏文字（例如打「笑臉」→ 開心類顏文字）；對照表在鍵盤設定（ImeConfig）
        val kaomojiKeywords = ImeConfig.get(context).kaomojiKeywords
        for (w in (if (kaomojiEnabled) textCandidates.take(6) else emptyList()).map { it.word }) {
            val cat = kaomojiKeywords[w] ?: continue
            for (km in d.kaomoji?.byCategory(cat, 4).orEmpty()) {
                if (!kaomojis.contains(km)) kaomojis.add(km)
            }
            if (kaomojis.size >= 6) break
        }

        // 5. iOS 風格 Emoji 即時語意穿插（候選列只取前 4 個詞各 3 個；展開面板的 Emoji 分頁取前 8 個詞各 8 個）
        val emojiCandidates = mutableListOf<String>()
        val emojiMore = LinkedHashSet<String>()
        for ((wi, w) in (if (emojiEnabled) textCandidates.take(8) else emptyList()).map { it.word }.withIndex()) {
            for ((n, emo) in d.emoji?.byKeyword(w, 8).orEmpty().filter { isEmojiRenderable(it) }.withIndex()) {
                emojiMore.add(emo)
                if (wi < 4 && n < 3 && !emojiCandidates.contains(emo)) emojiCandidates.add(emo)
            }
        }

        // 6. 排列候選字：前兩個照實際按的鍵；按歪了（落點靠近鄰鍵）時接著列鄰鍵的詞，再來 Emoji、顏文字與其他
        var textIndex = 0
        while (textIndex < textCandidates.size && textIndex < 2) {
            addCandidate(textCandidates[textIndex].word)
            textIndex++
        }
        if (!altsPooled) for ((w, _) in neighborAlts) addCandidate(w)

        for (emo in emojiCandidates) {
            addCandidate(emo)
        }

        for (km in kaomojis) {
            addCandidate(km)
        }

        while (textIndex < textCandidates.size) {
            addCandidate(textCandidates[textIndex].word)
            textIndex++
        }

        // 分組：字典裡本身就是符號的候選（例如 ⌨️）也歸到 emoji
        val kaomojiSet = kaomojis.toHashSet()
        val wordItems = result.filter { it !in kaomojiSet && !isSymbolOnly(it) }
        val emojiGroup = (result.filter { it !in kaomojiSet && isSymbolOnly(it) } + emojiMore).distinct()
        val groups = CandidateGroups(result.toList(), wordItems, emojiGroup, kaomojis.toList())
        candidatesCache.put(cacheKey, groups)
        Log.d(TAG, "選字耗時 ${(System.nanoTime() - startNs) / 1_000_000}ms（${allSegmentations.first().size} 個音節）")
        return@withContext groups
    }

    /**
     * 相容單一音節列表調用
     */
    suspend fun getCandidates(
        syllables: List<String>,
        hasTone: Boolean,
        prevWord: String? = null,
        touchPoints: List<BopomofoComposer.TouchPoint>? = null
    ): List<String> {
        return getCandidates(listOf(syllables), hasTone, prevWord, touchPoints)
    }

    /**
     * 實體鍵盤上的相鄰鍵與其懲罰值（八方向）。
     * 觸控偏移量以半個按鍵為 1：落點在中心時正交鄰鍵 1.2、斜角 1.7；越偏向某方向，該方向鄰鍵懲罰越低（最低 0.3）。
     */
    /** 鄰鍵與懲罰：由空間模型依落點與個人化偏移計算（高斯負對數機率差） */
    private fun neighborKeys(touch: BopomofoComposer.TouchPoint): List<Pair<Char, Double>> =
        spatial.neighborPenalties(touch)

    // 字典中出現過的所有單音節（無聲調），用來判斷輸入是否「拼不起來」
    @Volatile
    private var validSyllableSet: Set<String>? = null

    private fun validSyllables(): Set<String> {
        validSyllableSet?.let { return it }
        val set = HashSet<String>(1600)
        // SELECT DISTINCT toneless FROM dict WHERE instr(toneless, '-') = 0
        for (w in words.tables) {
            for (k in 0 until w.tlIx.size) {
                val sid = w.tlIx.keySid(k)
                if ((0 until w.f.strLength(sid)).none { w.f.charAt(sid, it) == '-' }) set.add(w.f.str(sid))
            }
        }
        if (words.tables.isNotEmpty()) validSyllableSet = set
        return set
    }

    // ---------- 倉頡／速成 ----------

    /** 倉頡碼表（SCD，依碼排序，前綴查詢用二分搜尋；速成碼對照表第一次用到時建） */
    private fun cangjieIndex(): CangjieTable? = data.cangjie

    /**
     * 倉頡／速成候選字：碼完全相符的依字頻排前面，再接上「再多打幾碼」的常用字（打一半也看得到）
     */
    suspend fun codeCandidates(code: String, quick: Boolean): List<String> = withContext(Dispatchers.IO) {
        val index = cangjieIndex() ?: return@withContext emptyList()
        val exact = ArrayList<Int>()
        val more = ArrayList<Int>()
        if (quick) {
            index.quick[code]?.let { exact.addAll(it) }
            if (code.length == 1) for ((q, ids) in index.quick) if (q.length == 2 && q[0] == code[0]) more.addAll(ids)
        } else {
            for (i in index.range(code)) if (index.codeEquals(i, code)) exact.add(i) else more.add(i)
        }
        val out = LinkedHashSet<String>()
        exact.sortedByDescending { index.freq(it) }.forEach { out.add(index.char(it)) }
        more.sortedByDescending { index.freq(it) }.take(40).forEach { out.add(index.char(it)) }
        out.filter { isRenderable(it) }.take(60)
    }

    /** 倉頡碼前綴的使用量（自然對數），供按鍵當下的動態感應區；沒有任何字以此為開頭時為 null */
    fun codePrefixLogMass(prefix: String, quick: Boolean): Double? {
        val index = data.cangjie ?: return 0.0
        var m = 0.0
        if (quick) {
            for ((q, ids) in index.quick) if (q.startsWith(prefix)) for (i in ids) m += Math.pow(10.0, index.freq(i))
        } else {
            for (i in index.range(prefix)) m += Math.pow(10.0, index.freq(i))
        }
        return if (m > 0) kotlin.math.ln(m) else null
    }

    @Volatile
    private var syllableFreqCache: Map<String, Double>? = null

    /** 音節字頻（快取），供字母鍵輸入法（拼音、26 鍵注音）解碼 */
    suspend fun syllableFrequencyMap(): Map<String, Double> = withContext(Dispatchers.IO) { syllableFrequencies() }

    /** 各音節（不含聲調）的使用量：單字字頻（10^score）加總，供按鍵當下的語言先驗 */
    private fun syllableFrequencies(): Map<String, Double> {
        syllableFreqCache?.let { return it }
        return computeSyllableFrequencies().also { if (it.isNotEmpty()) syllableFreqCache = it }
    }

    private fun computeSyllableFrequencies(): Map<String, Double> {
        val freq = words.syllableFrequencies()
        if (freq.isEmpty()) return validSyllables().associateWith { 1.0 }
        return freq
    }

    /** 單一符號（簡拼聲母、零聲母韻母）一律視為合法；表載入失敗時不做判斷 */
    private fun isValidSyllable(syl: String): Boolean {
        val tl = toToneless(syl)
        if (tl.length <= 1) return true
        val set = validSyllables()
        return set.isEmpty() || set.contains(tl)
    }

    /**
     * 按歪了的鄰鍵候選（比照 Gboard）：某一鍵的落點靠近鄰鍵（鄰鍵懲罰 ≤ NEIGHBOR_ALT_MAX，按在鍵中央時不會）時，
     * 把那一鍵換成鄰鍵，讀音仍然拼得起來、音節數不變，就列出那個讀音的常用詞（聲調要對得上）。
     * 越靠近鄰鍵的排越前面，最多 NEIGHBOR_ALT_WORDS 個；第一候選還是照實際按的鍵
     */
    private fun neighborAlternatives(points: List<BopomofoComposer.TouchPoint>, syllableCount: Int): List<Pair<String, Double>> {
        val variants = ArrayList<Pair<List<String>, Double>>()
        for ((i, p) in points.withIndex()) {
            if (p.char == BopomofoComposer.FIRST_TONE) continue
            for ((nb, pen) in neighborKeys(p)) {
                if (pen > NEIGHBOR_ALT_MAX) continue
                val swapped = points.toMutableList().also { it[i] = p.copy(char = nb) }
                val parsed = BopomofoComposer.parseSyllables(swapped)
                if (parsed.size != syllableCount || parsed.any { !isValidSyllable(it.text) }) continue
                variants.add(parsed.map { it.text } to pen)
            }
        }
        val out = LinkedHashMap<String, Double>()
        for ((syls, pen) in variants.sortedBy { it.second }) {
            val tonelessKey = toToneless(syls.joinToString("-").replace("ˉ", ""))
            // 每個讀音最多兩個，讓不同方向的鄰鍵都看得到
            var taken = 0
            for (h in words.byToneless(tonelessKey, 6)) {
                if (taken >= 2) break
                // 只列漢字詞（單打一個聲母時字典裡也有符號本身，例如「ㄍ」）
                if (toneMismatchCount(syls, h.reading) > 0 || !isHanWord(h.word) || !isRenderable(h.word) || h.word in out) continue
                // 跟網格同一個尺度：多字詞一樣加長度分
                out[h.word] = h.score - pen * 1.8 + (hanLength(h.word) - 1) * 2.8
                taken++
                if (out.size >= NEIGHBOR_ALT_WORDS) return out.toList()
            }
        }
        return out.toList()
    }

    data class RepairedParse(
        val syllables: List<String>,
        val touches: List<Map<Char, BopomofoComposer.TouchPoint>>,
        val penalty: Double
    )

    /**
     * 輸入中有拼不起來的音節時（例如 ㄐㄞ、ㄅㄧㄤ），逐一把每個按鍵換成實體相鄰鍵（或視為多按一下而刪除）重新切分，
     * 保留所有音節皆合法、且音節數不變的組合（音節數不變才能與部分選字的消費音節數對齊）。
     */
    /** merged：以不拆開聲母＋拼不起來介音的切法為準（ㄏㄩㄢ），找修正後音節數是 syllableCount 的讀法 */
    private fun repairByNeighborKeys(points: List<BopomofoComposer.TouchPoint>, syllableCount: Int, merged: Boolean = false): List<RepairedParse> {
        val found = LinkedHashMap<String, RepairedParse>()
        fun consider(list: List<BopomofoComposer.TouchPoint>, pen: Double) {
            val parsed = BopomofoComposer.parseSyllables(list)
            if (parsed.size != syllableCount) return
            val syls = parsed.map { it.text }
            if (!syls.all { isValidSyllable(it) }) return
            val key = syls.joinToString("-")
            val cur = found[key]
            if (cur == null || pen < cur.penalty) found[key] = RepairedParse(syls, parsed.map { it.touches }, pen)
        }
        // 個人慣性打錯：整個音節直接換成以前選過的正確讀音（ㄉㄨㄠ → ㄉㄧㄠ），不受鄰鍵距離限制
        val parsedNow = BopomofoComposer.parseSyllables(points, keepIncompatible = merged)
        if (parsedNow.size == syllableCount) {
            for ((idx, ps) in parsedNow.withIndex()) {
                val tl = toToneless(ps.text)
                val tone = ps.text.substring(tl.length.coerceAtMost(ps.text.length)).filter { BopomofoComposer.TONES.contains(it) }
                for ((intended, cnt) in typoFixes(tl)) {
                    val syls = parsedNow.map { it.text }.toMutableList()
                    syls[idx] = intended + tone
                    if (!syls.all { isValidSyllable(it) }) continue
                    val key = syls.joinToString("-")
                    val pen = typoPenalty(cnt, isValidSyllable(tl))
                    val cur = found[key]
                    if (cur == null || pen < cur.penalty) found[key] = RepairedParse(syls, parsedNow.map { it.touches }, pen)
                }
            }
        }
        // 不拆開的切法：只修拼不起來的那幾個音節裡的鍵（不能靠改別的鍵湊出音節數，例如把後面的 ㄗ 改成聲調）
        val fixable: Set<Int>? = if (!merged) null else {
            val out = HashSet<Int>()
            for (ps in parsedNow) {
                if (isValidSyllable(ps.text)) continue
                for (t in ps.touches.values) points.indexOfFirst { it === t }.takeIf { it >= 0 }?.let { out.add(it) }
            }
            out
        }
        for (i in points.indices) {
            val p = points[i]
            if (BopomofoComposer.TONES.contains(p.char)) continue
            if (fixable != null && i !in fixable) continue
            for ((nb, pen) in neighborKeys(p)) {
                val copy = points.toMutableList()
                copy[i] = p.copy(char = nb)
                consider(copy, pen)
            }
            val dropped = points.toMutableList()
            dropped.removeAt(i)
            consider(dropped, 1.4)
        }
        return found.values.sortedBy { it.penalty }.take(5)
    }


    /**
     * 產生台灣注音常見混淆／實體按錯鍵之容錯讀音變體
     */
    private fun generateFuzzyVariants(tonelessKey: String): List<String> {
        val variants = LinkedHashSet<String>()

        // 0. 常用口語/台灣打字省略聲母與詞尾簡拼容錯 (例如：ㄟ-ㄨㄣ-ㄊ -> ㄇㄟ-ㄨㄣ-ㄊㄧ)
        if (tonelessKey.startsWith("ㄟ-")) variants.add("ㄇㄟ-" + tonelessKey.substring(2))
        if (tonelessKey.startsWith("ㄠ-")) variants.add("ㄏㄠ-" + tonelessKey.substring(2))
        if (tonelessKey.startsWith("ㄢ-")) variants.add("ㄍㄢ-" + tonelessKey.substring(2))
        if (tonelessKey.endsWith("-ㄊ")) variants.add(tonelessKey.substring(0, tonelessKey.length - 1) + "ㄊㄧ")
        if (tonelessKey.endsWith("-ㄒ")) variants.add(tonelessKey.substring(0, tonelessKey.length - 1) + "ㄒㄧㄠ")
        if (tonelessKey.endsWith("-ㄇ")) variants.add(tonelessKey.substring(0, tonelessKey.length - 1) + "ㄇㄚ")
        if (tonelessKey.endsWith("-ㄅ")) variants.add(tonelessKey.substring(0, tonelessKey.length - 1) + "ㄅㄨ")
        if (tonelessKey.endsWith("-ㄉ")) variants.add(tonelessKey.substring(0, tonelessKey.length - 1) + "ㄉㄜ")

        // 1. 台灣常見發音易混淆互換（捲舌/平舌、前後鼻音、相似韻母、相似聲母）
        if (tonelessKey.contains('ㄕ')) variants.add(tonelessKey.replace('ㄕ', 'ㄙ'))
        if (tonelessKey.contains('ㄙ')) variants.add(tonelessKey.replace('ㄙ', 'ㄕ'))
        if (tonelessKey.contains('ㄓ')) variants.add(tonelessKey.replace('ㄓ', 'ㄗ'))
        if (tonelessKey.contains('ㄗ')) variants.add(tonelessKey.replace('ㄗ', 'ㄓ'))
        if (tonelessKey.contains('ㄔ')) variants.add(tonelessKey.replace('ㄔ', 'ㄘ'))
        if (tonelessKey.contains('ㄘ')) variants.add(tonelessKey.replace('ㄘ', 'ㄔ'))

        // 前後鼻音: ㄣ↔ㄥ, ㄢ↔ㄤ
        if (tonelessKey.contains('ㄣ')) variants.add(tonelessKey.replace('ㄣ', 'ㄥ'))
        if (tonelessKey.contains('ㄥ')) variants.add(tonelessKey.replace('ㄥ', 'ㄣ'))
        if (tonelessKey.contains('ㄢ')) variants.add(tonelessKey.replace('ㄢ', 'ㄤ'))
        if (tonelessKey.contains('ㄤ')) variants.add(tonelessKey.replace('ㄤ', 'ㄢ'))

        // 相似韻母: ㄛ↔ㄡ, ㄜ↔ㄝ, ㄛ↔ㄜ
        if (tonelessKey.contains('ㄛ')) variants.add(tonelessKey.replace('ㄛ', 'ㄡ'))
        if (tonelessKey.contains('ㄡ')) variants.add(tonelessKey.replace('ㄡ', 'ㄛ'))
        if (tonelessKey.contains('ㄜ')) variants.add(tonelessKey.replace('ㄜ', 'ㄝ'))
        if (tonelessKey.contains('ㄝ')) variants.add(tonelessKey.replace('ㄝ', 'ㄜ'))
        if (tonelessKey.contains('ㄛ')) variants.add(tonelessKey.replace('ㄛ', 'ㄜ'))
        if (tonelessKey.contains('ㄜ')) variants.add(tonelessKey.replace('ㄜ', 'ㄛ'))

        // 邊音/捲舌: ㄌ↔ㄖ
        if (tonelessKey.contains('ㄌ')) variants.add(tonelessKey.replace('ㄌ', 'ㄖ'))
        if (tonelessKey.contains('ㄖ')) variants.add(tonelessKey.replace('ㄖ', 'ㄌ'))

        // 介音混淆: ㄧ↔ㄩ
        if (tonelessKey.contains('ㄧ')) variants.add(tonelessKey.replace('ㄧ', 'ㄩ'))
        if (tonelessKey.contains('ㄩ')) variants.add(tonelessKey.replace('ㄩ', 'ㄧ'))

        // 聲母相近: ㄅ↔ㄆ, ㄉ↔ㄊ, ㄍ↔ㄎ, ㄐ↔ㄑ
        if (tonelessKey.contains('ㄅ')) variants.add(tonelessKey.replace('ㄅ', 'ㄆ'))
        if (tonelessKey.contains('ㄆ')) variants.add(tonelessKey.replace('ㄆ', 'ㄅ'))
        if (tonelessKey.contains('ㄉ')) variants.add(tonelessKey.replace('ㄉ', 'ㄊ'))
        if (tonelessKey.contains('ㄊ')) variants.add(tonelessKey.replace('ㄊ', 'ㄉ'))
        if (tonelessKey.contains('ㄍ')) variants.add(tonelessKey.replace('ㄍ', 'ㄎ'))
        if (tonelessKey.contains('ㄎ')) variants.add(tonelessKey.replace('ㄎ', 'ㄍ'))
        if (tonelessKey.contains('ㄐ')) variants.add(tonelessKey.replace('ㄐ', 'ㄑ'))
        if (tonelessKey.contains('ㄑ')) variants.add(tonelessKey.replace('ㄑ', 'ㄐ'))

        // 2. 大千注音鍵盤實體物理鄰近按鍵容錯（首鍵、各音節首字母或各鍵手滑按錯）
        val chars = tonelessKey.toCharArray()
        for (i in chars.indices) {
            val c = chars[i]
            val neighbors = NEAR_KEYS[c] ?: continue
            for (neighbor in neighbors) {
                val copy = chars.clone()
                copy[i] = neighbor
                variants.add(String(copy))
                if (variants.size >= 24) break
            }
            if (variants.size >= 24) break
        }

        return variants.toList()
    }

    data class LatticeResult(
        val fullSentence: String,
        val dpWords: List<String>,
        val spanPrefixWords: List<String>,
        val alternatives: List<String> = emptyList(),
        val bestScore: Double = Double.NEGATIVE_INFINITY,
        /** 前幾名整句與分數（排序用的分數，同 bestScore 的尺度） */
        val scored: List<Pair<String, Double>> = emptyList()
    )

    // ---------- 靜態 Bigram（詞與詞搭配機率，ln P(next | prev)） ----------
    private val bigramCache = LruCache<String, Map<String, Double>>(600)

    /** SELECT next, MAX(score) FROM bigram WHERE prev = ? AND source IN (開著的來源) GROUP BY next */
    private fun bigramsFor(prev: String): Map<String, Double> {
        bigramCache.get(prev)?.let { return it }
        val map = HashMap<String, Double>()
        // 多個來源都有同一組搭配時取最強的那個
        for (t in data.scoreBigrams) {
            t.forEach(prev) { next, score -> if (score > (map[next] ?: Double.NEGATIVE_INFINITY)) map[next] = score }
        }
        bigramCache.put(prev, map)
        return map
    }

    /**
     * 學過、但詞庫沒有的「片段」：整句送出時學到的相鄰幾個詞（現在打＝現在＋打、講你就＝講＋你＋就），拆成詞庫的詞。
     * 詞庫有的詞、拆不出來的、或只能拆成兩個單字的（小蠍＝小＋蠍，多半是自己造的新詞）回傳 null。
     * 片段在整句網格裡的長詞加分照拆出來的詞算、上文照最後一個詞算：不然學過一次的「現在打」整塊拿三個字的長詞加分，
     * 蓋過「現在／打字」，「打」後面接什麼的接續詞也用不上
     */
    private fun chunkParts(word: String): List<String>? {
        chunkCache.get(word)?.let { return it.ifEmpty { null } }
        val parts = splitChunk(word)
        chunkCache.put(word, parts ?: emptyList())
        return parts
    }

    private fun splitChunk(word: String): List<String>? {
        val n = word.length
        if (n < 2 || !isHanWord(word) || words.hasWord(word)) return null
        // 段數最少的切法
        val best = IntArray(n + 1) { Int.MAX_VALUE }
        val back = IntArray(n + 1)
        best[0] = 0
        for (i in 0 until n) {
            if (best[i] == Int.MAX_VALUE) continue
            for (l in 1..minOf(6, n - i)) {
                if (l == n || best[i] + 1 >= best[i + l]) continue
                if (words.hasWord(word.substring(i, i + l))) {
                    best[i + l] = best[i] + 1
                    back[i + l] = i
                }
            }
        }
        if (best[n] == Int.MAX_VALUE) return null
        val parts = ArrayList<String>()
        var j = n
        while (j > 0) {
            parts.add(word.substring(back[j], j))
            j = back[j]
        }
        parts.reverse()
        return parts.takeIf { it.size >= 3 || it.any { p -> p.length >= 2 } }
    }

    /** 整句網格的長詞加分：多字詞每多一個字 2.8；片段照拆出來的詞算 */
    private fun lengthBonusOf(word: String, len: Int): Double =
        chunkParts(word)?.sumOf { (it.length - 1) * 2.8 } ?: ((len - 1) * 2.8)

    /**
     * 詞間轉移加權：前詞 prev 之後接 w 的條件機率 b 若比 w 自身單詞分數 s 高，代表搭配性強
     * 例如「載具」→「發票」：即使「在劇」「災區」單詞分數相近，也會因搭配而置頂
     * 搭配性弱的組合（「位置」→「不」、「不」→「排」）不加分，否則拆成單字反而能吃到較多轉移加分
     */
    private fun bigramBonus(prev: String, w: String, s: Double): Double {
        if (prev.isEmpty()) return 0.0
        val b = bigramsFor(prev)[w] ?: return 0.0
        return (b - s + 1.0).coerceIn(0.0, 8.0)
    }

    private fun solveLatticeViterbi(
        syllables: List<String>,
        touchesPerSyllable: List<Map<Char, BopomofoComposer.TouchPoint>> = emptyList(),
        prevWord: String? = null,
        prevWord2: String? = null
    ): LatticeResult = solveLatticeViterbiImpl(syllables, touchesPerSyllable, prevWord, prevWord2)

    private fun solveLatticeViterbiImpl(
        syllables: List<String>,
        touchesPerSyllable: List<Map<Char, BopomofoComposer.TouchPoint>>,
        prevWord: String?,
        prevWord2: String?
    ): LatticeResult {
        val n = syllables.size
        val now = System.currentTimeMillis()
        if (words.tables.isEmpty()) return LatticeResult("", emptyList(), emptyList())

        // corr：這條路徑上「把打的注音改成別的」的懲罰合計（見 SyllableVariant.corrective）
        data class Node(val score: Double, val text: String, val last: String, val corr: Double = 0.0)
        val beam = 6
        val dp = Array(n + 1) { mutableListOf<Node>() }
        // 每個位置「整句文字 → 在 dp 裡的位置」：同一句話只留最高分，不必每次從頭比對字串
        val dpIndex = Array(n + 1) { HashMap<String, Int>() }
        dp[0].add(Node(0.0, "", prevWord.orEmpty()))

        val variantsPerPos = syllables.mapIndexed { idx, syl ->
            getWeightedSyllableVariants(syl, touchesPerSyllable.getOrNull(idx) ?: emptyMap())
        }
        val correctivePerPos = variantsPerPos.map { vs -> vs.filter { it.corrective }.associate { it.text to it.penalty } }
        val spanWords = mutableListOf<String>()

        for (i in 0 until n) {
            if (dp[i].isEmpty()) continue
            if (dp[i].size > beam) {
                val top = dp[i].sortedByDescending { it.score }.take(beam)
                dp[i].clear()
                dp[i].addAll(top)
            }
            val maxLen = minOf(6, n - i)
            val combosByLen = spanCombos(variantsPerPos, correctivePerPos, i, maxLen, n)
            for (len in 1..maxLen) {
                val subCombos = combosByLen[len]
                val spanTyped = syllables.subList(i, i + len)

                val spanCands = HashMap<String, Double>()
                val spanCorr = HashMap<String, Double>()

                val spanTouches = if (touchesPerSyllable.size >= i + len) touchesPerSyllable.subList(i, i + len) else emptyList()
                for (combo in subCombos) {
                    val key = combo.key()
                    val totalPenalty = combo.penalty
                    val corrPenalty = combo.corr
                    val top = spanLookup(key, spanTyped, spanTouches, len, if (len == n) FULL_SPAN_WORDS else 4, now)

                    for ((idx, hit) in top.withIndex()) {
                        val adj = hit.score - (totalPenalty * 1.8)
                        val cur = spanCands[hit.word]
                        if (cur == null || adj > cur) {
                            spanCands[hit.word] = adj
                            spanCorr[hit.word] = corrPenalty
                        }
                        if (idx == 0 && i == 0 && len >= 2 && totalPenalty <= 1.2 && !spanWords.contains(hit.word)) {
                            spanWords.add(hit.word)
                        }
                    }
                }

                // 區間「混合簡拼」查詢 (例如 ㄨㄛ-ㄐ-ㄉ -> 我覺得)
                // 簡拼的那個音節打了聲調（ㄗˇ）也要合：聲調不合一樣扣 tonePenalty
                if (len >= 2 && spanTyped.any { toToneless(it).let { t -> t.length == 1 && BopomofoComposer.INITIALS.contains(t[0]) } }) {
                    var first = true
                    for (h in spanLookup(toToneless(spanTyped.joinToString("-")), spanTyped, spanTouches, len, 3, now)) {
                        val w = h.word
                        val initScore = h.score
                        val cur = spanCands[w]
                        if (cur == null || initScore > cur) {
                            spanCands[w] = initScore
                            spanCorr[w] = 0.0
                        }
                        if (first && i == 0 && !spanWords.contains(w)) spanWords.add(w)
                        first = false
                    }
                }

                if (spanCands.isEmpty()) continue
                val bestCands = spanCands.entries.sortedByDescending { it.value }.take(if (len == n) FULL_SPAN_WORDS else 4)
                val target = dp[i + len]
                val targetIndex = dpIndex[i + len]
                for (node in dp[i]) {
                    val bbs = contextBonuses(node.last, if (i == 0) prevWord2 else null, bestCands, now)
                    for ((k, e) in bestCands.withIndex()) {
                        val w = e.key
                        val sc = e.value
                        val bb = bbs[k]
                        val lengthBonus = if (len >= 2) lengthBonusOf(w, len) else 0.0
                        val total = node.score + sc + lengthBonus + bb
                        val text = node.text + w
                        val corr = node.corr + (spanCorr[w] ?: 0.0)
                        val existing = targetIndex[text]
                        if (existing != null) {
                            if (total > target[existing].score) target[existing] = Node(total, text, w, corr)
                        } else {
                            targetIndex[text] = target.size
                            target.add(Node(total, text, w, corr))
                        }
                    }
                }
            }
        }

        // 打對的注音優先：把打的注音改成別的（按錯鄰鍵、慣性打錯、模糊音）才拼出來的整句，
        // 要比照打的整句高出 LITERAL_MARGIN 才排前面；落點壓在兩鍵交界（改注音懲罰很小）照原本的分數比
        fun ranked(nd: Node) = nd.score - if (nd.corr >= STRONG_CORRECTION) LITERAL_MARGIN else 0.0
        val finals = dp[n].sortedByDescending { ranked(it) }
        val full = finals.firstOrNull()?.text ?: ""
        val alternatives = finals.drop(1).take(2).map { it.text }.filter { it != full }
        val dpWords = mutableListOf<String>()
        for (idx in (n - 1) downTo 2) {
            val t = dp[idx].maxByOrNull { it.score }?.text
            if (!t.isNullOrBlank() && !dpWords.contains(t) && t != full) {
                dpWords.add(t)
            }
        }

        return LatticeResult(
            full, dpWords, spanWords, alternatives, finals.firstOrNull()?.score ?: Double.NEGATIVE_INFINITY,
            finals.take(FULL_SPAN_WORDS).map { it.text to ranked(it) }
        )
    }

    class SpanHit(val word: String, val score: Double, val share: Double, val reading: String)

    /**
     * 一段讀音（toneless key，len 個音節）的候選詞與分數（log10）：
     * 分數＝這個讀音的字頻總和 × 個人化後「這個讀音裡選這個詞」的比例，再扣聲調不合（見 tonePenalty）。
     * 比例照你在這個讀音選過各詞的次數，跟字典的比例混合：(選過次數 + MU_READING × 字典比例) / (這讀音總共選過 + MU_READING)。
     * 沒學過時就是字典分數；在 ㄏㄠˋ 選過一次「皓」，「皓」占三分之一，進前三；選兩次占一半，排第一。
     * 只在同一個讀音裡互相比，「來」選得再多也不會影響 ㄌㄢˊ 的「欄」
     */
    private fun spanLookup(
        key: String, typed: List<String>, touches: List<Map<Char, BopomofoComposer.TouchPoint>>, len: Int, limit: Int, now: Long
    ): List<SpanHit> {
        val base = spanCache.get(key) ?: spanBase(key, len, now).also { spanCache.put(key, it) }
        val out = ArrayList<SpanHit>(base.size)
        for (h in base) out.add(SpanHit(h.word, h.score - tonePenalty(typed, touches, h.reading), h.share, h.reading))
        out.sortByDescending { it.score }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    /**
     * 有音節只打聲母（簡拼，ㄋㄚ-ㄐ、ㄏ-ㄏ-ㄎ）時，同聲母開頭的詞也算這段讀音的字（字典分數少 ABBREV_COST），
     * 學過的詞也照簡拼找（學的時候存的是完整讀音），個人比例才跟沒簡拼時一樣算得到
     */
    private fun spanBase(key: String, len: Int, now: Long): List<SpanHit> {
        val syls = key.split("-")
        val abbrev = len >= 2 && syls.any { it.length == 1 && BopomofoComposer.INITIALS.contains(it[0]) }
        val like = if (abbrev) Like(abbrevPattern(syls)) else null
        val dict = LinkedHashMap<String, WordHit>()
        for (h in words.byToneless(key, 12)) if (!h.word.startsWith("MACRO@") && h.word !in dict) dict[h.word] = h
        if (abbrev) for (h in abbrevHits(syls, 8)) if (h.word !in dict) dict[h.word] = WordHit(h.word, h.score - ABBREV_COST, h.reading)
        val picks = HashMap<String, Double>()
        val pickedReading = HashMap<String, String>()
        var total = 0.0
        val learned = if (abbrev) user.matches(key, key, 24) + user.initialsLike(getInitialsFromReading(key), abbrevPattern(syls), 24) else user.matches(key, key, 24)
        for (u in learned) {
            if (!isHanWord(u.word) || hanLength(u.word) != len || u.word in pickedReading) continue
            val c = decayedCount(timesChosen(u.count), u.lastUsed, now)
            if (c <= 0.0) continue
            picks[u.word] = c
            pickedReading[u.word] = u.reading
            total += c
        }
        // 學過、但字典裡排不進前幾名的字（ㄏㄠˋ 的「皓」）：查它自己的字典分數
        for (w in picks.keys) if (w !in dict) {
            val h = words.byWord(w, 8).firstOrNull { toToneless(it.reading).let { r -> r == key || like?.matches(r) == true } } ?: continue
            dict[w] = if (toToneless(h.reading) == key) h else WordHit(w, h.score - ABBREV_COST, h.reading)
        }
        var mass = 0.0
        for (h in dict.values) mass += 10.0.pow(h.score)
        if (mass <= 0.0) mass = USER_READING_MASS
        val logMass = log10(mass)
        val out = ArrayList<SpanHit>(dict.size + picks.size)
        for (w in dict.keys + picks.keys.filter { it !in dict }) {
            val pDict = dict[w]?.let { 10.0.pow(it.score) / mass } ?: 0.0
            val share = if (total > 0.0) ((picks[w] ?: 0.0) + MU_READING * pDict) / (total + MU_READING) else pDict
            if (share <= 0.0) continue
            val logShare = log10(share)
            val r = dict[w]?.reading ?: pickedReading[w] ?: continue
            out.add(SpanHit(w, logMass + logShare, logShare, r))
        }
        return out
    }

    /**
     * 打了聲調、但跟這個讀音的聲調不同：落點靠近對的聲調鍵（ˇ 按在靠 ˋ 的那一邊，鄰鍵懲罰 ≤ NEIGHBOR_ALT_MAX）照鄰鍵懲罰算，
     * 按在聲調鍵中央或差很遠就扣 TONE_MISMATCH（一聲用空白鍵打，沒有落點可看）
     */
    private fun tonePenalty(typed: List<String>, touches: List<Map<Char, BopomofoComposer.TouchPoint>>, reading: String): Double {
        val rs = reading.split("-")
        if (rs.size != typed.size) return 0.0
        var pen = 0.0
        for (i in typed.indices) {
            val t = typedToneOf(typed[i]) ?: continue
            val d = dictToneOf(rs[i])
            if (t == d) continue
            val tap = touches.getOrNull(i)?.get(t)
            val near = if (tap == null || t == BopomofoComposer.FIRST_TONE) null else neighborKeys(tap).firstOrNull { it.first == d }?.second
            pen += if (near == null || near > NEIGHBOR_ALT_MAX) TONE_MISMATCH else near * 1.8
        }
        return pen
    }

    /**
     * 同一段的候選詞（cands：詞 → 分數）各自的上文加分（log10）：字典的接續詞（bigramBonus），再照個人搭配調整。
     * 個人搭配同 spanLookup：在 prev 後面、這幾個詞裡選各詞的次數，跟沒有個人搭配時的比例混合（MU_CONTEXT），
     * 前兩個詞都一樣（三詞搭配）時再混一層。比例是整組一起算的：在「通知」後面選過「欄」，「欄」加分、「來」同時扣分
     */
    private fun contextBonuses(prev: String, prev2: String?, cands: List<Map.Entry<String, Double>>, now: Long): DoubleArray {
        val n = cands.size
        if (prev.isEmpty() || n == 0) return DoubleArray(n)
        // 字典的接續詞：前面是片段就看它最後一個詞；候選是片段不加（片段的分數不是一個詞的字頻，比不出搭配強不強）
        val prevWord = chunkParts(prev)?.last() ?: prev
        val static = DoubleArray(n) { if (chunkParts(cands[it].key) != null) 0.0 else bigramBonus(prevWord, cands[it].key, cands[it].value) }
        val top = (0 until n).maxOf { cands[it].value + static[it] }
        val prior = DoubleArray(n) { 10.0.pow(cands[it].value + static[it] - top) }
        val z = prior.sum()
        for (k in 0 until n) prior[k] /= z
        fun mix(pairs: List<UserStore.Pair2>, p: DoubleArray, trigram: Boolean): DoubleArray {
            val c = DoubleArray(n)
            for (q in pairs) {
                val k = cands.indexOfFirst { it.key == q.next }
                if (k < 0) continue
                c[k] += if (trigram) decayedCount(q.count, q.lastUsed, now) else decayedCount(timesChosen(q.count), q.lastUsed, now)
            }
            val all = c.sum()
            return if (all <= 0.0) p else DoubleArray(n) { (c[it] + MU_CONTEXT * p[it]) / (all + MU_CONTEXT) }
        }
        var p = mix(user.nextAll(prev), prior, trigram = false)
        if (!prev2.isNullOrEmpty()) p = mix(user.nextAfter(prev2, prev, 50), p, trigram = true)
        return DoubleArray(n) { static[it] + log10(p[it] / prior[it]) }
    }

    /** 簡拼（有音節只打聲母，ㄨㄛ-ㄐ-ㄉ）查得到的詞；沒有單打聲母的音節就是空的 */
    private fun abbrevHits(subSyls: List<String>, limit: Int): List<WordHit> {
        if (subSyls.none { it.length == 1 && BopomofoComposer.INITIALS.contains(it[0]) }) return emptyList()
        val initKey1 = subSyls.map { syl -> if (syl.isNotEmpty() && BopomofoComposer.INITIALS.contains(syl[0])) syl[0].toString() else "_" }.joinToString("-")
        val initKey2 = subSyls.map { syl -> if (syl.isNotEmpty() && BopomofoComposer.INITIALS.contains(syl[0])) syl[0].toString() else "" }.joinToString("-")
        val initKey3 = subSyls.mapNotNull { it.firstOrNull()?.toString() }.joinToString("-")
        return words.byInitialsLike(listOf(initKey1, initKey2, initKey3), abbrevPattern(subSyls), limit).filter { !it.word.startsWith("MACRO@") }
    }

    /** 簡拼的比對樣式：單打聲母的音節比對該聲母開頭的任何音節（ㄐ → ㄐ%） */
    private fun abbrevPattern(subSyls: List<String>) = subSyls.joinToString("-") { syl ->
        if (syl.length == 1 && BopomofoComposer.INITIALS.contains(syl[0])) "$syl%" else syl
    }


    /** corrective：把打的注音改成別的（按錯鄰鍵、慣性打錯、模糊音）；補介音、簡拼展開不算 */
    data class SyllableVariant(val text: String, val penalty: Double, val corrective: Boolean = false)

    private fun getWeightedSyllableVariants(
        rawSyllable: String,
        touches: Map<Char, BopomofoComposer.TouchPoint> = emptyMap()
    ): List<SyllableVariant> {
        val s = toToneless(rawSyllable)
        val map = LinkedHashMap<String, Double>()
        val corrective = HashSet<String>()

        fun putMin(txt: String, pen: Double, corr: Boolean = false) {
            if (txt.isBlank()) return
            val cur = map[txt]
            if (cur == null || pen < cur) {
                map[txt] = pen
                if (corr) corrective.add(txt) else corrective.remove(txt)
            }
        }

        // (A) 原始無聲調音節 s
        putMin(s, 0.0)

        // (B) 雙碼音節漏打介音展開（嚴格限制國語語音規則相容組合，避免 ㄏㄣ 被篡改為 ㄏㄨㄣ 混）
        if (s.length == 2 && BopomofoComposer.INITIALS.contains(s[0]) && BopomofoComposer.FINALS.contains(s[1])) {
            val init = s[0]
            val fin = s[1]
            if (init == 'ㄐ' || init == 'ㄑ' || init == 'ㄒ') {
                putMin("${init}ㄧ$fin", 0.25)
                putMin("${init}ㄩ$fin", 0.35)
            } else if (fin == 'ㄛ' && !setOf('ㄅ', 'ㄆ', 'ㄇ', 'ㄈ').contains(init)) {
                putMin("${init}ㄨ$fin", 0.35)
            } else if (fin == 'ㄟ' && setOf('ㄊ', 'ㄘ', 'ㄙ', 'ㄓ', 'ㄔ', 'ㄖ', 'ㄎ').contains(init)) {
                putMin("${init}ㄨ$fin", 0.35)
            }
        }

        // (C) 簡拼展開（鍵盤設定的 lazySyllables）
        ImeConfig.get(context).lazySyllables[s]?.forEachIndexed { index, exp ->
            putMin(exp, 0.45 + index * 0.05)
        }

        // (D) 讀音混用（前後鼻音、平捲舌、ㄛ／ㄜ）：懲罰照你自己選字的習慣（見 FuzzySounds）
        for ((v, pen) in fuzzy.variants(s)) putMin(v, pen, corr = true)

        // (D2) 個人慣性打錯（以前打 s 卻選了 intended 讀音的字）
        for ((intended, cnt) in typoFixes(s)) putMin(intended, typoPenalty(cnt, isValidSyllable(s)), corr = !isTypoHabit(s, intended, cnt))

        // (E) 鄰鍵手滑容錯：一律納入實體相鄰鍵，觸控落點只調整各方向的可能性（偏向哪邊，哪邊的鍵懲罰越低）
        for (i in s.indices) {
            val c = s[i]
            val touch = touches[c] ?: BopomofoComposer.TouchPoint(c)
            for ((nb, pen) in neighborKeys(touch)) {
                if (BopomofoComposer.TONES.contains(nb)) continue
                putMin(s.substring(0, i) + nb + s.substring(i + 1), pen, corr = true)
            }
        }

        return map.entries.sortedBy { it.value }.take(14).map { SyllableVariant(it.key, it.value, it.key in corrective) }
    }

    /**
     * Viterbi 一個區間的注音組合：penalty＝變體懲罰合計、corr＝改注音懲罰合計；
     * 讀音（以 - 串起來）由前一段＋這一段的音節組成，排進前幾名、真的要查詞庫時才組字串
     */
    private val SPAN_BY_PENALTY = Comparator<SpanCombo> { a, b -> a.penalty.compareTo(b.penalty) }

    private class SpanCombo(val parent: SpanCombo?, private val text: String, val penalty: Double, val corr: Double) {
        private var keyCache: String? = null
        /** 讀音是字典裡的詞或詞的開頭（null＝還沒查） */
        var wordish: Boolean? = null
        /** 依懲罰排不進前幾名、因為拼得成詞才另外留下來的 */
        var extra = false

        fun key(): String {
            keyCache?.let { return it }
            val k = if (parent == null) text else parent.key() + "-" + text
            keyCache = k
            return k
        }

        /** 從第一個音節到這一個的清單（ArrayList：之後比對清單相不相等時走它不建物件的快速路徑） */
        fun texts(): List<String> {
            val out = ArrayList<String>()
            var c: SpanCombo? = this
            while (c != null) { out.add(c.text); c = c.parent }
            out.reverse()
            return out
        }
    }

    /**
     * 從位置 i 起、長度 1..maxLen 的注音組合，結果跟各長度分別呼叫 generateWeightedSubCombos 一樣
     * （每一步都依懲罰穩定排序、只留前 beamWidth 個）。寬度只看長度（1 → 10、整句 → 32、其他 18），
     * 同寬度的前幾步完全相同：算一次往下延伸，不必每個長度從頭組、每一步複製一份清單
     */
    private fun spanCombos(
        variants: List<List<SyllableVariant>>,
        corrective: List<Map<String, Double>>,
        i: Int,
        maxLen: Int,
        n: Int
    ): Array<List<SpanCombo>> {
        // 依懲罰只留前 width 個時，改兩個以上注音才拼得出的詞（ㄘㄣ-ㄋㄜ 要改成 ㄕㄣ-ㄇㄜ 才是「什麼」）會排在一堆不成詞的組合後面、
        // 還沒查字典就被丟掉：被丟掉的裡面，懲罰不太大、而且還拼得成字典裡的詞（或詞的開頭）的另外留下來。
        // 另外留的只拿來湊三個音節以上的詞（兩個音節改兩個鍵證據太弱：ㄖㄣㄇ 會冒出「什麼」、ㄔㄣㄐ 冒出「真假」）
        fun isExtra(c: SpanCombo): Boolean = c.extra || (c.parent?.let { isExtra(it) } ?: false)
        fun wordish(c: SpanCombo): Boolean {
            c.wordish?.let { return it }
            val p = c.parent
            val v = (p == null || wordish(p)) && words.isWordOrPrefix(c.key())
            c.wordish = v
            return v
        }
        fun extend(beam: List<SpanCombo>?, pos: Int, width: Int): List<SpanCombo> {
            val list = variants[pos]
            val corrMap = corrective[pos]
            val next = ArrayList<SpanCombo>((beam?.size ?: 1) * list.size)
            if (beam == null) {
                for (v in list) next.add(SpanCombo(null, v.text, 0.0 + v.penalty, 0.0 + (corrMap[v.text] ?: 0.0)))
            } else {
                for (c in beam) for (v in list) next.add(SpanCombo(c, v.text, c.penalty + v.penalty, c.corr + (corrMap[v.text] ?: 0.0)))
            }
            // 穩定排序、同 sortBy { it.penalty }，但比較時不把 Double 包成物件
            next.sortWith(SPAN_BY_PENALTY)
            if (next.size <= width) return next
            val kept = ArrayList(next.subList(0, width))
            if (beam != null) {
                var extra = 0
                for (k in width until next.size) {
                    val c = next[k]
                    if (c.penalty > WORD_KEEP_MAX_PENALTY) break
                    if (wordish(c)) {
                        c.extra = true
                        kept.add(c)
                        if (++extra >= WORD_KEEP_EXTRA) break
                    }
                }
            }
            return kept
        }
        val out = arrayOfNulls<List<SpanCombo>>(maxLen + 1)
        var beam: List<SpanCombo>? = null
        for (len in 1..maxLen) {
            beam = extend(beam, i + len - 1, 18)
            when {
                len == 1 -> out[1] = if (beam.size > 10) beam.subList(0, 10) else beam
                len == 2 -> out[2] = beam.filter { !isExtra(it) }
                len != n -> out[len] = beam
            }
        }
        // 整句（只有從頭開始、整句不超過 6 個音節時）用較寬的 32
        if (maxLen == n && n >= 2) {
            var wide: List<SpanCombo>? = null
            for (len in 1..n) wide = extend(wide, i + len - 1, 32)
            out[n] = if (n == 2) wide!!.filter { !isExtra(it) } else wide
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<List<SpanCombo>>
    }

    private fun generateWeightedSubCombos(
        lists: List<List<SyllableVariant>>,
        beamWidth: Int
    ): List<Pair<List<String>, Double>> {
        // 每一步只記「前一段＋這個音節」，留到最後的幾個才組成清單（不必每一步都複製整串音節）；
        // 依懲罰穩定排序、只留前 beamWidth 個，結果跟逐步複製清單的寫法一樣
        var beam: List<SpanCombo>? = null
        for (list in lists) {
            val next = ArrayList<SpanCombo>((beam?.size ?: 1) * list.size)
            if (beam == null) {
                for (v in list) next.add(SpanCombo(null, v.text, 0.0 + v.penalty, 0.0))
            } else {
                for (c in beam) for (v in list) next.add(SpanCombo(c, v.text, c.penalty + v.penalty, 0.0))
            }
            next.sortWith(SPAN_BY_PENALTY)
            beam = if (next.size > beamWidth) next.subList(0, beamWidth) else next
        }
        return beam?.map { it.texts() to it.penalty } ?: listOf(emptyList<String>() to 0.0)
    }

    /**
     * Lattice Viterbi 演算法：支援免聲調、帶權重 Beam Search、混合簡拼與長詞強化之網格解算
     */

    private fun solveViterbiSentence(syllables: List<String>): String {
        return solveLatticeViterbi(syllables).fullSentence
    }

    suspend fun recordSelection(reading: String, word: String) = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext
        if (!isLearnable(reading, word)) {
            Log.i(TAG, "略過學習（讀音音節數與字數不符或非漢字）")
            return@withContext
        }
        try {
            val reading = canonicalReading(reading, word)
            val toneless = toToneless(reading)
            val initials = getInitialsFromReading(reading)
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put("reading", reading)
                put("toneless", toneless)
                put("initials", initials)
                put("word", word)
                put("last_used", now)
            }
            database.insertWithOnConflict(
                "user_dict",
                null,
                cv,
                SQLiteDatabase.CONFLICT_IGNORE
            )
            database.execSQL(
                "UPDATE user_dict SET count = count + 1, last_used = ?, toneless = ?, initials = ? WHERE (reading = ? OR toneless = ?) AND word = ?",
                arrayOf(now, toneless, initials, reading, toneless, word)
            )
            // 寫進 SQLite 之後，把這個詞的列讀回記憶體鏡像
            user.reloadWord(database, word)
            candidatesCache.evictAll()
            spanCache.evictAll()
            userOnlyCache.evictAll()
            Log.i(TAG, "智慧學習新紀錄（${word.length} 字）")
        } catch (e: Exception) {
            Log.e(TAG, "更新 user_dict 失敗", e)
        }
    }

    suspend fun recordBigram(prevWord: String, nextWord: String) = withContext(Dispatchers.IO) {
        if (prevWord.isBlank() || nextWord.isBlank()) return@withContext
        val database = db ?: return@withContext
        try {
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put("prev_word", prevWord)
                put("next_word", nextWord)
                put("last_used", now)
            }
            database.insertWithOnConflict(
                "user_bigram",
                null,
                cv,
                SQLiteDatabase.CONFLICT_IGNORE
            )
            database.execSQL(
                "UPDATE user_bigram SET count = count + 1, last_used = ? WHERE prev_word = ? AND next_word = ?",
                arrayOf(now, prevWord, nextWord)
            )
            user.reloadBigrams(database, prevWord)
            Log.i(TAG, "智慧學習一組關聯詞")
        } catch (e: Exception) {
            Log.e(TAG, "更新 user_bigram 失敗", e)
        }
        // 個人搭配變了：候選快取要重算
        candidatesCache.evictAll()
        spanCache.evictAll()
    }

    /** 連續打的三個詞（prev2 prev1 next）：前兩個詞都一樣時，下一個詞照學過的往前排 */
    suspend fun recordTrigram(prev2: String, prev1: String, next: String) = withContext(Dispatchers.IO) {
        if (prev2.isBlank() || prev1.isBlank() || next.isBlank()) return@withContext
        val database = db ?: return@withContext
        try {
            val now = System.currentTimeMillis()
            database.execSQL(
                "INSERT OR IGNORE INTO user_trigram (prev2, prev1, next_word, count, last_used) VALUES (?, ?, ?, 0, ?)",
                arrayOf(prev2, prev1, next, now)
            )
            database.execSQL(
                "UPDATE user_trigram SET count = count + 1, last_used = ? WHERE prev2 = ? AND prev1 = ? AND next_word = ?",
                arrayOf(now, prev2, prev1, next)
            )
            user.reloadTrigrams(database, prev2, prev1)
        } catch (e: Exception) {
            Log.e(TAG, "更新 user_trigram 失敗", e)
        }
        candidatesCache.evictAll()
        spanCache.evictAll()
    }

    // 每組輸入（上文＋讀音）被學習往前排的詞：使用者跳過它選了別的，就扣回學習次數
    private val promotedCache = LruCache<String, Set<String>>(300)

    private fun promotedKey(prevWord: String?, syllables: List<String>) = "${prevWord.orEmpty()}|" + toToneless(syllables.joinToString("-"))

    /**
     * 選字時排第一的是「因為學過才往前排」的詞，使用者卻選了同樣字數的別的詞：那個詞的學習次數（選字、搭配）各扣一次，
     * 扣到 0 就刪掉。選錯一次、只用一次的人名不會一直擋在前面
     */
    suspend fun noteSkippedLearned(prevWord: String?, syllables: List<String>, top: String, chosen: String) = withContext(Dispatchers.IO) {
        if (top == chosen || hanLength(top) != hanLength(chosen)) return@withContext
        if (promotedCache.get(promotedKey(prevWord, syllables))?.contains(top) != true) return@withContext
        unlearn(toToneless(syllables.joinToString("-")), top, prevWord)
        Log.i(TAG, "跳過學過的詞，扣回一次學習")
    }

    /** 把一次選字的學習扣回來（選字次數、前一個詞接它的搭配各減一，扣到一次都沒選過就刪掉；count 的算法見 timesChosen） */
    private fun unlearn(toneless: String, word: String, prevWord: String?) {
        val database = db ?: return
        try {
            database.execSQL("UPDATE user_dict SET count = count - 1 WHERE toneless = ? AND word = ?", arrayOf(toneless, word))
            database.execSQL("DELETE FROM user_dict WHERE toneless = ? AND word = ? AND count <= 1", arrayOf(toneless, word))
            user.reloadWord(database, word)
            if (!prevWord.isNullOrBlank()) {
                database.execSQL("UPDATE user_bigram SET count = count - 1 WHERE prev_word = ? AND next_word = ?", arrayOf(prevWord, word))
                database.execSQL("DELETE FROM user_bigram WHERE prev_word = ? AND next_word = ? AND count <= 1", arrayOf(prevWord, word))
                user.reloadBigrams(database, prevWord)
            }
        } catch (e: Exception) {
            Log.e(TAG, "扣回學習失敗", e)
        }
        candidatesCache.evictAll()
        spanCache.evictAll()
        userOnlyCache.evictAll()
    }

    /**
     * 送出一個詞之後整個刪掉、改打了另一個同樣字數的詞：
     * 1. 刪掉的那次選字不算數（扣回選字與搭配的學習）
     * 2. 注音不一樣時，拿刪掉那次的落點對新的詞學：只差一個注音、落點又靠近想打的鍵（按歪）的那一鍵才學進落點習慣，
     *    其他的只記慣性打錯（同 recordTouchSamples 的規則）；注音一樣只是選錯字就不學落點
     */
    suspend fun learnCommitRetype(
        oldWord: String,
        oldSyllables: List<String>,
        oldTouches: List<BopomofoComposer.TouchPoint>,
        prevWord: String?,
        newWord: String,
        newSyllables: List<String>
    ) {
        if (oldWord == newWord || hanLength(oldWord) != hanLength(newWord)) return
        withContext(Dispatchers.IO) { unlearn(toToneless(oldSyllables.joinToString("-")), oldWord, prevWord) }
        if (toToneless(oldSyllables.joinToString("-")) != toToneless(newSyllables.joinToString("-")) && oldTouches.isNotEmpty()) {
            recordTouchSamples(oldTouches, newWord)
        }
        Log.i(TAG, "送出後刪掉重打：扣回刪掉的那次學習")
    }

    /**
     * 從個人自主學習庫中徹底移除指定的自選或學習詞彙（長按候選字刪除）
     */
    suspend fun deleteUserWord(word: String): Boolean = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext false
        try {
            database.beginTransaction()
            try {
                database.delete("user_dict", "word = ?", arrayOf(word))
                database.delete("user_bigram", "prev_word = ? OR next_word = ?", arrayOf(word, word))
                database.delete("user_trigram", "prev2 = ? OR prev1 = ? OR next_word = ?", arrayOf(word, word, word))
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            user.forgetWord(word)
            candidatesCache.evictAll()
            spanCache.evictAll()
            userOnlyCache.evictAll()
            Log.i(TAG, "已自個人學習庫刪除一個詞")
            true
        } catch (e: Exception) {
            Log.e(TAG, "刪除個人學習詞彙失敗", e)
            false
        }
    }

    // ---------- 設定頁：學習統計與管理 ----------

    data class LearningStats(
        val words: Int,
        val userCreated: Int,
        val bigrams: Int,
        val englishWords: Int,
        val typos: Int,
        val touchSamples: Int
    )

    // ---------- 詞庫來源：附加詞庫、匯入詞庫、Emoji／顏文字 ----------

    /** 設定頁列出的一個詞庫 */
    data class DictionaryInfo(
        val id: String,
        val name: String,
        val description: String,
        val count: Int,
        val enabled: Boolean,
        /** words／bigram（同 Pack.group） */
        val group: String = "words",
        /** 來源與授權，例如「內政部全國路名資料（政府資料開放授權條款第 1 版）」；匯入的詞庫沒有 */
        val source: String? = null,
        /** 資料包版本（線上更新用），例如 2026.10.23 */
        val version: String? = null,
        /** 只能線上安裝的資料包還沒裝時是 false（清單上顯示下載大小） */
        val installed: Boolean = true,
        val downloadSize: Long = 0,
        /** 主詞庫不能關 */
        val toggleable: Boolean = true,
        /** 匯入的詞庫可以刪除 */
        val removable: Boolean = false
    )

    private fun addonEnabled(id: String) = sourcePrefs.getBoolean(id, packDefaultOn(id))

    /**
     * 預熱：每個詞的第一鍵（單一注音符號）要查「這個符號開頭的所有詞」再依分數排序，冷的時候要 30～60ms。
     * 背景先把 37 個符號各跑一次，結果留在字典查詢快取裡，打字時第一鍵就直接命中
     */
    private fun warmFirstKeys() {
        val t0 = System.nanoTime()
        try {
            kotlinx.coroutines.runBlocking {
                for (c in BopomofoComposer.INITIALS + BopomofoComposer.MEDIALS + BopomofoComposer.FINALS) {
                    getCandidateGroups(listOf(listOf(c.toString())), hasTone = false)
                }
            }
            candidatesCache.evictAll()  // 只留查詢快取；候選快取跟前一個詞有關，預熱的用不到
        } catch (e: Exception) {
            Log.w(TAG, "預熱失敗", e)
        }
        Log.i(TAG, "預熱第一鍵 ${(System.nanoTime() - t0) / 1_000_000}ms")
    }

    /**
     * 系統要回收記憶體時釋放快取：鍵盤收起（UI_HIDDEN）放掉候選快取與 SQLite 頁面快取；
     * 真的缺記憶體（RUNNING_LOW、BACKGROUND 以上）時全部清掉。行程越小越不容易被系統殺掉
     * （詞庫是 mmap 的唯讀檔案頁，系統自己會丟，不必處理）
     */
    fun trimMemory(level: Int) {
        candidatesCache.evictAll()
        spanCache.evictAll()
        SQLiteDatabase.releaseMemory()
        if (level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            data.words.clearCache()
            bigramCache.evictAll()
            userOnlyCache.evictAll()
        }
    }

    /**
     * 資料包預設開不開：已安裝的看檔案 meta 的 default；還沒安裝的（只能線上安裝的資料包）一律不開，
     * 免得每日更新自己把沒裝的資料包下載下來
     */
    private fun packDefaultOn(id: String): Boolean = loaded[id]?.meta?.optBoolean("default", true) ?: false

    /** 鍵盤設定換了新版（簡拼展開、顏文字對照、讀音混用預設）：清掉算好的候選 */
    fun onConfigChanged() = invalidateCandidateCaches()

    private fun invalidateCandidateCaches() {
        candidatesCache.evictAll()
        spanCache.evictAll()
        chunkCache.evictAll()
        userOnlyCache.evictAll()
        syllableFreqCache = null
    }

    /** 開關、安裝、匯入有變動：依目前的開關重組查詢對象 */
    fun syncDictionarySources() = remount(false)

    // ---------- 匯入的詞庫（原文存在 scorpio_imports.db，在手機上編成 SCD） ----------

    /** 匯入的詞庫（編號, 開著嗎），依匯入先後 */
    private fun importList(): List<Pair<Long, Boolean>> {
        val out = ArrayList<Pair<Long, Boolean>>()
        try {
            importDb?.rawQuery("SELECT id, enabled FROM import_dicts ORDER BY created, id", null)?.use { c ->
                while (c.moveToNext()) out.add(c.getLong(0) to (c.getInt(1) != 0))
            }
        } catch (e: Exception) {
            Log.e(TAG, "讀取匯入詞庫清單失敗", e)
        }
        return out
    }

    /**
     * 還沒編成 SCD 的匯入詞庫現在編（舊版升級、檔案被清掉時），已刪除的匯入詞庫留下的檔案刪掉。
     * 回傳檔案有沒有增減（要重新載入）
     */
    private fun compileMissingImports(): Boolean {
        val imports = importDb ?: return false
        var changed = false
        try {
            val dicts = ArrayList<Pair<Long, String>>()
            imports.rawQuery("SELECT id, name FROM import_dicts", null).use { c ->
                while (c.moveToNext()) dicts.add(c.getLong(0) to c.getString(1))
            }
            val known = dicts.map { it.first }.toSet()
            for ((id, name) in dicts) {
                if (files.importFile(id).exists()) continue
                val rows = ArrayList<ScdWriter.Word>()
                imports.rawQuery("SELECT word, reading, score FROM import_entries WHERE dict_id = ? ORDER BY rowid", arrayOf(id.toString())).use { c ->
                    while (c.moveToNext()) {
                        val reading = c.getString(1)
                        val parts = reading.split('-').map { splitSyllable(it) }
                        rows.add(ScdWriter.Word(
                            reading, parts.joinToString("-") { it.first }, c.getString(0), c.getDouble(2),
                            parts.joinToString("-") { it.second }, parts.joinToString("-") { it.third }
                        ))
                    }
                }
                files.writeImport(id, ScdWriter.words(mapOf("id" to "import:$id", "group" to "import", "name" to name, "version" to "1"), rows))
                changed = true
            }
            for (f in files.dir.listFiles { f -> f.name.startsWith("import_") && f.name.endsWith(".scd") }.orEmpty()) {
                val id = f.name.removePrefix("import_").removeSuffix(".scd").toLongOrNull()
                if (id == null || id !in known) {
                    f.delete()
                    changed = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "編譯匯入詞庫失敗", e)
        }
        return changed
    }

    /** 音節 → （去聲調、聲母、韻母），沒有聲母／韻母記成 _（同主詞庫格式） */
    private fun splitSyllable(s: String): Triple<String, String, String> {
        val t = s.trimEnd { it in TONE_CHARS }
        val ini = if (t.isNotEmpty() && t[0] in INITIAL_CHARS) t.substring(0, 1) else ""
        val fin = t.substring(ini.length)
        return Triple(t, ini.ifEmpty { "_" }, fin.ifEmpty { "_" })
    }

    /** available：manifest 裡的資料包，還沒安裝、只能線上安裝的也列出來（關著，顯示下載大小） */
    suspend fun dictionaries(available: List<PackUpdater.ManifestPack> = emptyList()): List<DictionaryInfo> = withContext(Dispatchers.IO) {
        val out = ArrayList<DictionaryInfo>()
        var kaomojiPack: Pack? = null
        for (p in packs()) {
            val source = "${p.source}（${p.license}）"
            val f = loaded[p.id]
            when {
                p.id == "core" -> out.add(
                    DictionaryInfo(
                        p.id, p.name, p.description, f?.rows("w") ?: 0,
                        enabled = true, source = source, version = p.version, toggleable = false
                    )
                )
                p.group == "kaomoji" -> kaomojiPack = p
                p.group == "bigram" -> out.add(
                    DictionaryInfo(
                        p.id, p.name, p.description, f?.rows("b") ?: 0,
                        addonEnabled(p.id), group = "bigram", source = source, version = p.version
                    )
                )
                else -> out.add(
                    DictionaryInfo(
                        p.id, p.name, p.description, f?.rows("w") ?: 0,
                        addonEnabled(p.id), source = source, version = p.version
                    )
                )
            }
        }
        val local = packs().map { it.id }.toSet()
        for (m in available) {
            if (m.id in local || m.system || m.group == "kaomoji" || m.group == "layout") continue
            out.add(
                DictionaryInfo(
                    m.id, m.name, m.description.ifEmpty { "線上詞庫" }, m.rows, enabled = false,
                    group = m.group, source = if (m.source.isNotEmpty()) "${m.source}（${m.license}）" else null,
                    version = m.version, installed = false, downloadSize = m.size
                )
            )
        }
        val d = data
        out.add(
            DictionaryInfo(
                "emoji", "Emoji", "打字時依詞意穿插 Emoji 候選", d.emoji?.all()?.size ?: 0, emojiEnabled,
                source = "Unicode CLDR 繁中註解（Unicode License v3）", version = loaded["emoji"]?.version
            )
        )
        out.add(DictionaryInfo(
                "kaomoji", "顏文字", "打字時穿插顏文字候選", d.kaomoji?.let { k -> (0 until k.size).map { k.kaomoji(it) }.toSet().size } ?: 0, kaomojiEnabled,
                source = kaomojiPack?.let { "${it.source}（${it.license}）" } ?: "w33ble/emoticon-data（MIT License）",
                version = kaomojiPack?.version
            ))
        try {
            importDb?.rawQuery(
                "SELECT d.id, d.name, d.enabled, (SELECT count(*) FROM import_entries e WHERE e.dict_id = d.id) FROM import_dicts d ORDER BY d.created",
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(DictionaryInfo("import:${c.getLong(0)}", c.getString(1), "匯入的詞庫", c.getInt(3), c.getInt(2) != 0, removable = true))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "讀取匯入詞庫清單失敗", e)
        }
        out
    }

    // ---------- 線上更新的資料包（見 PackUpdater） ----------

    /** 詞庫有沒有開（開關就是安裝：只有開著的會自動更新） */
    fun isPackEnabled(id: String, group: String): Boolean = when (group) {
        "kaomoji" -> kaomojiEnabled
        // 版面用的資料（倉頡碼表）：有裝用到它的版面才算開著
        "layout" -> id in ChineseMethod.requiredPacks(context)
        else -> addonEnabled(id)
    }

    /** 目前生效的資料包版本（內建與下載的取新的那個）；沒裝是空字串 */
    fun installedPackVersion(id: String, @Suppress("UNUSED_PARAMETER") group: String): String = loaded[id]?.version ?: ""

    /**
     * 套用一個資料包（gzip 壓縮的 SCD，格式見資料 repo 的 tools/scd.py）：
     * 解壓後放進 files/scd/<id>.scd，重新載入。回傳套用的版本，失敗（格式不對）回傳 null
     */
    @Synchronized
    fun applyPack(gz: File): String? {
        val f = files.installPack(gz) ?: return null
        remount(true)
        Log.i(TAG, "已套用資料包 ${f.id} ${f.version}")
        return f.version
    }

    /** 移除只能線上安裝的資料包：檔案刪掉，清單上回到「未安裝」 */
    @Synchronized
    fun uninstallPack(id: String) {
        files.deletePack(id)
        sourcePrefs.edit().remove(id).apply()
        remount(true)
    }

    /**
     * 詞庫內容（設定頁點進去看）：回傳（主要文字, 次要文字）；query 有值時只列包含它的
     * 一般詞庫列「詞, 注音」；接續詞列「前詞 → 後詞」；Emoji 列「emoji, 關鍵字」；顏文字列「顏文字, 分類」
     */
    suspend fun browseDictionary(id: String, query: String, offset: Int, limit: Int): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val q = query.trim()
        // 同舊版的 LIKE '%q%'（ASCII 不分大小寫）
        val like = Like("%$q%")
        fun String.has() = q.isEmpty() || like.matches(this)
        fun <T> page(rows: Sequence<T>) = rows.drop(offset).take(limit).toList()
        val d = data
        try {
            when {
                id == "emoji" -> page(d.emoji?.browse(q).orEmpty().asSequence())
                id == "kaomoji" -> page(d.kaomoji?.browse(q).orEmpty().asSequence())
                else -> {
                    val f = loaded[id] ?: return@withContext emptyList()
                    if (f.has("b.prev") && f.group == "bigram") {
                        val b = BigramTable(f)
                        page((0 until b.size).asSequence().map { b.byScore(it) }
                            .filter { (prev, next) -> prev.has() || next.has() }
                            .map { (prev, next) -> "$prev → $next" to "" })
                    } else {
                        val w = WordTable(f)
                        val macro = Like("MACRO@%")
                        page((0 until w.size).asSequence().map { w.scoreOrder(it) }
                            .filter { !(id == "core" && macro.matches(w.word(it))) }
                            .filter { w.word(it).has() || w.reading(it).has() }
                            .map { w.word(it) to w.reading(it).replace('-', ' ') })
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "讀取詞庫內容失敗：$id", e)
            emptyList()
        }
    }

    suspend fun setDictionaryEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        when {
            id == "emoji" -> {
                emojiEnabled = enabled
                sourcePrefs.edit().putBoolean(id, enabled).apply()
                invalidateCandidateCaches()
            }
            id == "kaomoji" -> {
                kaomojiEnabled = enabled
                sourcePrefs.edit().putBoolean(id, enabled).apply()
                invalidateCandidateCaches()
            }
            id.startsWith("import:") -> {
                val v = android.content.ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
                importDb?.update("import_dicts", v, "id = ?", arrayOf(id.removePrefix("import:")))
                remount(false)
            }
            else -> {
                // 附加詞庫、接續詞：存開關再重組。還沒安裝的線上資料包先記住開關，下載套用後就照這個生效
                sourcePrefs.edit().putBoolean(id, enabled).commit()
                if (loaded.containsKey(id)) remount(false)
            }
        }
    }

    suspend fun removeImportedDictionary(id: String) = withContext(Dispatchers.IO) {
        val n = id.removePrefix("import:")
        importDb?.delete("import_entries", "dict_id = ?", arrayOf(n))
        importDb?.delete("import_dicts", "id = ?", arrayOf(n))
        remount(true)
    }

    data class ImportResult(val added: Int, val skipped: Int)

    /**
     * 匯入詞庫文字檔：一行一個詞，詞、注音、次數或詞頻以空白、Tab 或逗號分隔，順序不拘，# 開頭為註解
     * 注音音節以空白或 - 分隔（ㄊㄞˊ ㄅㄟˇ／ㄊㄞˊ-ㄅㄟˇ）；沒寫注音就依主詞庫推讀音，推不出來的略過
     *
     * 每行取第一個含漢字的欄位當詞，所以常見格式都能直接匯入：
     *   純詞表「詞」、小麥注音／威注音使用者詞庫「詞 ㄅ-ㄆ」、新酷音 tsi.src「詞 次數 ㄅ ㄆ」、
     *   小麥注音 data.txt「ㄅ-ㄆ 詞 詞頻」、RIME .dict.yaml「詞<Tab>拼音<Tab>權重」（略過 YAML 檔頭）、
     *   Gboard 匯出的 dictionary.txt「縮寫<Tab>詞<Tab>zh-TW」
     */
    suspend fun importDictionary(name: String, text: String): ImportResult = withContext(Dispatchers.IO) {
        val imports = importDb ?: return@withContext ImportResult(0, 0)
        val readingCache = HashMap<String, String?>()
        val entries = LinkedHashMap<Pair<String, String>, Double>()
        var skipped = 0
        for ((word, syllables, freq) in DictImportParser.parse(text)) {
            val reading = if (syllables.isNotEmpty()) syllables.joinToString("-") else guessReading(word, readingCache)
            if (reading == null || word.length > 20) {
                skipped++
                continue
            }
            // 次數 → 詞頻：沒寫就給中等偏常用；寫了次數就依次數往上加；本來就是 log 詞頻（負數）照用
            val score = when {
                freq == null -> -5.6
                freq < 0 -> freq.coerceIn(-8.0, -2.0)
                else -> (-6.0 + 0.5 * kotlin.math.log10(freq + 1)).coerceAtMost(-4.5)
            }
            entries[word to reading] = score
        }
        if (entries.isEmpty()) return@withContext ImportResult(0, skipped)
        imports.beginTransaction()
        try {
            val v = android.content.ContentValues().apply {
                put("name", name)
                put("enabled", 1)
                put("created", System.currentTimeMillis())
            }
            val id = imports.insert("import_dicts", null, v)
            val st = imports.compileStatement("INSERT INTO import_entries (dict_id, word, reading, score) VALUES (?, ?, ?, ?)")
            for ((k, score) in entries) {
                st.bindLong(1, id)
                st.bindString(2, k.first)
                st.bindString(3, k.second)
                st.bindDouble(4, score)
                st.executeInsert()
            }
            imports.setTransactionSuccessful()
        } finally {
            imports.endTransaction()
        }
        remount(false)
        ImportResult(entries.size, skipped)
    }

    /** 依主詞庫推讀音：最少切段（長詞優先，破音字跟著詞走），同段數取詞頻高的 */
    private fun guessReading(word: String, cache: HashMap<String, String?>): String? {
        if (words.tables.isEmpty()) return null
        val cps = word.codePoints().toArray().map { String(Character.toChars(it)) }
        val n = cps.size
        if (n == 0) return null
        fun lookup(w: String): Pair<String, Double>? {
            if (cache.containsKey(w)) return cache[w]?.let { it.substringBefore('|') to it.substringAfter('|').toDouble() }
            val hit = words.byWord(w, 1).firstOrNull()?.let { it.reading to it.score }
            cache[w] = hit?.let { "${it.first}|${it.second}" }
            return hit
        }
        data class Best(val segs: Int, val score: Double, val readings: List<String>)
        val best = arrayOfNulls<Best>(n + 1)
        best[0] = Best(0, 0.0, emptyList())
        for (i in 0 until n) {
            val b = best[i] ?: continue
            for (j in minOf(n, i + 6) downTo i + 1) {
                val hit = lookup(cps.subList(i, j).joinToString("")) ?: continue
                val cand = Best(b.segs + 1, b.score + hit.second, b.readings + hit.first)
                val cur = best[j]
                if (cur == null || cand.segs < cur.segs || (cand.segs == cur.segs && cand.score > cur.score)) best[j] = cand
            }
        }
        val r = best[n]?.readings?.joinToString("-") ?: return null
        return r.takeIf { it.count { c -> c == '-' } + 1 == n }
    }

    /** 匯出個人詞庫（學過的詞與自建詞）：「詞<Tab>注音<Tab>次數」，可以再匯入 */
    suspend fun exportUserDictionary(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder("# Scorpio 注音 個人詞庫匯出\n# 格式：詞<Tab>注音（音節以空白分隔）<Tab>使用次數\n")
        try {
            db?.rawQuery("SELECT word, reading, sum(count) FROM user_dict GROUP BY word, reading ORDER BY sum(count) DESC", null)?.use { c ->
                while (c.moveToNext()) {
                    sb.append(c.getString(0)).append('\t')
                        .append(c.getString(1).replace('-', ' ')).append('\t')
                        .append(c.getInt(2)).append('\n')
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "匯出個人詞庫失敗", e)
        }
        sb.toString()
    }

    private fun countOf(sql: String): Int = try {
        db?.rawQuery(sql, null)?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0
    } catch (e: Exception) {
        0
    }

    suspend fun learningStats(): LearningStats = withContext(Dispatchers.IO) {
        LearningStats(
            words = countOf("SELECT count(DISTINCT word) FROM user_dict"),
            userCreated = user.words().count { !words.hasWord(it) },
            bigrams = countOf("SELECT count(*) FROM user_bigram"),
            englishWords = countOf("SELECT count(*) FROM user_english"),
            typos = countOf("SELECT count(*) FROM user_typo"),
            touchSamples = spatial.sampleCount()
        )
    }

    /** 使用者自建詞（系統詞典沒有的），最近用過的在前 */
    suspend fun userCreatedWords(limit: Int = 2000): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<String, Int>>()
        try {
            db?.rawQuery("SELECT word, sum(count) AS c, max(last_used) AS lu FROM user_dict GROUP BY word ORDER BY lu DESC", null)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val w = c.getString(0)
                    if (!words.hasWord(w)) out.add(w to c.getInt(1))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "讀取自建詞失敗", e)
        }
        out
    }

    /** 清除所有個人學習（中文選字、接續搭配、英文、打錯修正）；按鍵校正另外重設 */
    suspend fun clearLearning(): Boolean = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext false
        try {
            database.beginTransaction()
            try {
                for (t in listOf("user_dict", "user_bigram", "user_trigram", "user_english", "user_typo")) database.delete(t, null, null)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            user.clear()
            synchronized(typoMemory) { typoMemory.clear() }
            fuzzy.restore(null)
            spatialPrefs.edit().remove(PREF_FUZZY).apply()
            candidatesCache.evictAll()
            spanCache.evictAll()
            userOnlyCache.evictAll()
            true
        } catch (e: Exception) {
            Log.e(TAG, "清除學習紀錄失敗", e)
            false
        }
    }

    /** 重設個人化按鍵校正（落點偏移，注音與 QWERTY 鍵盤） */
    fun resetTouchCalibration() {
        spatial.restore(null)
        spatial.motion.restore(null)
        qwerty.restore(null)
        synchronized(this) {
            qwertyGroups.values.forEach { it.restore(null) }
            qwertyDirty = 0
        }
        spatialPrefs.edit().apply {
            remove(PREF_SPATIAL_BIAS).remove(PREF_MOTION).remove(PREF_QWERTY_BIAS)
            for (k in spatialPrefs.all.keys) if (k.startsWith("$PREF_QWERTY_BIAS:")) remove(k)
        }.apply()
        candidatesCache.evictAll()
        spanCache.evictAll()
    }

    private val glyphPaint = android.graphics.Paint().apply { textLocale = java.util.Locale.TAIWAN }
    private val glyphCache = HashMap<Int, Boolean>()

    /**
     * 手機系統字型沒有的補充平面漢字（擴充 B 區以後，例如 𥯓）在候選列與多數 App 都會變方格，不列入候選
     * 一般字與 emoji 不受影響
     */
    fun isRenderable(word: String): Boolean {
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            if (cp in 0x20000..0x3FFFF) {
                val ok = synchronized(glyphCache) {
                    glyphCache.getOrPut(cp) { glyphPaint.hasGlyph(String(Character.toChars(cp))) }
                }
                if (!ok) return false
            }
            i += Character.charCount(cp)
        }
        return true
    }

    private val emojiGlyphCache = HashMap<String, Boolean>()

    /** 手機字型畫得出來的 Emoji（太新的 Emoji，例如 17.0 的 🫪，舊手機會變方格，不列出來） */
    fun isEmojiRenderable(emoji: String): Boolean =
        synchronized(emojiGlyphCache) { emojiGlyphCache.getOrPut(emoji) { glyphPaint.hasGlyph(emoji) } }

    suspend fun getNextWordPredictions(prevWord: String, prevWord2: String? = null): List<String> = withContext(Dispatchers.IO) {
        if (prevWord.isBlank()) return@withContext emptyList()
        val list = mutableListOf<String>()

        // 1. 個人學習的接續詞（最優先）：前兩個詞都一樣時接過的在最前面，再來是前一個詞接過的；次數照時間淡出（約一個月減半）
        // 不預測跟上一個詞一樣的多字詞（連點兩下同一個候選留下的紀錄，會讓候選看起來按了沒消失）；單字疊字（哈→哈）照常
        val now = System.currentTimeMillis()
        fun ok(next: String) = next != prevWord || next.codePointCount(0, next.length) == 1
        if (!prevWord2.isNullOrBlank()) {
            for (p in user.nextAfter(prevWord2, prevWord, 3)) if (ok(p.next) && !list.contains(p.next)) list.add(p.next)
        }
        for (p in user.nextAll(prevWord).filter { ok(it.next) }.sortedByDescending { decayedCount(it.count, it.lastUsed, now) }.take(6)) {
            if (!list.contains(p.next)) list.add(p.next)
        }

        // 2. 靜態 Bigram 語料接續詞（依搭配機率排序）；整詞查不到時退回以最後一字查詢
        val tables = data.allBigrams
        if (tables.isNotEmpty()) {
            val keys = LinkedHashSet<String>()
            keys.add(prevWord)
            if (prevWord.length > 1) keys.add(prevWord.takeLast(1))
            for (key in keys) {
                // SELECT next FROM bigram WHERE prev = ? ORDER BY score DESC LIMIT 14：舊版 bigram 表的主鍵是 (prev, next, source)，
                // 同分依 next（UTF-8）再依來源；不同來源的同一組搭配各算一筆
                val hits = ArrayList<Triple<String, Double, String>>()
                for ((id, t) in tables) t.forEach(key, 14) { next, score -> hits.add(Triple(next, score, id)) }
                hits.sortWith { a, b ->
                    if (a.second != b.second) b.second.compareTo(a.second)
                    else compareUtf8(a.first, b.first).let { if (it != 0) it else a.third.compareTo(b.third) }
                }
                for ((w, _, _) in hits.take(14)) {
                    if (!list.contains(w) && isRenderable(w)) list.add(w)
                }
                if (list.size >= 10) break
            }
        }
        return@withContext list.take(12)
    }

    suspend fun getEmojiCategories(): List<String> = withContext(Dispatchers.IO) {
        data.emoji?.categories() ?: emptyList()
    }

    suspend fun getEmojisByCategory(category: String): List<String> = withContext(Dispatchers.IO) {
        data.emoji?.byCategory(category)?.filter { isEmojiRenderable(it) } ?: emptyList()
    }

    suspend fun getKaomojiCategories(): List<String> = withContext(Dispatchers.IO) {
        data.kaomoji?.categories() ?: emptyList()
    }

    suspend fun getKaomojisByCategory(category: String): List<String> = withContext(Dispatchers.IO) {
        data.kaomoji?.byCategory(category) ?: emptyList()
    }

    // 放在類別最後：所有欄位都初始化完才開資料庫與背景執行緒
    // （放前面時 loadTypoMemory 等會碰到還沒建好的欄位而中斷初始化，後面的索引與整理都沒做到）
    init {
        // 詞庫檔（mmap）先掛上來：學習紀錄校正讀音要查主詞庫
        remount(true, updateSpatial = false)
        initUserDatabase()
        spatial.restore(spatialPrefs.getString(PREF_SPATIAL_BIAS, null))
        spatial.motion.restore(spatialPrefs.getString(PREF_MOTION, null))
        fuzzy.restore(spatialPrefs.getString(PREF_FUZZY, null))
        qwerty.restore(spatialPrefs.getString(PREF_QWERTY_BIAS, null))
        // 背景預先載入按鍵當下要用的資料：第一個按鍵就有音節先驗
        Thread {
            if (spatial.syllablePrefixes == null) spatial.setSyllableFrequencies(syllableFrequencies())
            // 倉頡碼表只有開了倉頡或速成才預先把速成對照表建好（沒開就等第一次用到）
            val methods = ChineseMethod.enabled(context, ChineseMethod.prefs(context))
            if (methods.any { it.isCodeTable }) data.cangjie?.quick?.size
            warmFirstKeys()
        }.start()
    }
}
