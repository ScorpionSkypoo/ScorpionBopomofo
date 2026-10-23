package com.p3dro.scorpio.ime.engine

/**
 * 匯入詞庫文字檔的解析：一行一個詞，詞、注音、次數或詞頻以空白、Tab 或逗號分隔，順序不拘，# 開頭為註解
 * 注音音節以空白或 - 分隔（ㄊㄞˊ ㄅㄟˇ／ㄊㄞˊ-ㄅㄟˇ）
 *
 * 每行取第一個含漢字的欄位當詞，所以常見格式都能直接匯入：
 *   純詞表「詞」、小麥注音／威注音使用者詞庫「詞 ㄅ-ㄆ」、新酷音 tsi.src「詞 次數 ㄅ ㄆ」、
 *   小麥注音 data.txt「ㄅ-ㄆ 詞 詞頻」、RIME .dict.yaml「詞<Tab>拼音<Tab>權重」（略過 YAML 檔頭）、
 *   Gboard 匯出的 dictionary.txt「縮寫<Tab>詞<Tab>zh-TW」
 */
object DictImportParser {

    data class Entry(val word: String, val syllables: List<String>, val freq: Double?)

    private val bopomofo = Regex("^[ㄅ-ㄯㆠ-ㆿˊˇˋ˙ˉ-]+$")
    private val number = Regex("^-?\\d+(\\.\\d+)?$")
    private val han = Regex("[㐀-鿿豈-﫿]")
    private val separators = Regex("[\\s,]+")

    fun parse(text: String): List<Entry> {
        var lines = text.removePrefix("﻿").lines()
        // RIME：--- 開頭的 YAML 檔頭到 ... 為止
        val yamlEnd = lines.take(200).indexOfFirst { it.trim() == "..." }
        if (yamlEnd >= 0 && lines.take(yamlEnd).any { it.trim() == "---" || it.trimStart().startsWith("name:") }) {
            lines = lines.drop(yamlEnd + 1)
        }
        val out = ArrayList<Entry>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val tokens = line.split(separators).filter { it.isNotEmpty() }
            val wordIndex = tokens.indexOfFirst { han.containsMatchIn(it) && !bopomofo.matches(it) }
            if (wordIndex < 0) continue
            val word = tokens[wordIndex]
            if (word.startsWith("MACRO@")) continue
            val syllables = ArrayList<String>()
            var freq: Double? = null
            tokens.forEachIndexed { i, t ->
                when {
                    i == wordIndex -> {}
                    bopomofo.matches(t) -> syllables.addAll(t.split('-').filter { it.isNotEmpty() })
                    number.matches(t) -> freq = t.toDouble()
                }
            }
            out.add(Entry(word, syllables, freq))
        }
        return out
    }
}
