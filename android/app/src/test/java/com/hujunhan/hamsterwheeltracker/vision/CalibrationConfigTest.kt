package com.hujunhan.hamsterwheeltracker.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationConfigTest {
    @Test
    fun `resolves normalized calibration for motorola stream`() {
        val resolved = CalibrationConfig().resolved(1280, 960)

        assertEquals(640f, resolved.centerX, 0.001f)
        assertEquals(480f, resolved.centerY, 0.001f)
        assertEquals(364.8f, resolved.wheelRadiusPx, 0.01f)
        assertEquals(273.6f, resolved.expectedMarkerRadiusPx, 0.01f)
        assertEquals(43.776f, resolved.radiusTolerancePx, 0.01f)
        assertEquals(30.0, resolved.minMarkerAreaPx, 0.001)
        assertEquals(20904.0, resolved.maxMarkerAreaPx, 1.0)
    }

    @Test
    fun `marker area upper bound grows with wheel size`() {
        val small = CalibrationConfig(wheelRadiusNorm = 0.20f).resolved(1280, 960)
        val large = CalibrationConfig(wheelRadiusNorm = 0.35f).resolved(1280, 960)

        // The adaptive limit is 5% of the calibrated wheel disk area. Even the
        // 0.20 short-side wheel is already above the absolute 5000 px floor.
        assertTrue(small.maxMarkerAreaPx > 5700.0)
        assertTrue(large.maxMarkerAreaPx > 17000.0)
        assertTrue(large.maxMarkerAreaPx > small.maxMarkerAreaPx)
    }

    @Test
    fun `marker patch sample creates practical hsv bounds`() {
        val sampled = CalibrationConfig().withMarkerSample(
            HsvSample(h = 62, s = 210, v = 180, sampleCount = 121),
        )

        assertEquals(47, sampled.hsvLowerH)
        assertEquals(77, sampled.hsvUpperH)
        assertEquals(150, sampled.hsvLowerS)
        assertEquals(120, sampled.hsvLowerV)
    }

    @Test
    fun `low saturation teal sample remains detectable`() {
        val sampled = CalibrationConfig().withMarkerSample(
            HsvSample(h = 96, s = 16, v = 78, sampleCount = 121),
        )

        assertEquals(81, sampled.hsvLowerH)
        assertEquals(111, sampled.hsvUpperH)
        assertEquals(25, sampled.hsvLowerS)
        assertEquals(20, sampled.hsvLowerV)
    }
}
