package com.p3dro.scorpio.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.p3dro.scorpio.ime.engine.KeyHabits
import kotlin.math.exp

/**
 * 設定頁的按鍵落點分佈圖：鍵盤上每個鍵畫一團柔邊的高斯暈開
 * - 暈開的中心 = 你瞄準這個鍵時平均按到的位置，大小 = 落點的散佈（x/y 各自的標準差）
 * - 顏色越深 = 這個鍵的樣本越多；鍵中心到平均落點的細線是習慣偏移的方向
 * 資料只來自 KeyHabits 的統計（平均、變異數、次數），沒有逐筆落點
 *
 * keys：每個鍵中心的位置（鍵距單位，原點為左上角鍵的中心）與鍵上的字；cols/rows 是整張圖的寬高（鍵距）
 */
class TapHeatmapView(
    context: Context,
    private val keys: Map<Char, Pair<Double, Double>>,
    private val cols: Int,
    private val rows: Int,
    private val spreads: Map<Char, KeyHabits.KeySpread>,
    private val keyColor: Int,
    private val labelColor: Int,
    private val heatColor: Int,
    private val label: (Char) -> String = { it.toString() }
) : View(context) {

    companion object {
        // 散佈樣本還不夠時的預設標準差（鍵距）
        private const val DEFAULT_SD = 0.18
        // 暈開畫到幾個標準差（邊緣已淡到約 4%）
        private const val SIGMAS = 2.5
        private const val FULL_SAMPLES = 300.0
        // 鍵高 ÷ 鍵寬（跟實際鍵盤差不多的比例）
        private const val CELL_ASPECT = 1.2f
    }

    private val density = resources.displayMetrics.density
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = keyColor }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = heatColor }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = heatColor
        alpha = 160
        strokeWidth = 1.2f * density
        strokeCap = Paint.Cap.ROUND
    }

    // 半徑 1 的高斯暈開，畫的時候縮放成每個鍵的橢圓；透明度用 paint.alpha 調
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val steps = 16
        val stops = FloatArray(steps + 1) { it / steps.toFloat() }
        val colors = IntArray(steps + 1) { i ->
            val d = stops[i] * SIGMAS
            val a = if (i == steps) 0.0 else exp(-d * d / 2)
            Color.argb((a * 255).toInt(), Color.red(heatColor), Color.green(heatColor), Color.blue(heatColor))
        }
        shader = RadialGradient(0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP)
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w / cols.toFloat() * CELL_ASPECT * rows).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cw = width / cols.toFloat()
        val ch = height / rows.toFloat()
        val gap = 2.5f * density
        val radius = 6f * density
        fun kx(p: Pair<Double, Double>) = (p.first.toFloat() + 0.5f) * cw
        fun ky(p: Pair<Double, Double>) = (p.second.toFloat() + 0.5f) * ch

        for (p in keys.values) {
            rect.set(kx(p) - cw / 2 + gap, ky(p) - ch / 2 + gap, kx(p) + cw / 2 - gap, ky(p) + ch / 2 - gap)
            canvas.drawRoundRect(rect, radius, radius, keyPaint)
        }

        for ((k, p) in keys) {
            val s = spreads[k] ?: continue
            if (s.n < 1.0) continue
            val weight = (s.n / FULL_SAMPLES).coerceIn(0.0, 1.0)
            val rx = (SIGMAS * (s.sdX ?: DEFAULT_SD) * cw).toFloat()
            val ry = (SIGMAS * (s.sdY ?: DEFAULT_SD) * ch).toFloat()
            blobPaint.alpha = (255 * (0.25 + 0.6 * weight)).toInt()
            canvas.save()
            canvas.translate(kx(p) + s.dx.toFloat() * cw, ky(p) + s.dy.toFloat() * ch)
            canvas.scale(rx, ry)
            canvas.drawCircle(0f, 0f, 1f, blobPaint)
            canvas.restore()
        }

        for ((k, p) in keys) {
            val s = spreads[k] ?: continue
            if (s.n < 1.0) continue
            val mx = kx(p) + s.dx.toFloat() * cw
            val my = ky(p) + s.dy.toFloat() * ch
            canvas.drawLine(kx(p), ky(p), mx, my, linePaint)
            canvas.drawCircle(mx, my, 2.5f * density, dotPaint)
        }

        // 標籤最後畫，才不會被暈開與圓點蓋住
        val labelOffset = (labelPaint.descent() + labelPaint.ascent()) / 2
        for ((k, p) in keys) canvas.drawText(label(k), kx(p), ky(p) - labelOffset, labelPaint)
    }
}
