package com.p3dro.scorpio.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 符號鍵盤的全形／半形鍵上的字：「全」「半」疊在同一格，用一條斜線切開，
 * 左上半邊是「全」、右下半邊是「半」；目前用的那一半是主要文字色，另一半淡掉
 */
class WidthToggleGlyph(
    context: Context,
    private val full: Boolean,
    private val activeColor: Int,
    private val dimColor: Int
) : View(context) {

    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dimColor
        strokeWidth = 1.2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = textPaint.textSize * 0.6f
        val l = cx - half
        val t = cy - half
        val r = cx + half
        val b = cy + half
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2

        fun drawHalf(text: String, active: Boolean, upperLeft: Boolean) {
            path.reset()
            if (upperLeft) {
                path.moveTo(l - half, t - half); path.lineTo(r + half, t - half); path.lineTo(l - half, b + half)
            } else {
                path.moveTo(r + half, t - half); path.lineTo(r + half, b + half); path.lineTo(l - half, b + half)
            }
            path.close()
            canvas.save()
            canvas.clipPath(path)
            textPaint.color = if (active) activeColor else dimColor
            canvas.drawText(text, cx, baseline, textPaint)
            canvas.restore()
        }

        drawHalf("全", full, upperLeft = true)
        drawHalf("半", !full, upperLeft = false)
        canvas.drawLine(l, b, r, t, linePaint)
    }
}
