package com.p3dro.scorpio.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.core.content.ContextCompat
import com.p3dro.scorpio.ime.R

/**
 * 使用者自建詞的小鉛筆：像上標一樣貼在詞的右上角，不佔整個字高
 */
object UserWordMark {

    fun apply(context: Context, word: String): CharSequence {
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_user_word)?.mutate() ?: return word
        val s = SpannableString(word + "￼")
        s.setSpan(TopIconSpan(icon, context.resources.displayMetrics.density), word.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private class TopIconSpan(private val icon: Drawable, density: Float) : ReplacementSpan() {
        private val size = (9 * density).toInt()
        private val gap = (1 * density).toInt()

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            gap + size

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            // 對齊中文字的上緣（基線往上約 0.85 個字高）
            val iconTop = (y - paint.textSize * 0.85f).toInt()
            val left = (x + gap).toInt()
            icon.setBounds(left, iconTop, left + size, iconTop + size)
            icon.draw(canvas)
        }
    }
}
