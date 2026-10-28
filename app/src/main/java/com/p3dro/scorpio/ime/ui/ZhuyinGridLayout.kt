package com.p3dro.scorpio.ime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 注音 10x4 空間座標觸控定位網格 (Spatial Touch Positioning Layout)
 *
 * 徹底解決「傳統按鍵點擊模式」造成的吞鍵、間隙死區與指腹邊界失誤：
 * 1. 保持原版按鍵 2.5dp 外觀尺寸與比例，不縮減按鍵間距
 * 2. 整個網格 100% 空間無縫映射：即便手指落在 2.5dp 間隙或排距中，亦由 2D Voronoi 座標定位至最近按鍵
 * 3. 採集即時相對中心位移 (offsetX, offsetY)，精確驅動高斯分佈與鄰鍵動態權重
 * 4. 支援多點觸控（雙手拇指極速輪流敲擊不衝突、不吞鍵）
 * 5. 排裡不是注音鍵的鍵（宣告的版面把刪除鍵放在字鍵區，例如三星的鍵位）不參與定位，按在它上面時觸控交還給它自己（長按連刪等）
 */
class ZhuyinGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onKeyTapListener: ((char: Char, offsetX: Float, offsetY: Float) -> Unit)? = null
    /** 長按放開：option 是選中的副鍵（沒有副鍵的鍵就是注音符號本身） */
    var onKeyLongPressListener: ((char: Char, option: String) -> Unit)? = null
    /** 鍵的長按選項（副鍵在前、注音符號本身最後）；空的就只有符號本身 */
    var alternatesFor: ((Char) -> List<String>)? = null
    var keyAlternates: KeyAlternates? = null
    var onKeyHapticListener: ((view: View) -> Unit)? = null
    /** 版面量好後回報「鍵距 ÷ 半鍵寬／高」，供空間模型換算落點 */
    var onPitchMeasured: ((halfKeysPerPitchX: Double, halfKeysPerPitchY: Double) -> Unit)? = null
    var keyPreview: KeyPreview? = null

    private data class PointerSession(
        val pointerId: Int,
        var targetKey: ZhuyinKeyView,
        val downX: Float,
        val downY: Float,
        val downTime: Long,
        var currentOffsetX: Float,
        var currentOffsetY: Float,
        var isLongPressTriggered: Boolean = false,
        // 長按成立時：只有一個選項就是它（泡泡變色），好幾個就跳出選單（usesAlternates）
        var longOption: String? = null,
        var usesAlternates: Boolean = false,
        // 長按成立後手指滑離該鍵：取消，放開時什麼都不送
        var isCancelled: Boolean = false,
        var longPressRunnable: Runnable? = null
    )

    private val activeSessions = mutableMapOf<Int, PointerSession>()
    private val winLoc = IntArray(2)
    private val keyGrid = ArrayList<ArrayList<ZhuyinKeyView>>()
    private val rowViews = ArrayList<LinearLayout>()

    init {
        isClickable = true
        isFocusable = true
    }

    /** 這一串觸控是從注音鍵以外的鍵（刪除鍵）開始的：整串交給那個鍵 */
    private var passThrough = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 按在刪除鍵這類功能鍵上：交給它自己；其餘完全攔截，由空間座標定位系統統籌處理
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> passThrough = isOnOtherKey(ev.x, ev.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (passThrough) {
                passThrough = false
                return false
            }
        }
        return !passThrough
    }

    private fun isOnOtherKey(x: Float, y: Float): Boolean {
        for (row in rowViews) for (j in 0 until row.childCount) {
            val v = row.getChildAt(j)
            if (v is ZhuyinKeyView || v.visibility != View.VISIBLE) continue
            val left = row.left + v.left
            val top = row.top + v.top
            if (x >= left && x < left + v.width && y >= top && y < top + v.height) return true
        }
        return false
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        rebuildKeyGrid()
        val row = keyGrid.firstOrNull()
        // 每次排版都量（換版面時網格大小不變、但鍵寬變了，例如 10 欄換成 11 欄）
        if (row != null && row.size >= 2 && rowViews.size >= 2 && row[0].width > 0 && row[0].height > 0) {
            val rx = (row[1].left - row[0].left) / (row[0].width / 2.0)
            val ry = (rowViews[1].top - rowViews[0].top) / (row[0].height / 2.0)
            onPitchMeasured?.invoke(rx, ry)
        }
    }

    fun rebuildKeyGrid() {
        keyGrid.clear()
        rowViews.clear()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is LinearLayout && child.visibility == View.VISIBLE) {
                rowViews.add(child)
                val rowList = ArrayList<ZhuyinKeyView>()
                for (j in 0 until child.childCount) {
                    val key = child.getChildAt(j)
                    if (key is ZhuyinKeyView) {
                        rowList.add(key)
                    }
                }
                if (rowList.isNotEmpty()) {
                    keyGrid.add(rowList)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || keyGrid.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)

                // 第一根手指落下時不該有任何泡泡：殘留的（漏收的）一律清掉
                if (event.actionMasked == MotionEvent.ACTION_DOWN) keyPreview?.hideAll()
                val hit = findNearestKey(x, y) ?: return true
                val key = hit.key
                if (!key.isEnabled) return true

                key.isPressed = true
                onKeyHapticListener?.invoke(key)

                val session = PointerSession(
                    pointerId = pointerId,
                    targetKey = key,
                    downX = x,
                    downY = y,
                    downTime = System.currentTimeMillis(),
                    currentOffsetX = hit.offsetX,
                    currentOffsetY = hit.offsetY
                )

                // 長按只標記為選中（泡泡變色，或跳出副鍵選單），手指放開才送出
                val runnable = Runnable {
                    val target = session.targetKey
                    session.isLongPressTriggered = true
                    onKeyHapticListener?.invoke(target)
                    val options = alternatesFor?.invoke(target.primaryChar).orEmpty().ifEmpty { listOf(target.primaryChar.toString()) }
                    if (options.size > 1 && keyAlternates?.show(session, target, options) == true) {
                        session.usesAlternates = true
                        keyPreview?.hide(session)
                    } else {
                        session.longOption = options[0]
                        keyPreview?.select(session, options[0])
                    }
                }
                session.longPressRunnable = runnable
                postDelayed(runnable, 400L)

                activeSessions[pointerId] = session
                keyPreview?.show(session, key, key.primaryChar.toString())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val session = activeSessions[pointerId] ?: continue
                    val x = event.getX(i)
                    val y = event.getY(i)
                    if (session.isCancelled) continue

                    if (session.usesAlternates) {
                        // 副鍵選單：左右滑選；往下滑離按鍵一個鍵高以上就取消
                        val key = session.targetKey
                        val parentRow = key.parent as? View
                        val keyBottom = (parentRow?.top ?: 0) + key.bottom
                        if (y > keyBottom + key.height) {
                            session.isCancelled = true
                            session.targetKey.isPressed = false
                            keyAlternates?.dismiss(session)
                        } else {
                            getLocationInWindow(winLoc)
                            keyAlternates?.track(session, winLoc[0] + x)
                        }
                        continue
                    }

                    if (session.isLongPressTriggered) {
                        // 長按後滑離原本的鍵就取消
                        val hit = findNearestKey(x, y)
                        if (hit == null || hit.key != session.targetKey) {
                            session.isCancelled = true
                            session.targetKey.isPressed = false
                            keyPreview?.hide(session)
                        }
                        continue
                    }

                    val dist = hypot((x - session.downX).toDouble(), (y - session.downY).toDouble()).toFloat()
                    if (dist > dpToPx(28)) {
                        session.longPressRunnable?.let { removeCallbacks(it) }
                    }

                    // 檢查是否滑動至其他按鍵
                    val hit = findNearestKey(x, y)
                    if (hit != null && hit.key != session.targetKey) {
                        session.targetKey.isPressed = false
                        if (hit.key.isEnabled) {
                            session.targetKey = hit.key
                            session.targetKey.isPressed = true
                            session.longPressRunnable?.let { removeCallbacks(it) }
                            keyPreview?.show(session, session.targetKey, session.targetKey.primaryChar.toString())
                        }
                    }

                    val offset = findOffsetForKey(session.targetKey, x, y)
                    session.currentOffsetX = offset.first
                    session.currentOffsetY = offset.second
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                handlePointerUp(pointerId)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                for (id in activeSessions.keys.toList()) {
                    val session = activeSessions.remove(id) ?: continue
                    session.longPressRunnable?.let { removeCallbacks(it) }
                    session.targetKey.isPressed = false
                    keyPreview?.hide(session)
                    keyAlternates?.dismiss(session)
                    // 手勢中斷但按壓時間 < 350ms 仍判定輸入，杜絕吞鍵
                    if (!session.isLongPressTriggered && (System.currentTimeMillis() - session.downTime) < 350L) {
                        onKeyTapListener?.invoke(session.targetKey.primaryChar, session.currentOffsetX, session.currentOffsetY)
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handlePointerUp(pointerId: Int) {
        val session = activeSessions.remove(pointerId) ?: return
        session.longPressRunnable?.let { removeCallbacks(it) }
        session.targetKey.isPressed = false
        keyPreview?.hide(session)

        val char = session.targetKey.primaryChar
        when {
            session.isCancelled -> {}
            session.usesAlternates -> keyAlternates?.dismiss(session)?.let { onKeyLongPressListener?.invoke(char, it) }
            session.isLongPressTriggered -> onKeyLongPressListener?.invoke(char, session.longOption ?: char.toString())
            else -> onKeyTapListener?.invoke(char, session.currentOffsetX, session.currentOffsetY)
        }
    }

    private data class HitResult(val key: ZhuyinKeyView, val offsetX: Float, val offsetY: Float)

    /**
     * 2D 空間 Voronoi 座標定位：
     * 將點擊 (x, y) 映射到最近按鍵中心，按鍵間隙與排距全域感應
     */
    private fun findNearestKey(x: Float, y: Float): HitResult? {
        if (keyGrid.isEmpty()) {
            rebuildKeyGrid()
            if (keyGrid.isEmpty()) return null
        }

        // 1. 垂直方向定位：找出最靠近的 Row
        var bestRowIdx = 0
        var minRowDiff = Float.MAX_VALUE
        for (i in 0 until rowViews.size) {
            val row = rowViews[i]
            val rowCenterY = row.top + row.height / 2.0f
            val diff = abs(y - rowCenterY)
            if (diff < minRowDiff) {
                minRowDiff = diff
                bestRowIdx = i
            }
        }

        val rowList = keyGrid.getOrNull(bestRowIdx) ?: return null
        if (rowList.isEmpty()) return null
        val rowView = rowViews[bestRowIdx]

        // 2. 水平方向定位：找出該 Row 中最靠近的 Key
        var bestKey: ZhuyinKeyView = rowList[0]
        var minKeyDiff = Float.MAX_VALUE
        var bestCenterX = 0f
        var bestCenterY = rowView.top + rowView.height / 2.0f
        var bestHalfW = 1f
        var bestHalfH = if (rowView.height > 0) rowView.height / 2.0f else 1f

        for (key in rowList) {
            val halfW = if (key.width > 0) key.width / 2.0f else 1f
            val halfH = if (key.height > 0) key.height / 2.0f else 1f
            val centerX = rowView.left + key.left + halfW
            val centerY = rowView.top + key.top + halfH

            val diff = abs(x - centerX)
            if (diff < minKeyDiff) {
                minKeyDiff = diff
                bestKey = key
                bestCenterX = centerX
                bestCenterY = centerY
                bestHalfW = halfW
                bestHalfH = halfH
            }
        }

        val offsetX = ((x - bestCenterX) / bestHalfW).coerceIn(-1.5f, 1.5f)
        val offsetY = ((y - bestCenterY) / bestHalfH).coerceIn(-1.5f, 1.5f)

        return HitResult(bestKey, offsetX, offsetY)
    }

    private fun findOffsetForKey(key: ZhuyinKeyView, x: Float, y: Float): Pair<Float, Float> {
        val parentRow = key.parent as? View
        val rowLeft = parentRow?.left?.toFloat() ?: 0f
        val rowTop = parentRow?.top?.toFloat() ?: 0f
        val halfW = if (key.width > 0) key.width / 2.0f else 1f
        val halfH = if (key.height > 0) key.height / 2.0f else 1f
        val centerX = rowLeft + key.left + halfW
        val centerY = rowTop + key.top + halfH

        val offsetX = ((x - centerX) / halfW).coerceIn(-1.5f, 1.5f)
        val offsetY = ((y - centerY) / halfH).coerceIn(-1.5f, 1.5f)
        return Pair(offsetX, offsetY)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
