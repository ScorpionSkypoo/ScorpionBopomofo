package com.p3dro.scorpio.ime.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.p3dro.scorpio.ime.R

/**
 * Gboard 1:1 風格注音按鍵 View
 * 中央顯示清晰白色注音主符號，右上角顯示淡灰色次要符號（英文 QWERTY 或數字）
 * 按鍵尺寸與邊距維持 2.5dp 原始黃金比例，觸控事件由 ZhuyinGridLayout 空間座標定位接管
 */
class ZhuyinKeyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val primaryTextView = TextView(context)
    private val subscriptTextView = TextView(context)

    var primaryChar: Char = ' '
        private set

    init {
        isClickable = false
        isFocusable = false
        background = ContextCompat.getDrawable(context, R.drawable.bg_keyboard_key)

        // 次要標示（英文字母或數字）位於右上角
        val subParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dpToPx(2)
            marginEnd = dpToPx(4)
        }
        subscriptTextView.textSize = 9.5f
        subscriptTextView.setTextColor(ContextCompat.getColor(context, R.color.ime_text_secondary))
        subscriptTextView.includeFontPadding = false
        addView(subscriptTextView, subParams)

        // 主要注音符號居中
        val primaryParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        primaryTextView.textSize = 18f
        primaryTextView.setTypeface(null, Typeface.NORMAL)
        primaryTextView.setTextColor(ContextCompat.getColor(context, R.color.ime_text_primary))
        primaryTextView.includeFontPadding = false
        addView(primaryTextView, primaryParams)
    }

    fun setKey(zhuyin: Char, subscript: String) {
        this.primaryChar = zhuyin
        primaryTextView.text = zhuyin.toString()
        subscriptTextView.text = subscript
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
