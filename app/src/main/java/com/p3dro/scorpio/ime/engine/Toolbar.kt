package com.p3dro.scorpio.ime.engine

import android.content.Context
import com.p3dro.scorpio.ime.R

/**
 * 工具列左邊的工具：內建的 Emoji、顏文字、手寫，加上安裝的擴充功能
 * 每個都可以在設定裡決定要不要顯示、調整順序（也可以全部不顯示）
 */
object Toolbar {

    data class Tool(val id: String, val name: String, val icon: Int, val visible: Boolean)

    private const val PREFS = "scorpio_ime_prefs"
    private const val PREF_ORDER = "toolbar_order"
    private const val PREF_HIDDEN = "toolbar_hidden"

    private val BUILT_IN = listOf(
        Triple("emoji", "Emoji", R.drawable.ic_ime_emoji),
        Triple("kaomoji", "顏文字", R.drawable.ic_ime_kaomoji),
        Triple("handwriting", "手寫", R.drawable.ic_ime_handwriting)
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun list(context: Context, key: String) = prefs(context).getString(key, "")!!.split(',').filter { it.isNotEmpty() }

    /** 全部的工具（含隱藏的），依設定的順序；新裝的擴充功能排在最後、預設顯示 */
    fun all(context: Context): List<Tool> {
        val available = BUILT_IN.map { (id, name, icon) -> Tool(id, name, icon, true) } +
            Plugins.installed(context).map { Tool("plugin:${it.id}", it.name, Plugins.iconRes(it.icon), true) }
        val order = list(context, PREF_ORDER)
        val hidden = list(context, PREF_HIDDEN).toSet()
        return available
            .sortedBy { t -> order.indexOf(t.id).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { it.copy(visible = it.id !in hidden) }
    }

    fun visible(context: Context) = all(context).filter { it.visible }

    fun setVisible(context: Context, id: String, visible: Boolean) {
        val hidden = list(context, PREF_HIDDEN).toMutableSet()
        if (visible) hidden.remove(id) else hidden.add(id)
        prefs(context).edit().putString(PREF_HIDDEN, hidden.joinToString(",")).apply()
    }

    /** delta = -1 往前、1 往後 */
    fun move(context: Context, id: String, delta: Int) {
        val ids = all(context).map { it.id }.toMutableList()
        val i = ids.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in ids.indices) return
        ids[i] = ids[j].also { ids[j] = id }
        prefs(context).edit().putString(PREF_ORDER, ids.joinToString(",")).apply()
    }

    /** 簽章：工具列設定變了就要重畫 */
    fun signature(context: Context) = visible(context).joinToString(",") { it.id }
}
