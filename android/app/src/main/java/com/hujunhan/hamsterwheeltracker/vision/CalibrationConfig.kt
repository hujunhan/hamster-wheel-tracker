package com.hujunhan.hamsterwheeltracker.vision

import kotlin.math.min

/**
 * Resolution-independent calibration for the wheel and colored marker.
 *
 * Wheel center is normalized independently by frame width/height. Wheel radius
 * is normalized by the frame's short dimension so calibration survives the
 * Motorola selecting 1280x960 instead of the originally requested 1280x720.
 */
data class CalibrationConfig(
    val centerXNorm: Float = 0.5f,
    val centerYNorm: Float = 0.5f,
    val wheelRadiusNorm: Float = 0.38f,
    val markerPathRadiusRatio: Float = 0.75f,
    val radiusToleranceRatio: Float = 0.12f,
    val hsvLowerH: Int = 40,
    val hsvUpperH: Int = 80,
    val hsvLowerS: Int = 80,
    val hsvLowerV: Int = 50,
    val minAreaPx: Double = 30.0,
    val maxAreaPx: Double = 5000.0,
    val morphologyKernel: Int = 3,
    val effectiveDiameterMm: Float = 228.6f,
) {
    fun resolved(frameWidth: Int, frameHeight: Int): ResolvedCalibration {
        val shortSide = min(frameWidth, frameHeight).toFloat()
        val wheelRadius = wheelRadiusNorm.coerceIn(0.1f, 0.49f) * shortSide
        val pathRatio = markerPathRadiusRatio.coerceIn(0.2f, 0.98f)
        val toleranceRatio = radiusToleranceRatio.coerceIn(0.01f, 0.4f)
        return ResolvedCalibration(
            centerX = centerXNorm.coerceIn(0f, 1f) * frameWidth,
            centerY = centerYNorm.coerceIn(0f, 1f) * frameHeight,
            wheelRadiusPx = wheelRadius,
            expectedMarkerRadiusPx = wheelRadius * pathRatio,
            radiusTolerancePx = wheelRadius * toleranceRatio,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )
    }

    /**
     * Builds forgiving initial bounds from the median of an 11x11 live patch.
     * The annulus is the stronger spatial discriminator, so color sampling is
     * intentionally broad enough to tolerate Android ISP/AWB variation.
     */
    fun withMarkerSample(sample: HsvSample): CalibrationConfig {
        val hue = sample.h.coerceIn(0, 179)
        val lowerH = (hue - 15).coerceAtLeast(0)
        val upperH = (hue + 15).coerceAtMost(179)
        return copy(
            hsvLowerH = lowerH,
            hsvUpperH = upperH,
            hsvLowerS = (sample.s - 60).coerceIn(25, 255),
            hsvLowerV = (sample.v - 60).coerceIn(20, 255),
        )
    }
}

data class ResolvedCalibration(
    val centerX: Float,
    val centerY: Float,
    val wheelRadiusPx: Float,
    val expectedMarkerRadiusPx: Float,
    val radiusTolerancePx: Float,
    val frameWidth: Int,
    val frameHeight: Int,
)

data class HsvSample(
    val h: Int,
    val s: Int,
    val v: Int,
    val sampleCount: Int = 1,
)
