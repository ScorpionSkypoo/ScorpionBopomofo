package com.p3dro.scorpio.ime.engine

import android.database.sqlite.SQLiteDatabase
import com.p3dro.scorpio.ime.engine.scd.Like
import java.util.TreeMap

/**
 * 個人學習資料（選字、接續搭配、英文）在記憶體裡的鏡像：檔案仍存在 SQLite（scorpio_user.db），
 * 每按一鍵的查詢改查這裡，不碰 SQLite。寫入時先寫 SQLite，再把受影響的列從 SQLite 讀回來，兩邊永遠一致。
 * 學習資料通常只有幾千筆，整份放記憶體不到 1 MB。
 *
 * 每個查詢對應舊版的一條 SQL，排序條件相同（count DESC[, last_used DESC]，再依 rowid）
 */
class UserStore {

    class Word(val reading: String, val toneless: String, val initials: String, val word: String, val count: Int, val lastUsed: Long, val rowid: Long)
    class Pair2(val prev: String, val next: String, val count: Int, val lastUsed: Long, val rowid: Long)
    class English(val key: String, val word: String, val count: Int, val lastUsed: Long, val rowid: Long)

    private val byToneless = TreeMap<String, MutableList<Word>>()
    private val byReading = HashMap<String, MutableList<Word>>()
    private val byInitials = HashMap<String, MutableList<Word>>()
    private val byWord = HashMap<String, MutableList<Word>>()
    private val bigrams = HashMap<String, MutableList<Pair2>>()
    private val english = TreeMap<String, English>()

    private val wordOrder = Comparator<Word> { a, b ->
        if (a.count != b.count) b.count - a.count else if (a.lastUsed != b.lastUsed) b.lastUsed.compareTo(a.lastUsed) else a.rowid.compareTo(b.rowid)
    }
    private val pairByCount = Comparator<Pair2> { a, b -> if (a.count != b.count) b.count - a.count else a.rowid.compareTo(b.rowid) }
    private val pairByCountRecent = Comparator<Pair2> { a, b ->
        if (a.count != b.count) b.count - a.count else if (a.lastUsed != b.lastUsed) b.lastUsed.compareTo(a.lastUsed) else a.rowid.compareTo(b.rowid)
    }

    // ---------- 載入與同步 ----------

    @Synchronized
    fun load(db: SQLiteDatabase) {
        byToneless.clear(); byReading.clear(); byInitials.clear(); byWord.clear(); bigrams.clear(); english.clear()
        db.rawQuery("SELECT reading, toneless, initials, word, count, last_used, rowid FROM user_dict", null).use { c ->
            while (c.moveToNext()) addWord(Word(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getLong(5), c.getLong(6)))
        }
        db.rawQuery("SELECT prev_word, next_word, count, last_used, rowid FROM user_bigram", null).use { c ->
            while (c.moveToNext()) addPair(Pair2(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4)))
        }
        db.rawQuery("SELECT key, word, count, last_used, rowid FROM user_english", null).use { c ->
            while (c.moveToNext()) english[c.getString(0)] = English(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4))
        }
    }

    private fun addWord(w: Word) {
        byToneless.getOrPut(w.toneless) { ArrayList(2) }.add(w)
        byReading.getOrPut(w.reading) { ArrayList(2) }.add(w)
        byInitials.getOrPut(w.initials) { ArrayList(2) }.add(w)
        byWord.getOrPut(w.word) { ArrayList(2) }.add(w)
    }

    private fun removeWordRows(word: String) {
        val rows = byWord.remove(word) ?: return
        for (w in rows) {
            byToneless[w.toneless]?.let { it.remove(w); if (it.isEmpty()) byToneless.remove(w.toneless) }
            byReading[w.reading]?.let { it.remove(w); if (it.isEmpty()) byReading.remove(w.reading) }
            byInitials[w.initials]?.let { it.remove(w); if (it.isEmpty()) byInitials.remove(w.initials) }
        }
    }

    private fun addPair(p: Pair2) {
        bigrams.getOrPut(p.prev) { ArrayList(4) }.add(p)
    }

    /** 這個詞在 user_dict 的列有變動（學習、校正、刪除）：整組從 SQLite 讀回來 */
    @Synchronized
    fun reloadWord(db: SQLiteDatabase, word: String) {
        removeWordRows(word)
        db.rawQuery("SELECT reading, toneless, initials, word, count, last_used, rowid FROM user_dict WHERE word = ?", arrayOf(word)).use { c ->
            while (c.moveToNext()) addWord(Word(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getLong(5), c.getLong(6)))
        }
    }

    @Synchronized
    fun reloadBigrams(db: SQLiteDatabase, prev: String) {
        bigrams.remove(prev)
        db.rawQuery("SELECT prev_word, next_word, count, last_used, rowid FROM user_bigram WHERE prev_word = ?", arrayOf(prev)).use { c ->
            while (c.moveToNext()) addPair(Pair2(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4)))
        }
    }

    /** 刪掉一個詞的所有學習：選字紀錄，以及前後接到它的搭配 */
    @Synchronized
    fun forgetWord(word: String) {
        removeWordRows(word)
        bigrams.remove(word)
        val empty = ArrayList<String>()
        for ((prev, list) in bigrams) {
            list.removeAll { it.next == word }
            if (list.isEmpty()) empty.add(prev)
        }
        empty.forEach { bigrams.remove(it) }
    }

    @Synchronized
    fun reloadEnglish(db: SQLiteDatabase, key: String) {
        english.remove(key)
        db.rawQuery("SELECT key, word, count, last_used, rowid FROM user_english WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) english[key] = English(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4))
        }
    }

    @Synchronized
    fun clear() {
        byToneless.clear(); byReading.clear(); byInitials.clear(); byWord.clear(); bigrams.clear(); english.clear()
    }

    // ---------- 選字（user_dict） ----------

    /** SELECT … FROM user_dict WHERE (toneless = ? OR reading = ?) [AND length(word) = 1] ORDER BY count DESC, last_used DESC LIMIT n */
    @Synchronized
    fun matches(toneless: String, reading: String, limit: Int, singleChar: Boolean = false): List<Word> {
        val a = byToneless[toneless]
        val b = byReading[reading]
        if (a == null && b == null) return emptyList()
        val set = LinkedHashSet<Word>()
        a?.let { set.addAll(it) }
        b?.let { set.addAll(it) }
        return set.filter { !singleChar || it.word.codePointCount(0, it.word.length) == 1 }.sortedWith(wordOrder).take(limit)
    }

    /** SELECT … FROM user_dict WHERE toneless > ? AND toneless < ? ORDER BY count DESC, last_used DESC LIMIT n */
    @Synchronized
    fun tonelessBetween(from: String, to: String, limit: Int): List<Word> {
        if (from >= to) return emptyList()
        val out = ArrayList<Word>()
        for (list in byToneless.subMap(from, false, to, false).values) out.addAll(list)
        return out.sortedWith(wordOrder).take(limit)
    }

    /** SELECT … FROM user_dict WHERE initials = ? AND toneless LIKE ? ORDER BY count DESC, last_used DESC LIMIT n */
    @Synchronized
    fun initialsLike(initials: String, pattern: String, limit: Int): List<Word> {
        val list = byInitials[initials] ?: return emptyList()
        val like = Like(pattern)
        return list.filter { like.matches(it.toneless) }.sortedWith(wordOrder).take(limit)
    }

    @Synchronized
    fun hasWord(word: String) = byWord.containsKey(word)

    @Synchronized
    fun words(): List<String> = byWord.keys.toList()

    // ---------- 接續搭配（user_bigram） ----------

    /** SELECT next_word, count FROM user_bigram WHERE prev_word = ? ORDER BY count DESC LIMIT n */
    @Synchronized
    fun next(prev: String, limit: Int): List<Pair2> = bigrams[prev]?.sortedWith(pairByCount)?.take(limit) ?: emptyList()

    /** … WHERE prev_word = ? AND <filter> ORDER BY count DESC, last_used DESC LIMIT n */
    @Synchronized
    fun nextRecent(prev: String, limit: Int, filter: (Pair2) -> Boolean): List<String> =
        bigrams[prev]?.filter(filter)?.sortedWith(pairByCountRecent)?.take(limit)?.map { it.next } ?: emptyList()

    @Synchronized
    fun bigramCount() = bigrams.values.sumOf { it.size }

    // ---------- 英文（user_english） ----------

    /** SELECT key, word, count FROM user_english WHERE key >= ? AND key < ? ORDER BY count DESC LIMIT n */
    @Synchronized
    fun englishPrefix(from: String, to: String, limit: Int): List<English> {
        if (from >= to) return emptyList()
        return english.subMap(from, true, to, false).values
            .sortedWith { a, b -> if (a.count != b.count) b.count - a.count else a.rowid.compareTo(b.rowid) }
            .take(limit)
    }

    @Synchronized
    fun englishWord(key: String): English? = english[key]

    @Synchronized
    fun englishCount() = english.size
}
