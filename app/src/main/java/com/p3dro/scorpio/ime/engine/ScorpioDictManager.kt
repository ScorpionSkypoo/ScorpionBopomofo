package com.p3dro.scorpio.ime.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/**
 * 智慧選字、Emoji 聯想、英文自動補全與顏文字核心管理器
 * 支援模糊音／易混淆按錯鍵容錯、免聲調盲打與 Viterbi 最佳路徑解算
 */
class ScorpioDictManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ScorpioDictManager"
        private const val DB_NAME = "scorpio_dict.db"
        private const val DB_MIN_SIZE_BYTES = 18 * 1024 * 1024L
        private const val CURRENT_DB_VERSION = 23
        private const val PREF_DB_VERSION = "scorpio_dict_db_version"
        private const val BIGRAM_DB_NAME = "scorpio_bigram.db"
        private const val CURRENT_BIGRAM_VERSION = 5
        private const val PREF_BIGRAM_VERSION = "scorpio_bigram_db_version"
        private const val PREF_USER_DICT_CLEANUP = "user_dict_cleanup_v1"
        private const val PREF_USER_DICT_CANONICAL = "user_dict_canonical_v1"
        private const val PREF_SPATIAL_BIAS = "spatial_bias_v1"

        // 英文學習詞（key 為小寫，word 保留最近一次的大小寫，例如 iPhone）
        // 個人慣性打錯：打了 typed 音節、實際選的是 intended 讀音的字（例如 ㄉㄨㄠ → ㄉㄧㄠ）
        private const val USER_TYPO_DDL = "CREATE TABLE IF NOT EXISTS user_typo (typed TEXT NOT NULL, intended TEXT NOT NULL, count INTEGER NOT NULL DEFAULT 1, last_used INTEGER NOT NULL, PRIMARY KEY(typed, intended))"
        private const val USER_ENGLISH_DDL = "CREATE TABLE IF NOT EXISTS user_english (key TEXT PRIMARY KEY, word TEXT NOT NULL, count INTEGER NOT NULL DEFAULT 1, last_used INTEGER NOT NULL)"

        val LAZY_SYLLABLES = mapOf(
            // 聲母簡拼高頻音節展開
            "ㄅ" to listOf("ㄅㄨ", "ㄅㄚ", "ㄅㄞ", "ㄅㄢ"),
            "ㄆ" to listOf("ㄆㄧㄥ", "ㄆㄥ", "ㄆㄧ"),
            "ㄇ" to listOf("ㄇㄟ", "ㄇㄚ", "ㄇㄜ", "ㄇㄣ", "ㄇㄧㄥ"),
            "ㄈ" to listOf("ㄈㄚ", "ㄈㄤ", "ㄈㄣ"),
            "ㄉ" to listOf("ㄉㄜ", "ㄉㄠ", "ㄉㄚ", "ㄉㄤ", "ㄉㄡ", "ㄉㄧ"),
            "ㄊ" to listOf("ㄊㄧ", "ㄊㄚ", "ㄊㄞ", "ㄊㄧㄢ", "ㄊㄨㄥ"),
            "ㄋ" to listOf("ㄋㄧ", "ㄋㄚ", "ㄋㄥ", "ㄋㄧㄢ"),
            "ㄌ" to listOf("ㄌㄜ", "ㄌㄞ", "ㄌㄧ", "ㄌㄧㄤ"),
            "ㄍ" to listOf("ㄍㄢ", "ㄍㄨㄛ", "ㄍㄟ", "ㄍㄨㄥ", "ㄍㄣ"),
            "ㄎ" to listOf("ㄎㄜ", "ㄎㄢ", "ㄎㄨ", "ㄎㄞ"),
            "ㄏ" to listOf("ㄏㄠ", "ㄏㄣ", "ㄏㄜ", "ㄏㄞ", "ㄏㄨㄟ", "ㄏㄡ"),
            "ㄐ" to listOf("ㄐㄧㄡ", "ㄐㄧㄣ", "ㄐㄧㄚ", "ㄐㄩㄝ", "ㄐㄧㄢ", "ㄐㄧ"),
            "ㄑ" to listOf("ㄑㄩ", "ㄑㄧㄥ", "ㄑㄧ", "ㄑㄧㄢ"),
            "ㄒ" to listOf("ㄒㄧㄠ", "ㄒㄧㄤ", "ㄒㄧㄝ", "ㄒㄧ", "ㄒㄧㄣ"),
            "ㄓ" to listOf("ㄓㄜ", "ㄓ", "ㄓㄨㄥ", "ㄓㄣ", "ㄓㄤ"),
            "ㄔ" to listOf("ㄔㄨ", "ㄔ", "ㄔㄥ", "ㄔㄤ"),
            "ㄕ" to listOf("ㄕ", "ㄕㄣ", "ㄕㄜ", "ㄕㄨㄛ", "ㄕㄤ"),
            "ㄖ" to listOf("ㄖㄣ", "ㄖㄨ", "ㄖㄤ"),
            "ㄗ" to listOf("ㄗㄞ", "ㄗㄨㄟ", "ㄗㄣ", "ㄗ"),
            "ㄘ" to listOf("ㄘㄨㄛ", "ㄘㄨㄥ", "ㄘ"),
            "ㄙ" to listOf("ㄙㄨㄛ", "ㄙㄢ", "ㄙ"),
            "ㄧ" to listOf("ㄧㄠ", "ㄧ", "ㄧㄡ", "ㄧㄝ", "ㄧㄣ", "ㄧㄥ"),
            "ㄨ" to listOf("ㄨㄛ", "ㄨㄟ", "ㄨㄣ", "ㄨㄢ"),
            "ㄩ" to listOf("ㄩㄝ", "ㄩㄥ", "ㄩㄣ")
        )

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

        /** 學習加分：疊加在字典分數上，讓選過的字在同一層級內勝出，而非蓋過所有正確讀音 */
        fun learnBonus(count: Int): Double = minOf(3.0 + count * 1.5, 12.0)

        // 顏文字關鍵字 → 分類（打「笑臉」「開心」等詞時同步穿插對應顏文字；分類見 tools/build_kaomoji.py）
        val KAOMOJI_KEYWORDS: Map<String, String> = buildMap {
            fun reg(cat: String, vararg words: String) { words.forEach { put(it, cat) } }
            reg("開心", "笑臉", "微笑", "笑", "笑容", "哈哈", "開心", "高興", "快樂", "愉快", "歡樂", "好笑", "笑死", "太好了", "爽", "讚", "好棒", "萬歲", "歡呼",
                "加油", "努力", "奮鬥", "衝", "拼了", "打氣", "撐住", "興奮")
            reg("喜歡", "喜歡", "愛", "愛你", "想你", "愛心", "謝謝", "感謝", "多謝", "謝啦", "感恩")
            reg("親親抱抱", "親親", "抱抱", "親一個", "擁抱", "么么噠")
            reg("打招呼", "你好", "哈囉", "嗨", "早安", "午安", "掰掰", "再見", "招手")
            reg("害羞", "害羞", "臉紅", "不好意思", "尷尬", "靦腆", "可愛", "萌", "撒嬌", "乖", "躲起來")
            reg("眨眼", "眨眼", "放電")
            reg("得意", "得意", "嘿嘿", "嘲諷", "跩")
            reg("哭泣", "哭", "哭哭", "難過", "傷心", "眼淚", "流淚", "嗚嗚", "悲傷", "委屈", "心碎", "好痛")
            reg("生氣", "生氣", "憤怒", "火大", "氣死", "怒", "不爽", "靠北", "抓狂")
            reg("翻桌", "翻桌", "掀桌")
            reg("驚訝", "驚訝", "驚嚇", "嚇到", "震驚", "哇", "不會吧", "傻眼", "害怕", "好可怕")
            reg("疑惑", "疑惑", "為什麼", "不懂", "什麼", "想想", "思考", "嗯")
            reg("擔心", "擔心", "緊張", "怎麼辦")
            reg("無奈", "無奈", "沒辦法", "囧", "算了", "唉", "好吧", "無言", "汗", "隨便")
            reg("投降", "投降", "認輸", "我輸了")
            reg("抱歉", "抱歉", "對不起", "歹勢", "失禮")
            reg("崩潰", "崩潰", "瘋了", "發瘋")
            reg("陣亡", "陣亡", "死了", "掛了", "累死")
            reg("盯著", "盯著", "瞪", "盯")
            reg("睡覺", "睡覺", "晚安", "睡", "好睏", "想睡", "累", "好累", "疲憊")
            reg("跳舞", "跳舞", "音樂", "唱歌", "派對")
            reg("逃跑", "逃跑", "快跑", "跑")
            reg("寫字", "寫字", "筆記", "讀書", "寫作業")
            reg("貓咪", "貓", "貓咪", "喵")
            reg("熊熊", "熊", "熊熊")
            reg("兔兔", "兔子", "兔兔")
            reg("狗狗", "狗", "狗狗", "汪")
            reg("動物", "動物", "小鳥", "豬", "猴子", "魚")
        }
    

        fun getInitialsFromReading(reading: String): String {
            if (reading.isBlank()) return ""
            return reading.split("-").mapNotNull { syl ->
                syl.firstOrNull()?.toString()
            }.joinToString("-")
        }

        // 大千注音 10x4 實體按鍵二維空間矩陣（用於 (X, Y) 觸控座標高斯分佈鄰鍵解算）
        val BOPOMOFO_GRID = SpatialModel.GRID

        // QWERTY 字母鍵中心座標（以按鍵寬為 1；第 2 排右移半鍵、第 3 排右移一鍵半，對應實際版面）
        // 英文按下當下判定：落點高斯（鍵寬單位）與字母先驗的權重、上限（同注音 SpatialModel）
        private const val EN_VAR_X = 0.5 / 1.2
        private const val EN_VAR_Y = 0.5
        private const val EN_PRIOR_WEIGHT = 0.12
        private const val EN_PRIOR_CAP = 0.35

        val QWERTY_POS: Map<Char, Pair<Double, Double>> = buildMap {
            "qwertyuiop".forEachIndexed { i, c -> put(c, i.toDouble() to 0.0) }
            "asdfghjkl".forEachIndexed { i, c -> put(c, i + 0.5 to 1.0) }
            "zxcvbnm".forEachIndexed { i, c -> put(c, i + 1.5 to 2.0) }
        }

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

    private var db: SQLiteDatabase? = null

    // 高速 LRU 記憶體快取：消弭重複 SQLite 磁碟/JNI 延遲
    private val subSyllableCache = LruCache<String, List<Pair<String, Double>>>(1500)
    private val candidatesCache = LruCache<String, CandidateGroups>(300)

    /**
     * 選字查詢的結果快取：打字是一個鍵一個鍵往上加，上一鍵查過的切分、讀音，下一鍵大多又會再查一次。
     * 每次 rawQuery 都要配一塊 CursorWindow（native 記憶體）並跑一次 SQLite，快取起來能省下大部分 CPU 與記憶體翻攪。
     * 跟 candidatesCache 一起清（學習、詞庫開關、套用資料包都會清）
     */
    private val queryCache = object : LruCache<String, List<Array<Any?>>>(4000) {}

    /** 查個人學習資料（user_ 開頭的表）的結果另外放：學一個詞只清這裡，字典的查詢快取留著，下一個詞的第一鍵不必重查 */
    private val userQueryCache = object : LruCache<String, List<Array<Any?>>>(1500) {}

    /** 快取過的查詢結果，用法跟 Cursor 一樣（只支援選字會用到的方法） */
    private class CachedCursor(private val rows: List<Array<Any?>>) : java.io.Closeable {
        private var pos = -1
        val count get() = rows.size
        fun moveToNext(): Boolean = (++pos < rows.size)
        fun moveToFirst(): Boolean { pos = 0; return rows.isNotEmpty() }
        fun getString(i: Int): String = rows[pos][i]?.toString() ?: ""
        fun getDouble(i: Int): Double = (rows[pos][i] as? Number)?.toDouble() ?: (rows[pos][i]?.toString()?.toDoubleOrNull() ?: 0.0)
        fun getInt(i: Int): Int = (rows[pos][i] as? Number)?.toInt() ?: (rows[pos][i]?.toString()?.toIntOrNull() ?: 0)
        fun getLong(i: Int): Long = (rows[pos][i] as? Number)?.toLong() ?: (rows[pos][i]?.toString()?.toLongOrNull() ?: 0L)
        fun isNull(i: Int): Boolean = rows[pos][i] == null
        override fun close() {}
    }

    private fun cachedQuery(database: SQLiteDatabase, sql: String, args: Array<String>?): CachedCursor {
        val key = sql + "\u0000" + (args?.joinToString("\u0001") ?: "")
        val cache = if (sql.contains("user_")) userQueryCache else queryCache
        cache.get(key)?.let { return CachedCursor(it) }
        val rows = ArrayList<Array<Any?>>()
        database.rawQuery(sql, args).use { c ->
            val n = c.columnCount
            while (c.moveToNext()) {
                rows.add(Array(n) { i ->
                    when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        else -> c.getString(i)
                    }
                })
            }
        }
        cache.put(key, rows)
        return CachedCursor(rows)
    }

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
    private val spatialPrefs = context.getSharedPreferences("scorpio_spatial", Context.MODE_PRIVATE)
    private var spatialDirty = 0

    // 以下在 init 之前宣告：init 開的背景執行緒會用到
    private val sourcePrefs = context.getSharedPreferences("scorpio_dict_sources", Context.MODE_PRIVATE)

    @Volatile
    var emojiEnabled = sourcePrefs.getBoolean("emoji", true)
        private set

    @Volatile
    var kaomojiEnabled = sourcePrefs.getBoolean("kaomoji", true)
        private set

    /** 詞庫資訊（名稱、來源、授權、版本），來自主詞庫的 dict_packs 表（tools/data/packs.json） */
    data class Pack(
        val id: String,
        /** words＝增加打得出的詞；bigram＝只影響詞與詞的接續 */
        val group: String,
        val name: String,
        val description: String,
        val source: String,
        val license: String,
        val url: String,
        val version: String
    )

    @Volatile
    private var packCache: List<Pack>? = null

    fun packs(): List<Pack> {
        packCache?.let { return it }
        val out = ArrayList<Pack>()
        try {
            db?.rawQuery("SELECT id, grp, name, description, source, license, url, version FROM dict_packs ORDER BY sort", null)?.use { c ->
                while (c.moveToNext()) {
                    out.add(Pack(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "讀取詞庫資訊失敗", e)
        }
        if (out.isNotEmpty()) packCache = out
        return out
    }

    /** 可開關的附加詞庫（詞會插進主詞庫） */
    private fun addonPacks() = packs().filter { it.group == "words" && it.id != "core" }

    /** 使用者開啟的接續詞來源；null 表示還沒算 */
    @Volatile
    private var enabledBigramSources: List<String>? = null

    private fun bigramSources(): List<String> =
        enabledBigramSources ?: packs().filter { it.group == "bigram" && addonEnabled(it.id) }.map { it.id }
            // 流行用語同時帶詞和搭配，跟著同一個開關
            .plus(if (addonEnabled("trendy")) listOf("trendy") else emptyList())
            .also { enabledBigramSources = it }

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


    private fun initDatabase() {
        try {
            val dbFile = context.getDatabasePath(DB_NAME)
            val dbDir = dbFile.parentFile
            if (dbDir != null && !dbDir.exists()) {
                dbDir.mkdirs()
            }

            val prefs = context.getSharedPreferences("scorpio_dict_prefs", Context.MODE_PRIVATE)
            val installedVersion = prefs.getInt(PREF_DB_VERSION, 0)
            val needsExtract = !dbFile.exists() || dbFile.length() < DB_MIN_SIZE_BYTES || installedVersion < CURRENT_DB_VERSION

            if (needsExtract) {
                // 1. 備份舊版個人學習詞庫與自定義字詞，避免字典更新後使用者詞彙被抹除
                data class SavedDict(val r: String, val t: String, val i: String, val w: String, val c: Int, val lu: Long)
                data class SavedBigram(val p: String, val n: String, val c: Int, val lu: Long)
                data class SavedEnglish(val k: String, val w: String, val c: Int, val lu: Long)
                data class SavedTypo(val t: String, val i: String, val c: Int, val lu: Long)
                val backupTypo = mutableListOf<SavedTypo>()
                val backupDict = mutableListOf<SavedDict>()
                val backupBigram = mutableListOf<SavedBigram>()
                val backupEnglish = mutableListOf<SavedEnglish>()

                if (dbFile.exists()) {
                    try {
                        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { oldDb ->
                            try {
                                oldDb.rawQuery("SELECT reading, toneless, initials, word, count, last_used FROM user_dict", null).use { cur ->
                                    while (cur.moveToNext()) {
                                        backupDict.add(SavedDict(cur.getString(0), cur.getString(1), cur.getString(2), cur.getString(3), cur.getInt(4), cur.getLong(5)))
                                    }
                                }
                            } catch (e: Exception) { /* ignore */ }
                            try {
                                oldDb.rawQuery("SELECT prev_word, next_word, count, last_used FROM user_bigram", null).use { cur ->
                                    while (cur.moveToNext()) {
                                        backupBigram.add(SavedBigram(cur.getString(0), cur.getString(1), cur.getInt(2), cur.getLong(3)))
                                    }
                                }
                            } catch (e: Exception) { /* ignore */ }
                            try {
                                oldDb.rawQuery("SELECT key, word, count, last_used FROM user_english", null).use { cur ->
                                    while (cur.moveToNext()) {
                                        backupEnglish.add(SavedEnglish(cur.getString(0), cur.getString(1), cur.getInt(2), cur.getLong(3)))
                                    }
                                }
                            } catch (e: Exception) { /* ignore */ }
                            try {
                                oldDb.rawQuery("SELECT typed, intended, count, last_used FROM user_typo", null).use { cur ->
                                    while (cur.moveToNext()) {
                                        backupTypo.add(SavedTypo(cur.getString(0), cur.getString(1), cur.getInt(2), cur.getLong(3)))
                                    }
                                }
                            } catch (e: Exception) { /* ignore */ }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "備份舊版個人詞庫略過: ${e.message}")
                    }
                }

                try {
                    SQLiteDatabase.deleteDatabase(dbFile)
                } catch (e: Exception) {
                    dbFile.delete()
                }
                File(dbFile.path + "-wal").delete()
                File(dbFile.path + "-shm").delete()
                File(dbFile.path + "-journal").delete()

                val assetName = try {
                    context.assets.open("scorpio_dict.db.gz").close()
                    "scorpio_dict.db.gz"
                } catch (e: Exception) {
                    "scorpio_dict.db"
                }

                Log.i(TAG, "正在自資產載入詞庫 [$assetName] (版本 $CURRENT_DB_VERSION)...")
                context.assets.open(assetName).use { inStream ->
                    if (assetName.endsWith(".gz")) {
                        GZIPInputStream(inStream).use { gzIn ->
                            FileOutputStream(dbFile).use { outStream ->
                                gzIn.copyTo(outStream)
                            }
                        }
                    } else {
                        FileOutputStream(dbFile).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                }
                prefs.edit().putInt(PREF_DB_VERSION, CURRENT_DB_VERSION).apply()
                Log.i(TAG, "詞庫載入完成，大小: ${dbFile.length() / 1024 / 1024} MB，版本: $CURRENT_DB_VERSION")

                // 2. 還原備份的個人學習詞庫
                if (backupDict.isNotEmpty() || backupBigram.isNotEmpty() || backupEnglish.isNotEmpty() || backupTypo.isNotEmpty()) {
                    try {
                        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { restoredDb ->
                            restoredDb.execSQL(
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
                            restoredDb.execSQL(
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
                            restoredDb.execSQL(USER_ENGLISH_DDL)
                            restoredDb.execSQL(USER_TYPO_DDL)
                            restoredDb.beginTransaction()
                            try {
                                val s1 = restoredDb.compileStatement("INSERT OR REPLACE INTO user_dict (reading, toneless, initials, word, count, last_used) VALUES (?, ?, ?, ?, ?, ?)")
                                for (d in backupDict) {
                                    s1.bindString(1, d.r)
                                    s1.bindString(2, d.t)
                                    s1.bindString(3, d.i)
                                    s1.bindString(4, d.w)
                                    s1.bindLong(5, d.c.toLong())
                                    s1.bindLong(6, d.lu)
                                    s1.executeInsert()
                                }
                                val s2 = restoredDb.compileStatement("INSERT OR REPLACE INTO user_bigram (prev_word, next_word, count, last_used) VALUES (?, ?, ?, ?)")
                                for (b in backupBigram) {
                                    s2.bindString(1, b.p)
                                    s2.bindString(2, b.n)
                                    s2.bindLong(3, b.c.toLong())
                                    s2.bindLong(4, b.lu)
                                    s2.executeInsert()
                                }
                                val s3 = restoredDb.compileStatement("INSERT OR REPLACE INTO user_english (key, word, count, last_used) VALUES (?, ?, ?, ?)")
                                for (e in backupEnglish) {
                                    s3.bindString(1, e.k)
                                    s3.bindString(2, e.w)
                                    s3.bindLong(3, e.c.toLong())
                                    s3.bindLong(4, e.lu)
                                    s3.executeInsert()
                                }
                                val s4 = restoredDb.compileStatement("INSERT OR REPLACE INTO user_typo (typed, intended, count, last_used) VALUES (?, ?, ?, ?)")
                                for (t in backupTypo) {
                                    s4.bindString(1, t.t)
                                    s4.bindString(2, t.i)
                                    s4.bindLong(3, t.c.toLong())
                                    s4.bindLong(4, t.lu)
                                    s4.executeInsert()
                                }
                                restoredDb.setTransactionSuccessful()
                                Log.i(TAG, "已成功還原 ${backupDict.size} 條個人學習詞彙與 ${backupBigram.size} 條自訂關聯詞")
                            } finally {
                                restoredDb.endTransaction()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "還原個人學習詞庫失敗", e)
                    }
                }
            }

            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            // 依字查詞（自建詞判定、學習校正讀音）：一開就建，後面初始化步驟出錯也不會缺這個索引
            try {
                db?.execSQL("CREATE INDEX IF NOT EXISTS idx_dict_word ON dict(word)")
            } catch (e: Exception) {
                Log.w(TAG, "建立 idx_dict_word 失敗: ${e.message}")
            }
            try {
                db?.enableWriteAheadLogging()
                db?.execSQL("PRAGMA synchronous = NORMAL")
                db?.execSQL("PRAGMA cache_size = -16000")
            } catch (e: Exception) {
                // Ignore
            }

            // 使用者學習詞庫 (包含注音讀音、無聲調別名與聲母簡拼)
            db?.execSQL(
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
            try {
                db?.execSQL("ALTER TABLE user_dict ADD COLUMN toneless TEXT NOT NULL DEFAULT ''")
            } catch (e: Exception) {
                // Ignore if column exists
            }
            try {
                db?.execSQL("ALTER TABLE user_dict ADD COLUMN initials TEXT NOT NULL DEFAULT ''")
            } catch (e: Exception) {
                // Ignore if column exists
            }
            db?.execSQL("CREATE INDEX IF NOT EXISTS idx_user_dict_toneless ON user_dict(toneless)")
            db?.execSQL("CREATE INDEX IF NOT EXISTS idx_user_dict_reading ON user_dict(reading)")
            db?.execSQL("CREATE INDEX IF NOT EXISTS idx_user_dict_initials ON user_dict(initials)")
            // userOnlyWords 每個候選詞都查一次 user_dict WHERE word = ?，沒索引就是整張表掃一遍
            db?.execSQL("CREATE INDEX IF NOT EXISTS idx_user_dict_word ON user_dict(word)")

            // 使用者智慧雙詞聯想預測庫 (Bigram)
            db?.execSQL(
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
            db?.execSQL("CREATE INDEX IF NOT EXISTS idx_user_bigram_prev ON user_bigram(prev_word)")
            db?.execSQL(USER_ENGLISH_DDL)
            db?.execSQL(USER_TYPO_DDL)
            loadTypoMemory()

            // 依字查讀音（學習時校正按錯／模糊音的讀音用）
            db?.execSQL("CREATE INDEX IF NOT EXISTS idx_dict_word ON dict(word)")

            if (!prefs.getBoolean(PREF_USER_DICT_CLEANUP, false)) {
                purgeUnlearnableUserWords()
                prefs.edit().putBoolean(PREF_USER_DICT_CLEANUP, true).apply()
            }
            if (!prefs.getBoolean(PREF_USER_DICT_CANONICAL, false)) {
                canonicalizeUserWords()
                prefs.edit().putBoolean(PREF_USER_DICT_CANONICAL, true).apply()
            }

            Log.i(TAG, "ScorpioDictManager 初始化成功 (已就緒智慧學習與高速快取)！")
        } catch (e: Exception) {
            Log.e(TAG, "資料庫初始化失敗", e)
        }
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
        val database = db ?: return reading
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
            val readings = ArrayList<String>()
            try {
                database.rawQuery("SELECT reading FROM dict WHERE word = ? ORDER BY score DESC LIMIT 8", arrayOf(chars[k])).use { c ->
                    while (c.moveToNext()) readings.add(c.getString(0))
                }
            } catch (e: Exception) {
                // Ignore
            }
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
            subSyllableCache.evictAll()
            Log.i(TAG, "記住一筆慣性打錯")
        } catch (e: Exception) {
            Log.e(TAG, "更新 user_typo 失敗", e)
        }
    }

    // 詞是否為使用者自建（user_dict 有、系統詞典沒有）的快取；學習或刪除時清空
    private val userOnlyCache = LruCache<String, Boolean>(3000)

    /** 從候選中挑出使用者自建詞（系統詞典沒有、後天選字學到的），供候選列標示小鉛筆、開放長按刪除 */
    suspend fun userOnlyWords(words: Collection<String>): Set<String> = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext emptySet()
        val out = HashSet<String>()
        for (w in words) {
            if (!isHanWord(w)) continue
            val cached = userOnlyCache.get(w)
            val isUser = cached ?: try {
                val inUser = database.rawQuery("SELECT 1 FROM user_dict WHERE word = ? LIMIT 1", arrayOf(w)).use { it.moveToFirst() }
                val inDict = inUser && database.rawQuery("SELECT 1 FROM dict WHERE word = ? LIMIT 1", arrayOf(w)).use { it.moveToFirst() }
                (inUser && !inDict).also { userOnlyCache.put(w, it) }
            } catch (e: Exception) {
                false
            }
            if (isUser) out.add(w)
        }
        out
    }

    /** 按下注音鍵當下判定實際的鍵（空間模型 + 音節合法性） */
    fun resolveZhuyinTap(
        rawChar: Char,
        offsetX: Float,
        offsetY: Float,
        history: List<BopomofoComposer.TouchPoint>
    ): BopomofoComposer.TouchPoint = spatial.resolveTap(rawChar, offsetX, offsetY, history)

    /**
     * 個人化偏移學習：確定選字後，比對每個音節的按鍵與該字的正確讀音，
     * 打對的鍵與只錯一個鍵的音節都是「想按某鍵、實際落在某處」的樣本
     */
    suspend fun recordTouchSamples(points: List<BopomofoComposer.TouchPoint>, word: String) = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext
        val parsed = BopomofoComposer.parseSyllables(points)
        val chars = ArrayList<String>()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            chars.add(String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        if (parsed.isEmpty() || parsed.size != chars.size) return@withContext
        var added = 0
        for (k in parsed.indices) {
            val typed = toToneless(parsed[k].text)
            val readings = ArrayList<String>()
            try {
                database.rawQuery("SELECT DISTINCT toneless FROM dict WHERE word = ?", arrayOf(chars[k])).use { c ->
                    while (c.moveToNext()) readings.add(c.getString(0))
                }
            } catch (e: Exception) {
                // Ignore
            }
            val intended = when {
                // 打對或打了正確讀音的開頭（簡拼）
                readings.any { it.startsWith(typed) } -> typed
                else -> readings.filter { r -> r.length == typed.length && r.indices.count { r[it] != typed[it] } == 1 }
                    .singleOrNull() ?: continue
            }
            if (intended != typed) recordTypo(typed, intended)
            for (j in typed.indices) {
                val touch = parsed[k].touches[typed[j]] ?: continue
                if (SpatialModel.isTone(touch.rawChar)) continue
                spatial.learn(intended[j], touch)
                added++
            }
        }
        if (added > 0) {
            spatialDirty += added
            if (spatialDirty >= 8) {
                spatialPrefs.edit().putString(PREF_SPATIAL_BIAS, spatial.serialize()).apply()
                spatialDirty = 0
            }
        }
    }

    /** 刪掉一個鍵後改按別的鍵：被刪的落點當作「想按 intended」的樣本 */
    fun learnRetypeCorrection(intended: Char, wrong: BopomofoComposer.TouchPoint) {
        if (SpatialModel.isTone(intended) || SpatialModel.isTone(wrong.rawChar)) return
        spatial.learn(intended, wrong)
        if (++spatialDirty >= 8) {
            spatialPrefs.edit().putString(PREF_SPATIAL_BIAS, spatial.serialize()).apply()
            spatialDirty = 0
        }
    }

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

    // ---------- 英文補全：記憶體前綴索引 + QWERTY 鄰鍵容錯 + 個人學習 ----------
    private class EnglishIndex(val words: Array<String>, val scores: IntArray) {
        /** 前綴區間 [from, to) */
        fun range(prefix: String): IntRange {
            var lo = 0
            var hi = words.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (words[mid] < prefix) lo = mid + 1 else hi = mid
            }
            val from = lo
            hi = words.size
            val end = prefix + "\uffff"
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (words[mid] < end) lo = mid + 1 else hi = mid
            }
            return from until lo
        }
    }

    @Volatile
    private var englishIndex: EnglishIndex? = null

    private fun englishIndex(): EnglishIndex? {
        englishIndex?.let { return it }
        val database = db ?: return null
        return try {
            val words = ArrayList<String>(50000)
            val scores = ArrayList<Int>(50000)
            database.rawQuery("SELECT word, score FROM english_dict ORDER BY word", null).use { c ->
                while (c.moveToNext()) {
                    words.add(c.getString(0))
                    scores.add(c.getInt(1))
                }
            }
            EnglishIndex(words.toTypedArray(), scores.toIntArray()).also { englishIndex = it }
        } catch (e: Exception) {
            Log.e(TAG, "載入英文字典失敗", e)
            null
        }
    }

    /**
     * QWERTY 鄰鍵與懲罰：以按鍵寬為 1 的座標估計手指落點，懲罰隨「落點到鄰鍵距離²−落點到目前判定鍵距離²」增加。
     * 點在中心時同排鄰鍵約 1.5；偏向某鄰鍵半個鍵寬時降到 0.3。
     * 落點以實際按到的鍵（rawChar）為準；按下當下被改判過的，原本按到的鍵一定列為候選
     */
    private fun englishNeighborKeys(touch: BopomofoComposer.TouchPoint): List<Pair<Char, Double>> {
        val c = touch.char.lowercaseChar()
        val pos = QWERTY_POS[c] ?: return emptyList()
        val rawPos = QWERTY_POS[touch.rawChar.lowercaseChar()] ?: pos
        val px = rawPos.first + touch.offsetX * 0.5
        val py = rawPos.second + touch.offsetY * 0.5
        val d0 = (px - pos.first) * (px - pos.first) + (py - pos.second) * (py - pos.second)
        val out = ArrayList<Pair<Char, Double>>(8)
        for ((k, p) in QWERTY_POS) {
            if (k == c) continue
            val dx = p.first - pos.first
            val dy = p.second - pos.second
            if (dx * dx + dy * dy > 1.6 && k != touch.rawChar.lowercaseChar()) continue
            val d = (px - p.first) * (px - p.first) + (py - p.second) * (py - p.second)
            out.add(k to ((d - d0) * 1.2 + 0.3).coerceIn(0.3, 3.0))
        }
        return out
    }

    /**
     * 中文 QWERTY 輸入法（拼音、26 鍵注音）按下當下判定：落點高斯代價 ＋ 解碼先驗
     * logScore(c)：接上 c 後最佳切分的分數（自然對數），null 為解不出來；與英文相同的權重與上限
     */
    fun resolveQwertyTap(raw: Char, offsetX: Float, offsetY: Float, logScore: (Char) -> Double?): Char {
        val rawPos = QWERTY_POS[raw] ?: return raw
        val px = rawPos.first + offsetX * 0.5
        val py = rawPos.second + offsetY * 0.5
        val options = QWERTY_POS.filter { (k, p) ->
            k == raw || (p.first - rawPos.first).let { it * it } + (p.second - rawPos.second).let { it * it } <= 1.6
        }
        // 只評估落點夠近、可能翻盤的鍵（其餘鍵的落點代價已超過先驗上限，不必解碼）
        val scores = HashMap<Char, Double?>()
        for ((k, p) in options) {
            val dx = px - p.first
            val dy = py - p.second
            val geo = 0.5 * (dx * dx / EN_VAR_X + dy * dy / EN_VAR_Y)
            if (k == raw || geo < 2.0) scores[k] = logScore(k)
        }
        val best = scores.values.filterNotNull().maxOrNull() ?: return raw
        var bestKey = raw
        var bestTotal = Double.MAX_VALUE
        for ((k, p) in options) {
            if (!scores.containsKey(k)) continue
            val dx = px - p.first
            val dy = py - p.second
            val geo = 0.5 * (dx * dx / EN_VAR_X + dy * dy / EN_VAR_Y)
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
        val index = englishIndex ?: return raw
        val rawPos = QWERTY_POS[raw] ?: return raw
        if (!prefix.all { it in 'a'..'z' }) return raw
        val px = rawPos.first + offsetX * 0.5
        val py = rawPos.second + offsetY * 0.5
        val options = QWERTY_POS.filter { (k, p) ->
            k == raw || (p.first - rawPos.first).let { it * it } + (p.second - rawPos.second).let { it * it } <= 1.6
        }
        val mass = HashMap<Char, Double>(options.size)
        for (k in options.keys) {
            var m = 0.0
            for (i in index.range(prefix + k)) m += Math.pow(10.0, index.scores[i] / 1000.0)
            mass[k] = m
        }
        val best = mass.values.maxOrNull() ?: 0.0
        var bestKey = raw
        var bestScore = Double.MAX_VALUE
        for ((k, p) in options) {
            val dx = px - p.first
            val dy = py - p.second
            val geo = 0.5 * (dx * dx / EN_VAR_X + dy * dy / EN_VAR_Y)
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
        val index = englishIndex()
        val scored = HashMap<String, Double>()
        val display = HashMap<String, String>()

        fun add(word: String, score: Double) {
            val cur = scored[word]
            if (cur == null || score > cur) scored[word] = score
        }

        // 1. 個人學習的英文字（前綴吻合）
        val learned = HashMap<String, Int>()
        try {
            db?.rawQuery(
                "SELECT key, word, count FROM user_english WHERE key >= ? AND key < ? ORDER BY count DESC LIMIT 20",
                arrayOf(lower, lower + "\uffff")
            )?.use { c ->
                while (c.moveToNext()) {
                    learned[c.getString(0)] = c.getInt(2)
                    display[c.getString(0)] = c.getString(1)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        // 打出來的就是一個正確的字（字典有或學過）：放第一個，跟中文一樣「打對就是第一候選」
        var exact = learned.containsKey(lower)
        for ((k, cnt) in learned) {
            if (k == lower) continue
            add(k, 2000.0 + minOf(800.0 + cnt * 400.0, 2800.0))
        }

        if (index != null) {
            // 2. 前綴完全吻合
            val r = index.range(lower)
            for (i in r) {
                val w = index.words[i]
                if (w == lower) {
                    exact = true
                    continue
                }
                add(w, index.scores[i] + minOf(800.0 + (learned[w] ?: -2) * 400.0, 2800.0).coerceAtLeast(0.0))
            }

            // 3. 鄰鍵容錯：每次替換一個字母，懲罰 1 單位約等於詞頻差 10 倍
            if (lower.length >= 2 && lower.all { it in 'a'..'z' }) {
                for (i in lower.indices) {
                    val tp = touches?.getOrNull(i)?.takeIf { it.char.lowercaseChar() == lower[i] }
                        ?: BopomofoComposer.TouchPoint(lower[i])
                    for ((nb, pen) in englishNeighborKeys(tp)) {
                        val variant = lower.substring(0, i) + nb + lower.substring(i + 1)
                        val vr = index.range(variant)
                        var taken = 0
                        // 區間內依詞頻取前幾名
                        val top = vr.sortedByDescending { index.scores[it] }
                        for (j in top) {
                            if (taken >= 4) break
                            add(index.words[j], index.scores[j] - pen * 1000.0)
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

        val upperFirst = prefix[0].isUpperCase()
        val allUpper = prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() }
        return@withContext scored.entries.sortedByDescending { it.value }.take(12).map { (k, _) ->
            val base = display[k] ?: k
            when {
                allUpper -> base.uppercase()
                upperFirst -> base.replaceFirstChar { it.uppercase() }
                k == "i" || k.startsWith("i'") -> base.replaceFirstChar { it.uppercase() }
                else -> base
            }
        }
    }

    /**
     * 英文接續詞：打完一個字（空白或選字）後預測下一個字
     * 1. 個人學過的搭配（user_bigram，英文一律小寫）
     * 2. 內建語料搭配（english_bigram，OpenSubtitles 口語對白統計）
     */
    suspend fun getEnglishPredictions(prevWord: String): List<String> = withContext(Dispatchers.IO) {
        val prev = prevWord.lowercase()
        if (prev.isBlank() || !prev.all { it in 'a'..'z' || it == '\'' }) return@withContext emptyList()
        val database = db ?: return@withContext emptyList()
        val list = LinkedHashSet<String>()
        try {
            database.rawQuery(
                "SELECT next_word FROM user_bigram WHERE prev_word = ? AND next_word != prev_word ORDER BY count DESC, last_used DESC LIMIT 4",
                arrayOf(prev)
            ).use { c -> while (c.moveToNext()) list.add(c.getString(0)) }
            database.rawQuery(
                "SELECT next FROM english_bigram WHERE prev = ? ORDER BY score DESC LIMIT 12",
                arrayOf(prev)
            ).use { c -> while (c.moveToNext()) list.add(c.getString(0)) }
        } catch (e: Exception) {
            // Ignore
        }
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
        } catch (e: Exception) {
            Log.e(TAG, "更新 user_english 失敗", e)
        }
    }

    /**
     * 取得所有收錄的 Emoji 清單（供 Emoji 面板瀏覽）
     */
    suspend fun getAllEmojis(): List<String> = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext emptyList()
        val list = mutableListOf<String>()
        try {
            database.rawQuery(
                "SELECT DISTINCT emoji FROM emoji_dict ORDER BY priority ASC",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.getString(0))
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return@withContext list
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
        inlineSymbols: Boolean = true
    ): List<String> {
        val groups = getCandidateGroups(allSegmentations, hasTone, prevWord, touchPoints)
        return if (inlineSymbols) groups.inline else groups.words
    }

    suspend fun getCandidateGroups(
        allSegmentations: List<List<String>>,
        hasTone: Boolean,
        prevWord: String? = null,
        touchPoints: List<BopomofoComposer.TouchPoint>? = null
    ): CandidateGroups = withContext(Dispatchers.IO) {
        if (allSegmentations.isEmpty()) return@withContext CandidateGroups.EMPTY
        val database = db ?: return@withContext CandidateGroups.EMPTY
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
        val cacheKey = "${prevWord.orEmpty()}_${hasTone}_" + allSegmentations.joinToString(";") { it.joinToString("-") } + "|" + touchSig
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
        if (touchPoints != null && primaryInput.isNotEmpty() && (rawInvalid || primaryInput.any { !isValidSyllable(it) })) {
            val repairs = repairByNeighborKeys(if (rawInvalid) rawPoints!! else touchPoints, primaryInput.size)
            if (repairs.isNotEmpty()) {
                // 同樣懲罰的修正可能有好幾種（ㄐ→ㄍ／ㄔ／聲調鍵），交給語言模型挑最像一句話的
                val scored = repairs.map { r ->
                    val lattice = if (r.syllables.size > 1) solveLatticeViterbi(r.syllables, r.touches, prevWord) else null
                    val plausibility = lattice?.bestScore ?: topDictScore(toToneless(r.syllables.first()))
                    Triple(r, lattice, plausibility - r.penalty * 1.8)
                }.sortedByDescending { it.third }
                val best = scored.first()
                primaryTouches = best.first.touches
                precomputedLattice = best.second
                segmentationsToUse = buildList {
                    add(best.first.syllables)
                    addAll(allSegmentations)
                    scored.drop(1).take(2).forEach { if (!contains(it.first.syllables)) add(it.first.syllables) }
                }
                // 其他修正方式的整句也列在前排（ㄏㄣㄉㄨㄠ 最可能是「很多」，但「很屌」也要看得到，選過一次就會學起來）
                repairAlternatives = scored.drop(1).take(4).mapNotNull { it.second?.fullSentence?.takeIf { s -> s.isNotEmpty() } }
                Log.d(TAG, "鄰鍵修正：${primaryInput.size} 個音節 (懲罰 ${best.first.penalty})")
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

        // 查詢 user_bigram 上下文即時加權
        val bigramBoostMap = mutableMapOf<String, Double>()
        if (!prevWord.isNullOrBlank()) {
            try {
                cachedQuery(database, 
                    "SELECT next_word, count FROM user_bigram WHERE prev_word = ? ORDER BY count DESC LIMIT 20",
                    arrayOf(prevWord)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val nextWord = cursor.getString(0)
                        val count = cursor.getInt(1)
                        bigramBoostMap[nextWord] = minOf(25.0 + count * 4.0, 45.0)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 靜態 Bigram：前一個已上屏詞對下一個詞的搭配加權（例如「載具」→「發票」）
        if (!prevWord.isNullOrBlank()) {
            for ((nw, b) in bigramsFor(prevWord)) {
                val boost = (6.0 + (b + 5.0).coerceIn(0.0, 5.0) * 2.0)
                if ((bigramBoostMap[nw] ?: 0.0) < boost) bigramBoostMap[nw] = boost
            }
        }

        // 個人學習加分（僅限讀音完全吻合的選字紀錄）
        val learnBoostMap = mutableMapOf<String, Double>()

        fun addScoredWord(word: String, baseScore: Double) {
            if (word.isNotBlank() && !word.startsWith("MACRO@")) {
                val contextBoost = bigramBoostMap[word] ?: 0.0
                val finalScore = baseScore + contextBoost + (learnBoostMap[word] ?: 0.0)
                val cur = bestWordScores[word]
                if (cur == null || finalScore > cur) {
                    bestWordScores[word] = finalScore
                }
            }
        }

        val allReadingsForKaomoji = mutableListOf<String>()

        // 智慧簡拼/懶人注音展開：例如輸入 [ㄨ, ㄐㄩㄝ, ㄉㄜ] 自動展開出 [ㄨㄛ, ㄐㄩㄝ, ㄉㄜ] (我覺得)
        val augmentedSegmentations = mutableListOf<List<String>>()
        augmentedSegmentations.addAll(segmentationsToUse)
        for (syllables in segmentationsToUse) {
            val hasLazy = syllables.any { LAZY_SYLLABLES.containsKey(it) }
            if (hasLazy) {
                val variantLists = syllables.map { syl ->
                    val list = mutableListOf(SyllableVariant(syl, 0.0))
                    LAZY_SYLLABLES[syl]?.forEachIndexed { idx, exp ->
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

            // Step 1: 多音節時，Lattice Viterbi 網格全句最佳組合（只在主要切分計算一次）
            if (isPrimary && syllables.size > 1) {
                val lattice = precomputedLattice ?: solveLatticeViterbi(syllables, primaryTouches, prevWord)
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
                try {
                    cachedQuery(database, 
                        "SELECT word, score FROM dict WHERE (initials = ? OR initials = ? OR initials = ?) AND toneless LIKE ? ORDER BY score DESC LIMIT 15",
                        arrayOf(initKey1, initKey2, initKey3, likePattern)
                    ).use { cursor ->
                        var rank = 0
                        while (cursor.moveToNext()) {
                            val word = cursor.getString(0)
                            val score = cursor.getDouble(1)
                            addScoredWord(word, score + 96.0 - (rank * 0.5))
                            rank++
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // 1c. 韻母/母音簡拼匹配 (例如打 ㄢㄩㄝ -> 感覺、三月、判決...)
            val isAllFinals = syllables.all { it.all { c -> BopomofoComposer.FINALS.contains(c) || BopomofoComposer.MEDIALS.contains(c) } }
            if (isAllFinals) {
                val finKey = syllables.joinToString("-")
                try {
                    cachedQuery(database, 
                        "SELECT word, score FROM dict WHERE finals = ? ORDER BY score DESC LIMIT 15",
                        arrayOf(finKey)
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            addScoredWord(cursor.getString(0), cursor.getDouble(1) + 60.0)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // Step 2: 個人學習詞庫 —— 只當加分，不再以固定高分蓋過字典
            // (a) 讀音完全吻合、字數一致、聲調不衝突：字典已收錄者只加學習分（交由 Step 3 依聲調評分），自創詞直接放入精準層
            try {
                cachedQuery(database, 
                    "SELECT word, count, reading FROM user_dict WHERE toneless = ? OR reading = ? ORDER BY count DESC, last_used DESC LIMIT 12",
                    arrayOf(fullTonelessKey, fullRawKey)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val word = cursor.getString(0)
                        if (!isHanWord(word) || hanLength(word) != syllables.size) continue
                        if (toneConflictCount(syllables, cursor.getString(2)) > 0) continue
                        val bonus = learnBonus(cursor.getInt(1))
                        if (bonus > (learnBoostMap[word] ?: 0.0)) learnBoostMap[word] = bonus
                        val inDict = cachedQuery(database, 
                            "SELECT 1 FROM dict WHERE toneless = ? AND word = ? LIMIT 1",
                            arrayOf(fullTonelessKey, word)
                        ).use { it.moveToFirst() }
                        if (!inDict) addScoredWord(word, (if (isPrimary) 104.0 else 94.0) - 4.5)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            // (b) 學過的較長詞作為前綴補全（與字典前綴補全同層級）
            try {
                cachedQuery(database, 
                    "SELECT word, count FROM user_dict WHERE toneless > ? AND toneless < ? ORDER BY count DESC, last_used DESC LIMIT 5",
                    arrayOf("$fullTonelessKey-", "$fullTonelessKey-\uffff")
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val word = cursor.getString(0)
                        if (isHanWord(word) && hanLength(word) > syllables.size) {
                            addScoredWord(word, 68.0 - 4.5 + learnBonus(cursor.getInt(1)))
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            // (c) 簡拼輸入（含單聲母音節）時，比對學過的同聲母、同字數詞
            if (isAbbrev) {
                val likeUser = syllables.joinToString("-") { syl ->
                    val tl = toToneless(syl)
                    if (tl.length == 1 && BopomofoComposer.INITIALS.contains(tl[0])) "$tl%" else tl
                }
                try {
                    cachedQuery(database, 
                        "SELECT word, count FROM user_dict WHERE initials = ? AND toneless LIKE ? ORDER BY count DESC, last_used DESC LIMIT 5",
                        arrayOf(getInitialsFromReading(fullTonelessKey), likeUser)
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val word = cursor.getString(0)
                            if (isHanWord(word) && hanLength(word) == syllables.size) {
                                addScoredWord(word, 96.0 - 4.5 + learnBonus(cursor.getInt(1)))
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // Step 3a: 精準匹配（若使用者有打聲調，精確匹配平滑遞減加權）
            if (hasTone) {
                try {
                    cachedQuery(database, 
                        "SELECT word, score FROM dict WHERE reading = ? ORDER BY score DESC LIMIT 20",
                        arrayOf(fullRawKey)
                    ).use { cursor ->
                        var rank = 0
                        while (cursor.moveToNext()) {
                            val baseBoost = if (isPrimary) 112.0 else 100.0
                            val boost = maxOf(baseBoost - (rank * 0.8), 88.0)
                            addScoredWord(cursor.getString(0), cursor.getDouble(1) + boost)
                            rank++
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // Step 3b: 無聲調匹配 + 聲調一致性重排（使用者已輸入聲調的音節若與字典讀音不符則大幅扣分；未輸入聲調的音節視為萬用）
            try {
                cachedQuery(database, 
                    "SELECT word, score, reading FROM dict WHERE toneless = ? ORDER BY score DESC LIMIT 40",
                    arrayOf(fullTonelessKey)
                ).use { cursor ->
                    var rank = 0
                    while (cursor.moveToNext()) {
                        val baseBoost = if (isPrimary) 104.0 else 94.0
                        val boost = maxOf(baseBoost - (rank * 0.8), 85.0)
                        val mismatch = toneMismatchCount(syllables, cursor.getString(2))
                        addScoredWord(cursor.getString(0), cursor.getDouble(1) + boost - mismatch * 14.0)
                        rank++
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }

            // Step 3c: 前綴預測匹配（例如輸入 ㄏㄠ-ㄏㄠ-ㄒ 補全「好好笑」；同字數 90.0，長詞 68.0）
            try {
                val nextKey = fullTonelessKey + "\uffff"
                cachedQuery(database, 
                    "SELECT word, score FROM dict WHERE toneless >= ? AND toneless < ? ORDER BY score DESC LIMIT 15",
                    arrayOf(fullTonelessKey, nextKey)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val word = cursor.getString(0)
                        val baseScore = cursor.getDouble(1)
                        val boost = if (word.length == syllables.size) 90.0 else 68.0
                        addScoredWord(word, baseScore + boost)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }

            // Step 3d: 模糊音與鄰鍵容錯 toneless IN (...) (多字詞 84.0，單字 50.0)
            if (isPrimary) {
                val fuzzyKeys = generateFuzzyVariants(fullTonelessKey)
                if (fuzzyKeys.isNotEmpty()) {
                    val placeholders = fuzzyKeys.joinToString(",") { "?" }
                    try {
                        cachedQuery(database, 
                            "SELECT word, score FROM dict WHERE toneless IN ($placeholders) ORDER BY score DESC LIMIT 10",
                            fuzzyKeys.toTypedArray()
                        ).use { cursor ->
                            while (cursor.moveToNext()) {
                                val word = cursor.getString(0)
                                val baseScore = cursor.getDouble(1)
                                val boost = if (word.length >= 2) 84.0 else 50.0
                                addScoredWord(word, baseScore + boost)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
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
            try {
                cachedQuery(database, 
                    "SELECT word, count FROM user_dict WHERE (toneless = ? OR reading = ?) AND length(word) = 1 ORDER BY count DESC, last_used DESC LIMIT 5",
                    arrayOf(tonelessFirst, firstReading)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val word = cursor.getString(0)
                        if (!isHanWord(word)) continue
                        val count = cursor.getInt(1)
                        addScoredWord(word, 78.0 + minOf(count * 1.5, 12.0))
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }

            // 2. 精確聲調單字 (若首音節有標聲調，如 ㄏㄠˇ 出 好、郝)
            if (firstHasTone) {
                try {
                    cachedQuery(database, 
                        "SELECT word, score FROM dict WHERE reading = ? AND length(word) = 1 ORDER BY score DESC LIMIT 20",
                        arrayOf(firstReading)
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            addScoredWord(cursor.getString(0), cursor.getDouble(1) + 75.0)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // 3. 完整列出該首音節的所有單字候選字（如 ㄏㄠ 列出 好、號、豪、毫、耗、浩、郝、皓、壕、鎬...）
            try {
                cachedQuery(database, 
                    "SELECT word, score FROM dict WHERE toneless = ? AND length(word) = 1 ORDER BY score DESC LIMIT 30",
                    arrayOf(tonelessFirst)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val boost = if (firstHasTone) 65.0 else 70.0
                        addScoredWord(cursor.getString(0), cursor.getDouble(1) + boost)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }

            // 4. 若輸入 3 音節以上（例如 ㄏㄠ-ㄏㄠ-ㄎㄢ），額外補上前 2 音節的雙字前綴詞（例如「好好」）
            if (primarySyllables.size >= 3) {
                val prefix2Toneless = toToneless(primarySyllables.take(2).joinToString("-"))
                try {
                    cachedQuery(database, 
                        "SELECT word, score FROM dict WHERE toneless = ? ORDER BY score DESC LIMIT 10",
                        arrayOf(prefix2Toneless)
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            addScoredWord(cursor.getString(0), cursor.getDouble(1) + 82.0)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        // 依據評分降序排序（修復低分覆蓋高分 Bug）
        val textCandidates = bestWordScores.map { ScoredCandidate(it.key, it.value) }.sortedByDescending { it.score }

        // 4. 查顏文字快捷 (Kaomoji)
        val kaomojis = mutableListOf<String>()
        val distinctReadings = if (kaomojiEnabled) allReadingsForKaomoji.distinct().take(3) else emptyList()
        for (rKey in distinctReadings) {
            try {
                cachedQuery(database, 
                    "SELECT kaomoji FROM kaomoji_dict WHERE reading = ? ORDER BY priority ASC LIMIT 3",
                    arrayOf(rKey)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val km = cursor.getString(0)
                        if (!kaomojis.contains(km)) kaomojis.add(km)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 4b. 依候選詞語意關鍵字穿插顏文字（例如打「笑臉」→ 開心類顏文字）
        for (w in (if (kaomojiEnabled) textCandidates.take(6) else emptyList()).map { it.word }) {
            val cat = KAOMOJI_KEYWORDS[w] ?: continue
            try {
                cachedQuery(database, 
                    "SELECT kaomoji FROM kaomoji_dict WHERE category = ? ORDER BY priority ASC LIMIT 4",
                    arrayOf(cat)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val km = cursor.getString(0)
                        if (!kaomojis.contains(km)) kaomojis.add(km)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            if (kaomojis.size >= 6) break
        }

        // 5. iOS 風格 Emoji 即時語意穿插（候選列只取前 4 個詞各 3 個；展開面板的 Emoji 分頁取前 8 個詞各 8 個）
        val emojiCandidates = mutableListOf<String>()
        val emojiMore = LinkedHashSet<String>()
        for ((wi, w) in (if (emojiEnabled) textCandidates.take(8) else emptyList()).map { it.word }.withIndex()) {
            try {
                cachedQuery(database, 
                    "SELECT emoji FROM emoji_dict WHERE keyword = ? ORDER BY priority ASC LIMIT 8",
                    arrayOf(w)
                ).use { cursor ->
                    var n = 0
                    while (cursor.moveToNext()) {
                        val emo = cursor.getString(0)
                        emojiMore.add(emo)
                        if (wi < 4 && n < 3 && !emojiCandidates.contains(emo)) emojiCandidates.add(emo)
                        n++
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 6. 排列候選字
        var textIndex = 0
        while (textIndex < textCandidates.size && textIndex < 2) {
            addCandidate(textCandidates[textIndex].word)
            textIndex++
        }

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
        val words = result.filter { it !in kaomojiSet && !isSymbolOnly(it) }
        val emojiGroup = (result.filter { it !in kaomojiSet && isSymbolOnly(it) } + emojiMore).distinct()
        val groups = CandidateGroups(result.toList(), words, emojiGroup, kaomojis.toList())
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
        try {
            db?.rawQuery("SELECT DISTINCT toneless FROM dict WHERE instr(toneless, '-') = 0", null)?.use { c ->
                while (c.moveToNext()) set.add(c.getString(0))
            }
        } catch (e: Exception) {
            Log.w(TAG, "載入合法音節表失敗: ${e.message}")
        }
        validSyllableSet = set
        return set
    }

    // ---------- 倉頡／速成 ----------

    /** 倉頡碼表（記憶體索引）：依碼排序，前綴查詢用二分搜尋；速成碼另建對照 */
    private class CangjieIndex(val codes: Array<String>, val chars: Array<String>, val freqs: DoubleArray, val quick: Map<String, List<Int>>) {
        fun range(prefix: String): IntRange {
            var lo = 0
            var hi = codes.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (codes[mid] < prefix) lo = mid + 1 else hi = mid
            }
            val from = lo
            hi = codes.size
            val end = prefix + "\uffff"
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (codes[mid] < end) lo = mid + 1 else hi = mid
            }
            return from until lo
        }
    }

    @Volatile
    private var cangjieIndex: CangjieIndex? = null

    private fun cangjieIndex(): CangjieIndex? {
        cangjieIndex?.let { return it }
        val database = db ?: return null
        return try {
            val codes = ArrayList<String>(28000)
            val chars = ArrayList<String>(28000)
            val freqs = ArrayList<Double>(28000)
            val quick = HashMap<String, MutableList<Int>>()
            database.rawQuery("SELECT code, quick, char, freq FROM cangjie ORDER BY code, freq DESC", null).use { c ->
                while (c.moveToNext()) {
                    quick.getOrPut(c.getString(1)) { ArrayList() }.add(codes.size)
                    codes.add(c.getString(0))
                    chars.add(c.getString(2))
                    freqs.add(c.getDouble(3))
                }
            }
            CangjieIndex(codes.toTypedArray(), chars.toTypedArray(), freqs.toDoubleArray(), quick).also { cangjieIndex = it }
        } catch (e: Exception) {
            Log.e(TAG, "載入倉頡碼表失敗", e)
            null
        }
    }

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
            for (i in index.range(code)) if (index.codes[i] == code) exact.add(i) else more.add(i)
        }
        val out = LinkedHashSet<String>()
        exact.sortedByDescending { index.freqs[it] }.forEach { out.add(index.chars[it]) }
        more.sortedByDescending { index.freqs[it] }.take(40).forEach { out.add(index.chars[it]) }
        out.filter { isRenderable(it) }.take(60)
    }

    /** 倉頡碼前綴的使用量（自然對數），供按鍵當下的動態感應區；沒有任何字以此為開頭時為 null */
    fun codePrefixLogMass(prefix: String, quick: Boolean): Double? {
        val index = cangjieIndex ?: return 0.0
        var m = 0.0
        if (quick) {
            for ((q, ids) in index.quick) if (q.startsWith(prefix)) for (i in ids) m += Math.pow(10.0, index.freqs[i])
        } else {
            for (i in index.range(prefix)) m += Math.pow(10.0, index.freqs[i])
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
        val freq = HashMap<String, Double>(1600)
        try {
            db?.rawQuery("SELECT toneless, score FROM dict WHERE instr(toneless, '-') = 0 AND toneless <> ''", null)?.use { c ->
                while (c.moveToNext()) {
                    val s = c.getString(0)
                    freq[s] = (freq[s] ?: 0.0) + Math.pow(10.0, c.getDouble(1))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "載入音節字頻失敗: ${e.message}")
        }
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

    data class RepairedParse(
        val syllables: List<String>,
        val touches: List<Map<Char, BopomofoComposer.TouchPoint>>,
        val penalty: Double
    )

    /**
     * 輸入中有拼不起來的音節時（例如 ㄐㄞ、ㄅㄧㄤ），逐一把每個按鍵換成實體相鄰鍵（或視為多按一下而刪除）重新切分，
     * 保留所有音節皆合法、且音節數不變的組合（音節數不變才能與部分選字的消費音節數對齊）。
     */
    private fun repairByNeighborKeys(points: List<BopomofoComposer.TouchPoint>, syllableCount: Int): List<RepairedParse> {
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
        val parsedNow = BopomofoComposer.parseSyllables(points)
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
        for (i in points.indices) {
            val p = points[i]
            if (BopomofoComposer.TONES.contains(p.char)) continue
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

    private fun topDictScore(tonelessKey: String): Double {
        try {
            db?.rawQuery("SELECT max(score) FROM dict WHERE toneless = ?", arrayOf(tonelessKey))?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getDouble(0)
            }
        } catch (e: Exception) {
            // Ignore
        }
        return -20.0
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
        val bestScore: Double = Double.NEGATIVE_INFINITY
    )

    // ---------- 靜態 Bigram（詞與詞搭配機率，ln P(next | prev)） ----------
    private var bigramDb: SQLiteDatabase? = null
    private val bigramCache = LruCache<String, Map<String, Double>>(600)

    private fun initBigramDb() {
        try {
            val file = context.getDatabasePath(BIGRAM_DB_NAME)
            val prefs = context.getSharedPreferences("scorpio_dict_prefs", Context.MODE_PRIVATE)
            if (!file.exists() || prefs.getInt(PREF_BIGRAM_VERSION, 0) < CURRENT_BIGRAM_VERSION) {
                file.parentFile?.mkdirs()
                File(file.path + "-wal").delete()
                File(file.path + "-shm").delete()
                try {
                    val isGz = try {
                        context.assets.open("$BIGRAM_DB_NAME.gz").close()
                        true
                    } catch (e: Exception) {
                        false
                    }
                    val assetName = if (isGz) "$BIGRAM_DB_NAME.gz" else BIGRAM_DB_NAME
                    context.assets.open(assetName).use { input ->
                        if (isGz) {
                            GZIPInputStream(input).use { gz ->
                                FileOutputStream(file).use { out -> gz.copyTo(out) }
                            }
                        } else {
                            FileOutputStream(file).use { out -> input.copyTo(out) }
                        }
                    }
                    prefs.edit().putInt(PREF_BIGRAM_VERSION, CURRENT_BIGRAM_VERSION).apply()
                    Log.i(TAG, "Bigram 詞庫載入完成: ${file.length() / 1024} KB")
                } catch (e: Exception) {
                    Log.e(TAG, "Bigram 資產解壓縮失敗", e)
                    return
                }
            }
            // 可寫入：線上更新的接續詞資料包直接換掉同來源的資料
            bigramDb = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            Log.i(TAG, "Bigram 詞庫開啟成功: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Bigram 詞庫載入失敗", e)
            bigramDb = null
        }
    }

    private fun bigramsFor(prev: String): Map<String, Double> {
        bigramCache.get(prev)?.let { return it }
        val map = HashMap<String, Double>()
        val bdb = bigramDb
        val sources = bigramSources()
        if (bdb != null && sources.isNotEmpty()) {
            try {
                // 多個來源都有同一組搭配時取最強的那個
                val marks = sources.joinToString(",") { "?" }
                bdb.rawQuery(
                    "SELECT next, MAX(score) FROM bigram WHERE prev = ? AND source IN ($marks) GROUP BY next",
                    arrayOf(prev) + sources
                ).use { c ->
                    while (c.moveToNext()) map[c.getString(0)] = c.getDouble(1)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        bigramCache.put(prev, map)
        return map
    }

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
        prevWord: String? = null
    ): LatticeResult {
        val n = syllables.size
        val database = db ?: return LatticeResult("", emptyList(), emptyList())

        data class Node(val score: Double, val text: String, val last: String)
        val beam = 6
        val dp = Array(n + 1) { mutableListOf<Node>() }
        dp[0].add(Node(0.0, "", prevWord.orEmpty()))

        val variantsPerPos = syllables.mapIndexed { idx, syl ->
            getWeightedSyllableVariants(syl, touchesPerSyllable.getOrNull(idx) ?: emptyMap())
        }
        val spanWords = mutableListOf<String>()

        for (i in 0 until n) {
            if (dp[i].isEmpty()) continue
            if (dp[i].size > beam) {
                val top = dp[i].sortedByDescending { it.score }.take(beam)
                dp[i].clear()
                dp[i].addAll(top)
            }
            val maxLen = minOf(6, n - i)
            for (len in 1..maxLen) {
                val subVarLists = variantsPerPos.subList(i, i + len)
                val beamWidth = when {
                    len == 1 -> 10
                    len == n -> 32
                    else -> 18
                }
                val subCombos = generateWeightedSubCombos(subVarLists, beamWidth)
                val spanTyped = syllables.subList(i, i + len)
                val toneSig = spanTyped.joinToString("") { (typedToneOf(it) ?: '_').toString() }

                val spanCands = HashMap<String, Double>()

                for ((combo, totalPenalty) in subCombos) {
                    val key = combo.joinToString("-")
                    val cacheKey2 = "$key|$toneSig"
                    var top = subSyllableCache.get(cacheKey2)
                    if (top == null) {
                        val list = ArrayList<Pair<String, Double>>()
                        // 1. 個人學習庫與自選詞融入 Viterbi 網格：字數須與區間音節數一致
                        val learned = LinkedHashMap<String, Pair<Int, String>>()
                        try {
                            cachedQuery(database, 
                                "SELECT word, count, reading FROM user_dict WHERE toneless = ? OR reading = ? ORDER BY count DESC, last_used DESC LIMIT 3",
                                arrayOf(key, key)
                            ).use { cursor ->
                                while (cursor.moveToNext()) {
                                    val uw = cursor.getString(0)
                                    if (!isHanWord(uw) || hanLength(uw) != len) continue
                                    if (!learned.containsKey(uw)) learned[uw] = Pair(cursor.getInt(1), cursor.getString(2))
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }

                        // 2. 查詢內建詞典
                        try {
                            cachedQuery(database, 
                                "SELECT word, score, reading FROM dict WHERE toneless = ? ORDER BY score DESC LIMIT 12",
                                arrayOf(key)
                            ).use { cursor ->
                                // 聲調一致性：已輸入聲調的音節與字典讀音不符則扣分，讓打得明確的詞優先
                                while (cursor.moveToNext()) {
                                    val cw = cursor.getString(0)
                                    if (cw.startsWith("MACRO@")) continue
                                    val tonePenalty = toneMismatchCount(spanTyped, cursor.getString(2)) * 6.0
                                    var adj = cursor.getDouble(1) - tonePenalty
                                    // 字典裡已有的學習詞只在字典分數上小幅加分，避免選過幾次的單字（例如「排」）蓋過常用詞「不太」
                                    learned[cw]?.let { (cnt, _) ->
                                        adj = if (len == 1) {
                                            val bonus = minOf(cnt * 0.3, 1.2)
                                            // 字典裡很少見但常用的字（例如「屌」）至少給到一般常用字的水準
                                            maxOf(adj, -4.5 - tonePenalty) + bonus
                                        } else {
                                            adj + minOf(cnt * 0.6, 2.4)
                                        }
                                    }
                                    list.add(Pair(cw, adj))
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                        // 字典沒有的自選詞（新詞、人名）：與字典同一 log 機率尺度給基礎分
                        val dictWords = list.mapTo(HashSet()) { it.first }
                        for ((uw, info) in learned) {
                            if (uw in dictWords) continue
                            list.add(Pair(uw, -3.0 + minOf(info.first * 0.5, 2.5) - toneConflictCount(spanTyped, info.second) * 6.0))
                        }
                        list.sortByDescending { it.second }
                        top = list.distinctBy { it.first }.take(4)
                        subSyllableCache.put(cacheKey2, top)
                    }

                    for ((idx, pair) in top.withIndex()) {
                        val adj = pair.second - (totalPenalty * 1.8)
                        val cur = spanCands[pair.first]
                        if (cur == null || adj > cur) spanCands[pair.first] = adj
                        if (idx == 0 && i == 0 && len >= 2 && totalPenalty <= 1.2 && !spanWords.contains(pair.first)) {
                            spanWords.add(pair.first)
                        }
                    }
                }

                // 區間「混合簡拼」查詢 (例如 ㄨㄛ-ㄐ-ㄉ -> 我覺得)
                if (len >= 2) {
                    val subSyls = spanTyped.map { toToneless(it) }
                    val hasSingleInitial = subSyls.any { it.length == 1 && BopomofoComposer.INITIALS.contains(it[0]) }
                    if (hasSingleInitial) {
                        val initKey1 = subSyls.map { syl -> if (syl.isNotEmpty() && BopomofoComposer.INITIALS.contains(syl[0])) syl[0].toString() else "_" }.joinToString("-")
                        val initKey2 = subSyls.map { syl -> if (syl.isNotEmpty() && BopomofoComposer.INITIALS.contains(syl[0])) syl[0].toString() else "" }.joinToString("-")
                        val initKey3 = subSyls.mapNotNull { it.firstOrNull()?.toString() }.joinToString("-")
                        val likePattern = subSyls.joinToString("-") { syl ->
                            if (syl.length == 1 && BopomofoComposer.INITIALS.contains(syl[0])) "$syl%" else syl
                        }
                        try {
                            cachedQuery(database, 
                                "SELECT word, score FROM dict WHERE (initials = ? OR initials = ? OR initials = ?) AND toneless LIKE ? ORDER BY score DESC LIMIT 3",
                                arrayOf(initKey1, initKey2, initKey3, likePattern)
                            ).use { cursor ->
                                var first = true
                                while (cursor.moveToNext()) {
                                    val w = cursor.getString(0)
                                    if (w.startsWith("MACRO@")) continue
                                    val initScore = cursor.getDouble(1) - 1.5
                                    val cur = spanCands[w]
                                    if (cur == null || initScore > cur) spanCands[w] = initScore
                                    if (first && i == 0 && !spanWords.contains(w)) spanWords.add(w)
                                    first = false
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }

                if (spanCands.isEmpty()) continue
                val bestCands = spanCands.entries.sortedByDescending { it.value }.take(4)
                val lengthBonus = (len - 1) * 2.8
                val target = dp[i + len]
                for (node in dp[i]) {
                    for ((w, sc) in bestCands) {
                        val total = node.score + sc + lengthBonus + bigramBonus(node.last, w, sc)
                        val text = node.text + w
                        val existing = target.indexOfFirst { it.text == text }
                        if (existing >= 0) {
                            if (total > target[existing].score) target[existing] = Node(total, text, w)
                        } else {
                            target.add(Node(total, text, w))
                        }
                    }
                }
            }
        }

        val finals = dp[n].sortedByDescending { it.score }
        val full = finals.firstOrNull()?.text ?: ""
        val alternatives = finals.drop(1).take(2).map { it.text }.filter { it != full }
        val dpWords = mutableListOf<String>()
        for (idx in (n - 1) downTo 2) {
            val t = dp[idx].maxByOrNull { it.score }?.text
            if (!t.isNullOrBlank() && !dpWords.contains(t) && t != full) {
                dpWords.add(t)
            }
        }

        return LatticeResult(full, dpWords, spanWords, alternatives, finals.firstOrNull()?.score ?: Double.NEGATIVE_INFINITY)
    }


    data class SyllableVariant(val text: String, val penalty: Double)

    private fun getWeightedSyllableVariants(
        rawSyllable: String,
        touches: Map<Char, BopomofoComposer.TouchPoint> = emptyMap()
    ): List<SyllableVariant> {
        val s = toToneless(rawSyllable)
        val map = LinkedHashMap<String, Double>()

        fun putMin(txt: String, pen: Double) {
            if (txt.isBlank()) return
            val cur = map[txt]
            if (cur == null || pen < cur) {
                map[txt] = pen
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

        // (C) 簡拼展開 (LAZY_SYLLABLES[s])
        LAZY_SYLLABLES[s]?.forEachIndexed { index, exp ->
            putMin(exp, 0.45 + index * 0.05)
        }

        // (D) 台灣常見模糊音互換
        // 平捲舌互換（ㄕ<->ㄙ、ㄓ<->ㄗ、ㄔ<->ㄘ）：penalty = 0.6
        if (s.contains('ㄕ')) putMin(s.replace('ㄕ', 'ㄙ'), 0.6)
        if (s.contains('ㄙ')) putMin(s.replace('ㄙ', 'ㄕ'), 0.6)
        if (s.contains('ㄓ')) putMin(s.replace('ㄓ', 'ㄗ'), 0.6)
        if (s.contains('ㄗ')) putMin(s.replace('ㄗ', 'ㄓ'), 0.6)
        if (s.contains('ㄔ')) putMin(s.replace('ㄔ', 'ㄘ'), 0.6)
        if (s.contains('ㄘ')) putMin(s.replace('ㄘ', 'ㄔ'), 0.6)

        // 前後鼻音互換（ㄣ<->ㄥ、ㄢ<->ㄤ）：penalty = 0.55
        if (s.contains('ㄣ')) putMin(s.replace('ㄣ', 'ㄥ'), 0.55)
        if (s.contains('ㄥ')) putMin(s.replace('ㄥ', 'ㄣ'), 0.55)
        if (s.contains('ㄢ')) putMin(s.replace('ㄢ', 'ㄤ'), 0.55)
        if (s.contains('ㄤ')) putMin(s.replace('ㄤ', 'ㄢ'), 0.55)

        // 韻母近似音（ㄛ<->ㄜ、ㄛ->ㄡ、ㄜ->ㄝ）：penalty = 0.6
        if (s.contains('ㄛ')) {
            putMin(s.replace('ㄛ', 'ㄜ'), 0.6)
            putMin(s.replace('ㄛ', 'ㄡ'), 0.6)
        }
        if (s.contains('ㄜ')) {
            putMin(s.replace('ㄜ', 'ㄛ'), 0.6)
            putMin(s.replace('ㄜ', 'ㄝ'), 0.6)
        }

        // (D2) 個人慣性打錯（以前打 s 卻選了 intended 讀音的字）
        for ((intended, cnt) in typoFixes(s)) putMin(intended, typoPenalty(cnt, isValidSyllable(s)))

        // (E) 鄰鍵手滑容錯：一律納入實體相鄰鍵，觸控落點只調整各方向的可能性（偏向哪邊，哪邊的鍵懲罰越低）
        for (i in s.indices) {
            val c = s[i]
            val touch = touches[c] ?: BopomofoComposer.TouchPoint(c)
            for ((nb, pen) in neighborKeys(touch)) {
                if (BopomofoComposer.TONES.contains(nb)) continue
                putMin(s.substring(0, i) + nb + s.substring(i + 1), pen)
            }
        }

        return map.entries.sortedBy { it.value }.take(14).map { SyllableVariant(it.key, it.value) }
    }

    private fun generateWeightedSubCombos(
        lists: List<List<SyllableVariant>>,
        beamWidth: Int
    ): List<Pair<List<String>, Double>> {
        var beam = listOf(Pair(emptyList<String>(), 0.0))
        for (list in lists) {
            val next = ArrayList<Pair<List<String>, Double>>(beam.size * list.size)
            for ((prefix, accPenalty) in beam) {
                for (v in list) {
                    next.add(Pair(prefix + v.text, accPenalty + v.penalty))
                }
            }
            next.sortBy { it.second }
            beam = if (next.size > beamWidth) next.subList(0, beamWidth) else next
        }
        return beam
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
            candidatesCache.evictAll()
        userQueryCache.evictAll()  // 只動到個人學習資料
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
            Log.i(TAG, "智慧學習一組關聯詞")
        } catch (e: Exception) {
            Log.e(TAG, "更新 user_bigram 失敗", e)
        }
        // 個人搭配變了：查 user_bigram 的快取與候選快取要重算
        userQueryCache.evictAll()
        candidatesCache.evictAll()
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
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            candidatesCache.evictAll()
        userQueryCache.evictAll()  // 只動到個人學習資料
            userOnlyCache.evictAll()
            subSyllableCache.evictAll()
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
     */
    fun trimMemory(level: Int) {
        candidatesCache.evictAll()
        userQueryCache.evictAll()
        subSyllableCache.trimToSize(subSyllableCache.maxSize() / 2)
        SQLiteDatabase.releaseMemory()
        if (level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            queryCache.evictAll()
            subSyllableCache.evictAll()
            bigramCache.evictAll()
            userOnlyCache.evictAll()
        }
    }

    /** 資料包預設開不開（dict_packs.default_on；舊版詞庫沒有這欄就當作開） */
    private fun packDefaultOn(id: String): Boolean = try {
        db?.rawQuery("SELECT default_on FROM dict_packs WHERE id = ?", arrayOf(id))?.use { !it.moveToFirst() || it.getInt(0) != 0 } ?: true
    } catch (e: Exception) {
        true
    }

    private fun sourcePresent(source: String): Boolean = try {
        db?.rawQuery("SELECT 1 FROM dict WHERE source = ? LIMIT 1", arrayOf(source))?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    private fun invalidateCandidateCaches() {
        candidatesCache.evictAll()
        queryCache.evictAll()
        userQueryCache.evictAll()
        userOnlyCache.evictAll()
        subSyllableCache.evictAll()
        syllableFreqCache = null
    }

    /** 依開關把附加／匯入詞庫加進或移出主詞庫 */
    @Synchronized
    fun syncDictionarySources() {
        val database = db ?: return
        var changed = false
        try {
            database.execSQL("ALTER TABLE dict ADD COLUMN source TEXT")
        } catch (e: Exception) {
            // 已經有欄位
        }
        try {
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_dict_source ON dict(source)")
            for (pack in addonPacks()) {
                val id = pack.id
                val want = addonEnabled(id)
                var has = sourcePresent(id)
                // 詞庫改版：已套用的舊版先移掉再插新版
                if (want && has && sourcePrefs.getString("ver:$id", null) != pack.version) {
                    database.delete("dict", "source = ?", arrayOf(id))
                    has = false
                }
                if (want && !has) {
                    database.execSQL(
                        "INSERT INTO dict (reading, toneless, word, score, initials, finals, source) " +
                            "SELECT reading, toneless, word, score, initials, finals, source FROM addon_dict WHERE source = ?",
                        arrayOf(id)
                    )
                    sourcePrefs.edit().putString("ver:$id", pack.version).apply()
                    changed = true
                } else if (!want && has) {
                    database.delete("dict", "source = ?", arrayOf(id))
                    changed = true
                }
            }
            val imports = importDb
            if (imports != null) {
                val dicts = ArrayList<Pair<Long, Boolean>>()
                imports.rawQuery("SELECT id, enabled FROM import_dicts", null).use { c ->
                    while (c.moveToNext()) dicts.add(c.getLong(0) to (c.getInt(1) != 0))
                }
                val known = dicts.map { "import:${it.first}" }.toSet()
                // 已刪除的匯入詞庫殘留在主詞庫的詞
                database.rawQuery("SELECT DISTINCT source FROM dict WHERE source LIKE 'import:%'", null).use { c ->
                    val stale = ArrayList<String>()
                    while (c.moveToNext()) if (c.getString(0) !in known) stale.add(c.getString(0))
                    for (src in stale) {
                        database.delete("dict", "source = ?", arrayOf(src))
                        changed = true
                    }
                }
                for ((id, enabled) in dicts) {
                    val src = "import:$id"
                    val has = sourcePresent(src)
                    if (enabled && !has) {
                        insertImportedEntries(database, imports, id)
                        changed = true
                    } else if (!enabled && has) {
                        database.delete("dict", "source = ?", arrayOf(src))
                        changed = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "同步詞庫來源失敗", e)
        }
        if (changed) {
            invalidateCandidateCaches()
            spatial.setSyllableFrequencies(syllableFrequencies())
        }
    }

    private fun insertImportedEntries(database: SQLiteDatabase, imports: SQLiteDatabase, id: Long) {
        database.beginTransaction()
        try {
            val st = database.compileStatement(
                "INSERT INTO dict (reading, toneless, word, score, initials, finals, source) VALUES (?, ?, ?, ?, ?, ?, ?)"
            )
            imports.rawQuery("SELECT word, reading, score FROM import_entries WHERE dict_id = ?", arrayOf(id.toString())).use { c ->
                while (c.moveToNext()) {
                    val reading = c.getString(1)
                    val parts = reading.split('-').map { splitSyllable(it) }
                    st.bindString(1, reading)
                    st.bindString(2, parts.joinToString("-") { it.first })
                    st.bindString(3, c.getString(0))
                    st.bindDouble(4, c.getDouble(2))
                    st.bindString(5, parts.joinToString("-") { it.second })
                    st.bindString(6, parts.joinToString("-") { it.third })
                    st.bindString(7, "import:$id")
                    st.executeInsert()
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
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
        fun count(database: SQLiteDatabase?, sql: String, id: String) = try {
            database?.rawQuery(sql, arrayOf(id))?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0
        } catch (e: Exception) {
            0
        }
        var kaomojiPack: Pack? = null
        for (p in packs()) {
            val source = "${p.source}（${p.license}）"
            val version = installedPackVersion(p.id, p.group).ifEmpty { p.version }
            when {
                p.id == "core" -> out.add(
                    DictionaryInfo(
                        p.id, p.name, p.description, countOf("SELECT count(*) FROM dict WHERE source IS NULL"),
                        enabled = true, source = source, version = p.version, toggleable = false
                    )
                )
                p.group == "kaomoji" -> kaomojiPack = p
                p.group == "bigram" -> out.add(
                    DictionaryInfo(
                        p.id, p.name, p.description, count(bigramDb, "SELECT count(*) FROM bigram WHERE source = ?", p.id),
                        addonEnabled(p.id), group = "bigram", source = source, version = version
                    )
                )
                else -> out.add(
                    DictionaryInfo(
                        p.id, p.name, p.description, count(db, "SELECT count(*) FROM addon_dict WHERE source = ?", p.id),
                        addonEnabled(p.id), source = source, version = version
                    )
                )
            }
        }
        val local = packs().map { it.id }.toSet()
        for (m in available) {
            if (m.id in local || m.group == "kaomoji") continue
            out.add(
                DictionaryInfo(
                    m.id, m.name, m.description.ifEmpty { "線上詞庫" }, m.rows, enabled = false,
                    group = m.group, source = if (m.source.isNotEmpty()) "${m.source}（${m.license}）" else null,
                    version = m.version, installed = false, downloadSize = m.size
                )
            )
        }
        out.add(
            DictionaryInfo(
                "emoji", "Emoji", "打字時依詞意穿插 Emoji 候選", countOf("SELECT count(DISTINCT emoji) FROM emoji_dict"), emojiEnabled,
                source = "Unicode CLDR 繁中註解（Unicode License v3）"
            )
        )
        out.add(DictionaryInfo(
                "kaomoji", "顏文字", "打字時穿插顏文字候選", countOf("SELECT count(DISTINCT kaomoji) FROM kaomoji_dict"), kaomojiEnabled,
                source = kaomojiPack?.let { "${it.source}（${it.license}）" } ?: "w33ble/emoticon-data（MIT License）",
                version = kaomojiPack?.let { installedPackVersion(it.id, it.group).ifEmpty { it.version } }
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
        else -> addonEnabled(id)
    }

    /** 下載的資料包存在這裡（<id>.db.gz），App 改版覆蓋詞庫檔後用來重新套用 */
    val packDir: File get() = File(context.filesDir, "packs").apply { mkdirs() }

    /**
     * 目前生效的資料包版本：看資料實際存放的那個資料庫
     * 詞與顏文字在主詞庫（dict_packs），接續詞在 bigram 庫（pack_versions）；流行用語兩邊都有，取舊的那邊
     */
    fun installedPackVersion(id: String, group: String): String {
        fun fromDict() = try {
            db?.rawQuery("SELECT version FROM dict_packs WHERE id = ?", arrayOf(id))?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        } ?: ""
        fun fromBigram() = try {
            bigramDb?.rawQuery("SELECT version FROM pack_versions WHERE id = ?", arrayOf(id))?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        } ?: ""
        return when {
            group == "bigram" -> fromBigram()
            id == "trendy" -> minOf(fromDict(), fromBigram(), PackVersion.comparator)
            else -> fromDict()
        }
    }

    /**
     * 套用一個資料包（gzip 壓縮的 SQLite，格式見資料 repo 的 tools/make_packs.py）：
     * 依 meta.id 把同來源的舊資料換成新的，並記下版本。回傳套用的版本，失敗回傳 null
     */
    @Synchronized
    fun applyPack(gz: File): String? {
        val database = db ?: return null
        val tmp = File(context.cacheDir, "pack-${System.nanoTime()}.db")
        try {
            GZIPInputStream(gz.inputStream()).use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            val meta = HashMap<String, String>()
            val tables = HashSet<String>()
            SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { pack ->
                pack.rawQuery("SELECT key, value FROM meta", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) }
                pack.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
            }
            val id = meta["id"] ?: return null
            val version = meta["version"] ?: return null

            if ("addon_dict" in tables || "kaomoji_dict" in tables) {
                database.execSQL("ATTACH DATABASE ? AS pack", arrayOf(tmp.absolutePath))
                try {
                    database.beginTransaction()
                    try {
                        if ("addon_dict" in tables) {
                            database.delete("addon_dict", "source = ?", arrayOf(id))
                            database.execSQL("INSERT INTO addon_dict SELECT reading, toneless, word, score, initials, finals, source FROM pack.addon_dict")
                        }
                        if ("kaomoji_dict" in tables) {
                            database.delete("kaomoji_dict", null, null)
                            database.execSQL("INSERT INTO kaomoji_dict (reading, kaomoji, category, priority) SELECT reading, kaomoji, category, priority FROM pack.kaomoji_dict")
                        }
                        val v = android.content.ContentValues().apply {
                            put("id", id)
                            put("grp", meta["group"] ?: "words")
                            put("name", meta["name"] ?: id)
                            put("description", meta["description"] ?: "")
                            put("source", meta["source"] ?: "")
                            put("license", meta["license"] ?: "")
                            put("url", meta["url"] ?: "")
                            put("version", version)
                        }
                        if (database.update("dict_packs", v, "id = ?", arrayOf(id)) == 0) {
                            v.put("sort", 99)
                            database.insert("dict_packs", null, v)
                        }
                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    database.execSQL("DETACH DATABASE pack")
                }
            }
            if ("bigram" in tables) {
                val bdb = bigramDb ?: return null
                bdb.execSQL("CREATE TABLE IF NOT EXISTS pack_versions (id TEXT PRIMARY KEY, version TEXT NOT NULL)")
                bdb.execSQL("ATTACH DATABASE ? AS pack", arrayOf(tmp.absolutePath))
                try {
                    bdb.beginTransaction()
                    try {
                        bdb.delete("bigram", "source = ?", arrayOf(id))
                        bdb.execSQL("INSERT OR REPLACE INTO bigram (prev, next, score, source) SELECT prev, next, score, source FROM pack.bigram")
                        bdb.execSQL("INSERT OR REPLACE INTO pack_versions (id, version) VALUES (?, ?)", arrayOf(id, version))
                        bdb.setTransactionSuccessful()
                    } finally {
                        bdb.endTransaction()
                    }
                } finally {
                    bdb.execSQL("DETACH DATABASE pack")
                }
            }
            packCache = null
            enabledBigramSources = null
            bigramCache.evictAll()
            invalidateCandidateCaches()
            syncDictionarySources()
            // 記下下載檔的版本與類型：啟動時比對用，不必每次都解壓縮資料包讀 meta
            sourcePrefs.edit().putString("file:$id", version).putString("filegrp:$id", meta["group"] ?: "words").apply()
            Log.i(TAG, "已套用資料包 $id $version")
            return version
        } catch (e: Exception) {
            Log.e(TAG, "套用資料包失敗：${gz.name}", e)
            return null
        } finally {
            tmp.delete()
        }
    }

    /** 移除只能線上安裝的資料包：資料整個刪掉，清單上回到「未安裝」 */
    @Synchronized
    fun uninstallPack(id: String) {
        File(packDir, "$id.db.gz").delete()
        try {
            db?.delete("dict", "source = ?", arrayOf(id))
            db?.delete("addon_dict", "source = ?", arrayOf(id))
            db?.delete("dict_packs", "id = ?", arrayOf(id))
            bigramDb?.delete("bigram", "source = ?", arrayOf(id))
            bigramDb?.delete("pack_versions", "id = ?", arrayOf(id))
        } catch (e: Exception) {
            Log.e(TAG, "移除資料包失敗：$id", e)
        }
        sourcePrefs.edit().remove(id).remove("ver:$id").remove("file:$id").remove("filegrp:$id").apply()
        packCache = null
        enabledBigramSources = null
        bigramCache.evictAll()
        invalidateCandidateCaches()
    }

    /**
     * 下載過、比目前生效的版本新的資料包重新套用（App 內建詞庫改版覆蓋後）
     * 版本與類型套用時就記在設定裡；只有舊版沒記到的才解壓縮讀 meta（輸入法常被系統重啟，啟動要快）
     */
    private fun reapplyDownloadedPacks() {
        for (f in packDir.listFiles { f -> f.name.endsWith(".db.gz") } ?: return) {
            val id = f.name.removeSuffix(".db.gz")
            val known = sourcePrefs.getString("file:$id", null)
            val info = if (known != null) Triple(id, known, sourcePrefs.getString("filegrp:$id", "words")!!)
            else PackUpdater.readMeta(f, context.cacheDir) ?: continue
            if (PackVersion.compare(info.second, installedPackVersion(info.first, info.third)) > 0) applyPack(f)
        }
    }

    /**
     * 詞庫內容（設定頁點進去看）：回傳（主要文字, 次要文字）；query 有值時只列包含它的
     * 一般詞庫列「詞, 注音」；接續詞列「前詞 → 後詞」；Emoji 列「emoji, 關鍵字」；顏文字列「顏文字, 分類」
     */
    suspend fun browseDictionary(id: String, query: String, offset: Int, limit: Int): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val like = "%${query.trim()}%"
        val q = query.isNotBlank()
        fun rows(database: SQLiteDatabase?, sql: String, args: Array<String>): List<Pair<String, String>> = try {
            database?.rawQuery(sql, args)?.use { c ->
                val out = ArrayList<Pair<String, String>>()
                while (c.moveToNext()) out.add(c.getString(0) to (c.getString(1) ?: "").replace('-', ' '))
                out
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "讀取詞庫內容失敗：$id", e)
            emptyList()
        }
        val page = "LIMIT $limit OFFSET $offset"
        when {
            id == "core" -> rows(db, "SELECT word, reading FROM dict WHERE source IS NULL AND word NOT LIKE 'MACRO@%'" +
                (if (q) " AND (word LIKE ? OR reading LIKE ?)" else "") + " ORDER BY score DESC $page", if (q) arrayOf(like, like) else emptyArray())
            id == "emoji" -> rows(db, "SELECT emoji, group_concat(keyword, '、') FROM emoji_dict" +
                (if (q) " WHERE keyword LIKE ? OR emoji = ?" else "") + " GROUP BY emoji ORDER BY MIN(priority) $page", if (q) arrayOf(like, query.trim()) else emptyArray())
            id == "kaomoji" -> rows(db, "SELECT kaomoji, group_concat(category, '、') FROM kaomoji_dict" +
                (if (q) " WHERE category LIKE ? OR kaomoji LIKE ?" else "") + " GROUP BY kaomoji ORDER BY MIN(priority) $page", if (q) arrayOf(like, like) else emptyArray())
            id.startsWith("import:") -> rows(importDb, "SELECT word, reading FROM import_entries WHERE dict_id = ?" +
                (if (q) " AND (word LIKE ? OR reading LIKE ?)" else "") + " ORDER BY score DESC $page",
                if (q) arrayOf(id.removePrefix("import:"), like, like) else arrayOf(id.removePrefix("import:")))
            packs().any { it.id == id && it.group == "bigram" } -> rows(bigramDb, "SELECT prev || ' → ' || next, '' FROM bigram WHERE source = ?" +
                (if (q) " AND (prev LIKE ? OR next LIKE ?)" else "") + " ORDER BY score DESC $page", if (q) arrayOf(id, like, like) else arrayOf(id))
            else -> rows(db, "SELECT word, reading FROM addon_dict WHERE source = ?" +
                (if (q) " AND (word LIKE ? OR reading LIKE ?)" else "") + " ORDER BY score DESC $page", if (q) arrayOf(id, like, like) else arrayOf(id))
        }
    }

    suspend fun setDictionaryEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        // 開關就是安裝：關掉時刪掉下載的新版資料包（省空間；再打開會重新下載最新版）
        if (!enabled) File(packDir, "$id.db.gz").delete()
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
            packs().any { it.id == id && it.group == "bigram" } -> {
                sourcePrefs.edit().putBoolean(id, enabled).apply()
                enabledBigramSources = null
                bigramCache.evictAll()
                invalidateCandidateCaches()
            }
            addonPacks().any { it.id == id } -> {
                sourcePrefs.edit().putBoolean(id, enabled).commit()
                enabledBigramSources = null
                bigramCache.evictAll()
                syncDictionarySources()
            }
            !id.startsWith("import:") -> {
                // 還沒安裝的線上資料包：先記住開關，下載套用後就照這個生效
                sourcePrefs.edit().putBoolean(id, enabled).commit()
            }
            else -> {
                val v = android.content.ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
                importDb?.update("import_dicts", v, "id = ?", arrayOf(id.removePrefix("import:")))
                syncDictionarySources()
            }
        }
    }

    suspend fun removeImportedDictionary(id: String) = withContext(Dispatchers.IO) {
        val n = id.removePrefix("import:")
        importDb?.delete("import_entries", "dict_id = ?", arrayOf(n))
        importDb?.delete("import_dicts", "id = ?", arrayOf(n))
        syncDictionarySources()
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
        syncDictionarySources()
        ImportResult(entries.size, skipped)
    }

    /** 依主詞庫推讀音：最少切段（長詞優先，破音字跟著詞走），同段數取詞頻高的 */
    private fun guessReading(word: String, cache: HashMap<String, String?>): String? {
        val database = db ?: return null
        val cps = word.codePoints().toArray().map { String(Character.toChars(it)) }
        val n = cps.size
        if (n == 0) return null
        fun lookup(w: String): Pair<String, Double>? {
            if (cache.containsKey(w)) return cache[w]?.let { it.substringBefore('|') to it.substringAfter('|').toDouble() }
            val hit = try {
                database.rawQuery("SELECT reading, score FROM dict WHERE word = ? ORDER BY score DESC LIMIT 1", arrayOf(w)).use {
                    if (it.moveToFirst()) it.getString(0) to it.getDouble(1) else null
                }
            } catch (e: Exception) {
                null
            }
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
            userCreated = countOf("SELECT count(DISTINCT word) FROM user_dict WHERE word NOT IN (SELECT word FROM dict)"),
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
            db?.rawQuery(
                "SELECT word, sum(count) AS c, max(last_used) AS lu FROM user_dict u " +
                    "WHERE word NOT IN (SELECT word FROM dict) GROUP BY word ORDER BY lu DESC LIMIT ?",
                arrayOf(limit.toString())
            )?.use { c -> while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1)) }
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
                for (t in listOf("user_dict", "user_bigram", "user_english", "user_typo")) database.delete(t, null, null)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            synchronized(typoMemory) { typoMemory.clear() }
            candidatesCache.evictAll()
        userQueryCache.evictAll()  // 只動到個人學習資料
            userOnlyCache.evictAll()
            subSyllableCache.evictAll()
            true
        } catch (e: Exception) {
            Log.e(TAG, "清除學習紀錄失敗", e)
            false
        }
    }

    /** 重設個人化按鍵校正（落點偏移） */
    fun resetTouchCalibration() {
        spatial.restore(null)
        spatialPrefs.edit().remove(PREF_SPATIAL_BIAS).apply()
        candidatesCache.evictAll()
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

    suspend fun getNextWordPredictions(prevWord: String): List<String> = withContext(Dispatchers.IO) {
        if (prevWord.isBlank()) return@withContext emptyList()
        val list = mutableListOf<String>()

        // 1. 個人學習的接續詞（最優先）
        val database = db
        if (database != null) {
            try {
                database.rawQuery(
                    // 不預測跟上一個詞一樣的多字詞（連點兩下同一個候選留下的紀錄，會讓候選看起來按了沒消失）；
                    // 單字疊字（哈→哈）照常
                    "SELECT next_word FROM user_bigram WHERE prev_word = ? AND (next_word != prev_word OR length(next_word) = 1) " +
                        "ORDER BY count DESC, last_used DESC LIMIT 6",
                    arrayOf(prevWord)
                ).use { cursor ->
                    while (cursor.moveToNext()) list.add(cursor.getString(0))
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 2. 靜態 Bigram 語料接續詞（依搭配機率排序）；整詞查不到時退回以最後一字查詢
        val bdb = bigramDb
        if (bdb != null) {
            val keys = LinkedHashSet<String>()
            keys.add(prevWord)
            if (prevWord.length > 1) keys.add(prevWord.takeLast(1))
            for (key in keys) {
                try {
                    bdb.rawQuery(
                        "SELECT next FROM bigram WHERE prev = ? ORDER BY score DESC LIMIT 14",
                        arrayOf(key)
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val w = cursor.getString(0)
                            if (!list.contains(w) && isRenderable(w)) list.add(w)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
                if (list.size >= 10) break
            }
        }
        return@withContext list.take(12)
    }

    suspend fun getEmojiCategories(): List<String> = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext emptyList()
        val list = mutableListOf<String>()
        try {
            database.rawQuery("SELECT DISTINCT category FROM emoji_category_dict ORDER BY rowid ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.getString(0))
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return@withContext list
    }

    suspend fun getEmojisByCategory(category: String): List<String> = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext emptyList()
        val list = mutableListOf<String>()
        try {
            database.rawQuery(
                "SELECT emoji FROM emoji_category_dict WHERE category = ? ORDER BY priority ASC",
                arrayOf(category)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.getString(0))
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return@withContext list
    }

    suspend fun getKaomojiCategories(): List<String> = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext emptyList()
        val list = mutableListOf<String>()
        try {
            database.rawQuery("SELECT category FROM kaomoji_dict GROUP BY category ORDER BY MIN(priority)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.getString(0))
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return@withContext list
    }

    suspend fun getKaomojisByCategory(category: String): List<String> = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext emptyList()
        val list = mutableListOf<String>()
        try {
            database.rawQuery("SELECT kaomoji FROM kaomoji_dict WHERE category = ? ORDER BY priority ASC", arrayOf(category)).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.getString(0))
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return@withContext list
    }

    // 放在類別最後：所有欄位都初始化完才開資料庫與背景執行緒
    // （放前面時 loadTypoMemory 等會碰到還沒建好的欄位而中斷初始化，後面的索引與整理都沒做到）
    init {
        initDatabase()
        initBigramDb()
        spatial.restore(spatialPrefs.getString(PREF_SPATIAL_BIAS, null))
        // 背景預先載入按鍵當下要用的資料：第一個按鍵就有音節先驗與英文字母先驗
        Thread {
            // 詞庫檔被 App 內建的新版覆蓋時，之前下載、比內建新的資料包要重新套用
            reapplyDownloadedPacks()
            // 附加／匯入詞庫依開關套進主詞庫（新版詞庫檔覆蓋後要重新套）
            syncDictionarySources()
            if (spatial.syllablePrefixes == null) spatial.setSyllableFrequencies(syllableFrequencies())
            englishIndex()
            // 倉頡碼表 2.8 萬筆，只有開了倉頡或速成才預先載入（沒開就等第一次用到）
            val methods = ChineseMethod.enabled(context.getSharedPreferences("scorpio_ime_prefs", Context.MODE_PRIVATE))
            if (ChineseMethod.CANGJIE in methods || ChineseMethod.QUICK in methods) cangjieIndex()
            warmFirstKeys()
        }.start()
    }
}
