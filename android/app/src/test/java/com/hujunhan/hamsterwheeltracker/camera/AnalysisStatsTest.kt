package com.hujunhan.hamsterwheeltracker.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisStatsTest {
    @Test
    fun reportsApproximatelyThirtyFpsForRegularFrames() {
        val stats = AnalysisStats(reportIntervalNs = 500_000_000L)
        var snapshot: AnalysisStats.Snapshot? = null
        val framePeriodNs = 33_333_333L

        repeat(20) { index ->
            snapshot = stats.onFrame(
                timestampNs = 1_000_000_000L + index * framePeriodNs,
                width = 1280,
                height = 720,
            ) ?: snapshot
        }

        assertNotNull(snapshot)
        assertEquals(30.0, snapshot!!.fps, 0.2)
        assertEquals(1280, snapshot!!.width)
        assertEquals(720, snapshot!!.height)
        assertTrue(snapshot!!.totalFrames in 16L..20L)
    }

    @Test
    fun resetStartsAFreshTelemetryWindow() {
        val stats = AnalysisStats(reportIntervalNs = 1L)
        stats.onFrame(1_000L, 640, 480)
        stats.onFrame(2_000L, 640, 480)
        stats.reset()

        val firstAfterReset = stats.onFrame(10_000L, 320, 240)
        val secondAfterReset = stats.onFrame(20_000L, 320, 240)

        assertEquals(null, firstAfterReset)
        assertNotNull(secondAfterReset)
        assertEquals(2L, secondAfterReset!!.totalFrames)
        assertEquals(320, secondAfterReset!!.width)
        assertEquals(240, secondAfterReset!!.height)
    }
}
