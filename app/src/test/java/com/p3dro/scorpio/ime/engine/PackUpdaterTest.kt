package com.p3dro.scorpio.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackUpdaterTest {

    @Test
    fun versionCompare() {
        assertTrue(PackVersion.compare("2026.10.23", "2026.10") > 0)
        assertTrue(PackVersion.compare("2026.10.23", "2026.10.24") < 0)
        assertTrue(PackVersion.compare("2026.11", "2026.10.30") > 0)
        assertEquals(0, PackVersion.compare("2026.10.23", "2026.10.23"))
        assertTrue(PackVersion.compare("2026.10", "") > 0)
    }

    private val manifest = """
        {"schema": 1, "packs": [
          {"id": "trendy", "group": "words", "name": "流行與口語用語", "version": "2026.10.30", "size": 10,
           "sha256": "ab", "url": "https://example.com/trendy.db.gz", "minAppVersion": 103},
          {"id": "bg_wiki", "group": "bigram", "name": "維基百科", "version": "2026.10.23", "size": 20,
           "sha256": "cd", "url": "https://example.com/bg_wiki.db.gz", "minAppVersion": 103},
          {"id": "layout_x", "group": "layout", "name": "未來的版面", "version": "2027.1.1", "size": 1,
           "sha256": "ef", "url": "https://example.com/x", "minAppVersion": 999}
        ], "layouts": []}
    """.trimIndent()

    @Test
    fun parseManifest() {
        val packs = PackUpdater.parseManifest(manifest)
        assertEquals(listOf("trendy", "bg_wiki", "layout_x"), packs.map { it.id })
        assertEquals("bigram", packs[1].group)
        assertEquals(103, packs[0].minAppVersion)
    }

    @Test
    fun pendingSkipsCurrentAndTooNewForApp() {
        val packs = PackUpdater.parseManifest(manifest)
        val installed = mapOf("trendy" to "2026.10.23", "bg_wiki" to "2026.10.23")
        val p = PackUpdater.pending(packs, 105) { installed[it.id] ?: "" }
        assertEquals(listOf("trendy"), p.map { it.id })
    }

    @Test
    fun newerSchemaIsIgnored() {
        assertEquals(emptyList<PackUpdater.ManifestPack>(), PackUpdater.parseManifest("""{"schema": 2, "packs": []}"""))
    }
}
