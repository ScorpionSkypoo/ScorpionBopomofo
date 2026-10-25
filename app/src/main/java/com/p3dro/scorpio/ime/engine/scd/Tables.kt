package com.p3dro.scorpio.ime.engine.scd

/**
 * SCD 檔裡各種表的查詢。每個方法對應舊版的一條 SQL，結果與順序相同：
 * 同一個檔裡同分的資料依 SQLite 掃描順序（資料 repo 的 tools/verify_scd.py 逐鍵驗過）；
 * 多個檔（主詞庫＋附加詞庫＋匯入詞庫）合併時，同分依檔案順序（等於舊版依序插進 dict 表的 rowid 順序）
 */

/** 取最多 limit 筆的結束位置（limit 可以是 Int.MAX_VALUE，不能直接相加溢位） */
internal fun capEnd(start: Int, end: Int, limit: Int) = if (limit >= end - start) end else start + limit

/** 詞的查詢結果（同舊版 SELECT word, score, reading） */
class WordHit(val word: String, val score: Double, val reading: String)

/** 一個檔裡的詞表 */
class WordTable(val f: ScdFile) {
    private val word = f.ints("w.word")!!
    private val read = f.ints("w.read")!!
    private val tl = f.ints("w.tl")!!
    private val score = f.doubles("w.score")!!
    val tlIx = f.index("w.tl")!!
    val rdIx = f.index("w.rd")!!
    val inIx = f.index("w.in")!!
    val fnIx = f.index("w.fn")!!
    val wdIx = f.index("w.wd")!!
    private val tpIx = f.index("w.tp")!!
    private val bySc = f.ints("w.sc")!!
    val size: Int get() = score.limit()

    fun score(row: Int) = score.get(row)
    fun wordSid(row: Int) = word.get(row)
    fun word(row: Int) = f.str(word.get(row))
    fun reading(row: Int) = f.str(read.get(row))
    fun toneless(row: Int) = f.str(tl.get(row))
    fun tonelessSid(row: Int) = tl.get(row)
    fun hit(row: Int) = WordHit(word(row), score(row), reading(row))
    fun isSingleChar(row: Int) = f.isSingleCodePoint(word.get(row))

    /** 前綴（toneless >= p AND toneless < p || U+FFFF）預先算好的前幾名；沒有就 null */
    fun heavyPrefix(p: String, limit: Int): IntArray? {
        if (limit > ScdWriter.TOPK) return null
        val k = tpIx.find(p)
        if (k < 0) return null
        return IntArray(minOf(limit, tpIx.end(k) - tpIx.start(k))) { tpIx.row(tpIx.start(k) + it) }
    }

    fun scoreOrder(pos: Int) = bySc.get(pos)
}

/** WordStore 查詢結果快取的筆數（一筆通常幾個到幾十個詞） */
private const val CACHE_SIZE = 1200

/** 合併查詢用：哪個檔、哪一列、分數、檔內的先後 */
private class Ref(val t: Int, val row: Int, val score: Double, val ord: Int)

private val refOrder = Comparator<Ref> { a, b ->
    if (a.score > b.score) -1 else if (a.score < b.score) 1 else if (a.t != b.t) a.t - b.t else a.ord - b.ord
}

/** 生效中的所有詞表（主詞庫在前，再來是開著的附加詞庫、匯入詞庫），等於舊版的 dict 表 */
class WordStore(val tables: List<WordTable>) {

    /**
     * 查詢結果快取：打字是一鍵一鍵往上加，同一個鍵（讀音、前綴、模糊音）下一鍵大多又會再查一次，
     * 每次都要在每個詞表各做一次二分搜尋。詞表一有變動（開關、安裝、匯入）就換一個新的 WordStore，快取跟著丟掉，不會拿到舊資料
     */
    private val cache = object : LinkedHashMap<String, List<WordHit>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<WordHit>>) = size > CACHE_SIZE
    }

    private inline fun cached(key: String, compute: () -> List<WordHit>): List<WordHit> {
        synchronized(cache) { cache[key]?.let { return it } }
        val v = compute()
        synchronized(cache) { cache[key] = v }
        return v
    }

    /** 系統要記憶體時清掉 */
    fun clearCache() = synchronized(cache) { cache.clear() }

    private fun decode(refs: MutableList<Ref>, limit: Int): List<WordHit> {
        refs.sortWith(refOrder)
        return List(minOf(limit, refs.size)) { tables[refs[it].t].hit(refs[it].row) }
    }

    /** 單一鍵：... WHERE <col> = ? [AND length(word) = 1] ORDER BY score DESC LIMIT n */
    private fun exact(ix: (WordTable) -> ScdFile.Index, key: String, limit: Int, singleChar: Boolean): List<WordHit> {
        val refs = ArrayList<Ref>()
        for ((t, w) in tables.withIndex()) {
            val idx = ix(w)
            val k = idx.find(key)
            if (k < 0) continue
            var taken = 0
            for (pos in idx.start(k) until idx.end(k)) {
                val row = idx.row(pos)
                if (singleChar && !w.isSingleChar(row)) continue
                refs.add(Ref(t, row, w.score(row), pos))
                if (++taken >= limit) break
            }
        }
        return decode(refs, limit)
    }

    fun byToneless(key: String, limit: Int, singleChar: Boolean = false) = cached("t$limit$singleChar\u0000$key") { exact({ it.tlIx }, key, limit, singleChar) }
    fun byReading(key: String, limit: Int, singleChar: Boolean = false) = cached("r$limit$singleChar\u0000$key") { exact({ it.rdIx }, key, limit, singleChar) }
    fun byFinals(key: String, limit: Int) = cached("f$limit\u0000$key") { exact({ it.fnIx }, key, limit, false) }
    fun byWord(word: String, limit: Int) = cached("w$limit\u0000$word") { exact({ it.wdIx }, word, limit, false) }

    /**
     * 一個詞表裡分數前 limit 名（分數高到低、同分依位置，同 refOrder）。
     * 一筆一筆餵進來；排不進前 limit 名的不建物件（前綴、模糊音查詢一次會掃過幾千列）
     */
    private class TopK(private val t: Int, private val limit: Int) {
        val best = ArrayList<Ref>(limit + 1)

        /** 同一個詞表裡 (score, ord) 是否排在 r 前面（refOrder 的同表版本） */
        private fun before(score: Double, ord: Int, r: Ref) = score > r.score || (score == r.score && ord < r.ord)

        fun offer(row: Int, score: Double, ord: Int) {
            if (limit <= 0) return
            if (best.size == limit && !before(score, ord, best[limit - 1])) return
            var i = best.size
            while (i > 0 && before(score, ord, best[i - 1])) i--
            best.add(i, Ref(t, row, score, ord))
            if (best.size > limit) best.removeAt(limit)
        }
    }

    /** SELECT … WHERE toneless >= ? AND toneless < ? || U+FFFF ORDER BY score DESC LIMIT n */
    fun byTonelessPrefix(prefix: String, limit: Int): List<WordHit> = cached("p$limit\u0000$prefix") {
        val refs = ArrayList<Ref>()
        for ((t, w) in tables.withIndex()) {
            val heavy = w.heavyPrefix(prefix, limit)
            if (heavy != null) {
                for (row in heavy) refs.add(Ref(t, row, w.score(row), row))
                continue
            }
            val keys = w.tlIx.prefixKeys(prefix)
            if (keys.isEmpty()) continue
            val top = TopK(t, limit)
            var row = w.tlIx.start(keys.first)
            val end = w.tlIx.end(keys.last)
            while (row < end) {
                top.offer(row, w.score(row), row)
                row++
            }
            refs.addAll(top.best)
        }
        decode(refs, limit)
    }

    /** SELECT … WHERE toneless IN (…) ORDER BY score DESC LIMIT n */
    fun byTonelessIn(keys: Collection<String>, limit: Int): List<WordHit> {
        val distinct = keys.toSortedSet()
        return cached("i$limit\u0000" + distinct.joinToString("\u0001")) { tonelessIn(distinct, limit) }
    }

    private fun tonelessIn(distinct: Set<String>, limit: Int): List<WordHit> {
        val refs = ArrayList<Ref>()
        for ((t, w) in tables.withIndex()) {
            val top = TopK(t, limit)
            for (key in distinct) {
                val k = w.tlIx.find(key)
                if (k < 0) continue
                var pos = w.tlIx.start(k)
                val end = w.tlIx.end(k)
                while (pos < end) {
                    top.offer(pos, w.score(pos), pos)
                    pos++
                }
            }
            refs.addAll(top.best)
        }
        return decode(refs, limit)
    }

    /** SELECT … WHERE (initials = ? OR initials = ? OR …) AND toneless LIKE ? ORDER BY score DESC LIMIT n */
    fun byInitialsLike(initials: Collection<String>, pattern: String, limit: Int): List<WordHit> {
        val keys = initials.toSortedSet()
        return cached("n$limit\u0000$pattern\u0000" + keys.joinToString("\u0001")) { initialsLike(keys, pattern, limit) }
    }

    private fun initialsLike(keys: Set<String>, pattern: String, limit: Int): List<WordHit> {
        val refs = ArrayList<Ref>()
        val like = Like(pattern)
        for ((t, w) in tables.withIndex()) {
            val best = ArrayList<Ref>()
            // 同一個無聲調讀音在這次查詢只比對一次（直接比對檔案裡的字元，不產生字串；記錄表同一條執行緒重複使用）
            val memo = IntBoolMemo.local()
            for (key in keys) {
                val k = w.inIx.find(key)
                if (k < 0) continue
                var taken = 0
                for (pos in w.inIx.start(k) until w.inIx.end(k)) {
                    val row = w.inIx.row(pos)
                    val sid = w.tonelessSid(row)
                    val seen = memo.get(sid)
                    val ok = if (seen != IntBoolMemo.UNKNOWN) seen == IntBoolMemo.YES else like.matches(w.f, sid).also { memo.put(sid, it) }
                    if (!ok) continue
                    best.add(Ref(t, row, w.score(row), pos))
                    if (++taken >= limit) break
                }
            }
            best.sortWith(refOrder)
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

    private val wordPrefixCache = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 4096
    }

    /** 字典裡有這個讀音（無聲調、以 - 串起來）的詞，或有以它開頭的更長的詞（ㄕㄣ-ㄇㄜ-ㄉㄨㄥ → 什麼東西） */
    fun isWordOrPrefix(toneless: String): Boolean {
        synchronized(wordPrefixCache) { wordPrefixCache[toneless]?.let { return it } }
        val v = tables.any { it.tlIx.find(toneless) >= 0 || !it.tlIx.prefixKeys("$toneless-").isEmpty() }
        synchronized(wordPrefixCache) { wordPrefixCache[toneless] = v }
        return v
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

    /** SELECT DISTINCT reading FROM dict WHERE word = ?（有聲調，一聲沒有符號） */
    fun readingsOfWord(word: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (w in tables) {
            val k = w.wdIx.find(word)
            if (k >= 0) for (pos in w.wdIx.start(k) until w.wdIx.end(k)) out.add(w.reading(w.wdIx.row(pos)))
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

/**
 * int → 是／否 的小型雜湊表（開放定址），取代 HashMap<Int, Boolean>：查詢時不把整數包成物件。
 * local() 取這條執行緒重複使用的那一個並清空（只換代號，不必把陣列清零）
 */
internal class IntBoolMemo {
    companion object {
        const val UNKNOWN = 0
        const val YES = 1
        const val NO = 2

        // 比這大的表用完就丟，不讓少數幾次特別大的查詢一直佔著記憶體
        private const val KEEP_CAPACITY = 1 shl 14
        private val perThread = ThreadLocal<IntBoolMemo>()

        fun local(): IntBoolMemo {
            val m = perThread.get()?.takeIf { it.keys.size <= KEEP_CAPACITY } ?: IntBoolMemo().also { perThread.set(it) }
            m.clear()
            return m
        }
    }

    private var keys = IntArray(64)
    private var vals = ByteArray(64)
    private var gens = IntArray(64)
    private var gen = 1
    private var size = 0

    fun clear() {
        size = 0
        if (++gen == 0) {
            // 代號繞回一圈：真的清一次
            gens.fill(0)
            gen = 1
        }
    }

    private fun slot(key: Int, cap: Int): Int {
        var i = (key * -0x61c88647) ushr (32 - Integer.numberOfTrailingZeros(cap))
        while (gens[i] == gen && keys[i] != key) i = (i + 1) and (cap - 1)
        return i
    }

    fun get(key: Int): Int {
        val i = slot(key, keys.size)
        return if (gens[i] == gen) vals[i].toInt() else UNKNOWN
    }

    fun put(key: Int, value: Boolean) {
        if ((size + 1) * 2 > keys.size) grow()
        val i = slot(key, keys.size)
        if (gens[i] != gen) size++
        keys[i] = key
        vals[i] = (if (value) YES else NO).toByte()
        gens[i] = gen
    }

    private fun grow() {
        val oldK = keys
        val oldV = vals
        val oldG = gens
        val live = gen
        keys = IntArray(oldK.size * 2)
        vals = ByteArray(oldV.size * 2)
        gens = IntArray(oldG.size * 2)
        for (j in oldK.indices) {
            if (oldG[j] != live) continue
            val i = slot(oldK[j], keys.size)
            keys[i] = oldK[j]
            vals[i] = oldV[j]
            gens[i] = live
        }
    }
}

/**
 * SQLite LIKE：% 任意長度、_ 一個字（code point），ASCII 字母不分大小寫，沒有跳脫字元
 */
class Like(pattern: String) {
    private val p = pattern.codePoints().toArray()

    /** 樣式沒有 _ 也沒有補充平面字元時，逐個 char 比對就等於逐個字比對，不必先轉 code point（選字時的簡拼樣式都是這種） */
    private val fast: CharArray? = pattern.takeIf { s -> '_' !in s && s.none { it.isSurrogate() } }?.toCharArray()

    fun matches(s: CharSequence): Boolean {
        val q = fast ?: return matchesCodePoints(s.toString())
        return matchFast(q, s.length) { s[it] }
    }

    /** 直接比對 SCD 檔裡的字串（不產生 String 或包裝物件） */
    fun matches(f: ScdFile, sid: Int): Boolean {
        val q = fast ?: return matchesCodePoints(f.str(sid))
        return matchFast(q, f.strLength(sid)) { f.charAt(sid, it) }
    }

    private inline fun matchFast(q: CharArray, n: Int, at: (Int) -> Char): Boolean {
        var i = 0
        var j = 0
        var star = -1
        var mark = 0
        while (i < n) {
            if (j < q.size && q[j] != '%' && eq(q[j].code, at(i).code)) {
                i++
                j++
            } else if (j < q.size && q[j] == '%') {
                star = j++
                mark = i
            } else if (star >= 0) {
                j = star + 1
                i = ++mark
            } else {
                return false
            }
        }
        while (j < q.size && q[j] == '%') j++
        return j == q.size
    }

    private fun matchesCodePoints(s: String): Boolean {
        val t = s.codePoints().toArray()
        // 經典的萬用字元比對：記住最後一個 % 的位置以便回溯
        var i = 0
        var j = 0
        var star = -1
        var mark = 0
        while (i < t.size) {
            if (j < p.size && p[j] != '%'.code && (p[j] == '_'.code || eq(p[j], t[i]))) {
                i++
                j++
            } else if (j < p.size && p[j] == '%'.code) {
                star = j++
                mark = i
            } else if (star >= 0) {
                j = star + 1
                i = ++mark
            } else {
                return false
            }
        }
        while (j < p.size && p[j] == '%'.code) j++
        return j == p.size
    }

    private fun eq(a: Int, b: Int) = a == b || (a < 128 && b < 128 && Character.toLowerCase(a) == Character.toLowerCase(b))
}

/** 詞與詞的接續（ln P(next | prev)） */
class BigramTable(val f: ScdFile) {
    private val next = f.ints("b.next")!!
    private val score = f.doubles("b.score")!!
    private val pv = f.index("b.pv")!!
    private val bySc = f.ints("b.sc")!!
    private val prev = f.ints("b.prev")!!
    val size: Int get() = score.limit()

    /** prev 之後接的詞，分數高到低（同分依 next 的 UTF-8 順序） */
    fun forEach(prevWord: String, limit: Int = Int.MAX_VALUE, action: (String, Double) -> Unit) {
        val k = pv.find(prevWord)
        if (k < 0) return
        val end = capEnd(pv.start(k), pv.end(k), limit)
        for (pos in pv.start(k) until end) {
            val row = pv.row(pos)
            action(f.str(next.get(row)), score.get(row))
        }
    }

    /** 設定頁瀏覽：依分數排的第 pos 筆（前詞, 後詞） */
    fun byScore(pos: Int): Pair<String, String> = bySc.get(pos).let { f.str(prev.get(it)) to f.str(next.get(it)) }
}

/** 英文單字（依字母排，前綴查詢用二分搜尋）與英文接續 */
class EnglishTable(val f: ScdFile) {
    private val word = f.ints("e.word")!!
    private val score = f.ints("e.score")!!
    private val ebNext = f.ints("eb.next")!!
    private val eb = f.index("eb.pv")!!
    val size: Int get() = score.limit()

    fun word(i: Int) = f.str(word.get(i))
    fun score(i: Int) = score.get(i)

    /** 前綴區間 [from, to)（同舊版記憶體索引的 range：String 比較） */
    fun range(prefix: String): IntRange {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (f.compare(word.get(mid), prefix) < 0) lo = mid + 1 else hi = mid
        }
        val from = lo
        hi = size
        val end = prefix + "￿"
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (f.compare(word.get(mid), end) < 0) lo = mid + 1 else hi = mid
        }
        return from until lo
    }

    /** SELECT next FROM english_bigram WHERE prev = ? ORDER BY score DESC LIMIT n */
    fun next(prev: String, limit: Int): List<String> {
        val k = eb.find(prev)
        if (k < 0) return emptyList()
        return (eb.start(k) until capEnd(eb.start(k), eb.end(k), limit)).map { f.str(ebNext.get(eb.row(it))) }
    }
}

/** 倉頡碼表：依 (碼, 字頻高到低) 排；速成碼對照表第一次用到時建（同舊版記憶體索引，HashMap 的走訪順序也一樣） */
class CangjieTable(val f: ScdFile) {
    private val code = f.ints("cj.code")!!
    private val quickCol = f.ints("cj.quick")!!
    private val chr = f.ints("cj.char")!!
    private val freq = f.doubles("cj.freq")!!
    val size: Int get() = freq.limit()

    val quick: Map<String, List<Int>> by lazy {
        val m = HashMap<String, MutableList<Int>>()
        for (i in 0 until size) m.getOrPut(f.str(quickCol.get(i))) { ArrayList() }.add(i)
        m
    }

    fun code(i: Int) = f.str(code.get(i))
    fun codeEquals(i: Int, c: String) = f.equals(code.get(i), c)
    fun char(i: Int) = f.str(chr.get(i))
    fun freq(i: Int) = freq.get(i)

    fun range(prefix: String): IntRange {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (f.compare(code.get(mid), prefix) < 0) lo = mid + 1 else hi = mid
        }
        val from = lo
        hi = size
        val end = prefix + "￿"
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (f.compare(code.get(mid), end) < 0) lo = mid + 1 else hi = mid
        }
        return from until lo
    }
}

/** Emoji：依關鍵字找（優先序小的在前）、依分類瀏覽 */
class EmojiTable(val f: ScdFile) {
    private val kw = f.ints("em.kw")!!
    private val emoji = f.ints("em.emoji")!!
    private val pri = f.ints("em.pri")!!
    private val ix = f.index("em.ix")!!
    private val all = f.ints("em.all")!!
    private val catEmoji = f.ints("ec.emoji")!!
    private val cats = f.ints("ec.cats")!!
    private val catIx = f.index("ec.ix")!!
    val size: Int get() = pri.limit()

    /** SELECT emoji FROM emoji_dict WHERE keyword = ? ORDER BY priority LIMIT n */
    fun byKeyword(keyword: String, limit: Int): List<String> {
        val k = ix.find(keyword)
        if (k < 0) return emptyList()
        return (ix.start(k) until capEnd(ix.start(k), ix.end(k), limit)).map { f.str(emoji.get(ix.row(it))) }
    }

    fun all(): List<String> = List(all.limit()) { f.str(all.get(it)) }
    fun categories(): List<String> = List(cats.limit()) { f.str(cats.get(it)) }

    fun byCategory(category: String): List<String> {
        val k = catIx.find(category)
        if (k < 0) return emptyList()
        return (catIx.start(k) until catIx.end(k)).map { f.str(catEmoji.get(catIx.row(it))) }
    }

    /**
     * 設定頁瀏覽（emoji, 關鍵字以「、」接起來），同舊版
     * SELECT emoji, group_concat(keyword, '、') FROM emoji_dict [WHERE keyword LIKE %q% OR emoji = q] GROUP BY emoji ORDER BY MIN(priority)：
     * 先篩再分組；組依最小優先序、再依 emoji 的 UTF-8 順序；組內關鍵字依 UTF-8 順序
     */
    fun browse(q: String): List<Pair<String, String>> {
        val like = Like("%$q%")
        val groups = HashMap<String, Pair<IntArray, MutableList<String>>>()
        for (i in 0 until size) {
            val k = f.str(kw.get(i))
            val e = f.str(emoji.get(i))
            if (q.isNotEmpty() && !like.matches(k) && e != q) continue
            val g = groups.getOrPut(e) { intArrayOf(Int.MAX_VALUE) to ArrayList() }
            g.first[0] = minOf(g.first[0], pri.get(i))
            g.second.add(k)
        }
        return groups.entries.sortedWith { a, b ->
            val c = a.value.first[0].compareTo(b.value.first[0])
            if (c != 0) c else compareUtf8(a.key, b.key)
        }.map { (e, g) -> e to g.second.sortedWith { a, b -> compareUtf8(a, b) }.joinToString("、") }
    }
}

/** 顏文字：依讀音、依分類找（優先序小的在前） */
class KaomojiTable(val f: ScdFile) {
    private val kao = f.ints("k.kao")!!
    private val cat = f.ints("k.cat")!!
    private val pri = f.ints("k.pri")!!
    private val cats = f.ints("k.cats")!!
    private val rd = f.index("k.rd")!!
    private val ct = f.index("k.ct")!!
    val size: Int get() = pri.limit()

    private fun list(ix: ScdFile.Index, key: String, limit: Int): List<String> {
        val k = ix.find(key)
        if (k < 0) return emptyList()
        return (ix.start(k) until capEnd(ix.start(k), ix.end(k), limit)).map { f.str(kao.get(ix.row(it))) }
    }

    fun byReading(reading: String, limit: Int) = list(rd, reading, limit)
    fun byCategory(category: String, limit: Int = Int.MAX_VALUE) = list(ct, category, limit)
    fun categories(): List<String> = List(cats.limit()) { f.str(cats.get(it)) }

    fun kaomoji(i: Int) = f.str(kao.get(i))

    /**
     * 設定頁瀏覽（顏文字, 分類以「、」接起來），同舊版
     * SELECT kaomoji, group_concat(category, '、') FROM kaomoji_dict [WHERE category LIKE %q% OR kaomoji LIKE %q%] GROUP BY kaomoji ORDER BY MIN(priority)：
     * 先篩再分組；組依最小優先序、再依顏文字的 UTF-8 順序；組內分類依原順序
     */
    fun browse(q: String): List<Pair<String, String>> {
        val like = Like("%$q%")
        val groups = HashMap<String, Pair<IntArray, MutableList<String>>>()
        for (i in 0 until size) {
            val c = f.str(cat.get(i))
            val k = f.str(kao.get(i))
            if (q.isNotEmpty() && !like.matches(c) && !like.matches(k)) continue
            val g = groups.getOrPut(k) { intArrayOf(Int.MAX_VALUE) to ArrayList() }
            g.first[0] = minOf(g.first[0], pri.get(i))
            g.second.add(c)
        }
        return groups.entries.sortedWith { a, b ->
            val c = a.value.first[0].compareTo(b.value.first[0])
            if (c != 0) c else compareUtf8(a.key, b.key)
        }.map { (k, g) -> k to g.second.joinToString("、") }
    }
}

/** SQLite 的字串比較（BINARY：UTF-8 位元組順序；跟 String.compareTo 只在補充平面字元上不同） */
fun compareUtf8(a: String, b: String): Int {
    val x = a.toByteArray(Charsets.UTF_8)
    val y = b.toByteArray(Charsets.UTF_8)
    for (i in 0 until minOf(x.size, y.size)) {
        val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
        if (c != 0) return c
    }
    return x.size - y.size
}
