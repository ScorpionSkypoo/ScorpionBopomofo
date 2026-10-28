package com.p3dro.scorpio.ime.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

/**
 * 中文輸入法（鍵盤版面）：大千注音內建（10×4 注音鍵盤），其餘從資料 repo（scorpio-ime-data）的 layouts/ 線上安裝，
 * 用 QWERTY 鍵盤、鍵上標該版面的符號。版面是宣告式 JSON，只能選 App 內建的打字方式（engine），不含程式碼：
 *   zhuyin26  26 鍵注音（keymap：41 個字母依序對應 ㄅ…ㄙ、ㄧㄨㄩ、ㄚ…ㄦ、˙ˊˇˋ）
 *   pinyin    漢語拼音
 *   cangjie   倉頡；quick 速成（requires 碼表資料包 cangjie）
 * schema 2 可以加 keys 宣告 QWERTY 面板的字鍵排（KeyboardLayout，格式見 docs/keyboard-layouts.md）；沒寫就是內建 QWERTY。
 * 目前的打字方式都用 26 個英文字母，所以 keys 的字鍵要剛好是 a–z 各一次
 * 設定存 id；啟用的輸入法以地球鍵依序切換（最後是英文）
 */
data class ChineseMethod(
    val id: String,
    val label: String,
    val spaceLabel: String,
    val description: String,
    val engine: Engine,
    /** 26 鍵注音的鍵位表 */
    val keymap: String? = null,
    val version: String = "",
    /** 這個版面要用到的資料包（例如倉頡碼表），安裝版面時一起下載 */
    val requires: List<String> = emptyList(),
    /** 設定頁與地球鍵切換的順序（小的在前） */
    val sort: Int = 99,
    /** 語言（BCP 47）與顯示名稱：設定頁「新增輸入法」先選語言再選版面 */
    val language: String = "zh-TW",
    val languageName: String = "中文（台灣）",
    /** 字鍵排（schema 2）：QWERTY 面板的，或大千注音鍵盤的（例如三星的鍵位）；null 是內建的 QWERTY／大千 */
    val keys: KeyboardLayout? = null,
    /** 版面檔的長按副鍵（寫在預設值上面，見 LongPressKeys） */
    val longOverrides: Map<Char, List<String>> = emptyMap()
) {

    enum class Engine(val id: String) { DACHEN("dachen"), ZHUYIN26("zhuyin26"), PINYIN("pinyin"), CANGJIE("cangjie"), QUICK("quick") }

    val usesQwerty: Boolean get() = engine != Engine.DACHEN

    /** 大千注音鍵盤的字鍵排：有宣告就照宣告（ㄦ、刪除鍵在字鍵區，最下排跟英文一樣），沒有就是內建的 10×4 */
    val zhuyinLayout: KeyboardLayout get() = if (engine == Engine.DACHEN) keys ?: KeyboardLayout.DACHEN else KeyboardLayout.DACHEN
    val isPinyin: Boolean get() = engine == Engine.PINYIN

    /** 字鍵的長按副鍵：大千用大千的預設、其他用中文 QWERTY 的預設，再套上版面檔寫的 */
    val longPress: Map<Char, List<String>> by lazy {
        LongPressKeys.merge(if (engine == Engine.DACHEN) LongPressKeys.DACHEN else LongPressKeys.CHINESE_QWERTY, longOverrides)
    }

    /** 倉頡、速成：查碼表，不經注音 */
    val isCodeTable: Boolean get() = engine == Engine.CANGJIE || engine == Engine.QUICK
    val isQuick: Boolean get() = engine == Engine.QUICK
    val maxCodeLength: Int get() = if (isQuick) 2 else 5

    /** 經過注音整句選字的 QWERTY 版面（拼音、26 鍵注音）用的解碼方式 */
    val decoderKind: LetterDecoder.Kind? get() = when (engine) {
        Engine.PINYIN -> LetterDecoder.Kind.PINYIN
        Engine.ZHUYIN26 -> LetterDecoder.Kind.ZHUYIN26
        else -> null
    }

    companion object {
        const val PREF_ENABLED = "chinese_methods"
        const val PREF_CURRENT = "chinese_method"
        const val SCHEMA = 2

        val DACHEN = ChineseMethod("dachen", "注音（大千）", "注音", "標準大千 41 鍵注音鍵盤，支援免聲調、簡拼與打錯修正。", Engine.DACHEN)

        /** 倉頡字根（a = 日 … z = 重） */
        val CANGJIE_RADICALS: Map<Char, Char> = "日月金木水火土竹戈十大中一弓人心手口尸廿山女田難卜重"
            .mapIndexed { i, r -> ('a' + i) to r }.toMap()

        private val ID = Regex("[a-z0-9_]{1,32}")
        private val LETTERS = ('a'..'z').toSet()

        /** 解析版面 JSON；格式不對、或是這版 App 不認得的打字方式回傳 null（新版面在舊 App 上不會出錯） */
        fun parse(json: String): ChineseMethod? = try {
            val o = JSONObject(json)
            val engine = Engine.entries.firstOrNull { it.id == o.optString("engine") }
            val id = o.getString("id")
            val keymap = o.optString("keymap").ifEmpty { null }
            val keys = o.optJSONObject("keys")?.let { KeyboardLayout.parse(it) ?: return null }
            // 長按副鍵只能寫這個版面有的字鍵
            val long = LongPressKeys.parse(o.optJSONObject("long"), if (engine == Engine.DACHEN) KeyboardLayout.ZHUYIN_KEYS else LETTERS)
            when {
                o.optInt("schema", 1) > SCHEMA || engine == null || !ID.matches(id) || id == DACHEN.id -> null
                engine == Engine.ZHUYIN26 && (keymap == null || !LetterDecoder.isValidKeymap(keymap)) -> null
                // 大千：一定要宣告字鍵排（內建的 10×4 就是 dachen 本身），字鍵是 41 個注音符號與聲調、不能有 Shift
                engine == Engine.DACHEN && (keys == null || !keys.isZhuyin) -> null
                engine != Engine.DACHEN && keys != null && keys.centers.keys != LETTERS -> null
                long == null -> null
                else -> ChineseMethod(
                    id, o.getString("name"), o.optString("spaceLabel").ifEmpty { o.getString("name") }, o.optString("description", ""),
                    engine, keymap, o.optString("version", ""),
                    o.optJSONArray("requires")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    o.optInt("sort", 99),
                    o.optString("language").ifEmpty { "zh-TW" },
                    o.optString("languageName").ifEmpty { "中文（台灣）" },
                    keys,
                    long
                )
            }
        } catch (e: Exception) {
            null
        }

        // ---------- 安裝在手機上的版面 ----------

        private fun dir(context: Context) = File(context.filesDir, "layouts").apply { mkdirs() }

        /** 手機上有的版面：大千在最前面，其餘依版面的 sort、id */
        fun installed(context: Context): List<ChineseMethod> =
            listOf(DACHEN) + (dir(context).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
                .mapNotNull { parse(it.readText()) }
                .sortedWith(compareBy({ it.sort }, { it.id }))

        fun installedVersion(context: Context, id: String): String? =
            File(dir(context), "$id.json").takeIf { it.exists() }?.let { parse(it.readText())?.version }

        /** 安裝或更新：json 驗證過才寫入；新裝的版面直接啟用 */
        fun install(context: Context, prefs: SharedPreferences, json: String): ChineseMethod? {
            val m = parse(json) ?: return null
            val isNew = installedVersion(context, m.id) == null
            File(dir(context), "${m.id}.json").writeText(json)
            val ids = enabledIds(prefs)
            if (isNew && m.id !in ids) prefs.edit().putString(PREF_ENABLED, (ids + m.id).joinToString(",")).apply()
            return m
        }

        fun uninstall(context: Context, prefs: SharedPreferences, id: String) {
            if (id == DACHEN.id) return
            File(dir(context), "$id.json").delete()
            prefs.edit().putString(PREF_ENABLED, enabledIds(prefs).filter { it != id }.joinToString(",")).apply()
        }

        private fun enabledIds(prefs: SharedPreferences): List<String> =
            prefs.getString(PREF_ENABLED, DACHEN.id).orEmpty().split(',').filter { it.isNotEmpty() }

        /** 已啟用的輸入法（依版面順序；至少一個） */
        fun enabled(context: Context, prefs: SharedPreferences): List<ChineseMethod> {
            val ids = enabledIds(prefs)
            return installed(context).filter { it.id in ids }.ifEmpty { listOf(DACHEN) }
        }

        fun setEnabled(context: Context, prefs: SharedPreferences, id: String, on: Boolean) {
            val ids = enabledIds(prefs).toMutableList()
            if (on && id !in ids) ids.add(id)
            if (!on) ids.remove(id)
            // 至少保留一個
            if (ids.none { e -> installed(context).any { it.id == e } }) ids.add(DACHEN.id)
            prefs.edit().putString(PREF_ENABLED, ids.joinToString(",")).apply()
        }

        /** 啟用了卻不在手機上的版面（舊版 App 內建、升級後要線上重新安裝的） */
        fun missingEnabled(context: Context, prefs: SharedPreferences): List<String> {
            val have = installed(context).map { it.id }.toSet()
            return enabledIds(prefs).filter { it !in have }
        }

        /** 裝著的版面要用到的資料包 */
        fun requiredPacks(context: Context): Set<String> = installed(context).flatMap { it.requires }.toSet()

        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("scorpio_ime_prefs", Context.MODE_PRIVATE)
    }
}
