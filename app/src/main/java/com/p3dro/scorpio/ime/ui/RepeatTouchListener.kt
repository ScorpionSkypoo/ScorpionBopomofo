package com.p3dro.scorpio.ime.ui

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

/**
 * 支援單擊與長按連續快速觸發的 TouchListener（專為退格刪除鍵打造）
 */
class RepeatTouchListener(
    private val initialDelayMs: Long = 350L,
    private val repeatIntervalMs: Long = 45L,
    private val onAction: () -> Unit
) : View.OnTouchListener {

    private val handler = Handler(Looper.getMainLooper())
    private var target: View? = null

    private val repeatRunnable = object : Runnable {
        override fun run() {
            // 按著刪除鍵時鍵盤收起或面板重建，收不到放開事件：按鍵不在畫面上就停止連刪
            val v = target
            if (v == null || !v.isAttachedToWindow || !v.isShown) {
                v?.isPressed = false
                target = null
                return
            }
            onAction()
            handler.postDelayed(this, repeatIntervalMs)
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                target = v
                v.isPressed = true
                onAction()
                handler.removeCallbacks(repeatRunnable)
                handler.postDelayed(repeatRunnable, initialDelayMs)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                target = null
                v.isPressed = false
                handler.removeCallbacks(repeatRunnable)
                return true
            }
        }
        return false
    }
}
