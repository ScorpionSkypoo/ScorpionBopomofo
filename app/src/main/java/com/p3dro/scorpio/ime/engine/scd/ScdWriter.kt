package com.p3dro.scorpio.ime.engine.scd

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 在手機上產生 SCD 檔（使用者匯入的詞庫）：跟資料 repo 的 tools/scd.py 同一套寫法，
 * 同一份資料寫出來的檔案每個 byte 都一樣（單元測試用 tools/scd_fixture.py 產生的檔案比對）。
 * 目前只需要「詞」這種表（[Word]）
 */
object ScdWriter {

    /** 一筆詞：欄位同主詞庫的 dict 表 */
    data class Word(val reading: String, val toneless: String, val word: String, val score: Double, val initials: String, val finals: String)

    /** 前綴範圍超過這麼多筆的前綴預先算好前 [TOPK] 名（同 tools/scd.py） */
    const val PREFIX_HEAVY = 256
    const val TOPK = 16

    /** rows 依原本的順序（同分時的先後）；meta 的值只能是字串、整數、布林 */
    fun words(meta: Map<String, Any>, rows: List<Word>): ByteArray {
        val n = rows.size
        // 資料列依 (toneless, 分數高到低, 原順序) 排
        val orig = (0 until n).sortedWith { a, b ->
            val c = rows[a].toneless.compareTo(rows[b].toneless)
            if (c != 0) c else scoreDesc(rows[a].score, rows[b].score).let { if (it != 0) it else a - b }
        }.toIntArray()
        val rs = List(n) { rows[orig[it]] }

        // 熱門前綴
        val prefixes = HashSet<String>()
        var last: String? = null
        for (r in rs) {
            if (r.toneless != last) {
                last = r.toneless
                for (j in 1..last.length) prefixes.add(last.substring(0, j))
            }
        }
        val heavy = ArrayList<Pair<String, List<Int>>>()
        for (p in prefixes.sorted()) {
            val lo = lowerBound(rs, p)
            val hi = lowerBound(rs, p + "￿")
            if (hi - lo > PREFIX_HEAVY) {
                heavy.add(p to (lo until hi).sortedWith { a, b -> scoreDesc(rs[a].score, rs[b].score).let { if (it != 0) it else a - b } }.take(TOPK))
            }
        }

        val strings = HashSet<String>()
        for (r in rs) {
            strings.add(r.reading); strings.add(r.toneless); strings.add(r.word); strings.add(r.initials); strings.add(r.finals)
        }
        for ((p, _) in heavy) strings.add(p)
        val strList = strings.sorted()
        val sid = HashMap<String, Int>(strList.size * 2)
        strList.forEachIndexed { i, s -> sid[s] = i }

        val sections = ArrayList<Pair<String, ByteArray>>()
        val offs = IntArray(strList.size + 1)
        val chars = StringBuilder()
        strList.forEachIndexed { i, s ->
            chars.append(s)
            offs[i + 1] = chars.length
        }
        sections.add("str.off" to u32(offs))
        sections.add("str.chr" to ByteBuffer.allocate(chars.length * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> for (c in chars) b.putChar(c) }.array())

        sections.add("w.word" to u32(IntArray(n) { sid.getValue(rs[it].word) }))
        sections.add("w.read" to u32(IntArray(n) { sid.getValue(rs[it].reading) }))
        sections.add("w.tl" to u32(IntArray(n) { sid.getValue(rs[it].toneless) }))
        sections.add("w.score" to ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN).also { b -> rs.forEach { b.putDouble(it.score) } }.array())

        val byScore = Comparator<Int> { a, b -> scoreDesc(rs[a].score, rs[b].score).let { if (it != 0) it else orig[a] - orig[b] } }
        index(sections, "w.tl", sid, group(n, { rs[it].toneless }, { a, b -> a - b }), clustered = true)
        index(sections, "w.rd", sid, group(n, { rs[it].reading }, byScore))
        index(sections, "w.in", sid, group(n, { rs[it].initials }, byScore))
        index(sections, "w.fn", sid, group(n, { rs[it].finals }, byScore))
        index(sections, "w.wd", sid, group(n, { rs[it].word }, byScore))
        sections.add("w.sc" to u32((0 until n).sortedWith(byScore).toIntArray()))
        index(sections, "w.tp", sid, heavy)

        val m = LinkedHashMap(meta)
        m["rows"] = mapOf("w" to n)
        return build(json(m).toByteArray(Charsets.UTF_8), sections)
    }

    private fun scoreDesc(a: Double, b: Double) = if (a > b) -1 else if (a < b) 1 else 0

    private fun lowerBound(rs: List<Word>, key: String): Int {
        var lo = 0
        var hi = rs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rs[mid].toneless < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun group(n: Int, key: (Int) -> String, order: Comparator<Int>): List<Pair<String, List<Int>>> {
        val d = LinkedHashMap<String, MutableList<Int>>()
        for (i in 0 until n) d.getOrPut(key(i)) { ArrayList() }.add(i)
        return d.keys.sorted().map { it to d.getValue(it).sortedWith(order) }
    }

    private fun index(out: MutableList<Pair<String, ByteArray>>, name: String, sid: Map<String, Int>, groups: List<Pair<String, List<Int>>>, clustered: Boolean = false) {
        val starts = IntArray(groups.size + 1)
        val post = ArrayList<Int>()
        groups.forEachIndexed { i, (_, g) ->
            if (clustered) {
                check(g.first() == starts[i] && g.last() == starts[i] + g.size - 1)
                starts[i + 1] = starts[i] + g.size
            } else {
                post.addAll(g)
                starts[i + 1] = post.size
            }
        }
        out.add("$name.k" to u32(IntArray(groups.size) { sid.getValue(groups[it].first) }))
        out.add("$name.s" to u32(starts))
        if (!clustered) out.add("$name.p" to u32(post.toIntArray()))
    }

    private fun u32(v: IntArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putInt(it) } }.array()

    private fun align(n: Int) = (n + 7) and 7.inv()

    private fun build(meta: ByteArray, sections: List<Pair<String, ByteArray>>): ByteArray {
        var pos = 32
        val metaOff = pos
        pos = align(pos + meta.size)
        val dirOff = pos
        pos = align(pos + sections.size * ScdFile.DIR_ENTRY)
        val offsets = IntArray(sections.size)
        sections.forEachIndexed { i, (_, d) ->
            offsets[i] = pos
            pos = align(pos + d.size)
        }
        val b = ByteBuffer.allocate(pos).order(ByteOrder.LITTLE_ENDIAN)
        b.put(ScdFile.MAGIC.toByteArray(Charsets.US_ASCII))
        b.putInt(ScdFile.VERSION).putInt(metaOff).putInt(meta.size).putInt(dirOff).putInt(sections.size).putInt(pos).putInt(0)
        b.position(metaOff)
        b.put(meta)
        sections.forEachIndexed { i, (name, d) ->
            b.position(dirOff + i * ScdFile.DIR_ENTRY)
            val nb = name.toByteArray(Charsets.US_ASCII)
            require(nb.size <= ScdFile.NAME_LEN)
            b.put(nb)
            b.position(dirOff + i * ScdFile.DIR_ENTRY + ScdFile.NAME_LEN)
            b.putInt(offsets[i]).putInt(d.size)
            b.position(offsets[i])
            b.put(d)
        }
        return b.array()
    }

    /** 同 Python json.dumps(ensure_ascii=False, sort_keys=True, separators=(",", ":")) */
    internal fun json(v: Any?): String = when (v) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (c in v) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
        is Boolean, is Int, is Long -> v.toString()
        is Map<*, *> -> v.entries.sortedBy { it.key as String }.joinToString(",", "{", "}") { json(it.key) + ":" + json(it.value) }
        else -> error("meta 不支援 ${v.javaClass}")
    }
}
