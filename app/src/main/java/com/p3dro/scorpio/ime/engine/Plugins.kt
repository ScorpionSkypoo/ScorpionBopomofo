package com.p3dro.scorpio.ime.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 工具列擴充功能（外掛）：宣告式 JSON，只能組合下面這幾種內建動作，不含可執行的程式碼
 *   insert     插入文字（可用 {date:格式}、{time:格式}、{weekday}、{weekday:short}、{roc}、{selection}）
 *   transform  轉換選取的文字（fullwidth、halfwidth、upper、lower、title、trim_spaces）
 *   open_url   用選取的文字開網址（只接受 https，{selection} 會先 URL 編碼）
 * 外掛從資料 repo（scorpio-ime-data）的 plugins/ 線上安裝，格式見該 repo 的 README
 */
object Plugins {

    const val SCHEMA = 1

    data class Item(val label: String, val action: String, val insert: String?, val op: String?, val url: String?)
    data class Tab(val name: String, val items: List<Item>)
    data class Plugin(
        val id: String,
        val name: String,
        val icon: String,
        val description: String,
        val version: String,
        val tabs: List<Tab>
    )

    fun parse(json: String): Plugin? = try {
        val o = JSONObject(json)
        if (o.optInt("schema", 1) > SCHEMA) null else Plugin(
            o.getString("id"), o.getString("name"), o.optString("icon", ""), o.optString("description", ""),
            o.optString("version", ""), o.getJSONArray("tabs").objects().map { t ->
                Tab(t.getString("name"), t.getJSONArray("items").let { arr ->
                    (0 until arr.length()).mapNotNull { i -> parseItem(arr.get(i)) }
                })
            }
        )
    } catch (e: Exception) {
        null
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun parseItem(raw: Any): Item? = when (raw) {
        is String -> Item(raw, "insert", raw, null, null)
        is JSONObject -> {
            val action = raw.optString("action", "insert")
            val item = Item(
                raw.getString("label"), action, raw.optString("insert").ifEmpty { null },
                raw.optString("op").ifEmpty { null }, raw.optString("url").ifEmpty { null }
            )
            when {
                action == "insert" && item.insert != null -> item
                action == "transform" && item.op in TRANSFORMS -> item
                action == "open_url" && item.url?.startsWith("https://") == true -> item
                else -> null  // 不認得的動作直接略過（新版外掛在舊 App 上不會出錯）
            }
        }
        else -> null
    }

    private val TOKEN = Regex("\\{(date|time|weekday|roc|selection)(?::([^}]*))?\\}")
    private val WEEKDAYS = listOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
    private val WEEKDAYS_SHORT = listOf("日", "一", "二", "三", "四", "五", "六")

    /** 展開文字裡的 {date:…} 等標記；encode 用在網址（{selection} 會 URL 編碼） */
    fun expand(template: String, now: Date = Date(), selection: String = "", encode: Boolean = false): String {
        val cal = Calendar.getInstance().apply { time = now }
        return TOKEN.replace(template) { m ->
            val arg = m.groupValues[2]
            when (m.groupValues[1]) {
                "date", "time" -> SimpleDateFormat(arg.ifEmpty { if (m.groupValues[1] == "date") "yyyy/MM/dd" else "HH:mm" }, Locale.TAIWAN).format(now)
                "weekday" -> (if (arg == "short") WEEKDAYS_SHORT else WEEKDAYS)[cal.get(Calendar.DAY_OF_WEEK) - 1]
                "roc" -> (cal.get(Calendar.YEAR) - 1911).toString()
                "selection" -> if (encode) java.net.URLEncoder.encode(selection, "UTF-8") else selection
                else -> m.value
            }
        }
    }

    val TRANSFORMS = setOf("fullwidth", "halfwidth", "upper", "lower", "title", "trim_spaces")

    fun transform(op: String, text: String): String = when (op) {
        "fullwidth" -> text.map { c ->
            when (c) {
                ' ' -> '　'
                in '!'..'~' -> c + 0xFEE0
                else -> c
            }
        }.joinToString("")
        "halfwidth" -> text.map { c ->
            when (c) {
                '　' -> ' '
                in '！'..'～' -> c - 0xFEE0
                else -> c
            }
        }.joinToString("")
        "upper" -> text.uppercase()
        "lower" -> text.lowercase()
        "title" -> text.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        "trim_spaces" -> text.replace(Regex("[ \\t\\u3000]+"), "")
        else -> text
    }

    // ---------- 安裝在手機上的外掛 ----------

    private fun dir(context: Context) = File(context.filesDir, "plugins").apply { mkdirs() }
    private fun prefs(context: Context) = context.getSharedPreferences("scorpio_plugins", Context.MODE_PRIVATE)

    fun installed(context: Context): List<Plugin> =
        (dir(context).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { parse(it.readText()) }
            .sortedBy { order(context).indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }

    fun installedVersion(context: Context, id: String): String? =
        File(dir(context), "$id.json").takeIf { it.exists() }?.let { parse(it.readText())?.version }

    /** 安裝或更新：json 驗證過才寫入 */
    fun install(context: Context, json: String): Plugin? {
        val p = parse(json) ?: return null
        File(dir(context), "${p.id}.json").writeText(json)
        val ids = order(context)
        if (p.id !in ids) prefs(context).edit().putString("order", (ids + p.id).joinToString(",")).apply()
        return p
    }

    fun uninstall(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
        prefs(context).edit()
            .putString("order", order(context).filter { it != id }.joinToString(","))
            .remove("pin:$id")
            .apply()
    }

    private fun order(context: Context) = prefs(context).getString("order", "")!!.split(',').filter { it.isNotEmpty() }

    /** 釘選到工具列：在工具列上直接有一顆按鈕 */
    fun isPinned(context: Context, id: String) = prefs(context).getBoolean("pin:$id", false)
    fun setPinned(context: Context, id: String, pinned: Boolean) = prefs(context).edit().putBoolean("pin:$id", pinned).apply()
}
