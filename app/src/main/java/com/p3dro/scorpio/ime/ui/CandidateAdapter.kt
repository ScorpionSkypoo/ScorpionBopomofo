package com.p3dro.scorpio.ime.ui

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.p3dro.scorpio.ime.R

class CandidateAdapter(
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<CandidateAdapter.ViewHolder>() {

    var onLongSelect: ((String) -> Boolean)? = null

    private val items = mutableListOf<String>()

    // 使用者自建詞（系統詞典沒有、後天學到的）：顯示小鉛筆，且只有這些可以長按刪除
    private var userWords: Set<String> = emptySet()

    val currentList: List<String>
        get() = items

    val topCandidate: String?
        get() = items.firstOrNull()

    /**
     * 換一份候選：只通知哪幾格變了（不用 notifyDataSetChanged）。格子原地重新綁定，不必重建；
     * 候選列固定大小（setHasFixedSize），只有列表自己重新排版，不會讓整個鍵盤跟著重新量一次
     */
    fun submitList(newItems: List<String>) {
        val old = items.size
        items.clear()
        items.addAll(newItems)
        userWords = emptySet()
        val n = items.size
        if (minOf(old, n) > 0) notifyItemRangeChanged(0, minOf(old, n))
        if (n > old) notifyItemRangeInserted(old, n - old) else if (old > n) notifyItemRangeRemoved(n, old - n)
    }

    /** 候選更新後非同步標記使用者自建詞（只在清單沒換掉時套用） */
    fun markUserWords(forItems: List<String>, words: Set<String>) {
        if (forItems != items || words == userWords) return
        userWords = words
        notifyItemRangeChanged(0, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_candidate, parent, false)
        return ViewHolder(view).also { it.tvCandidate.textLocale = java.util.Locale.TAIWAN }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val text = items[position]
        val isUserWord = text in userWords
        val tv = holder.tvCandidate
        val density = tv.resources.displayMetrics.density
        tv.text = if (isUserWord) UserWordMark.apply(tv.context, text) else text
        // 鉛筆佔用右側留白，字的位置與一般候選相同
        tv.setPaddingRelative(tv.paddingStart, 0, ((if (isUserWord) 5 else 15) * density).toInt(), 0)
        holder.itemView.setOnClickListener {
            // 選到候選字不觸發任何震動（依使用者要求維持零震動手感）
            onSelect(text)
        }
        if (isUserWord) {
            holder.itemView.setOnLongClickListener { onLongSelect?.invoke(text) ?: false }
        } else {
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.isLongClickable = false
        }
        // 最後一項隱藏右邊分隔線
        holder.divider.visibility = if (position == items.size - 1) View.GONE else View.VISIBLE
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCandidate: TextView = view.findViewById(R.id.tv_candidate)
        val divider: View = view.findViewById(R.id.candidate_divider)
    }
}
