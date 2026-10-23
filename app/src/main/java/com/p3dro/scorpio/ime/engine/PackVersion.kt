package com.p3dro.scorpio.ime.engine

/** 資料包版本號：以 . 分段的數字（2026.10.23），逐段比大小；空字串最舊 */
object PackVersion {

    fun compare(a: String, b: String): Int {
        val x = a.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { -1 }).compareTo(y.getOrElse(i) { -1 })
            if (c != 0) return c
        }
        return 0
    }

    val comparator = Comparator<String> { a, b -> compare(a, b) }
}
