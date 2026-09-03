package com.hujunhan.hamsterwheeltracker.camera

import com.hujunhan.hamsterwheeltracker.tracking.TrackerSnapshot
import com.hujunhan.hamsterwheeltracker.tracking.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisPowerControllerTest {
    @Test
    fun `trusted stationary marker enters idle after hold`() {
        val controller = AnalysisPowerController(idleHoldNs = 20_000_000_000L)
        val stationary = trackerSnapshot(
            state = TrackingState.TRACKING,
            markerVisible = true,
            running = false,
            speedMS = 0.0,
        )

        assertNull(controller.onTrackerFrame(0L, stationary))
        assertNull(controller.onTrackerFrame(19_999_999_999L, stationary))
        val transition = controller.onTrackerFrame(20_000_000_000L, stationary)

        assertEquals(AnalysisPowerMode.IDLE, transition?.mode)
        assertEquals(AnalysisPowerMode.IDLE, controller.state.mode)
    }

    @Test
    fun `missing or uncertain tracking never enters idle`() {
        val controller = AnalysisPowerController(idleHoldNs = 1_000_000_000L)
        val uncertain = trackerSnapshot(
            state = TrackingState.UNCERTAIN,
            markerVisible = false,
            running = false,
            speedMS = 0.0,
        )

        assertNull(controller.onTrackerFrame(0L, uncertain))
        assertNull(controller.onTrackerFrame(60_000_000_000L, uncertain))
        assertEquals(AnalysisPowerMode.ACTIVE, controller.state.mode)
    }

    @Test
    fun `wheel region motion wakes idle immediately`() {
        val controller = AnalysisPowerController(idleHoldNs = 1L)
        val stationary = trackerSnapshot(
            state = TrackingState.TRACKING,
            markerVisible = true,
            running = false,
            speedMS = 0.0,
        )
        controller.onTrackerFrame(0L, stationary)
        controller.onTrackerFrame(1L, stationary)
        assertEquals(AnalysisPowerMode.IDLE, controller.state.mode)

        val wake = controller.onIdleMotion(
            IdleMotionSample(
                motionDetected = true,
                meanAbsLumaDelta = 5.0,
                changedFraction = 0.12,
                sampledPixels = 800,
            ),
        )

        assertEquals(AnalysisPowerMode.ACTIVE, wake?.mode)
        assertEquals("wheel_region_motion", wake?.reason)
    }

    @Test
    fun `active motion resets stationary hold`() {
        val controller = AnalysisPowerController(idleHoldNs = 10L)
        val stationary = trackerSnapshot(
            state = TrackingState.TRACKING,
            markerVisible = true,
            running = false,
            speedMS = 0.0,
        )
        val moving = trackerSnapshot(
            state = TrackingState.TRACKING,
            markerVisible = true,
            running = true,
            speedMS = 0.2,
        )

        controller.onTrackerFrame(0L, stationary)
        controller.onTrackerFrame(9L, stationary)
        controller.onTrackerFrame(9L, moving)
        assertNull(controller.onTrackerFrame(15L, stationary))
        assertNull(controller.onTrackerFrame(24L, stationary))
        assertEquals(AnalysisPowerMode.ACTIVE, controller.state.mode)
    }

    private fun trackerSnapshot(
        state: TrackingState,
        markerVisible: Boolean,
        running: Boolean,
        speedMS: Double,
    ) = TrackerSnapshot(
        timestampSec = 0.0,
        trackingState = state,
        markerVisible = markerVisible,
        running = running,
        rawSpeedMS = speedMS,
        displaySpeedMS = speedMS,
        totalDistanceM = 0.0,
        equivalentRevolutions = 0.0,
        signedAngleRad = 0.0,
        lastReason = "test",
        detectionQuality = 1.0f,
    )
}
