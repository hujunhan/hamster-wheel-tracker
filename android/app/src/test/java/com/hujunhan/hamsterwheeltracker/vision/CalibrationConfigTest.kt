package com.hujunhan.hamsterwheeltracker.vision

import org.junit.Assert.assertEquals
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
    }

    @Test
    fun `marker sample creates practical hsv bounds`() {
        val sampled = CalibrationConfig().withMarkerSample(h = 62, s = 210, v = 180)

        assertEquals(50, sampled.hsvLowerH)
        assertEquals(74, sampled.hsvUpperH)
        assertEquals(130, sampled.hsvLowerS)
        assertEquals(100, sampled.hsvLowerV)
    }
}
