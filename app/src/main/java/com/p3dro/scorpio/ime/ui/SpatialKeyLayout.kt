package com.p3dro.scorpio.ime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 英文鍵盤空間座標觸控定位（比照 ZhuyinGridLayout）
 *
 * 1. 點在某個鍵上就是那個鍵；點在間隙才找最近的按鍵（先找最近的排，再找排內最近的鍵），按鍵間隙不再吞鍵
 * 2. 回報相對按鍵中心的偏移量（半個按鍵為 1），供英文補全的鄰鍵容錯加權
 * 3. 支援多點觸控（雙手拇指輪流敲擊）
 * 4. 最近的鍵若是功能鍵（Shift、刪除、空白…）則不攔截，交還給該按鈕自己的點擊／長按處理
 * 5. 有副鍵的字鍵長按（比照 Gboard）：一個副鍵時泡泡換成它，好幾個時跳出選單左右滑選，放開才送出；
 *    長按成立前滑到別的鍵就照一般點擊，沒有副鍵的鍵按多久都是點擊
 */
class SpatialKeyLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onKeyTapListener: ((char: Char, offsetX: Float, offsetY: Float) -> Unit)? = null
    var onKeyHapticListener: ((view: View) -> Unit)? = null
    var keyPreview: KeyPreview? = null
    /** 預覽泡泡顯示的字（英文依 Shift 狀態顯示大小寫） */
    var previewLabel: (Char) -> String = { it.toString() }
    /** 字鍵的長按副鍵（空的就是沒有，按多久都是點擊） */
    var alternatesFor: ((Char) -> List<String>)? = null
    var keyAlternates: KeyAlternates? = null
    /** 長按放開，option 是選中的副鍵 */
    var onKeyLongPressListener: ((char: Char, option: String) -> Unit)? = null

    private class KeyEntry(val view: View, val char: Char?)

    private class PointerSession(var key: KeyEntry, var offsetX: Float, var offsetY: Float, val downTime: Long, val downX: Float, val downY: Float) {
        var longRunnable: Runnable? = null
        /** 長按成立後選的副鍵（只有一個時）；usesAlternates 是跳出了選單 */
        var longOption: String? = null
        var usesAlternates = false
        var cancelled = false
        val isLong get() = longOption != null || usesAlternates
    }

    private val keys = ArrayList<KeyEntry>()
    private val activeSessions = HashMap<Int, PointerSession>()
    private val tmpRect = Rect()
    private val winLoc = IntArray(2)

    private fun armLongPress(session: PointerSession) {
        session.longRunnable?.let { removeCallbacks(it) }
        session.longRunnable = null
        val char = session.key.char ?: return
        val options = alternatesFor?.invoke(char).orEmpty()
        if (options.isEmpty()) return
        session.longRunnable = Runnable {
            onKeyHapticListener?.invoke(session.key.view)
            if (options.size > 1 && keyAlternates?.show(session, session.key.view, options) == true) {
                session.usesAlternates = true
                keyPreview?.hide(session)
            } else {
                session.longOption = options[0]
                keyPreview?.select(session, options[0])
            }
        }.also { postDelayed(it, LONG_PRESS_MS) }
    }

    private fun endSession(session: PointerSession) {
        session.longRunnable?.let { removeCallbacks(it) }
        session.key.view.isPressed = false
        keyPreview?.hide(session)
    }

    /** 註冊字元鍵（char 非 null）或功能鍵（char 為 null，只用來劃分觸控範圍、不攔截） */
    fun registerKey(view: View, char: Char?) {
        keys.removeAll { it.view === view }
        keys.add(KeyEntry(view, char))
    }

    fun clearKeys() {
        for (session in activeSessions.values) {
            endSession(session)
            keyAlternates?.dismiss(session)
        }
        keys.clear()
        activeSessions.clear()
    }

    companion object {
        /** 跟注音鍵盤的長按一樣 */
        const val LONG_PRESS_MS = 400L
    }

    private class Hit(val key: KeyEntry, val offsetX: Float, val offsetY: Float)

    private fun findNearestKey(x: Float, y: Float): Hit? {
        var best: KeyEntry? = null
        var bestRowDiff = Float.MAX_VALUE
        var bestColDiff = Float.MAX_VALUE
        var bestRect: Rect? = null
        for (k in keys) {
            val v = k.view
            if (!v.isShown || v.width == 0) continue
            v.getDrawingRect(tmpRect)
            offsetDescendantRectToMyCoords(v, tmpRect)
            // 點在鍵上：直接就是它（高矮不同的鍵排在一起時，「最近的排」可能是隔壁比較高的鍵）
            if (tmpRect.contains(x.toInt(), y.toInt())) {
                best = k
                bestRect = Rect(tmpRect)
                break
            }
            val rowDiff = abs(y - tmpRect.exactCenterY())
            val colDiff = abs(x - tmpRect.exactCenterX())
            // 先比排（容許 1px 誤差視為同排），同排再比水平距離
            if (rowDiff < bestRowDiff - 1f || (abs(rowDiff - bestRowDiff) <= 1f && colDiff < bestColDiff)) {
                best = k
                bestRowDiff = rowDiff
                bestColDiff = colDiff
                bestRect = Rect(tmpRect)
            }
        }
        val k = best ?: return null
        val r = bestRect ?: return null
        val halfW = (r.width() / 2f).coerceAtLeast(1f)
        val halfH = (r.height() / 2f).coerceAtLeast(1f)
        val ox = ((x - r.exactCenterX()) / halfW).coerceIn(-1.5f, 1.5f)
        val oy = ((y - r.exactCenterY()) / halfH).coerceIn(-1.5f, 1.5f)
        return Hit(k, ox, oy)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val hit = findNearestKey(ev.x, ev.y) ?: return false
            return hit.key.char != null
        }
        return activeSessions.isNotEmpty()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                // 第一根手指落下時不該有任何泡泡：殘留的（漏收的）一律清掉
                if (event.actionMasked == MotionEvent.ACTION_DOWN) keyPreview?.hideAll()
                val hit = findNearestKey(event.getX(idx), event.getY(idx)) ?: return true
                if (hit.key.char == null) return true
                hit.key.view.isPressed = true
                onKeyHapticListener?.invoke(hit.key.view)
                val session = PointerSession(hit.key, hit.offsetX, hit.offsetY, System.currentTimeMillis(), event.getX(idx), event.getY(idx))
                activeSessions[event.getPointerId(idx)] = session
                keyPreview?.show(session, hit.key.view, previewLabel(hit.key.char))
                armLongPress(session)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val session = activeSessions[event.getPointerId(i)] ?: continue
                    if (session.cancelled) continue
                    val x = event.getX(i)
                    val y = event.getY(i)
                    if (session.usesAlternates) {
                        // 副鍵選單：左右滑選；往下滑離按鍵一個鍵高以上就取消
                        val v = session.key.view
                        v.getDrawingRect(tmpRect)
                        offsetDescendantRectToMyCoords(v, tmpRect)
                        if (y > tmpRect.bottom + tmpRect.height()) {
                            session.cancelled = true
                            endSession(session)
                            keyAlternates?.dismiss(session)
                        } else {
                            getLocationInWindow(winLoc)
                            keyAlternates?.track(session, winLoc[0] + x)
                        }
                        continue
                    }
                    val hit = findNearestKey(x, y) ?: continue
                    if (hit.key.char == null) continue
                    if (session.longOption != null) {
                        // 單一副鍵選好了：滑離這個鍵就取消
                        if (hit.key !== session.key) {
                            session.cancelled = true
                            endSession(session)
                        }
                        continue
                    }
                    // 手指移動太多就不算長按（跟注音鍵盤一樣 28dp）
                    if (kotlin.math.hypot((x - session.downX).toDouble(), (y - session.downY).toDouble()) > 28 * resources.displayMetrics.density) {
                        session.longRunnable?.let { removeCallbacks(it) }
                    }
                    if (hit.key !== session.key) {
                        session.key.view.isPressed = false
                        session.key = hit.key
                        session.key.view.isPressed = true
                        keyPreview?.show(session, hit.key.view, previewLabel(hit.key.char))
                        session.longRunnable?.let { removeCallbacks(it) }
                    }
                    session.offsetX = hit.offsetX
                    session.offsetY = hit.offsetY
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val session = activeSessions.remove(event.getPointerId(event.actionIndex)) ?: return true
                endSession(session)
                val char = session.key.char ?: return true
                when {
                    session.cancelled -> {}
                    session.usesAlternates -> keyAlternates?.dismiss(session)?.let { onKeyLongPressListener?.invoke(char, it) }
                    session.longOption != null -> onKeyLongPressListener?.invoke(char, session.longOption!!)
                    else -> onKeyTapListener?.invoke(char, session.offsetX, session.offsetY)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val now = System.currentTimeMillis()
                for (session in activeSessions.values) {
                    endSession(session)
                    keyAlternates?.dismiss(session)
                    // 手勢中斷但按壓時間 < 350ms 仍判定輸入，杜絕吞鍵
                    if (!session.isLong && !session.cancelled && now - session.downTime < 350L) {
                        session.key.char?.let { onKeyTapListener?.invoke(it, session.offsetX, session.offsetY) }
                    }
                }
                activeSessions.clear()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
