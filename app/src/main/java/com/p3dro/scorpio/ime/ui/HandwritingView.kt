package com.p3dro.scorpio.ime.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.digitalink.Ink
import com.p3dro.scorpio.ime.R

/**
 * 手寫板：記錄筆畫（座標＋時間）給 ML Kit 辨識，同時畫出筆跡
 *
 * 連續書寫：一個字送出後呼叫 fadeOut()，舊筆跡淡出（不擋住下一個字），筆畫資料清空；
 * 淡出期間就能接著寫下一個字
 */
class HandwritingView(context: Context) : View(context) {

    /** 每寫完一筆（目前這個字的全部筆跡） */
    var onStrokeFinished: ((Ink) -> Unit)? = null

    /** 開始寫下一筆（落筆位置） */
    var onStrokeStarted: ((x: Float, y: Float) -> Unit)? = null

    /** 沒有筆跡時顯示的提示（例如模型下載進度） */
    var hint: String = "在這裡手寫"
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ime_text_primary)
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fadePaint = Paint(inkPaint)
    private val baseAlpha = inkPaint.alpha
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ime_text_secondary)
        textSize = 16 * density
        textAlign = Paint.Align.CENTER
    }

    private val paths = ArrayList<Path>()
    private var fading: List<Path> = emptyList()
    private var fadeAlpha = 0f
    private var fadeAnimator: ValueAnimator? = null
    private var current: Path? = null
    private var inkBuilder = Ink.builder()
    private var strokeBuilder: Ink.Stroke.Builder? = null
    private var strokeCount = 0
    // 目前這個字的筆跡範圍
    private val bounds = android.graphics.RectF()

    /** 目前這個字還沒有筆畫（淡出中的舊字不算） */
    val isEmpty: Boolean get() = strokeCount == 0

    private fun resetInk() {
        bounds.setEmpty()
        paths.clear()
        current = null
        inkBuilder = Ink.builder()
        strokeBuilder = null
        strokeCount = 0
    }

    /** 目前的字已送出：筆跡淡出，筆畫資料清空，可以馬上寫下一個字 */
    fun fadeOut() {
        fadeAnimator?.cancel()
        fading = ArrayList(paths)
        resetInk()
        fadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 350
            addUpdateListener {
                fadeAlpha = it.animatedValue as Float
                if (fadeAlpha <= 0f) fading = emptyList()
                invalidate()
            }
            start()
        }
    }

    fun clear() {
        fadeAnimator?.cancel()
        fading = emptyList()
        resetInk()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val t = System.currentTimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onStrokeStarted?.invoke(x, y)
                current = Path().apply { moveTo(x, y) }.also { paths.add(it) }
                if (bounds.isEmpty && strokeCount == 0) bounds.set(x, y, x, y) else bounds.union(x, y)
                strokeBuilder = Ink.Stroke.builder().apply { addPoint(Ink.Point.create(x, y, t)) }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i)
                    current?.lineTo(hx, hy)
                    strokeBuilder?.addPoint(Ink.Point.create(hx, hy, event.getHistoricalEventTime(i) - event.eventTime + t))
                }
                current?.lineTo(x, y)
                strokeBuilder?.addPoint(Ink.Point.create(x, y, t))
                bounds.union(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.lineTo(x, y)
                strokeBuilder?.let {
                    it.addPoint(Ink.Point.create(x, y, t))
                    inkBuilder.addStroke(it.build())
                    strokeCount++
                }
                strokeBuilder = null
                current = null
                onStrokeFinished?.invoke(inkBuilder.build())
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (fading.isNotEmpty() && fadeAlpha > 0f) {
            fadePaint.alpha = (fadeAlpha * baseAlpha).toInt()
            for (p in fading) canvas.drawPath(p, fadePaint)
        }
        if (paths.isEmpty() && fading.isEmpty()) {
            canvas.drawText(hint, width / 2f, height / 2f, hintPaint)
            return
        }
        for (p in paths) canvas.drawPath(p, inkPaint)
    }
}
