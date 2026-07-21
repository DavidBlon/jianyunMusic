package com.ncm.app.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricScrollPlannerTest {

    @Test
    fun `first lyric view snaps directly near current line`() {
        val plan = planLyricScroll(previousActiveIndex = null, activeIndex = 50)

        assertEquals(47, plan.targetIndex)
        assertEquals(LyricScrollMotion.SNAP, plan.motion)
    }

    @Test
    fun `normal playback advance animates a short distance`() {
        val plan = planLyricScroll(previousActiveIndex = 50, activeIndex = 51)

        assertEquals(48, plan.targetIndex)
        assertEquals(LyricScrollMotion.ANIMATE, plan.motion)
    }

    @Test
    fun `seeking far away snaps instead of flying through lyrics`() {
        val plan = planLyricScroll(previousActiveIndex = 12, activeIndex = 70)

        assertEquals(67, plan.targetIndex)
        assertEquals(LyricScrollMotion.SNAP, plan.motion)
    }

    @Test
    fun `early lyric lines do not scroll needlessly`() {
        val plan = planLyricScroll(previousActiveIndex = 1, activeIndex = 2)

        assertEquals(0, plan.targetIndex)
        assertEquals(LyricScrollMotion.NONE, plan.motion)
    }
}
