package com.p3dro.scorpio.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ImeConfigTest {

    private val bundled = File("src/main/assets/config/ime.json").readText()

    @Test
    fun bundledConfigParses() {
        val c = ImeConfig.parse(bundled)
        assertNotNull(c)
        c!!
        assertEquals("ㄨㄛ", c.lazySyllables.getValue("ㄨ").first())
        assertEquals("開心", c.kaomojiKeywords["笑臉"])
        assertEquals(0.01, c.fuzzyPriors.getValue("ㄢㄤ"), 1e-9)
        assertEquals(2, c.symbols.getValue("zh").size)
        assertEquals("、", c.symbols.getValue("zh")[0].row3[0])
    }

    @Test
    fun badSymbolPageRejected() {
        // 第三排少一個：整份不用（沿用原本的設定）
        val bad = bundled.replace("\"row3\":[\"、\",", "\"row3\":[")
        assertNull(ImeConfig.parse(bad))
    }

    @Test
    fun lazyExpansionMustStartWithKey() {
        assertNull(ImeConfig.parse(bundled.replace("\"ㄅ\":[\"ㄅㄨ\"", "\"ㄅ\":[\"ㄆㄨ\"")))
    }
}
