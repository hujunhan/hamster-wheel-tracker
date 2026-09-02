package com.hujunhan.hamsterwheeltracker.tracking

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelTrackerTest {
    @Test
    fun `shared parity vectors match expected travel and state`() {
        val resource = checkNotNull(javaClass.classLoader?.getResourceAsStream("tracker_sequences.tsv"))
        val lines = BufferedReader(InputStreamReader(resource)).use { it.readLines() }

        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val fields = line.split('|')
            require(fields.size == 5) { "bad tracker vector row: $line" }
            val name = fields[0]
            val observations = fields[1].split(';')
            val expectedRevolutions = fields[2].toDouble()
            val expectedSignedAngle = fields[3].toDouble()
            val expectedState = TrackingState.valueOf(fields[4])

            val tracker = WheelTracker(effectiveDiameterMm = 228.6)
            var snapshot = tracker.snapshot()
            for (token in observations) {
                val parts = token.split(':')
                val timestamp = parts[0].toDouble()
                val observation = if (parts[1] == "MISSING") {
                    null
                } else {
                    MarkerObservation(parts[1].toDouble(), quality = 0.9f)
                }
                snapshot = tracker.process(timestamp, observation)
            }

            assertEquals("$name revolutions", expectedRevolutions, snapshot.equivalentRevolutions, 1e-7)
            assertEquals("$name signed angle", expectedSignedAngle, snapshot.signedAngleRad, 1e-7)
            assertEquals("$name final state", expectedState, snapshot.trackingState)

            val expectedDistance = expectedRevolutions * PI * 228.6 / 1000.0
            assertEquals("$name distance", expectedDistance, snapshot.totalDistanceM, 1e-7)
        }
    }

    @Test
    fun `high speed missing gap becomes uncertain before safe reinitialization`() {
        val tracker = WheelTracker()
        tracker.process(0.0, MarkerObservation(0.0))
        tracker.process(1.0 / 30.0, MarkerObservation(PI / 3.0))

        val missing = tracker.process(0.15, null)
        assertEquals(TrackingState.UNCERTAIN, missing.trackingState)
        val beforeReacquire = missing.equivalentRevolutions

        val reacquired = tracker.process(1.0 / 6.0, MarkerObservation(-PI / 3.0))
        assertEquals(TrackingState.TRACKING, reacquired.trackingState)
        assertEquals("reacquisition must not invent hidden travel", beforeReacquire, reacquired.equivalentRevolutions, 1e-12)
        assertEquals("reinitialized", reacquired.lastReason)
    }

    @Test
    fun `display speed smoothing does not alter accumulated distance`() {
        val tracker = WheelTracker(displaySpeedTimeConstantSec = 0.25)
        tracker.process(0.0, MarkerObservation(0.0))
        val firstMotion = tracker.process(0.1, MarkerObservation(0.5))
        val secondMotion = tracker.process(0.2, MarkerObservation(1.0))

        assertTrue(firstMotion.rawSpeedMS > 0.0)
        assertTrue(secondMotion.displaySpeedMS in 0.0..secondMotion.rawSpeedMS)
        assertEquals(1.0 / (2.0 * PI), secondMotion.equivalentRevolutions, 1e-12)
        assertEquals(0.1143, secondMotion.totalDistanceM, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `timestamps must be monotonic`() {
        val tracker = WheelTracker()
        tracker.process(1.0, MarkerObservation(0.0))
        tracker.process(0.9, MarkerObservation(0.1))
    }
}
