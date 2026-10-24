package com.p3dro.scorpio.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class PluginsTest {

    private val date = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 23, 9, 5, 7) }.time

    @Test
    fun expandsDateTimeTokens() {
        assertEquals("2026/10/23", Plugins.expand("{date:yyyy/MM/dd}", date))
        assertEquals("09:05", Plugins.expand("{time:HH:mm}", date))
        assertEquals("民國115年10月23日", Plugins.expand("民國{roc}年{date:M月d日}", date))
        assertEquals("星期五", Plugins.expand("{weekday}", date))
        assertEquals("10/23（五）", Plugins.expand("{date:M/d}（{weekday:short}）", date))
    }

    @Test
    fun dayOffsets() {
        assertEquals("10/24（六）", Plugins.expand("{date+1:M/d}（{weekday+1:short}）", date))
        assertEquals("星期四", Plugins.expand("{weekday-1}", date))
        // 跨月、跨年
        assertEquals("2026/11/01", Plugins.expand("{date+9:yyyy/MM/dd}", date))
        assertEquals("116", Plugins.expand("{roc+70}", date))
        assertEquals("09:05", Plugins.expand("{time:HH:mm}", date))
    }

    @Test
    fun parsesItemTitle() {
        val p = Plugins.parse("""{"id":"t","name":"t","tabs":[{"name":"A","items":[{"title":"明天","label":"{date+1:M/d}","insert":"{date+1:M/d}"},"，"]}]}""")!!
        assertEquals("明天", p.tabs[0].items[0].title)
        assertNull(p.tabs[0].items[1].title)
    }

    @Test
    fun selectionIsUrlEncodedOnlyForUrls() {
        assertEquals("q=台北 101", Plugins.expand("q={selection}", date, "台北 101"))
        assertEquals("q=%E5%8F%B0%E5%8C%97+101", Plugins.expand("q={selection}", date, "台北 101", encode = true))
    }

    @Test
    fun transforms() {
        assertEquals("ＡＢＣ　１２３！", Plugins.transform("fullwidth", "ABC 123!"))
        assertEquals("ABC 123!", Plugins.transform("halfwidth", "ＡＢＣ　１２３！"))
        assertEquals("Hello World", Plugins.transform("title", "hello WORLD"))
        assertEquals("台北101", Plugins.transform("trim_spaces", "台北 101"))
    }

    @Test
    fun parsesPluginAndSkipsUnknownActions() {
        val p = Plugins.parse(
            """{"schema":1,"id":"t","name":"測試","icon":"📅","tabs":[{"name":"A","items":[
              "，",
              {"label":"今天","insert":"{date:M/d}"},
              {"label":"大寫","action":"transform","op":"upper"},
              {"label":"不安全","action":"open_url","url":"http://example.com"},
              {"label":"未來的動作","action":"run_script"}
            ]}]}"""
        )!!
        assertEquals(listOf("，", "今天", "大寫"), p.tabs[0].items.map { it.label })
        assertEquals("transform", p.tabs[0].items[2].action)
    }

    @Test
    fun rejectsNewerSchema() {
        assertNull(Plugins.parse("""{"schema":2,"id":"t","name":"x","tabs":[]}"""))
    }
}
