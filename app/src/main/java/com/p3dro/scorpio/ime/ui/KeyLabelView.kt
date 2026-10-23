package com.p3dro.scorpio.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import androidx.appcompat.widget.AppCompatTextView

/**
 * 按鍵上的英文、數字標籤：以字形實際的視覺中心置中
 *
 * TextView 置中的是整個行高（含字型的上下留白與降部），小寫字母看起來會偏下。
 * 這裡以參考字形（小寫用 x、大寫用 H、數字用 0）的中心對齊按鍵中心，
 * 同一排的 g、p、b 仍維持自然的高低，不會各自跳動
 */
class KeyLabelView(context: Context) : AppCompatTextView(context) {

    private val bounds = Rect()

    init {
        includeFontPadding = false
    }

    override fun onDraw(canvas: Canvas) {
        val s = text?.toString().orEmpty()
        if (s.isEmpty() || layout == null) {
            super.onDraw(canvas)
            return
        }
        val ref = when {
            s[0].isDigit() -> "0"
            s[0].isUpperCase() -> "H"
            s[0].isLowerCase() -> "x"
            else -> s
        }
        paint.getTextBounds(ref, 0, ref.length, bounds)
        val baseline = layout.getLineBaseline(0) + totalPaddingTop
        val glyphCenter = baseline + (bounds.top + bounds.bottom) / 2f
        val dy = height / 2f - glyphCenter
        canvas.save()
        canvas.translate(0f, dy)
        super.onDraw(canvas)
        canvas.restore()
    }
}
