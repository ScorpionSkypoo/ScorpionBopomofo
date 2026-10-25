package com.p3dro.scorpio.ime.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 跟著資料 repo 更新的鍵盤設定（資料 repo 的 config/ime.json）：簡拼展開、顏文字語意對照、讀音混用的預設比例、符號鍵盤。
 * App 內建一份（assets/config/ime.json，發版時由 tools/sync_data.sh 從資料 repo 同步），
 * 線上有新版時 [PackUpdater] 下載到 files/config/ime.json；兩份取版本新的。只有資料，不含可執行的程式碼
 */
object ImeConfig {

    private const val TAG = "ImeConfig"
    private const val SCHEMA = 1
    const val ID = "ime"

    /** 符號鍵盤的一頁：兩排各 10 個、第三排 7 個 */
    class SymbolPage(val rows: List<List<String>>, val row3: List<String>)

    class Data(
        val version: String,
        /** 只打聲母或介音時展開成哪些音節（越前面越常用） */
        val lazySyllables: Map<String, List<String>>,
        /** 候選詞 → 穿插哪一類顏文字 */
        val kaomojiKeywords: Map<String, String>,
        /** 讀音混用的預設比例（見 FuzzySounds） */
        val fuzzyPriors: Map<String, Double>,
        /** zh＝從中文切過來、en＝從英文 */
        val symbols: Map<String, List<SymbolPage>>
    )

    private val EMPTY = Data("", emptyMap(), emptyMap(), emptyMap(), emptyMap())

    @Volatile
    private var current: Data? = null

    private fun file(context: Context) = File(File(context.filesDir, "config").apply { mkdirs() }, "$ID.json")

    fun get(context: Context): Data = current ?: synchronized(this) {
        current ?: load(context).also { current = it }
    }

    private fun load(context: Context): Data {
        val bundled = try {
            context.assets.open("config/$ID.json").bufferedReader().use { parse(it.readText()) }
        } catch (e: Exception) {
            Log.e(TAG, "內建鍵盤設定讀取失敗", e)
            null
        }
        val f = file(context)
        val downloaded = if (f.exists()) parse(f.readText()).also { if (it == null) Log.w(TAG, "下載的鍵盤設定格式不對，不用") } else null
        return when {
            downloaded != null && (bundled == null || PackVersion.compare(downloaded.version, bundled.version) > 0) -> downloaded
            else -> {
                // App 改版內建了更新的版本：下載的舊版不用了
                if (f.exists()) f.delete()
                bundled ?: EMPTY
            }
        }
    }

    fun installedVersion(context: Context) = get(context).version

    /** 套用下載的設定：格式不對回傳 false（沿用原本的） */
    fun install(context: Context, json: String): Boolean {
        val d = parse(json) ?: run {
            Log.w(TAG, "線上的鍵盤設定格式不對，不套用")
            return false
        }
        val f = file(context)
        val tmp = File(f.parentFile, "$ID.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            return false
        }
        current = d
        return true
    }

    /** 檢查規則同資料 repo 的 tools/make_configs.py；有任何不合就整份不用（回傳 null） */
    fun parse(json: String): Data? = try {
        val o = JSONObject(json)
        require(o.optInt("schema") == SCHEMA)
        val lazy = LinkedHashMap<String, List<String>>()
        o.getJSONObject("lazySyllables").let { m ->
            for (k in m.keys()) lazy[k] = strings(m.getJSONArray(k)).also { v -> require(v.isNotEmpty() && v.all { it.startsWith(k) }) }
        }
        val kaomoji = HashMap<String, String>()
        o.getJSONObject("kaomojiKeywords").let { m ->
            for (cat in m.keys()) for (w in strings(m.getJSONArray(cat))) kaomoji[w] = cat
        }
        val priors = LinkedHashMap<String, Double>()
        o.getJSONObject("fuzzyPriors").let { m ->
            for (k in m.keys()) priors[k] = m.getDouble(k).also { require(it > 0 && it < 1) }
        }
        val symbols = HashMap<String, List<SymbolPage>>()
        o.getJSONObject("symbols").let { m ->
            for (lang in listOf("zh", "en")) {
                val pages = m.getJSONArray(lang)
                symbols[lang] = (0 until pages.length()).map { i ->
                    val p = pages.getJSONObject(i)
                    val rows = p.getJSONArray("rows").let { r -> (0 until r.length()).map { strings(r.getJSONArray(it)) } }
                    val row3 = strings(p.getJSONArray("row3"))
                    require(rows.size == 2 && rows.all { it.size == 10 } && row3.size == 7)
                    require((rows.flatten() + row3).all { it.isNotEmpty() && it.length <= 4 })
                    SymbolPage(rows, row3)
                }.also { require(it.isNotEmpty()) }
            }
        }
        Data(o.optString("version"), lazy, kaomoji, priors, symbols)
    } catch (e: Exception) {
        null
    }

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
}
