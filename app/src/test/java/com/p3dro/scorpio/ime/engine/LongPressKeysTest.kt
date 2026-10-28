package com.p3dro.scorpio.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LongPressKeysTest {

    @Test
    fun defaultsFollowGboard() {
        // 大千：第一排數字、其他排實體鍵盤的字母
        assertEquals(listOf("1"), LongPressKeys.DACHEN['ㄅ'])
        assertEquals(listOf("0"), LongPressKeys.DACHEN['ㄢ'])
        assertEquals(listOf("q"), LongPressKeys.DACHEN['ㄆ'])
        assertEquals(listOf("m"), LongPressKeys.DACHEN['ㄩ'])
        assertEquals(listOf("？"), LongPressKeys.DACHEN['ㄥ'])
        // 40 個字鍵都有，ㄦ 沒有（內建的 ㄦ 在最下排、長按是符號本身）
        assertEquals(KeyboardLayout.DACHEN.centers.keys, LongPressKeys.DACHEN.keys)
        assertEquals(listOf("1"), LongPressKeys.QWERTY['q'])
        assertEquals(listOf("@"), LongPressKeys.QWERTY['a'])
        assertEquals(('a'..'z').toSet(), LongPressKeys.QWERTY.keys)
        // 中文 QWERTY 的標點用全形
        assertEquals(listOf("？"), LongPressKeys.CHINESE_QWERTY['m'])
        assertEquals(listOf("1"), LongPressKeys.CHINESE_QWERTY['q'])
    }

    @Test
    fun layoutOverridesMergeOverDefaults() {
        val o = org.json.JSONObject("""{"e":"éèê","q":["1","!"],"a":"","x":["www.",".com"]}""")
        val m = LongPressKeys.merge(LongPressKeys.QWERTY, LongPressKeys.parse(o, ('a'..'z').toSet())!!)
        assertEquals(listOf("é", "è", "ê"), m['e'])
        assertEquals(listOf("1", "!"), m['q'])
        assertEquals(listOf("www.", ".com"), m['x'])
        // 空的：這個鍵不要副鍵
        assertFalse('a' in m)
        assertEquals(listOf("#"), m['s'])
        // 補充平面的字不拆開
        assertEquals(listOf("𠀀", "a"), LongPressKeys.parse(org.json.JSONObject("""{"b":"𠀀a"}"""), setOf('b'))!!['b'])
    }

    @Test
    fun rejectsBadLong() {
        val letters = ('a'..'z').toSet()
        assertNull(LongPressKeys.parse(org.json.JSONObject("""{"ㄅ":"1"}"""), letters))
        assertNull(LongPressKeys.parse(org.json.JSONObject("""{"ab":"1"}"""), letters))
        assertNull(LongPressKeys.parse(org.json.JSONObject("""{"a":[" x"]}"""), letters))
        assertNull(LongPressKeys.parse(org.json.JSONObject("""{"a":"123456789"}"""), letters))
        assertNull(LongPressKeys.parse(org.json.JSONObject("""{"a":1}"""), letters))
    }

    @Test
    fun layoutsCarryLongPress() {
        val hsu = ChineseMethod.parse("""{"id":"hsu","name":"許","engine":"zhuyin26","keymap":"bpmfdtnlgkhjvcjvcrzasexuyhgeiawomnkllsdfj","long":{"m":"？！"}}""")!!
        assertEquals(listOf("？", "！"), hsu.longPress['m'])
        assertEquals(listOf("1"), hsu.longPress['q'])
        assertEquals(listOf("1"), ChineseMethod.DACHEN.longPress['ㄅ'])
        // 寫了不是這個版面的鍵：整個版面不收
        assertNull(ChineseMethod.parse("""{"id":"x","name":"x","engine":"pinyin","long":{"ㄅ":"1"}}"""))
        // 沒寫 long 的舊版面照預設
        assertEquals(LongPressKeys.CHINESE_QWERTY, ChineseMethod.parse("""{"id":"p","name":"p","engine":"pinyin"}""")!!.longPress)
    }
}
