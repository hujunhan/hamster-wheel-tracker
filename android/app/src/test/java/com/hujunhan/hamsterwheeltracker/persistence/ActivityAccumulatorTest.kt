package com.hujunhan.hamsterwheeltracker.persistence

import com.hujunhan.hamsterwheeltracker.tracking.TrackerSnapshot
import com.hujunhan.hamsterwheeltracker.tracking.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityAccumulatorTest {
    @Test
    fun `splits cumulative distance across one-second buckets`() {
        val accumulator = ActivityAccumulator()

        assertTrue(accumulator.add(1_000, snapshot(1.0, 0.0, 0.0)).isEmpty())
        assertTrue(accumulator.add(1_500, snapshot(1.5, 0.5, 1.0, speed = 1.0)).isEmpty())
        val completed = accumulator.add(2_100, snapshot(2.1, 1.1, 2.0, speed = 2.0))

        assertEquals(1, completed.size)
        assertEquals(1_000L, completed.single().bucketStartEpochMs)
        assertEquals(1.0, completed.single().distanceM, 1e-9)
        assertEquals(1.0, completed.single().movingDurationSec, 1e-9)
        assertEquals(2.0, completed.single().maxSpeedMS, 1e-9)

        val partial = accumulator.flush()!!
        assertEquals(2_000L, partial.bucketStartEpochMs)
        assertEquals(0.1, partial.distanceM, 1e-9)
        assertEquals(0.1, partial.movingDurationSec, 1e-9)
    }

    @Test
    fun `tracker reset never becomes negative persisted distance`() {
        val accumulator = ActivityAccumulator()
        accumulator.add(10_000, snapshot(10.0, 5.0, 20.0))
        accumulator.add(10_500, snapshot(10.5, 5.5, 21.0, speed = 1.0))
        val completed = accumulator.add(11_100, snapshot(11.1, 0.1, 0.2, speed = 0.1))

        assertEquals(1, completed.size)
        assertEquals(0.5, completed.single().distanceM, 1e-9)
        assertTrue(completed.single().distanceM >= 0.0)
    }

    @Test
    fun `long lifecycle gap establishes a new baseline`() {
        val accumulator = ActivityAccumulator()
        accumulator.add(20_000, snapshot(20.0, 1.0, 1.0))
        val completed = accumulator.add(
            25_000,
            snapshot(25.0, 10.0, 10.0, state = TrackingState.UNCERTAIN),
        )

        assertTrue(completed.isEmpty())
        assertEquals(null, accumulator.flush())
    }

    private fun snapshot(
        timestampSec: Double,
        totalDistanceM: Double,
        revolutions: Double,
        speed: Double = 0.0,
        state: TrackingState = TrackingState.TRACKING,
    ) = TrackerSnapshot(
        timestampSec = timestampSec,
        trackingState = state,
        markerVisible = state == TrackingState.TRACKING,
        running = speed > 0.0,
        rawSpeedMS = speed,
        displaySpeedMS = speed,
        totalDistanceM = totalDistanceM,
        equivalentRevolutions = revolutions,
        signedAngleRad = 0.0,
        lastReason = "test",
        detectionQuality = null,
    )
}
