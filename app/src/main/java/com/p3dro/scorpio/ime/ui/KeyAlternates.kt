package com.p3dro.scorpio.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.p3dro.scorpio.ime.R

/**
 * 長按副鍵的選單（比照 Gboard）：按鍵上方跳出一排選項，手指左右滑動選，放開輸入選中的那個。
 * 第一個選項對準按下的鍵，往右排；右邊放不下就整排往左移。一次只會有一個（長按中的那根手指）
 */
class KeyAlternates(private val root: ViewGroup) {

    private val density = root.resources.displayMetrics.density
    private val strip = Strip(root.context, density)
    private val window = PopupWindow(strip).apply {
        isClippingEnabled = false
        isTouchable = false
        isFocusable = false
        isOutsideTouchable = false
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        animationStyle = 0
        setBackgroundDrawable(null)
        elevation = 0f
    }
    private val loc = IntArray(2)
    private val rootLoc = IntArray(2)

    /** 選單的左緣、每格寬（視窗座標），手指位置換算成第幾個 */
    private var left = 0
    private var cellW = 1

    /** 目前的 token（長按中的觸控）；別的 token 來收或更新不理 */
    private var owner: Any? = null

    val isShowing: Boolean get() = owner != null

    private fun px(dp: Float) = (dp * density).toInt()

    /** 在 anchor 上方顯示 options，先選第一個 */
    fun show(token: Any, anchor: View, options: List<String>): Boolean {
        if (!root.isAttachedToWindow || root.windowToken == null || anchor.width == 0 || options.isEmpty()) return false
        anchor.getLocationInWindow(loc)
        root.getLocationInWindow(rootLoc)
        cellW = maxOf(anchor.width, px(36f))
        val w = cellW * options.size
        val h = px(52f)
        val keyCenterX = loc[0] + anchor.width / 2
        left = (keyCenterX - cellW / 2).coerceIn(rootLoc[0], maxOf(rootLoc[0], rootLoc[0] + root.width - w))
        val top = loc[1] - px(KeyPreview.TIP_GAP_DP) - h
        strip.options = options
        strip.selected = 0
        strip.invalidate()
        return try {
            if (window.isShowing) {
                window.update(left, top, w, h)
            } else {
                window.width = w
                window.height = h
                window.showAtLocation(root, Gravity.NO_GRAVITY, left, top)
            }
            owner = token
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 手指在視窗座標 x：選最近的那格（超出兩端就是頭尾那個） */
    fun track(token: Any, windowX: Float) {
        if (token !== owner) return
        val i = ((windowX - left) / cellW).toInt().coerceIn(0, strip.options.size - 1)
        if (i != strip.selected) {
            strip.selected = i
            strip.invalidate()
        }
    }

    /** 收起；回傳選中的選項（沒在顯示就是 null） */
    fun dismiss(token: Any): String? {
        if (token !== owner) return null
        owner = null
        val chosen = strip.options.getOrNull(strip.selected)
        try {
            if (window.isShowing) window.dismiss()
        } catch (_: Exception) {
        }
        return chosen
    }

    fun dismissAll() {
        owner?.let { dismiss(it) }
    }

    private class Strip(context: Context, private val density: Float) : View(context) {
        var options: List<String> = emptyList()
        var selected = 0

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ime_key_bg) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.ime_key_pressed)
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val chosenFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ime_key_action_bg) }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textLocale = java.util.Locale.TAIWAN
        }
        private val normalText = ContextCompat.getColor(context, R.color.ime_text_primary)
        private val chosenText = ContextCompat.getColor(context, R.color.ime_key_action_icon)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            if (options.isEmpty()) return
            val inset = density / 2
            val radius = 10 * density
            rect.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.drawRoundRect(rect, radius, radius, stroke)
            val cw = width.toFloat() / options.size
            val scale = resources.configuration.fontScale
            for ((i, s) in options.withIndex()) {
                if (i == selected) {
                    rect.set(i * cw + 3 * density, 3 * density, (i + 1) * cw - 3 * density, height - 3 * density)
                    canvas.drawRoundRect(rect, radius * 0.7f, radius * 0.7f, chosenFill)
                }
                // 好幾個字的選項（.com）字小一點才放得下
                text.textSize = (if (s.length > 2) 15 else 22) * density * scale
                text.color = if (i == selected) chosenText else normalText
                val fm = text.fontMetrics
                canvas.drawText(s, i * cw + cw / 2, height / 2f - (fm.ascent + fm.descent) / 2, text)
            }
        }
    }
}
