package com.p3dro.scorpio.ime.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun newerTag() {
        assertTrue(AppUpdater.isNewer("v1.1.0", "1.0.0"))
        assertTrue(AppUpdater.isNewer("v1.1.0", "1.0.0-9-g72ff49d"))
        assertTrue(AppUpdater.isNewer("v2.0", "1.9.9"))
        assertTrue(AppUpdater.isNewer("v1.0.1", "1.0.0-30-gabc1234-dirty"))
    }

    @Test
    fun sameOrOlderTag() {
        assertFalse(AppUpdater.isNewer("v1.0.0", "1.0.0"))
        // 手機上的版本在 v1.0.0 之後又多了幾個 commit，不算有新版
        assertFalse(AppUpdater.isNewer("v1.0.0", "1.0.0-9-g72ff49d"))
        assertFalse(AppUpdater.isNewer("v0.9.0", "1.0.0"))
        assertFalse(AppUpdater.isNewer("nightly", "1.0.0"))
    }
}
