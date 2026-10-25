package com.p3dro.scorpio.ime.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * 鍵盤最外層：每一根手指按下時先告訴 onTapDown（座標相對於這一層），再照常交給裡面的按鍵處理。
 * 用來判斷握法——不管按的是注音、聲調、空白、刪除還是符號鍵，都是手的移動
 */
class TapObserverLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onTapDown: ((x: Float, y: Float, time: Long) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                onTapDown?.invoke(ev.getX(i), ev.getY(i), ev.eventTime)
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
