package com.ncm.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySessionAccumulatorTest {

    @Test
    fun freshSessionStartsEmpty() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        assertEquals(0L, acc.currentAccumulatedPlayedMs())
        assertFalse(acc.consumeQualification(180_000))
    }

    @Test
    fun accumulatesPlayedMillisWhilePlaying() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(1_000, isPlaying = true)
        acc.track(3_000, isPlaying = true)
        assertEquals(3_000L, acc.currentAccumulatedPlayedMs())
    }

    @Test
    fun pausedGapIsNotCounted() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(2_000, isPlaying = true)
        acc.track(4_000, isPlaying = true)
        acc.track(6_000, isPlaying = true)
        acc.track(8_000, isPlaying = true)
        acc.track(10_000, isPlaying = true)
        acc.track(12_000, isPlaying = true)
        acc.track(14_000, isPlaying = true)
        acc.track(16_000, isPlaying = true)    // 16s played, within cap per step
        acc.track(16_000, isPlaying = false)   // pause: baseline dropped
        acc.track(32_000, isPlaying = true)    // resume 16s later: no accumulation for the gap
        acc.track(34_000, isPlaying = true)
        acc.track(36_000, isPlaying = true)
        assertEquals(20_000L, acc.currentAccumulatedPlayedMs())
    }

    @Test
    fun seekDoesNotCountThePositionJump() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(1_000, isPlaying = true)
        acc.track(2_000, isPlaying = true)
        acc.track(3_000, isPlaying = true)
        acc.track(4_000, isPlaying = true)
        acc.track(5_000, isPlaying = true)
        acc.onSeekStarted()
        acc.track(90_000, isPlaying = true)    // jump right after seek: baseline rebuilt, not counted
        acc.track(91_000, isPlaying = true)
        assertEquals(6_000L, acc.currentAccumulatedPlayedMs())
    }

    @Test
    fun bufferingJumpOverThresholdIsNotCounted() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(2_000, isPlaying = true)
        acc.track(4_000, isPlaying = true)
        acc.track(6_000, isPlaying = true)
        acc.track(8_000, isPlaying = true)
        acc.track(10_000, isPlaying = true)    // 10s played, within cap per step
        acc.track(60_000, isPlaying = true)    // buffered jump of 50s without a seek: rejected
        acc.track(61_000, isPlaying = true)
        assertEquals(11_000L, acc.currentAccumulatedPlayedMs())
    }

    @Test
    fun qualifiesAfterThirtySeconds() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        var position = 0L
        acc.track(position, isPlaying = true)
        while (position < 30_000) {
            position += 500
            acc.track(position, isPlaying = true)
        }
        assertTrue(acc.consumeQualification(300_000))
        assertFalse(acc.consumeQualification(300_000))   // fires once per session
    }

    @Test
    fun qualifiesByRatioWithoutThirtySeconds() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(2_000, isPlaying = true)
        acc.track(4_000, isPlaying = true)
        // duration 8000 → ratio 0.5
        assertTrue(acc.consumeQualification(8_000))
    }

    @Test
    fun doesNotQualifyWhenDurationUnknownAndUnderThirtySeconds() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(2_000, isPlaying = true)
        acc.track(4_000, isPlaying = true)
        assertFalse(acc.consumeQualification(0))
        assertFalse(acc.consumeQualification(-1L))
    }

    @Test
    fun beginSessionResetsState() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        var position = 0L
        acc.track(position, isPlaying = true)
        while (position < 40_000) {
            position += 500
            acc.track(position, isPlaying = true)
        }
        assertTrue(acc.consumeQualification(120_000))
        val previousSessionStart = acc.sessionStartedAt
        // Ensure sessionStartedAt (System.currentTimeMillis()) advances between the two
        // back-to-back beginSession() calls, which otherwise land in the same millisecond.
        Thread.sleep(20)
        acc.beginSession()
        assertEquals(0L, acc.currentAccumulatedPlayedMs())
        assertFalse(acc.consumeQualification(120_000))
        assertNotEquals(previousSessionStart, acc.sessionStartedAt)
    }
}
