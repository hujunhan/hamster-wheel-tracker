package com.hujunhan.hamsterwheeltracker.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/** Android/OpenCV port of the Python reference HSV + annulus detector. */
class MarkerDetector {
    private val rgb = Mat()
    private val hsv = Mat()
    private val mask = Mat()
    private val hierarchy = Mat()
    private var morphologyKernel = Mat()
    private var morphologyKernelSize = 0

    fun detect(rgbaFrame: Mat, config: CalibrationConfig): MarkerFrameResult {
        val resolved = config.resolved(rgbaFrame.cols(), rgbaFrame.rows())

        Imgproc.cvtColor(rgbaFrame, rgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        Core.inRange(
            hsv,
            Scalar(config.hsvLowerH.toDouble(), config.hsvLowerS.toDouble(), config.hsvLowerV.toDouble()),
            Scalar(config.hsvUpperH.toDouble(), 255.0, 255.0),
            mask,
        )

        val kernelSize = config.morphologyKernel.coerceAtLeast(1)
        if (kernelSize > 1) {
            ensureMorphologyKernel(kernelSize)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, morphologyKernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, morphologyKernel)
        }

        val contours = mutableListOf<MatOfPoint>()
        val candidates = mutableListOf<MarkerCandidate>()
        var best: MarkerDetection? = null

        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        try {
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < config.minAreaPx || area > config.maxAreaPx) {
                    val rect = Imgproc.boundingRect(contour)
                    candidates += MarkerCandidate(
                        xPx = rect.x + rect.width / 2f,
                        yPx = rect.y + rect.height / 2f,
                        areaPx = area,
                        accepted = false,
                        rejection = "area",
                    )
                    continue
                }

                val moments = Imgproc.moments(contour)
                if (moments.m00 == 0.0) continue

                val x = (moments.m10 / moments.m00).toFloat()
                val y = (moments.m01 / moments.m00).toFloat()
                val radial = hypot(
                    (x - resolved.centerX).toDouble(),
                    (y - resolved.centerY).toDouble(),
                ).toFloat()
                val radialError = kotlin.math.abs(radial - resolved.expectedMarkerRadiusPx)
                if (radialError > resolved.radiusTolerancePx) {
                    candidates += MarkerCandidate(x, y, area, false, "annulus")
                    continue
                }

                val radialScore = 1.0 - radialError / resolved.radiusTolerancePx.coerceAtLeast(1e-6f)
                val areaScore = (area / (config.minAreaPx * 4.0).coerceAtLeast(1.0)).coerceAtMost(1.0)
                val score = (0.8 * radialScore + 0.2 * areaScore).toFloat()
                val detection = MarkerDetection(x, y, area, radial, score)
                candidates += MarkerCandidate(x, y, area, true, null)
                if (best == null || detection.score > best!!.score) best = detection
            }
        } finally {
            contours.forEach { it.release() }
        }

        return MarkerFrameResult(
            resolvedCalibration = resolved,
            detection = best,
            candidates = candidates.take(MAX_DEBUG_CANDIDATES),
        )
    }

    fun hsvAt(xPx: Float, yPx: Float): HsvSample? {
        if (hsv.empty()) return null
        val x = xPx.toInt().coerceIn(0, hsv.cols() - 1)
        val y = yPx.toInt().coerceIn(0, hsv.rows() - 1)
        val values = hsv.get(y, x) ?: return null
        if (values.size < 3) return null
        return HsvSample(values[0].toInt(), values[1].toInt(), values[2].toInt())
    }

    fun close() {
        rgb.release()
        hsv.release()
        mask.release()
        hierarchy.release()
        morphologyKernel.release()
    }

    private fun ensureMorphologyKernel(size: Int) {
        if (size == morphologyKernelSize && !morphologyKernel.empty()) return
        morphologyKernel.release()
        morphologyKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(size.toDouble(), size.toDouble()),
        )
        morphologyKernelSize = size
    }

    companion object {
        private const val MAX_DEBUG_CANDIDATES = 32
    }
}

data class MarkerDetection(
    val xPx: Float,
    val yPx: Float,
    val areaPx: Double,
    val radialDistancePx: Float,
    val score: Float,
)

data class MarkerCandidate(
    val xPx: Float,
    val yPx: Float,
    val areaPx: Double,
    val accepted: Boolean,
    val rejection: String?,
)

data class MarkerFrameResult(
    val resolvedCalibration: ResolvedCalibration,
    val detection: MarkerDetection?,
    val candidates: List<MarkerCandidate>,
    val frameWidth: Int = resolvedCalibration.frameWidth,
    val frameHeight: Int = resolvedCalibration.frameHeight,
    val rotationDegrees: Int = 0,
) {
    fun withRotation(rotationDegrees: Int): MarkerFrameResult = copy(rotationDegrees = rotationDegrees)
}
