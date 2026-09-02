package com.hujunhan.hamsterwheeltracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.hujunhan.hamsterwheeltracker.camera.AnalysisStats
import com.hujunhan.hamsterwheeltracker.camera.CameraFrameAnalyzer
import com.hujunhan.hamsterwheeltracker.persistence.TrackingDatabase
import com.hujunhan.hamsterwheeltracker.persistence.TrackingRecorder
import com.hujunhan.hamsterwheeltracker.tracking.TrackerSnapshot
import com.hujunhan.hamsterwheeltracker.ui.DetectionOverlayView
import com.hujunhan.hamsterwheeltracker.vision.CalibrationConfig
import com.hujunhan.hamsterwheeltracker.vision.CalibrationStore
import com.hujunhan.hamsterwheeltracker.vision.HsvSample
import com.hujunhan.hamsterwheeltracker.vision.MarkerFrameResult
import com.hujunhan.hamsterwheeltracker.web.DashboardServer
import com.hujunhan.hamsterwheeltracker.web.LanAddress
import fi.iki.elonen.NanoHTTPD
import org.opencv.android.OpenCVLoader
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var detectionView: TextView
    private lateinit var trackingView: TextView
    private lateinit var calibrationView: TextView
    private lateinit var dashboardView: TextView
    private lateinit var toggleButton: Button

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var frameAnalyzer: CameraFrameAnalyzer
    private lateinit var calibrationStore: CalibrationStore
    private lateinit var trackingRecorder: TrackingRecorder
    private var dashboardServer: DashboardServer? = null
    private var calibration = CalibrationConfig()
    private var analysisEnabled = true
    private var cameraProvider: ProcessCameraProvider? = null
    private var tapMode = TapMode.NONE
    private var detectionUiCounter = 0
    private var trackerUiCounter = 0

    @Volatile
    private var lastMarkerFrame: MarkerFrameResult? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            statusView.text = "Camera permission denied"
            toggleButton.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        calibrationStore = CalibrationStore(this)
        calibration = calibrationStore.load()
        renderCalibration()

        val database = TrackingDatabase.get(applicationContext)
        trackingRecorder = TrackingRecorder(database.trackingDao())
        startDashboard(database.trackingDao())

        val openCvReady = try {
            OpenCVLoader.initDebug()
        } catch (error: Throwable) {
            statusView.text = "OpenCV load failed: ${error.message ?: error.javaClass.simpleName}"
            false
        }
        if (!openCvReady) {
            statusView.text = "OpenCV initialization failed"
            toggleButton.isEnabled = false
            return
        }

        analysisExecutor = Executors.newSingleThreadExecutor()
        frameAnalyzer = CameraFrameAnalyzer(
            initialCalibration = calibration,
            onStats = { snapshot -> runOnUiThread { renderStats(snapshot) } },
            onMarkerFrame = { result ->
                lastMarkerFrame = result
                overlayView.update(result)
                detectionUiCounter++
                if (detectionUiCounter % 6 == 0) {
                    runOnUiThread { renderDetection(result) }
                }
            },
            onTrackerSnapshot = { snapshot ->
                trackingRecorder.record(snapshot)
                trackerUiCounter++
                if (trackerUiCounter % 6 == 0) {
                    runOnUiThread { renderTracking(snapshot) }
                }
            },
            onHsvSample = { sample -> runOnUiThread { applyMarkerSample(sample) } },
            onVisionError = { message -> runOnUiThread { statusView.text = "Vision error: $message" } },
        )

        requestCameraOrStart()
    }

    private fun startDashboard(dao: com.hujunhan.hamsterwheeltracker.persistence.TrackingDao) {
        val server = DashboardServer(
            dao = dao,
            liveProvider = { trackingRecorder.latest() },
        )
        dashboardServer = server
        val startError = runCatching {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }.exceptionOrNull()
        dashboardView.text = if (startError == null) {
            val url = LanAddress.dashboardUrl(DashboardServer.DEFAULT_PORT)
            if (url == null) {
                "Dashboard: running on port ${DashboardServer.DEFAULT_PORT}; connect this phone to Wi-Fi to get a LAN address"
            } else {
                "Dashboard: $url · open this address from another phone on the same Wi-Fi"
            }
        } else {
            "Dashboard failed to start: ${startError.message ?: startError.javaClass.simpleName}"
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val previewContainer = FrameLayout(this)
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        overlayView = DetectionOverlayView(this).apply {
            setOnTouchListener { _, event -> handleOverlayTouch(event) }
        }
        previewContainer.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        previewContainer.addView(
            overlayView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            previewContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val scroll = ScrollView(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
            setBackgroundColor(Color.rgb(24, 24, 24))
        }

        statusView = textView(Color.WHITE, 14f, "Starting vision pipeline…")
        statsView = textView(Color.LTGRAY, 13f, "Waiting for analysis frames…")
        detectionView = textView(Color.WHITE, 14f, "Marker: waiting…")
        trackingView = textView(Color.WHITE, 14f, "Tracker: waiting for marker…")
        calibrationView = textView(Color.LTGRAY, 12f, "Calibration loading…")
        dashboardView = textView(Color.CYAN, 12f, "Dashboard starting…")
        panel.addView(statusView)
        panel.addView(statsView)
        panel.addView(detectionView)
        panel.addView(trackingView)
        panel.addView(calibrationView)
        panel.addView(dashboardView)

        toggleButton = Button(this).apply {
            text = "Pause analysis"
            gravity = Gravity.CENTER
            setOnClickListener { toggleAnalysis() }
        }
        panel.addView(toggleButton, fullWidthParams())

        panel.addView(buttonRow(
            button("Set wheel center") {
                tapMode = TapMode.SET_CENTER
                statusView.text = "Tap the wheel center in the preview"
            },
            button("Sample marker HSV") {
                tapMode = TapMode.SAMPLE_MARKER
                statusView.text = "Tap the colored marker in the preview"
            },
        ))
        panel.addView(buttonRow(
            button("Wheel R −") { updateCalibration(calibration.copy(wheelRadiusNorm = (calibration.wheelRadiusNorm - 0.01f).coerceAtLeast(0.1f))) },
            button("Wheel R +") { updateCalibration(calibration.copy(wheelRadiusNorm = (calibration.wheelRadiusNorm + 0.01f).coerceAtMost(0.49f))) },
            button("Path −") { updateCalibration(calibration.copy(markerPathRadiusRatio = (calibration.markerPathRadiusRatio - 0.02f).coerceAtLeast(0.2f))) },
            button("Path +") { updateCalibration(calibration.copy(markerPathRadiusRatio = (calibration.markerPathRadiusRatio + 0.02f).coerceAtMost(0.98f))) },
        ))
        panel.addView(buttonRow(
            button("Tol −") { updateCalibration(calibration.copy(radiusToleranceRatio = (calibration.radiusToleranceRatio - 0.01f).coerceAtLeast(0.01f))) },
            button("Tol +") { updateCalibration(calibration.copy(radiusToleranceRatio = (calibration.radiusToleranceRatio + 0.01f).coerceAtMost(0.4f))) },
            button("Reset green HSV") {
                updateCalibration(calibration.copy(hsvLowerH = 40, hsvUpperH = 80, hsvLowerS = 80, hsvLowerV = 50))
            },
        ))
        panel.addView(buttonRow(
            button("Diameter −1 mm") { updateCalibration(calibration.copy(effectiveDiameterMm = (calibration.effectiveDiameterMm - 1f).coerceAtLeast(10f))) },
            button("Diameter +1 mm") { updateCalibration(calibration.copy(effectiveDiameterMm = calibration.effectiveDiameterMm + 1f)) },
        ))

        scroll.addView(panel)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(root)
    }

    private fun textView(color: Int, sizeSp: Float, initial: String): TextView = TextView(this).apply {
        setTextColor(color)
        textSize = sizeSp
        text = initial
        setPadding(0, 4, 0, 4)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 11f
        setOnClickListener { action() }
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (item in buttons) {
            addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun fullWidthParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun requestCameraOrStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        statusView.text = "Opening rear camera…"
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                cameraProvider = provider
                bindUseCases(provider)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, frameAnalyzer) }

        try {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
            showCameraDiagnostics(camera)
        } catch (error: Exception) {
            statusView.text = "Camera bind failed: ${error.message ?: error.javaClass.simpleName}"
            toggleButton.isEnabled = false
        }
    }

    private fun showCameraDiagnostics(camera: Camera) {
        val exposureRange = camera.cameraInfo.exposureState.exposureCompensationRange
        val flash = if (camera.cameraInfo.hasFlashUnit()) "yes" else "no"
        statusView.text = buildString {
            append("Rear camera + OpenCV active")
            append(" · flash: ").append(flash)
            append(" · exposure comp: ")
            append(exposureRange.lower).append("…").append(exposureRange.upper)
        }
    }

    private fun renderStats(snapshot: AnalysisStats.Snapshot) {
        statsView.text = String.format(
            Locale.US,
            "Analysis: %.1f FPS · %d×%d · frames %d · gap %.1f/%.1f ms",
            snapshot.fps,
            snapshot.width,
            snapshot.height,
            snapshot.totalFrames,
            snapshot.latestGapMs,
            snapshot.maxGapMs,
        )
    }

    private fun renderDetection(result: MarkerFrameResult) {
        val debug = String.format(
            Locale.US,
            "mask %d px · contours %d · rejected area %d annulus %d · accepted %d",
            result.maskPixelCount,
            result.contourCount,
            result.areaRejectedCount,
            result.annulusRejectedCount,
            result.acceptedCandidateCount,
        )
        val detection = result.detection
        detectionView.text = if (detection == null) {
            "Marker: not found\n$debug"
        } else {
            String.format(
                Locale.US,
                "Marker: (%.0f, %.0f) · score %.3f · area %.0f px²\n%s",
                detection.xPx,
                detection.yPx,
                detection.score,
                detection.areaPx,
                debug,
            )
        }
    }

    private fun renderTracking(snapshot: TrackerSnapshot) {
        val session = snapshot.completedSession
        trackingView.text = buildString {
            append(
                String.format(
                    Locale.US,
                    "Tracker: %s · running %s · reason %s\nDistance %.3f m · %.3f rev · speed %.3f m/s (display %.3f)",
                    snapshot.trackingState.name,
                    if (snapshot.running) "yes" else "no",
                    snapshot.lastReason,
                    snapshot.totalDistanceM,
                    snapshot.equivalentRevolutions,
                    snapshot.rawSpeedMS,
                    snapshot.displaySpeedMS,
                ),
            )
            if (session != null) {
                append(
                    String.format(
                        Locale.US,
                        "\nSession closed: %.2f m · %.1f s moving",
                        session.distanceM,
                        session.movingDurationSec,
                    ),
                )
            }
        }
    }

    private fun renderCalibration() {
        calibrationView.text = String.format(
            Locale.US,
            "Calibration: center %.3f,%.3f · wheel R %.3f short-side · path %.2fR · tol %.2fR\nHSV H %d…%d S≥%d V≥%d · effective diameter %.1f mm",
            calibration.centerXNorm,
            calibration.centerYNorm,
            calibration.wheelRadiusNorm,
            calibration.markerPathRadiusRatio,
            calibration.radiusToleranceRatio,
            calibration.hsvLowerH,
            calibration.hsvUpperH,
            calibration.hsvLowerS,
            calibration.hsvLowerV,
            calibration.effectiveDiameterMm,
        )
    }

    private fun updateCalibration(value: CalibrationConfig) {
        calibration = value
        calibrationStore.save(value)
        if (::frameAnalyzer.isInitialized) frameAnalyzer.setCalibration(value)
        renderCalibration()
    }

    private fun applyMarkerSample(sample: HsvSample) {
        updateCalibration(calibration.withMarkerSample(sample))
        tapMode = TapMode.NONE
        statusView.text = "Sampled ${sample.sampleCount}-px marker patch: HSV ${sample.h}, ${sample.s}, ${sample.v}; thresholds updated"
    }

    private fun handleOverlayTouch(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || tapMode == TapMode.NONE) return true
        val point = overlayView.viewToFrame(event.x, event.y) ?: return true
        val frame = lastMarkerFrame ?: return true
        when (tapMode) {
            TapMode.SET_CENTER -> {
                updateCalibration(
                    calibration.copy(
                        centerXNorm = (point.x / frame.frameWidth).coerceIn(0f, 1f),
                        centerYNorm = (point.y / frame.frameHeight).coerceIn(0f, 1f),
                    ),
                )
                tapMode = TapMode.NONE
                statusView.text = "Wheel center updated; tracker phase reset"
            }
            TapMode.SAMPLE_MARKER -> {
                frameAnalyzer.requestHsvSample(point.x, point.y)
                statusView.text = "Sampling marker color patch on next frame…"
            }
            TapMode.NONE -> Unit
        }
        return true
    }

    private fun toggleAnalysis() {
        analysisEnabled = !analysisEnabled
        frameAnalyzer.setEnabled(analysisEnabled)
        toggleButton.text = if (analysisEnabled) "Pause analysis" else "Resume analysis"
        if (!analysisEnabled) {
            statsView.text = "Analysis paused; preview remains active."
            detectionView.text = "Marker detection paused."
            trackingView.text = "Tracker paused with analysis."
        } else {
            statsView.text = "Analysis resumed; collecting frame statistics…"
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        dashboardServer?.stop()
        if (::trackingRecorder.isInitialized) trackingRecorder.close()
        if (::frameAnalyzer.isInitialized) frameAnalyzer.close()
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
        super.onDestroy()
    }

    private enum class TapMode { NONE, SET_CENTER, SAMPLE_MARKER }
}
