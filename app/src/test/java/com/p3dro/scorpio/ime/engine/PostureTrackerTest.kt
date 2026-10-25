package com.p3dro.scorpio.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PostureTrackerTest {

    /** 輪流按近鍵與遠鍵：近的間隔 near、遠的間隔 far（毫秒） */
    private fun type(t: PostureTracker, near: Long, far: Long, rounds: Int = 8): Int {
        var time = 1000L
        var x = 0.0
        var result = PostureTracker.UNKNOWN
        repeat(rounds) {
            time += near; x += 1.0; result = t.onTap(x, time)
            time += far; x = if (x > 4.5) 0.0 else 9.0; result = t.onTap(x, time)
        }
        return result
    }

    @Test
    fun farMovesFasterThanNearMeansTwoThumbs() {
        assertEquals(PostureTracker.TWO_THUMBS, type(PostureTracker(), near = 260, far = 180))
    }

    @Test
    fun farMovesSlowerThanNearMeansOneHand() {
        assertEquals(PostureTracker.ONE_HAND, type(PostureTracker(), near = 220, far = 380))
    }

    @Test
    fun similarTimingIsUnknown() {
        assertEquals(PostureTracker.UNKNOWN, type(PostureTracker(), near = 250, far = 260))
    }

    @Test
    fun tooFewSamplesIsUnknown() {
        assertEquals(PostureTracker.UNKNOWN, type(PostureTracker(), near = 260, far = 180, rounds = 3))
    }

    @Test
    fun pausesAreNotMoves() {
        val t = PostureTracker()
        var time = 0L
        repeat(20) { i -> time += 3000; t.onTap(if (i % 2 == 0) 0.0 else 9.0, time) }
        assertEquals(PostureTracker.UNKNOWN, t.posture)
    }
}
