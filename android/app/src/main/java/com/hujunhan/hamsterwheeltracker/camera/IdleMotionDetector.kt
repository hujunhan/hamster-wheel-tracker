package com.hujunhan.hamsterwheeltracker.camera

import androidx.camera.core.ImageProxy
import com.hujunhan.hamsterwheeltracker.vision.CalibrationConfig
import kotlin.math.max

/** Result from one sparse IDLE-mode wheel-region comparison. */
data class IdleMotionSample(
    val motionDetected: Boolean,
    val meanAbsLumaDelta: Double,
    val changedFraction: Double,
    val sampledPixels: Int,
)

/**
 * Very cheap IDLE-mode motion detector.
 *
 * CameraX remains open, but this path avoids allocating/copying a full OpenCV Mat
 * and avoids RGB->HSV, morphology and contour extraction. It samples a sparse
 * annulus directly from the RGBA ImageProxy plane at ~10 Hz.
 */
class IdleMotionDetector(
    private val minIntervalNs: Long = 100_000_000L,
    private val changedPixelDelta: Int = 12,
    private val wakeChangedFraction: Double = 0.04,
    private val wakeMeanAbsDelta: Double = 2.5,
) {
    private var lastCheckNs = Long.MIN_VALUE
    private var samplingKey: SamplingKey? = null
    private var pointsXY = IntArray(0)
    private var previousLuma = IntArray(0)
    private var baselineReady = false

    fun reset() {
        lastCheckNs = Long.MIN_VALUE
        samplingKey = null
        pointsXY = IntArray(0)
        previousLuma = IntArray(0)
        baselineReady = false
    }

    /** Prime from the final ACTIVE frame so the first IDLE check can detect motion. */
    fun prime(image: ImageProxy, config: CalibrationConfig) {
        capture(image, config, enforceInterval = false, reportMotion = false)
    }

    /** Returns null on frames skipped by the ~10 Hz IDLE cadence. */
    fun sample(image: ImageProxy, config: CalibrationConfig): IdleMotionSample? =
        capture(image, config, enforceInterval = true, reportMotion = true)

    private fun capture(
        image: ImageProxy,
        config: CalibrationConfig,
        enforceInterval: Boolean,
        reportMotion: Boolean,
    ): IdleMotionSample? {
        val timestampNs = image.imageInfo.timestamp
        if (
            enforceInterval &&
            lastCheckNs != Long.MIN_VALUE &&
            timestampNs - lastCheckNs < minIntervalNs
        ) {
            return null
        }
        lastCheckNs = timestampNs

        val plane = image.planes.firstOrNull() ?: return null
        if (plane.pixelStride < 3) return null

        ensurePoints(image.width, image.height, config)
        val sampleCount = pointsXY.size / 2
        if (sampleCount == 0) return null

        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var totalDelta = 0L
        var changed = 0
        var valid = 0

        for (index in 0 until sampleCount) {
            val x = pointsXY[index * 2]
            val y = pointsXY[index * 2 + 1]
            val offset = y * rowStride + x * pixelStride
            if (offset < 0 || offset + 2 >= buffer.limit()) continue

            val r = buffer.get(offset).toInt() and 0xff
            val g = buffer.get(offset + 1).toInt() and 0xff
            val b = buffer.get(offset + 2).toInt() and 0xff
            // Integer BT.601-ish luma approximation; exact color is irrelevant here.
            val luma = (77 * r + 150 * g + 29 * b) shr 8

            if (baselineReady) {
                val delta = kotlin.math.abs(luma - previousLuma[index])
                totalDelta += delta.toLong()
                if (delta >= changedPixelDelta) changed++
            }
            previousLuma[index] = luma
            valid++
        }

        if (!baselineReady || !reportMotion || valid == 0) {
            baselineReady = valid > 0
            return IdleMotionSample(
                motionDetected = false,
                meanAbsLumaDelta = 0.0,
                changedFraction = 0.0,
                sampledPixels = valid,
            )
        }

        val meanDelta = totalDelta.toDouble() / valid.toDouble()
        val changedFraction = changed.toDouble() / valid.toDouble()
        return IdleMotionSample(
            motionDetected = shouldWake(meanDelta, changedFraction),
            meanAbsLumaDelta = meanDelta,
            changedFraction = changedFraction,
            sampledPixels = valid,
        )
    }

    private fun shouldWake(meanDelta: Double, changedFraction: Double): Boolean =
        meanDelta >= wakeMeanAbsDelta && changedFraction >= wakeChangedFraction

    private fun ensurePoints(width: Int, height: Int, config: CalibrationConfig) {
        val resolved = config.resolved(width, height)
        val key = SamplingKey(
            width = width,
            height = height,
            centerX = resolved.centerX.toInt(),
            centerY = resolved.centerY.toInt(),
            wheelRadius = resolved.wheelRadiusPx.toInt(),
        )
        if (samplingKey == key) return
        samplingKey = key
        baselineReady = false

        val outerRadius = resolved.wheelRadiusPx * 1.02f
        val innerRadius = resolved.wheelRadiusPx * 0.35f
        val outer2 = outerRadius * outerRadius
        val inner2 = innerRadius * innerRadius
        // Roughly 45-50 samples across the wheel diameter, regardless of resolution.
        val step = max(8, (resolved.wheelRadiusPx / 24f).toInt())
        val x0 = max(0, (resolved.centerX - outerRadius).toInt())
        val x1 = minOf(width - 1, (resolved.centerX + outerRadius).toInt())
        val y0 = max(0, (resolved.centerY - outerRadius).toInt())
        val y1 = minOf(height - 1, (resolved.centerY + outerRadius).toInt())

        val points = ArrayList<Int>(4096)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val dx = x - resolved.centerX
                val dy = y - resolved.centerY
                val radius2 = dx * dx + dy * dy
                if (radius2 in inner2..outer2) {
                    points += x
                    points += y
                }
                x += step
            }
            y += step
        }
        pointsXY = points.toIntArray()
        previousLuma = IntArray(pointsXY.size / 2)
    }

    private data class SamplingKey(
        val width: Int,
        val height: Int,
        val centerX: Int,
        val centerY: Int,
        val wheelRadius: Int,
    )
}
