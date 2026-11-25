package dev.towhedul.expresscast.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBitrateControllerTest {
    @Test
    fun ignoresIdleZeroFrameWindows() {
        val abr = AdaptiveBitrateController(maxBitrateBps = 10_000_000)
        val result = abr.onStatsReport(
            ControlMessage.StreamStatsReport(1000, framesCompleted = 0, framesLost = 0, renderedFps = 0, rttMs = 0),
            nowMs = 1000L
        )
        assertNull(result)
        assertEquals(10_000_000, abr.currentBitrateBps)
    }

    @Test
    fun severeAndModerateLossStepDownWithCooldownAndFloor() {
        val abr = AdaptiveBitrateController(maxBitrateBps = 8_000_000, minBitrateBps = 2_000_000)

        val firstDrop = abr.onStatsReport(
            ControlMessage.StreamStatsReport(1000, framesCompleted = 40, framesLost = 6, renderedFps = 40, rttMs = 15),
            nowMs = 2000L
        )
        assertEquals(6_000_000, firstDrop)

        // Cooldown prevents immediate second drop
        val suppressed = abr.onStatsReport(
            ControlMessage.StreamStatsReport(1000, framesCompleted = 40, framesLost = 6, renderedFps = 40, rttMs = 15),
            nowMs = 2500L
        )
        assertNull(suppressed)

        // Moderate drop after cooldown
        val secondDrop = abr.onStatsReport(
            ControlMessage.StreamStatsReport(1000, framesCompleted = 58, framesLost = 2, renderedFps = 58, rttMs = 15),
            nowMs = 4500L
        )
        assertTrue(secondDrop != null && secondDrop < 6_000_000)

        // Repeated severe drops clamp at minBitrateBps
        var time = 7000L
        repeat(5) {
            abr.onStatsReport(
                ControlMessage.StreamStatsReport(1000, framesCompleted = 30, framesLost = 10, renderedFps = 30, rttMs = 150),
                nowMs = time
            )
            time += 2500L
        }
        assertEquals(2_000_000, abr.currentBitrateBps)
    }

    @Test
    fun sustainedCleanWindowsClimbBackToMax() {
        val abr = AdaptiveBitrateController(maxBitrateBps = 6_000_000)
        abr.onStatsReport(
            ControlMessage.StreamStatsReport(1000, framesCompleted = 40, framesLost = 5, renderedFps = 40, rttMs = 10),
            nowMs = 2000L
        )
        assertEquals(4_500_000, abr.currentBitrateBps)

        val clean = ControlMessage.StreamStatsReport(1000, framesCompleted = 60, framesLost = 0, renderedFps = 60, rttMs = 5)
        assertNull(abr.onStatsReport(clean, nowMs = 3000L))
        assertNull(abr.onStatsReport(clean, nowMs = 4000L))
        assertNull(abr.onStatsReport(clean, nowMs = 5000L))
        val steppedUp = abr.onStatsReport(clean, nowMs = 6000L)
        assertEquals(5_000_000, steppedUp)
    }
}
