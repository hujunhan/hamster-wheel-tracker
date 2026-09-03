package com.hujunhan.hamsterwheeltracker.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hujunhan.hamsterwheeltracker.tracking.MarkerObservation
import com.hujunhan.hamsterwheeltracker.tracking.TrackerSnapshot
import com.hujunhan.hamsterwheeltracker.tracking.WheelTracker
import com.hujunhan.hamsterwheeltracker.vision.CalibrationConfig
import com.hujunhan.hamsterwheeltracker.vision.HsvSample
import com.hujunhan.hamsterwheeltracker.vision.MarkerDetector
import com.hujunhan.hamsterwheeltracker.vision.MarkerFrameResult
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan2

class CameraFrameAnalyzer(
    initialCalibration: CalibrationConfig,
    private val onStats: (AnalysisStats.Snapshot) -> Unit,
    private val onMarkerFrame: (MarkerFrameResult) -> Unit,
    private val onTrackerSnapshot: (TrackerSnapshot) -> Unit,
    private val onHsvSample: (HsvSample) -> Unit,
    private val onVisionError: (String) -> Unit,
    private val onPowerState: (AnalysisPowerState) -> Unit = {},
) : ImageAnalysis.Analyzer {
    private val stats = AnalysisStats()
    private val rgbaReader = RgbaMatReader()
    private val markerDetector = MarkerDetector()
    private val idleMotionDetector = IdleMotionDetector()
    private val powerController = AnalysisPowerController()
    private val wheelTracker = WheelTracker(initialCalibration.effectiveDiameterMm.toDouble())
    private val calibration = AtomicReference(initialCalibration)
    private val sampleRequest = AtomicReference<SampleRequest?>(null)

    @Volatile
    private var enabled = true

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) {
            stats.reset()
            idleMotionDetector.reset()
            onPowerState(powerController.reset())
        }
    }

    fun setCalibration(value: CalibrationConfig) {
        val previous = calibration.getAndSet(value)
        wheelTracker.setEffectiveDiameterMm(value.effectiveDiameterMm.toDouble())
        idleMotionDetector.reset()
        powerController.forceActive("calibration_changed")?.let(onPowerState)
        if (
            previous.centerXNorm != value.centerXNorm ||
            previous.centerYNorm != value.centerYNorm
        ) {
            // Changing the angular reference must never look like wheel motion.
            wheelTracker.reset()
            wheelTracker.setEffectiveDiameterMm(value.effectiveDiameterMm.toDouble())
        }
    }

    fun requestHsvSample(xPx: Float, yPx: Float) {
        sampleRequest.set(SampleRequest(xPx, yPx))
        powerController.forceActive("hsv_sample_requested")?.let(onPowerState)
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!enabled) return

            val timestampNs = image.imageInfo.timestamp
            stats.onFrame(
                timestampNs = timestampNs,
                width = image.width,
                height = image.height,
            )?.let(onStats)

            val currentCalibration = calibration.get()
            if (powerController.state.mode == AnalysisPowerMode.IDLE) {
                // An explicit calibration sample must never wait for wheel motion.
                if (sampleRequest.get() != null) {
                    powerController.forceActive("hsv_sample_requested")?.let(onPowerState)
                } else {
                    val motion = idleMotionDetector.sample(image, currentCalibration) ?: return
                    val wake = powerController.onIdleMotion(motion) ?: return
                    onPowerState(wake)
                    // Continue through the full pipeline on this same wake frame.
                    // WheelTracker sees the long gap and reinitializes phase instead
                    // of inventing distance across the IDLE interval.
                }
            }

            val rgba = rgbaReader.read(image)
            val result = markerDetector
                .detect(rgba, currentCalibration)
                .withRotation(image.imageInfo.rotationDegrees)
            onMarkerFrame(result)

            val resolved = result.resolvedCalibration
            val observation = result.detection?.let { detection ->
                MarkerObservation(
                    angleRad = atan2(
                        (detection.yPx - resolved.centerY).toDouble(),
                        (detection.xPx - resolved.centerX).toDouble(),
                    ),
                    quality = detection.score,
                )
            }
            val trackerSnapshot = wheelTracker.process(
                timestampSec = timestampNs / 1_000_000_000.0,
                observation = observation,
            )
            onTrackerSnapshot(trackerSnapshot)

            powerController.onTrackerFrame(timestampNs, trackerSnapshot)?.let { transition ->
                if (transition.mode == AnalysisPowerMode.IDLE) {
                    idleMotionDetector.reset()
                    // Reuse this final ACTIVE frame as the IDLE baseline. This
                    // keeps wake latency near one 10 Hz motion-check interval.
                    idleMotionDetector.prime(image, currentCalibration)
                }
                onPowerState(transition)
            }

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
