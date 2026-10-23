package com.p3dro.scorpio.ime.engine.scd

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.random.Random

/**
 * App 的 SCD 查詢（WordStore，多個檔合併）與舊版手機上的 SQLite dict 表逐筆比對：同一條查詢，結果與順序都要一樣。
 *
 * 舊版手機上的 dict 表＝主詞庫，再依 dict_packs 的順序插進開著的附加詞庫；這裡照樣建一張，下 App 舊版的 SQL。
 * 需要解壓後的舊版 scorpio_dict.db（資料 repo 的 Release base），沒給就略過：
 *   ./gradlew testReleaseUnitTest -Pscd.sqlite=/path/to/scorpio_dict.db --tests '*ScdSqliteEquivalenceTest*'
 */
class ScdSqliteEquivalenceTest {

    private val dir = File("src/main/assets/scd")

    private class Row(val word: String, val score: Double, val reading: String) {
        override fun toString() = "$word/$score/$reading"
        override fun equals(other: Any?) = other is Row && other.word == word && other.score == score && other.reading == reading
        override fun hashCode() = word.hashCode()
    }

    private fun Connection.rows(sql: String, vararg args: String): List<Row> = prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setString(i + 1, a) }
        st.executeQuery().use { rs ->
            val out = ArrayList<Row>()
            while (rs.next()) out.add(Row(rs.getString(1), rs.getDouble(2), rs.getString(3)))
            out
        }
    }

    private fun Connection.strings(sql: String): List<String> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val out = ArrayList<String>()
            while (rs.next()) out.add(rs.getString(1))
            out
        }
    }

    @Test
    fun wordQueriesMatchSqlite() {
        val path = System.getProperty("scd.sqlite")
        assumeTrue("沒給 -Pscd.sqlite，略過", path != null && File(path).exists())

        // 跟 App 的 buildData 一樣：主詞庫在前，附加詞庫依 meta.sort、id
        val files = dir.listFiles { f -> f.name.endsWith(".scd") }!!.map { ScdFile.open(it) }
        val core = files.first { it.id == "core" }
        val packs = files.filter { it.id != "core" && it.group == "words" && it.has("w.word") }
            .sortedWith(compareBy({ it.meta.optInt("sort", 99) }, { it.id }))
        val store = WordStore((listOf(core) + packs).map { WordTable(it) })

        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.execute("ATTACH DATABASE '$path' AS src")
            st.execute("CREATE TABLE dict (reading TEXT NOT NULL, toneless TEXT NOT NULL, word TEXT NOT NULL, score REAL NOT NULL, initials TEXT, finals TEXT, source TEXT)")
            st.execute("INSERT INTO dict SELECT reading, toneless, word, score, initials, finals, NULL FROM src.dict WHERE source IS NULL ORDER BY rowid")
            for (p in packs) {
                st.execute("INSERT INTO dict SELECT reading, toneless, word, score, initials, finals, source FROM src.addon_dict WHERE source = '${p.id}' ORDER BY rowid")
            }
            for (c in listOf("reading", "toneless", "initials", "finals", "word", "source")) st.execute("CREATE INDEX idx_$c ON dict($c)")
        }

        val rnd = Random(20261023)
        val toneless = db.strings("SELECT DISTINCT toneless FROM dict").shuffled(rnd).take(4000)
        val readings = db.strings("SELECT DISTINCT reading FROM dict").shuffled(rnd).take(2000)
        val finals = db.strings("SELECT DISTINCT finals FROM dict").shuffled(rnd).take(1000)
        val words = db.strings("SELECT DISTINCT word FROM dict").shuffled(rnd).take(2000)
        val initialSet = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ"

        var checked = 0
        val failures = ArrayList<String>()
        var tieOnly = 0
        fun check(name: String, expected: List<Row>, actual: List<Row>) {
            checked++
            if (expected == actual) return
            // 只差在跨檔同分的先後（舊版是手機上插進 dict 表的先後）也記下來
            if (expected.size == actual.size && expected.map { it.score } == actual.map { it.score } && expected.toSet() == actual.toSet()) {
                tieOnly++
                return
            }
            if (failures.size < 20) failures.add("$name\n  SQLite: $expected\n  SCD:    $actual")
        }
        fun List<WordHit>.rows() = map { Row(it.word, it.score, it.reading) }

        for (k in toneless) {
            check("toneless $k", db.rows("SELECT word, score, reading FROM dict WHERE toneless = ? ORDER BY score DESC LIMIT 40", k), store.byToneless(k, 40).rows())
            check("toneless1 $k", db.rows("SELECT word, score, reading FROM dict WHERE toneless = ? AND length(word) = 1 ORDER BY score DESC LIMIT 30", k), store.byToneless(k, 30, singleChar = true).rows())
            val p = k.substring(0, rnd.nextInt(1, k.length + 1))
            check("prefix $p", db.rows("SELECT word, score, reading FROM dict WHERE toneless >= ? AND toneless < ? ORDER BY score DESC LIMIT 15", p, p + "￿"), store.byTonelessPrefix(p, 15).rows())
            val maxSql = db.rows("SELECT word, score, reading FROM dict WHERE toneless = ? ORDER BY score DESC LIMIT 1", k).firstOrNull()?.score
            assertEquals("max $k", maxSql, store.maxScore(k))
            // 簡拼：多音節的詞把部分音節縮成聲母
            val syls = k.split("-")
            if (syls.size > 1) {
                val sub = syls.map { s -> if (s.isNotEmpty() && s[0] in initialSet && rnd.nextDouble() < 0.6) s.substring(0, 1) else s }
                if (sub.any { it.length == 1 && it[0] in initialSet }) {
                    val k1 = sub.joinToString("-") { s -> if (s.isNotEmpty() && s[0] in initialSet) s.substring(0, 1) else "_" }
                    val k2 = sub.joinToString("-") { s -> if (s.isNotEmpty() && s[0] in initialSet) s.substring(0, 1) else "" }
                    val k3 = sub.mapNotNull { it.firstOrNull()?.toString() }.joinToString("-")
                    val like = sub.joinToString("-") { s -> if (s.length == 1 && s[0] in initialSet) "$s%" else s }
                    for (n in listOf(15, 3)) {
                        check("initials $like", db.rows("SELECT word, score, reading FROM dict WHERE (initials = ? OR initials = ? OR initials = ?) AND toneless LIKE ? ORDER BY score DESC LIMIT $n", k1, k2, k3, like),
                            store.byInitialsLike(listOf(k1, k2, k3), like, n).rows())
                    }
                }
            }
        }
        for (i in 0 until 1500) {
            val keys = (0 until rnd.nextInt(2, 25)).map { toneless[rnd.nextInt(toneless.size)] } + "ㄅㄨ-ㄘㄨㄣ-ㄗㄞ"
            val marks = keys.joinToString(",") { "?" }
            check("in", db.rows("SELECT word, score, reading FROM dict WHERE toneless IN ($marks) ORDER BY score DESC LIMIT 10", *keys.toTypedArray()), store.byTonelessIn(keys, 10).rows())
        }
        for (r in readings) {
            check("reading $r", db.rows("SELECT word, score, reading FROM dict WHERE reading = ? ORDER BY score DESC LIMIT 20", r), store.byReading(r, 20).rows())
            check("reading1 $r", db.rows("SELECT word, score, reading FROM dict WHERE reading = ? AND length(word) = 1 ORDER BY score DESC LIMIT 20", r), store.byReading(r, 20, singleChar = true).rows())
        }
        for (f in finals) {
            check("finals $f", db.rows("SELECT word, score, reading FROM dict WHERE finals = ? ORDER BY score DESC LIMIT 15", f), store.byFinals(f, 15).rows())
        }
        for (w in words) {
            check("word $w", db.rows("SELECT word, score, reading FROM dict WHERE word = ? ORDER BY score DESC LIMIT 8", w), store.byWord(w, 8).rows())
            assertEquals("toneless of $w", db.strings("SELECT DISTINCT toneless FROM dict WHERE word = '${w.replace("'", "''")}'").toSet(), store.tonelessOfWord(w))
            val t = store.tonelessOfWord(w).first()
            assertEquals(true, store.hasTonelessWord(t, w))
        }
        val validSql = db.strings("SELECT DISTINCT toneless FROM dict WHERE instr(toneless, '-') = 0 AND toneless <> ''").toSet()
        val freq = store.syllableFrequencies()
        assertEquals(validSql, freq.keys)
        db.createStatement().use { st ->
            st.executeQuery("SELECT toneless, score FROM dict WHERE instr(toneless, '-') = 0 AND toneless <> ''").use { rs ->
                val m = HashMap<String, Double>()
                while (rs.next()) m[rs.getString(1)] = (m[rs.getString(1)] ?: 0.0) + Math.pow(10.0, rs.getDouble(2))
                for ((k, v) in m) assertEquals("freq $k", v, freq.getValue(k), v * 1e-12)
            }
        }
        // 設定頁瀏覽 Emoji、顏文字（分組、排序、篩選）
        val emoji = EmojiTable(files.first { it.id == "emoji" })
        val kao = KaomojiTable(files.first { it.id == "kaomoji" })
        fun Connection.pairs(sql: String, vararg args: String): List<Pair<String, String>> = prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toList() }
        }
        for (q in listOf("", "笑", "哭", "a", "ok", "😄", "早安", "%", "_", "貓")) {
            val e = if (q.isEmpty()) db.pairs("SELECT emoji, group_concat(keyword, '、') FROM src.emoji_dict GROUP BY emoji ORDER BY MIN(priority)")
            else db.pairs("SELECT emoji, group_concat(keyword, '、') FROM src.emoji_dict WHERE keyword LIKE ? OR emoji = ? GROUP BY emoji ORDER BY MIN(priority)", "%$q%", q)
            assertEquals("emoji 瀏覽「$q」", e, emoji.browse(q))
            val k = if (q.isEmpty()) db.pairs("SELECT kaomoji, group_concat(category, '、') FROM src.kaomoji_dict GROUP BY kaomoji ORDER BY MIN(priority)")
            else db.pairs("SELECT kaomoji, group_concat(category, '、') FROM src.kaomoji_dict WHERE category LIKE ? OR kaomoji LIKE ? GROUP BY kaomoji ORDER BY MIN(priority)", "%$q%", "%$q%")
            assertEquals("顏文字瀏覽「$q」", k, kao.browse(q))
        }
        println("SCD／SQLite 比對 $checked 條查詢：跨檔同分先後不同 $tieOnly 條，其他不同 ${failures.size} 條")
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }
}
