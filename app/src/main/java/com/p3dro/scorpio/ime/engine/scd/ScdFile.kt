package com.p3dro.scorpio.ime.engine.scd

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.DoubleBuffer
import java.nio.IntBuffer
import java.nio.channels.FileChannel

/**
 * SCD（Scorpio Compiled Dictionary）唯讀二進位詞庫：整個檔 mmap 進來，查詢時直接讀檔案頁，不經 SQLite。
 * 檔案頁是乾淨的唯讀頁，系統缺記憶體時可以直接丟掉、要用時再從檔案讀回，不算行程自己的記憶體負擔。
 *
 * 格式（資料 repo 的 tools/scd.py 產生；匯入詞庫由 [ScdWriter] 在手機上產生，兩邊每個 byte 都一樣）：
 *   檔頭 32 bytes：magic "SCD1"、版本、meta JSON 位置與長度、區段目錄位置與數量、檔案長度
 *   區段目錄：每筆 32 bytes（名稱 24 bytes＋位置＋長度），區段 8 bytes 對齊
 *   str.off／str.chr：字串表（UTF-16，依字串排序編號）；其餘區段是 u32／i32／f64 陣列或索引（.k 鍵、.s 起點、.p 資料列）
 * 全部 little-endian；所有讀取都用絕對位置，可以多執行緒同時查
 */
class ScdFile private constructor(private val buf: ByteBuffer) {

    val meta: JSONObject
    private val sections = HashMap<String, Pair<Int, Int>>()
    private val strOff: IntBuffer
    private val strChr: CharBuffer

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { for (i in 0 until 4) it[i] = buf.get(i) }
        require(String(magic, Charsets.US_ASCII) == MAGIC) { "不是 SCD 檔" }
        require(buf.getInt(4) == VERSION) { "不支援的 SCD 版本 ${buf.getInt(4)}" }
        require(buf.getInt(24) == buf.capacity()) { "SCD 檔長度不符" }
        val metaOff = buf.getInt(8)
        val metaLen = buf.getInt(12)
        meta = JSONObject(String(ByteArray(metaLen).also { for (i in 0 until metaLen) it[i] = buf.get(metaOff + i) }, Charsets.UTF_8))
        val dirOff = buf.getInt(16)
        for (i in 0 until buf.getInt(20)) {
            val p = dirOff + i * DIR_ENTRY
            var n = 0
            while (n < NAME_LEN && buf.get(p + n) != 0.toByte()) n++
            val name = String(ByteArray(n).also { for (j in 0 until n) it[j] = buf.get(p + j) }, Charsets.US_ASCII)
            sections[name] = buf.getInt(p + NAME_LEN) to buf.getInt(p + NAME_LEN + 4)
        }
        strOff = ints("str.off") ?: error("缺字串表")
        strChr = (slice("str.chr") ?: error("缺字串表")).asCharBuffer()
    }

    val id: String get() = meta.optString("id")
    val version: String get() = meta.optString("version")
    val group: String get() = meta.optString("group")

    /** 這個表有幾筆資料（meta.rows） */
    fun rows(table: String): Int = meta.optJSONObject("rows")?.optInt(table, 0) ?: 0

    fun has(name: String) = sections.containsKey(name)

    private fun slice(name: String): ByteBuffer? {
        val (off, len) = sections[name] ?: return null
        val d = buf.duplicate()
        d.position(off)
        d.limit(off + len)
        return d.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    fun ints(name: String): IntBuffer? = slice(name)?.asIntBuffer()
    fun doubles(name: String): DoubleBuffer? = slice(name)?.asDoubleBuffer()

    val stringCount: Int get() = strOff.limit() - 1

    fun str(sid: Int): String {
        val a = strOff.get(sid)
        val n = strOff.get(sid + 1) - a
        val chars = CharArray(n)
        for (i in 0 until n) chars[i] = strChr.get(a + i)
        return String(chars)
    }

    fun strLength(sid: Int): Int = strOff.get(sid + 1) - strOff.get(sid)

    fun charAt(sid: Int, i: Int): Char = strChr.get(strOff.get(sid) + i)

    /** 字串的字元直接從檔案讀（不複製、不產生 String），比對用 */
    fun chars(sid: Int): CharSequence = Chars(strOff.get(sid), strOff.get(sid + 1) - strOff.get(sid))

    private inner class Chars(private val start: Int, override val length: Int) : CharSequence {
        override fun get(index: Int): Char = strChr.get(start + index)
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Chars(start + startIndex, endIndex - startIndex)
        override fun toString(): String = String(CharArray(length) { strChr.get(start + it) })
    }

    /** 跟 String.compareTo 相同的比較（UTF-16 code unit），不產生字串 */
    fun compare(sid: Int, key: CharSequence): Int {
        val a = strOff.get(sid)
        val n = strOff.get(sid + 1) - a
        val m = minOf(n, key.length)
        for (i in 0 until m) {
            val c = strChr.get(a + i)
            val k = key[i]
            if (c != k) return c - k
        }
        return n - key.length
    }

    fun equals(sid: Int, key: CharSequence) = strLength(sid) == key.length && compare(sid, key) == 0

    fun startsWith(sid: Int, prefix: CharSequence): Boolean {
        val a = strOff.get(sid)
        if (strOff.get(sid + 1) - a < prefix.length) return false
        for (i in prefix.indices) if (strChr.get(a + i) != prefix[i]) return false
        return true
    }

    /** 只有一個字（SQLite 的 length(word) = 1：以 code point 計） */
    fun isSingleCodePoint(sid: Int): Boolean {
        val n = strLength(sid)
        return n == 1 || (n == 2 && Character.isHighSurrogate(charAt(sid, 0)) && Character.isLowSurrogate(charAt(sid, 1)))
    }

    /** 字串表裡找字串（字串依 UTF-16 排序編號）；沒有回傳 -1 */
    fun findString(key: CharSequence): Int {
        var lo = 0
        var hi = stringCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(mid, key)
            if (c < 0) lo = mid + 1 else if (c > 0) hi = mid - 1 else return mid
        }
        return -1
    }

    fun index(name: String): Index? {
        val keys = ints("$name.k") ?: return null
        return Index(this, keys, ints("$name.s")!!, ints("$name.p"))
    }

    /**
     * 索引：鍵（字串編號，依字串排序）→ 一段資料列。post 為 null 表示資料列本身就依這個鍵排（clustered），
     * 起點直接是資料列編號
     */
    class Index(private val f: ScdFile, private val keys: IntBuffer, private val starts: IntBuffer, private val post: IntBuffer?) {
        val size: Int get() = keys.limit()

        fun keySid(k: Int) = keys.get(k)
        fun start(k: Int) = starts.get(k)
        fun end(k: Int) = starts.get(k + 1)

        /** 位置 pos（start..end）上的資料列 */
        fun row(pos: Int) = post?.get(pos) ?: pos

        /**
         * 每 [SAMPLE] 個鍵取一個放在記憶體（第一次用到時建，全部詞庫加起來幾百 KB）：
         * 二分搜尋先在記憶體裡找到區段，再到檔案裡比幾次就好（原本每次比對都要從 mmap 一個字一個字讀）
         */
        private val sample: Array<String> by lazy { Array((size + SAMPLE - 1) / SAMPLE) { f.str(keys.get(it * SAMPLE)) } }

        /** 第一個 >= key 的鍵 */
        fun lowerBound(key: CharSequence): Int {
            val q = key.toString()
            var a = 0
            var b = sample.size
            while (a < b) {
                val mid = (a + b) ushr 1
                if (sample[mid] < q) a = mid + 1 else b = mid
            }
            // sample[a-1] < key <= sample[a]：答案在 ((a-1)*SAMPLE, a*SAMPLE]
            if (a == 0) return 0
            var lo = (a - 1) * SAMPLE + 1
            var hi = minOf(a * SAMPLE, size)
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (f.compare(keys.get(mid), key) < 0) lo = mid + 1 else hi = mid
            }
            return lo
        }

        fun find(key: CharSequence): Int {
            val k = lowerBound(key)
            return if (k < size && f.equals(keys.get(k), key)) k else -1
        }

        /** key <= 鍵 < key + U+FFFF 的鍵（SQL 的前綴範圍） */
        fun prefixKeys(prefix: String): IntRange = lowerBound(prefix) until lowerBound(prefix + "￿")
    }

    companion object {
        const val MAGIC = "SCD1"
        const val VERSION = 1
        const val NAME_LEN = 24
        const val DIR_ENTRY = 32
        /** 索引的取樣間隔（見 Index.lowerBound） */
        const val SAMPLE = 32

        fun open(file: File): ScdFile = RandomAccessFile(file, "r").use { raf ->
            ScdFile(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()))
        }

        /**
         * APK 裡不壓縮的資產（build.gradle 設了 noCompress）直接 mmap，不必先解出來。
         * fd 由呼叫端（AssetFileDescriptor）關；mmap 出來的內容在 fd 關掉後仍然有效
         */
        fun open(fd: java.io.FileDescriptor, offset: Long, length: Long): ScdFile =
            ScdFile(FileInputStream(fd).channel.map(FileChannel.MapMode.READ_ONLY, offset, length))

        fun wrap(bytes: ByteArray): ScdFile = ScdFile(ByteBuffer.wrap(bytes))
    }
}
