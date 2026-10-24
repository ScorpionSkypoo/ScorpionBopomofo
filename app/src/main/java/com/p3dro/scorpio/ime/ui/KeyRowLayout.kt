package com.p3dro.scorpio.ime.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * 最下排共用的鍵位：注音、英文、符號鍵盤的最下排都照同一組欄位排，切換鍵盤時共有的鍵（符號／返回、逗號、地球、Enter）
 * 像素完全重合。一般 LinearLayout 用權重分寬度時是「總寬扣掉所有鍵的間距再分」，鍵數不同（大千注音多一個 ㄦ）邊界就會差幾個像素。
 *
 * 欄位寬度比例是大千注音的最下排 [SLOTS]；每一段 [Segment] 蓋住幾個欄位，可以再依權重切成幾個鍵（鍵與鍵之間同樣留一個間距）。
 * 上面的字鍵排用 setColumnSpans：照版面宣告（KeyboardLayout）的欄位精確排。
 * 兩種都沒設定、或鍵數對不上時跟一般 LinearLayout 一樣
 */
class KeyRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    class Segment(val from: Int, val to: Int = from, val weights: List<Float> = listOf(1f))

    var segments: List<Segment>? = null
        set(value) {
            field = value
            requestLayout()
        }

    /**
     * 字鍵排（上面幾排）：每個鍵的起點與寬度（欄，KeyboardLayout.Key），一欄＝排寬 ÷ columns。
     * 每個鍵照欄位精確排，不同排同一欄的鍵上下對齊，落點模型的座標跟畫面一致
     */
    private var columnSpans: List<Pair<Double, Double>>? = null
    private var columns = 10

    fun setColumnSpans(columns: Int, spans: List<Pair<Double, Double>>) {
        this.columns = columns
        columnSpans = spans
        requestLayout()
    }

    /** 按鍵左右各 2.5dp，跟版面檔一樣四捨五入 */
    private val keyMargin = (2.5f * resources.displayMetrics.density + 0.5f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val segs = segments
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val spans = columnSpans
        val bounds = when {
            width <= 0 -> null
            spans != null && spans.size == childCount -> {
                val unit = width.toDouble() / columns
                spans.map { (start, w) -> intArrayOf(Math.round(start * unit).toInt() + keyMargin, Math.round((start + w) * unit).toInt() - keyMargin) }
            }
            segs != null && segs.sumOf { it.weights.size } == childCount -> {
                val slots = slotBounds(width, keyMargin)
                segs.flatMap { s -> split(slots[s.from][0], slots[s.to][1], s.weights, 2 * keyMargin) }
            }
            else -> null
        }
        if (bounds != null) {
            var x = 0
            for (i in 0 until childCount) {
                val lp = getChildAt(i).layoutParams as LayoutParams
                val (l, r) = bounds[i]
                lp.weight = 0f
                lp.width = r - l
                lp.leftMargin = l - x
                lp.rightMargin = 0
                x = r
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    companion object {
        /** 欄位寬度比例：符號、逗號、地球、空白、ㄦ、刪除、Enter（總和 10.4） */
        val SLOTS = floatArrayOf(1.4f, 0.9f, 0.9f, 3.3f, 0.9f, 1.5f, 1.5f)

        /** 大千注音：一鍵一欄 */
        val ZHUYIN = (0..6).map { Segment(it) }

        /** 英文／QWERTY：空白＋句號蓋住空白、ㄦ、刪除三欄（照原本的 4.7：1.0 分） */
        val QWERTY = listOf(Segment(0), Segment(1), Segment(2), Segment(3, 5, listOf(4.7f, 1.0f)), Segment(6))

        /** 從大千注音來的符號鍵盤：空白蓋住空白＋ㄦ，句號在刪除鍵的位置 */
        val SYMBOLS_FROM_ZHUYIN = listOf(Segment(0), Segment(1), Segment(2), Segment(3, 4), Segment(5), Segment(6))

        /** 數字欄的數字鍵盤：數字三欄平分第 1～6 欄，右欄（－ 空白 ⌫ Enter）在 Enter 的欄位 */
        val NUMPAD = listOf(Segment(0, 5, listOf(1f, 1f, 1f)), Segment(6))

        /** 手寫面板（返回、，、空白、。、刪除、Enter）：空白蓋住地球＋空白，。在 ㄦ 的位置 */
        val HANDWRITING = listOf(Segment(0), Segment(1), Segment(2, 3), Segment(4), Segment(5), Segment(6))

        /** 每個欄位按鍵的左右邊界（相對於排的內容區）：每個鍵左右各留 margin，其餘依比例分，累計後四捨五入 */
        fun slotBounds(width: Int, margin: Int): List<IntArray> {
            val usable = width - 2 * margin * SLOTS.size
            val total = SLOTS.sum()
            var acc = 0f
            return SLOTS.mapIndexed { i, w ->
                val left = margin * (2 * i + 1) + Math.round(usable * acc / total)
                acc += w
                val right = if (i == SLOTS.lastIndex) width - margin else margin * (2 * i + 1) + Math.round(usable * acc / total)
                intArrayOf(left, right)
            }
        }

        /** 把 left～right 依權重切成幾個鍵，鍵與鍵之間留 gap */
        fun split(left: Int, right: Int, weights: List<Float>, gap: Int): List<IntArray> {
            val usable = right - left - gap * (weights.size - 1)
            val total = weights.sum()
            var acc = 0f
            return weights.mapIndexed { i, w ->
                val start = left + Math.round(usable * acc / total) + gap * i
                acc += w
                val end = if (i == weights.lastIndex) right else left + Math.round(usable * acc / total) + gap * i
                intArrayOf(start, end)
            }
        }
    }
}
