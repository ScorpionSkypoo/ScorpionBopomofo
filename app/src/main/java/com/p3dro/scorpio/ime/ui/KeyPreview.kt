package com.p3dro.scorpio.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.p3dro.scorpio.ime.R
import java.util.IdentityHashMap

/**
 * 按鍵預覽泡泡（比照 Gboard）：手指按住時在按鍵上方顯示按到的字，長按期間持續顯示
 *
 * 泡泡下緣收成尖角，指向按下的鍵。每顆泡泡是一個獨立的小浮動視窗（PopupWindow），
 * 可以超出鍵盤頂端；收起時直接關掉視窗——畫在鍵盤視窗的透明區時，Samsung 上舊像素會殘留
 * 每個觸控點一顆泡泡（雙手連打互不干擾）
 */
class KeyPreview(private val root: ViewGroup) {

    private val density = root.resources.displayMetrics.density

    // 以物件身分當鍵：觸控 session 是可變的 data class，hashCode 會隨滑動改變
    private val active = IdentityHashMap<Any, Popup>()
    private val pool = ArrayList<Popup>()
    private val loc = IntArray(2)
    private val rootLoc = IntArray(2)

    /** 放開後泡泡多留一下，快速點擊也看得到 */
    private val hideDelayMs = 70L

    var enabled = true

    companion object {
        /** 尖角到按鍵上緣的距離 */
        const val TIP_GAP_DP = 11f
    }

    private class Popup(val bubble: Bubble) {
        val window = PopupWindow(bubble).apply {
            isClippingEnabled = false
            isTouchable = false
            isFocusable = false
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            animationStyle = 0
            setBackgroundDrawable(null)
            elevation = 0f
        }
        /** 遞增：延遲關閉時確認這顆泡泡沒被重新拿去用 */
        var generation = 0
    }

    private fun px(dp: Float) = (dp * density).toInt()

    /** token 代表一次按壓（同一根手指滑到別的鍵時用同一個 token 更新位置） */
    fun show(token: Any, anchor: View, label: String) {
        if (!enabled || !root.isAttachedToWindow || root.windowToken == null || anchor.width == 0 || label.isBlank()) return
        val popup = active[token] ?: (pool.removeLastOrNull() ?: Popup(Bubble(root.context, density))).also {
            it.generation++
            active[token] = it
        }
        val bubble = popup.bubble

        anchor.getLocationInWindow(loc)
        root.getLocationInWindow(rootLoc)
        val keyCenterX = loc[0] + anchor.width / 2
        val keyTop = loc[1]

        val w = maxOf((anchor.width * 1.3f).toInt(), px(48f))
        val bodyH = px(52f)
        val tailH = px(10f)
        val h = bodyH + tailH
        val left = (keyCenterX - w / 2).coerceIn(rootLoc[0], maxOf(rootLoc[0], rootLoc[0] + root.width - w))
        // 尖角與按鍵之間留一段距離，手指按著也不會擋住泡泡；可以超出鍵盤頂端
        val top = keyTop - px(TIP_GAP_DP) - h

        bubble.label = label
        bubble.chosen = false
        bubble.bodyHeight = bodyH.toFloat()
        bubble.tipX = (keyCenterX - left).toFloat()
        bubble.invalidate()
        try {
            if (popup.window.isShowing) {
                popup.window.update(left, top, w, h)
            } else {
                popup.window.width = w
                popup.window.height = h
                popup.window.showAtLocation(root, Gravity.NO_GRAVITY, left, top)
            }
        } catch (e: Exception) {
            // 視窗正在關閉（token 失效）：這次不顯示就好
            active.remove(token)
        }
    }

    /** 長按成立：泡泡變成選中色，放開才送出 */
    fun select(token: Any) {
        val popup = active[token] ?: return
        popup.bubble.chosen = true
        popup.bubble.invalidate()
    }

    fun hide(token: Any) {
        val popup = active.remove(token) ?: return
        val gen = popup.generation
        root.postDelayed({
            // 期間被 hideAll 收過或又被拿去用了，就不要再動它
            if (popup.generation != gen || popup in pool) return@postDelayed
            dismiss(popup)
            pool.add(popup)
        }, hideDelayMs)
    }

    /** 收掉所有泡泡（含延遲關閉中的） */
    fun hideAll() {
        for (p in active.values) {
            p.generation++
            dismiss(p)
            if (p !in pool) pool.add(p)
        }
        active.clear()
        for (p in pool) dismiss(p)
    }

    private fun dismiss(p: Popup) {
        try {
            if (p.window.isShowing) p.window.dismiss()
        } catch (_: Exception) {
        }
    }

    /** 圓角泡泡＋下方尖角 */
    class Bubble(context: Context, private val density: Float) : View(context) {
        var label = ""
        var bodyHeight = 0f
        var tipX = 0f
        var chosen = false

        private val normalFill = ContextCompat.getColor(context, R.color.ime_key_bg)
        private val normalText = ContextCompat.getColor(context, R.color.ime_text_primary)
        private val selectedFill = ContextCompat.getColor(context, R.color.ime_key_action_bg)
        private val selectedText = ContextCompat.getColor(context, R.color.ime_key_action_icon)

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.ime_key_bg)
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.ime_key_pressed)
            style = Paint.Style.STROKE
            strokeWidth = density
            strokeJoin = Paint.Join.ROUND
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.ime_text_primary)
            textSize = 26 * density * context.resources.configuration.fontScale
            textAlign = Paint.Align.CENTER
            textLocale = java.util.Locale.TAIWAN
        }
        private val path = Path()
        private val tail = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val inset = density / 2
            val w = width.toFloat()
            val radius = 10 * density
            rect.set(inset, inset, w - inset, bodyHeight)
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)

            // 尖角底寬隨泡泡寬度，靠邊時夾在圓角內側
            val half = minOf(w * 0.22f, 14 * density)
            val baseX = tipX.coerceIn(radius + half, w - radius - half)
            tail.reset()
            tail.moveTo(baseX - half, bodyHeight - radius)
            tail.lineTo(baseX + half, bodyHeight - radius)
            tail.lineTo(tipX.coerceIn(inset, w - inset), height - inset)
            tail.close()
            path.op(tail, Path.Op.UNION)

            fill.color = if (chosen) selectedFill else normalFill
            text.color = if (chosen) selectedText else normalText
            canvas.drawPath(path, fill)
            if (!chosen) canvas.drawPath(path, stroke)
            val fm = text.fontMetrics
            canvas.drawText(label, w / 2, bodyHeight / 2 - (fm.ascent + fm.descent) / 2, text)
        }
    }
}
