package com.hujunhan.hamsterwheeltracker.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hujunhan.hamsterwheeltracker.vision.CalibrationConfig
import com.hujunhan.hamsterwheeltracker.vision.HsvSample
import com.hujunhan.hamsterwheeltracker.vision.MarkerDetector
import com.hujunhan.hamsterwheeltracker.vision.MarkerFrameResult
import java.util.concurrent.atomic.AtomicReference

class CameraFrameAnalyzer(
    initialCalibration: CalibrationConfig,
    private val onStats: (AnalysisStats.Snapshot) -> Unit,
    private val onMarkerFrame: (MarkerFrameResult) -> Unit,
    private val onHsvSample: (HsvSample) -> Unit,
    private val onVisionError: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val stats = AnalysisStats()
    private val rgbaReader = RgbaMatReader()
    private val markerDetector = MarkerDetector()
    private val calibration = AtomicReference(initialCalibration)
    private val sampleRequest = AtomicReference<SampleRequest?>(null)

    @Volatile
    private var enabled = true

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) stats.reset()
    }

    fun setCalibration(value: CalibrationConfig) {
        calibration.set(value)
    }

    fun requestHsvSample(xPx: Float, yPx: Float) {
        sampleRequest.set(SampleRequest(xPx, yPx))
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!enabled) return

            stats.onFrame(
                timestampNs = image.imageInfo.timestamp,
                width = image.width,
                height = image.height,
            )?.let(onStats)

            val rgba = rgbaReader.read(image)
            val result = markerDetector
                .detect(rgba, calibration.get())
                .withRotation(image.imageInfo.rotationDegrees)
            onMarkerFrame(result)

            sampleRequest.getAndSet(null)?.let { request ->
                markerDetector.hsvPatchAt(request.xPx, request.yPx)?.let(onHsvSample)
            }
        } catch (error: Exception) {
            onVisionError(error.message ?: error.javaClass.simpleName)
        } finally {
            image.close()
        }
    }

    fun close() {
        rgbaReader.close()
        markerDetector.close()
    }

    private data class SampleRequest(val xPx: Float, val yPx: Float)
}
