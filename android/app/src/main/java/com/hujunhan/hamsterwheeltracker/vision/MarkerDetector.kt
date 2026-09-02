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

        val maskPixelCount = Core.countNonZero(mask)
        val contours = mutableListOf<MatOfPoint>()
        val candidates = mutableListOf<MarkerCandidate>()
        var best: MarkerDetection? = null
        var areaRejected = 0
        var annulusRejected = 0
        var acceptedCandidates = 0

        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val contourCount = contours.size
        try {
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < resolved.minMarkerAreaPx || area > resolved.maxMarkerAreaPx) {
                    areaRejected++
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
                    annulusRejected++
                    candidates += MarkerCandidate(x, y, area, false, "annulus")
                    continue
                }

                val radialScore = 1.0 - radialError / resolved.radiusTolerancePx.coerceAtLeast(1e-6f)
                val areaScore = (area / (resolved.minMarkerAreaPx * 4.0).coerceAtLeast(1.0)).coerceAtMost(1.0)
                val score = (0.8 * radialScore + 0.2 * areaScore).toFloat()
                val detection = MarkerDetection(x, y, area, radial, score)
                acceptedCandidates++
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
            maskPixelCount = maskPixelCount,
            contourCount = contourCount,
            areaRejectedCount = areaRejected,
            annulusRejectedCount = annulusRejected,
            acceptedCandidateCount = acceptedCandidates,
        )
    }

    /**
     * Samples a small patch rather than one pixel so a highlight, Bayer/ISP noise,
     * or a slightly inaccurate tap does not determine the complete HSV range.
     */
    fun hsvPatchAt(xPx: Float, yPx: Float, radiusPx: Int = 5): HsvSample? {
        if (hsv.empty()) return null
        val centerX = xPx.toInt().coerceIn(0, hsv.cols() - 1)
        val centerY = yPx.toInt().coerceIn(0, hsv.rows() - 1)
        val radius = radiusPx.coerceAtLeast(1)
        val x0 = (centerX - radius).coerceAtLeast(0)
        val x1 = (centerX + radius).coerceAtMost(hsv.cols() - 1)
        val y0 = (centerY - radius).coerceAtLeast(0)
        val y1 = (centerY + radius).coerceAtMost(hsv.rows() - 1)

        val hueAll = mutableListOf<Int>()
        val hueChromatic = mutableListOf<Int>()
        val saturation = mutableListOf<Int>()
        val value = mutableListOf<Int>()

        for (y in y0..y1) {
            for (x in x0..x1) {
                val pixel = hsv.get(y, x) ?: continue
                if (pixel.size < 3) continue
                val h = pixel[0].toInt().coerceIn(0, 179)
                val s = pixel[1].toInt().coerceIn(0, 255)
                val v = pixel[2].toInt().coerceIn(0, 255)
                if (v < 15) continue
                hueAll += h
                saturation += s
                value += v
                // Hue becomes poorly defined near gray. Prefer pixels carrying
                // at least some chroma, but fall back to the full patch if needed.
                if (s >= 20) hueChromatic += h
            }
        }

        if (hueAll.isEmpty()) return null
        val hueSource = if (hueChromatic.size >= MIN_CHROMATIC_SAMPLES) hueChromatic else hueAll
        return HsvSample(
            h = median(hueSource),
            s = median(saturation),
            v = median(value),
            sampleCount = hueAll.size,
        )
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

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    companion object {
        private const val MAX_DEBUG_CANDIDATES = 32
        private const val MIN_CHROMATIC_SAMPLES = 5
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
    val maskPixelCount: Int = 0,
    val contourCount: Int = 0,
    val areaRejectedCount: Int = 0,
    val annulusRejectedCount: Int = 0,
    val acceptedCandidateCount: Int = 0,
    val frameWidth: Int = resolvedCalibration.frameWidth,
    val frameHeight: Int = resolvedCalibration.frameHeight,
    val rotationDegrees: Int = 0,
) {
    fun withRotation(rotationDegrees: Int): MarkerFrameResult = copy(rotationDegrees = rotationDegrees)
}
