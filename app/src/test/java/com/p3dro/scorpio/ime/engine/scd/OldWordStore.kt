package com.p3dro.scorpio.ime.engine.scd

// 測試用：修改前的 WordStore，比對新版結果
/** WordStore 查詢結果快取的筆數（一筆通常幾個到幾十個詞） */
private const val OLD_CACHE_SIZE = 1200

/** 合併查詢用：哪個檔、哪一列、分數、檔內的先後 */
private class OldRef(val t: Int, val row: Int, val score: Double, val ord: Int)

private val oldRefOrder = Comparator<OldRef> { a, b ->
    if (a.score > b.score) -1 else if (a.score < b.score) 1 else if (a.t != b.t) a.t - b.t else a.ord - b.ord
}

/** 生效中的所有詞表（主詞庫在前，再來是開著的附加詞庫、匯入詞庫），等於舊版的 dict 表 */
class OldWordStore(val tables: List<WordTable>) {

    /**
     * 查詢結果快取：打字是一鍵一鍵往上加，同一個鍵（讀音、前綴、模糊音）下一鍵大多又會再查一次，
     * 每次都要在每個詞表各做一次二分搜尋。詞表一有變動（開關、安裝、匯入）就換一個新的 WordStore，快取跟著丟掉，不會拿到舊資料
     */
    private val cache = object : LinkedHashMap<String, List<WordHit>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<WordHit>>) = size > OLD_CACHE_SIZE
    }

    private inline fun cached(key: String, compute: () -> List<WordHit>): List<WordHit> {
        synchronized(cache) { cache[key]?.let { return it } }
        val v = compute()
        synchronized(cache) { cache[key] = v }
        return v
    }

    /** 系統要記憶體時清掉 */
    fun clearCache() = synchronized(cache) { cache.clear() }

    private fun decode(refs: MutableList<OldRef>, limit: Int): List<WordHit> {
        refs.sortWith(oldRefOrder)
        return List(minOf(limit, refs.size)) { tables[refs[it].t].hit(refs[it].row) }
    }

    /** 單一鍵：... WHERE <col> = ? [AND length(word) = 1] ORDER BY score DESC LIMIT n */
    private fun exact(ix: (WordTable) -> ScdFile.Index, key: String, limit: Int, singleChar: Boolean): List<WordHit> {
        val refs = ArrayList<OldRef>()
        for ((t, w) in tables.withIndex()) {
            val idx = ix(w)
            val k = idx.find(key)
            if (k < 0) continue
            var taken = 0
            for (pos in idx.start(k) until idx.end(k)) {
                val row = idx.row(pos)
                if (singleChar && !w.isSingleChar(row)) continue
                refs.add(OldRef(t, row, w.score(row), pos))
                if (++taken >= limit) break
            }
        }
        return decode(refs, limit)
    }

    fun byToneless(key: String, limit: Int, singleChar: Boolean = false) = cached("t$limit$singleChar\u0000$key") { exact({ it.tlIx }, key, limit, singleChar) }
    fun byReading(key: String, limit: Int, singleChar: Boolean = false) = cached("r$limit$singleChar\u0000$key") { exact({ it.rdIx }, key, limit, singleChar) }
    fun byFinals(key: String, limit: Int) = cached("f$limit\u0000$key") { exact({ it.fnIx }, key, limit, false) }
    fun byWord(word: String, limit: Int) = cached("w$limit\u0000$word") { exact({ it.wdIx }, word, limit, false) }

    /** 前 limit 名（分數高到低、同分依位置）：一段資料列裡挑 */
    private fun topOf(t: Int, w: WordTable, rows: Iterator<Int>, limit: Int, ordOf: (Int) -> Int, into: MutableList<OldRef>) {
        val best = ArrayList<OldRef>(limit + 1)
        for (row in rows) {
            val r = OldRef(t, row, w.score(row), ordOf(row))
            if (best.size == limit && oldRefOrder.compare(r, best[limit - 1]) >= 0) continue
            var i = best.size
            while (i > 0 && oldRefOrder.compare(r, best[i - 1]) < 0) i--
            best.add(i, r)
            if (best.size > limit) best.removeAt(limit)
        }
        into.addAll(best)
    }

    /** SELECT … WHERE toneless >= ? AND toneless < ? || U+FFFF ORDER BY score DESC LIMIT n */
    fun byTonelessPrefix(prefix: String, limit: Int): List<WordHit> = cached("p$limit\u0000$prefix") {
        val refs = ArrayList<OldRef>()
        for ((t, w) in tables.withIndex()) {
            val heavy = w.heavyPrefix(prefix, limit)
            if (heavy != null) {
                for (row in heavy) refs.add(OldRef(t, row, w.score(row), row))
                continue
            }
            val keys = w.tlIx.prefixKeys(prefix)
            if (keys.isEmpty()) continue
            topOf(t, w, (w.tlIx.start(keys.first) until w.tlIx.end(keys.last)).iterator(), limit, { it }, refs)
        }
        decode(refs, limit)
    }

    /** SELECT … WHERE toneless IN (…) ORDER BY score DESC LIMIT n */
    fun byTonelessIn(keys: Collection<String>, limit: Int): List<WordHit> {
        val distinct = keys.toSortedSet()
        return cached("i$limit\u0000" + distinct.joinToString("\u0001")) { tonelessIn(distinct, limit) }
    }

    private fun tonelessIn(distinct: Set<String>, limit: Int): List<WordHit> {
        val refs = ArrayList<OldRef>()
        for ((t, w) in tables.withIndex()) {
            val rows = ArrayList<Int>()
            for (key in distinct) {
                val k = w.tlIx.find(key)
                if (k >= 0) for (pos in w.tlIx.start(k) until w.tlIx.end(k)) rows.add(pos)
            }
            topOf(t, w, rows.iterator(), limit, { it }, refs)
        }
        return decode(refs, limit)
    }

    /** SELECT … WHERE (initials = ? OR initials = ? OR …) AND toneless LIKE ? ORDER BY score DESC LIMIT n */
    fun byInitialsLike(initials: Collection<String>, pattern: String, limit: Int): List<WordHit> {
        val keys = initials.toSortedSet()
        return cached("n$limit\u0000$pattern\u0000" + keys.joinToString("\u0001")) { initialsLike(keys, pattern, limit) }
    }

    private fun initialsLike(keys: Set<String>, pattern: String, limit: Int): List<WordHit> {
        val refs = ArrayList<OldRef>()
        val like = Like(pattern)
        for ((t, w) in tables.withIndex()) {
            val best = ArrayList<OldRef>()
            // 同一個無聲調讀音在這次查詢只比對一次（直接比對檔案裡的字元，不產生字串）
            val memo = HashMap<Int, Boolean>()
            for (key in keys) {
                val k = w.inIx.find(key)
                if (k < 0) continue
                var taken = 0
                for (pos in w.inIx.start(k) until w.inIx.end(k)) {
                    val row = w.inIx.row(pos)
                    val sid = w.tonelessSid(row)
                    if (!memo.getOrPut(sid) { like.matches(w.f.chars(sid)) }) continue
                    best.add(OldRef(t, row, w.score(row), pos))
                    if (++taken >= limit) break
                }
            }
            best.sortWith(oldRefOrder)
            refs.addAll(best.take(limit))
        }
        return decode(refs, limit)
    }

    /** SELECT max(score) FROM dict WHERE toneless = ? */
    fun maxScore(toneless: String): Double? = byToneless(toneless, 1).firstOrNull()?.score

    /** SELECT 1 FROM dict WHERE toneless = ? AND word = ? */
    fun hasTonelessWord(toneless: String, word: String): Boolean {
        for (w in tables) {
            val k = w.tlIx.find(toneless)
            if (k < 0) continue
            for (row in w.tlIx.start(k) until w.tlIx.end(k)) if (w.f.equals(w.wordSid(row), word)) return true
        }
        return false
    }

    /** SELECT 1 FROM dict WHERE word = ? */
    fun hasWord(word: String) = tables.any { it.wdIx.find(word) >= 0 }

    /** SELECT DISTINCT toneless FROM dict WHERE word = ? */
    fun tonelessOfWord(word: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (w in tables) {
            val k = w.wdIx.find(word)
            if (k >= 0) for (pos in w.wdIx.start(k) until w.wdIx.end(k)) out.add(w.toneless(w.wdIx.row(pos)))
        }
        return out
    }

    /** 單音節（不含 -）的無聲調讀音 → 該讀音所有詞的 10^score 加總（SQL 的 instr(toneless, '-') = 0 AND toneless <> ''） */
    fun syllableFrequencies(): HashMap<String, Double> {
        val out = HashMap<String, Double>(1600)
        for (w in tables) {
            val ix = w.tlIx
            for (k in 0 until ix.size) {
                val sid = ix.keySid(k)
                val n = w.f.strLength(sid)
                if (n == 0 || (0 until n).any { w.f.charAt(sid, it) == '-' }) continue
                val key = w.f.str(sid)
                var sum = out[key] ?: 0.0
                for (row in ix.start(k) until ix.end(k)) sum += Math.pow(10.0, w.score(row))
                out[key] = sum
            }
        }
        return out
    }
}


