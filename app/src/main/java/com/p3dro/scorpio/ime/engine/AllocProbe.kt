package com.p3dro.scorpio.ime.engine

import android.os.Debug
import java.util.concurrent.ConcurrentHashMap

/**
 * 開發用（dev/BenchReceiver）：各段程式配置了多少記憶體。
 * 用 ART 的精確配置計數（Debug.startAllocCounting，會讓配置變慢，只在量測時開；art.gc.bytes-allocated 以整塊緩衝計量，分段時不準）。
 * 平常關著，不花任何成本
 */
object AllocProbe {
    @Volatile
    var enabled = false
    val totals = ConcurrentHashMap<String, Long>()

    // 全域計數是 Int（2 GB 會繞回）：差值用 Int 減再轉 Long，一段不超過 2 GB 都對
    @Suppress("DEPRECATION")
    fun now(): Long = if (enabled) Debug.getGlobalAllocSize().toLong() else 0L

    @Suppress("DEPRECATION")
    fun add(name: String, since: Long) {
        if (enabled) totals.merge(name, (Debug.getGlobalAllocSize() - since.toInt()).toLong(), Long::plus)
    }

    @Suppress("DEPRECATION")
    fun start() {
        totals.clear()
        Debug.resetAllCounts()
        Debug.startAllocCounting()
        enabled = true
    }

    @Suppress("DEPRECATION")
    fun stop() {
        enabled = false
        Debug.stopAllocCounting()
    }

    inline fun <T> measure(name: String, block: () -> T): T {
        if (!enabled) return block()
        val a = now()
        try {
            return block()
        } finally {
            add(name, a)
        }
    }
}
