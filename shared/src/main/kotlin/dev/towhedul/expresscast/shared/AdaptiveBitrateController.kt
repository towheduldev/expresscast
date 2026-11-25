package dev.towhedul.expresscast.shared

import kotlin.math.roundToInt

/**
 * Pure Kotlin adaptive bitrate (ABR) state machine driven by periodic [ControlMessage.StreamStatsReport]
 * summaries from the receiver. Steps down quickly on loss/latency spikes and climbs back slowly
 * only after sustained clean windows.
 */
class AdaptiveBitrateController(
    maxBitrateBps: Int,
    minBitrateBps: Int = maxOf(FLOOR_BITRATE_BPS, maxBitrateBps / 4)
) {
    var maxBitrateBps: Int = maxBitrateBps.coerceAtLeast(FLOOR_BITRATE_BPS)
        private set

    var minBitrateBps: Int = minBitrateBps.coerceIn(FLOOR_BITRATE_BPS, this.maxBitrateBps)
        private set

    var currentBitrateBps: Int = this.maxBitrateBps
        private set

    private var cleanStreak: Int = 0
    private var lastDecreaseMs: Long = Long.MIN_VALUE / 2
    private var lastChangeMs: Long = Long.MIN_VALUE / 2

    fun reset(
        newMaxBitrateBps: Int = this.maxBitrateBps,
        newMinBitrateBps: Int = maxOf(FLOOR_BITRATE_BPS, newMaxBitrateBps / 4)
    ) {
        maxBitrateBps = newMaxBitrateBps.coerceAtLeast(FLOOR_BITRATE_BPS)
        minBitrateBps = newMinBitrateBps.coerceIn(FLOOR_BITRATE_BPS, maxBitrateBps)
        currentBitrateBps = maxBitrateBps
        cleanStreak = 0
        lastDecreaseMs = Long.MIN_VALUE / 2
        lastChangeMs = Long.MIN_VALUE / 2
    }

    /**
     * Evaluates one stats window at [nowMs] and returns a new target bitrate (bps) if changed,
     * or null when the current bitrate should remain unchanged.
     */
    fun onStatsReport(report: ControlMessage.StreamStatsReport, nowMs: Long): Int? {
        val total = report.framesCompleted + report.framesLost
        if (total <= 0) return null

        val lossRatio = report.framesLost.toDouble() / total.toDouble()
        val severe = (report.framesLost >= 4 || (report.framesLost >= 2 && lossRatio >= 0.10)) || report.rttMs >= 120
        val moderate = !severe && ((report.framesLost >= 2 && lossRatio >= 0.03) || report.rttMs >= 60)

        if (severe) {
            cleanStreak = 0
            if (nowMs - lastDecreaseMs < SEVERE_COOLDOWN_MS) return null
            val target = snapToStep((currentBitrateBps * 0.75).roundToInt()).coerceIn(minBitrateBps, maxBitrateBps)
            return applyChange(target, nowMs, isDecrease = true)
        }

        if (moderate) {
            cleanStreak = 0
            if (nowMs - lastDecreaseMs < MODERATE_COOLDOWN_MS) return null
            val target = snapToStep((currentBitrateBps * 0.88).roundToInt()).coerceIn(minBitrateBps, maxBitrateBps)
            return applyChange(target, nowMs, isDecrease = true)
        }

        val clean = report.framesLost == 0 && (report.rttMs <= 0 || report.rttMs < 35)
        if (!clean) {
            if (report.framesLost > 0) cleanStreak = 0
            return null
        }

        cleanStreak++
        if (cleanStreak < CLEAN_WINDOWS_BEFORE_STEP_UP || currentBitrateBps >= maxBitrateBps) {
            return null
        }
        if (nowMs - lastChangeMs < STEP_UP_COOLDOWN_MS) {
            return null
        }

        cleanStreak = 0
        val stepUp = maxOf(500_000, (maxBitrateBps * 0.10).roundToInt())
        val target = snapToStep(currentBitrateBps + stepUp).coerceIn(minBitrateBps, maxBitrateBps)
        return applyChange(target, nowMs, isDecrease = false)
    }

    private fun applyChange(targetBps: Int, nowMs: Long, isDecrease: Boolean): Int? {
        if (targetBps == currentBitrateBps) return null
        currentBitrateBps = targetBps
        lastChangeMs = nowMs
        if (isDecrease) lastDecreaseMs = nowMs
        return targetBps
    }

    private fun snapToStep(bps: Int): Int {
        val steps = (bps.toDouble() / BITRATE_STEP_BPS).roundToInt()
        return (steps * BITRATE_STEP_BPS).coerceAtLeast(FLOOR_BITRATE_BPS)
    }

    private companion object {
        const val FLOOR_BITRATE_BPS = 1_000_000
        const val BITRATE_STEP_BPS = 250_000
        const val SEVERE_COOLDOWN_MS = 1500L
        const val MODERATE_COOLDOWN_MS = 2000L
        const val STEP_UP_COOLDOWN_MS = 2000L
        const val CLEAN_WINDOWS_BEFORE_STEP_UP = 4
    }
}
