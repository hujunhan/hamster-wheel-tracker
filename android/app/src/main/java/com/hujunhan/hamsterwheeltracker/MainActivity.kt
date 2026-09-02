package com.hujunhan.hamsterwheeltracker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.hujunhan.hamsterwheeltracker.camera.AnalysisStats
import com.hujunhan.hamsterwheeltracker.service.TrackingService
import com.hujunhan.hamsterwheeltracker.tracking.TrackerSnapshot
import com.hujunhan.hamsterwheeltracker.ui.DetectionOverlayView
import com.hujunhan.hamsterwheeltracker.vision.CalibrationConfig
import com.hujunhan.hamsterwheeltracker.vision.CalibrationStore
import com.hujunhan.hamsterwheeltracker.vision.HsvSample
import com.hujunhan.hamsterwheeltracker.vision.MarkerFrameResult
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var detectionView: TextView
    private lateinit var trackingView: TextView
    private lateinit var calibrationView: TextView
    private lateinit var dashboardView: TextView
    private lateinit var startStopButton: Button
    private lateinit var toggleButton: Button

    private lateinit var calibrationStore: CalibrationStore
    private var calibration = CalibrationConfig()
    private var trackingService: TrackingService? = null
    private var bindRequested = false
    private var serviceBound = false
    private var analysisEnabled = true
    private var tapMode = TapMode.NONE
    private var detectionUiCounter = 0
    private var trackerUiCounter = 0

    @Volatile
    private var lastMarkerFrame: MarkerFrameResult? = null

    private val runtimePrefs by lazy {
        getSharedPreferences("tracking_runtime", Context.MODE_PRIVATE)
    }

    private val trackingDesired: Boolean
        get() = runtimePrefs.getBoolean(KEY_TRACKING_ENABLED, true)

    private val serviceListener = object : TrackingService.Listener {
        override fun onServiceState(state: TrackingService.ServiceState) {
            analysisEnabled = state.analysisEnabled
            runOnUiThread { renderServiceState(state) }
        }

        override fun onStats(snapshot: AnalysisStats.Snapshot) {
            runOnUiThread { renderStats(snapshot) }
        }

        override fun onMarkerFrame(result: MarkerFrameResult) {
            lastMarkerFrame = result
            overlayView.update(result)
            detectionUiCounter++
            if (detectionUiCounter % 6 == 0) {
                runOnUiThread { renderDetection(result) }
            }
        }

        override fun onTrackerSnapshot(snapshot: TrackerSnapshot) {
            trackerUiCounter++
            if (trackerUiCounter % 6 == 0) {
                runOnUiThread { renderTracking(snapshot) }
            }
        }

        override fun onHsvSample(sample: HsvSample) {
            runOnUiThread { applyMarkerSample(sample) }
        }

        override fun onVisionError(message: String) {
            runOnUiThread { statusView.text = "Vision error: $message" }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? TrackingService.LocalBinder ?: return
            val service = localBinder.service()
            trackingService = service
            serviceBound = true
            service.addListener(serviceListener)
            service.attachPreview(previewView.surfaceProvider)
            calibration = service.currentCalibration()
            renderCalibration()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService = null
            serviceBound = false
            bindRequested = false
            runOnUiThread {
                statusView.text = "Tracking service disconnected"
                startStopButton.text = "Start tracking"
            }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startTrackingFromUi()
        } else {
            statusView.text = "Camera permission denied"
            startStopButton.isEnabled = false
            toggleButton.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dedicated counter mode: normal display timeout is disabled while the
        // calibration UI is visible. Explicit screen-off still leaves the service alive.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()
        calibrationStore = CalibrationStore(this)
        calibration = calibrationStore.load()
        renderCalibration()
        requestCameraOrStart()
    }

    override fun onStart() {
        super.onStart()
        if (
            trackingDesired &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            TrackingService.start(this)
            bindTrackingService()
        }
    }

    override fun onStop() {
        trackingService?.detachPreview()
        unbindTrackingService()
        super.onStop()
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

        statusView = textView(Color.WHITE, 14f, "Starting foreground service…")
        statsView = textView(Color.LTGRAY, 13f, "Waiting for analysis frames…")
        detectionView = textView(Color.WHITE, 14f, "Marker: waiting…")
        trackingView = textView(Color.WHITE, 14f, "Tracker: waiting for marker…")
        calibrationView = textView(Color.LTGRAY, 12f, "Calibration loading…")
        dashboardView = textView(Color.CYAN, 12f, "Dashboard waiting for service…")
        panel.addView(statusView)
        panel.addView(statsView)
        panel.addView(detectionView)
        panel.addView(trackingView)
        panel.addView(calibrationView)
        panel.addView(dashboardView)

        startStopButton = Button(this).apply {
            text = if (trackingDesired) "Stop tracking" else "Start tracking"
            gravity = Gravity.CENTER
            setOnClickListener {
                if (trackingDesired) stopTrackingFromUi() else startTrackingFromUi()
            }
        }
        panel.addView(startStopButton, fullWidthParams())

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

    private fun requestCameraOrStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (trackingDesired) startTrackingFromUi() else renderStoppedState()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startTrackingFromUi() {
        runtimePrefs.edit().putBoolean(KEY_TRACKING_ENABLED, true).apply()
        startStopButton.text = "Stop tracking"
        toggleButton.isEnabled = true
        statusView.text = "Starting foreground tracking service…"
        TrackingService.start(this)
        bindTrackingService()
    }

    private fun stopTrackingFromUi() {
        runtimePrefs.edit().putBoolean(KEY_TRACKING_ENABLED, false).apply()
        trackingService?.detachPreview()
        TrackingService.stop(this)
        startStopButton.text = "Start tracking"
        toggleButton.isEnabled = false
        statsView.text = "Tracking stopped."
        detectionView.text = "Marker detection stopped."
        trackingView.text = "Tracker stopped."
        dashboardView.text = "Dashboard stopped with service."
        statusView.text = "Tracking stopped; releasing camera and background resources"
    }

    private fun bindTrackingService() {
        if (bindRequested) return
        bindRequested = bindService(
            Intent(this, TrackingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun unbindTrackingService() {
        if (!bindRequested) return
        if (serviceBound) trackingService?.removeListener(serviceListener)
        runCatching { unbindService(serviceConnection) }
        trackingService = null
        serviceBound = false
        bindRequested = false
    }

    private fun renderServiceState(state: TrackingService.ServiceState) {
        statusView.text = state.message
        startStopButton.text = if (state.tracking) "Stop tracking" else "Start tracking"
        toggleButton.isEnabled = state.tracking
        toggleButton.text = if (state.analysisEnabled) "Pause analysis" else "Resume analysis"
        dashboardView.text = when {
            !state.tracking -> "Dashboard stopped with service."
            state.dashboardUrl != null -> "Dashboard: ${state.dashboardUrl} · open from another device on the same Wi-Fi"
            else -> "Dashboard: port 8080 active; connect this phone to Wi-Fi for a LAN address"
        }
    }

    private fun renderStoppedState() {
        statusView.text = "Tracking is stopped. Tap Start tracking to begin."
        startStopButton.text = "Start tracking"
        toggleButton.isEnabled = false
        dashboardView.text = "Dashboard stopped with service."
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
        trackingService?.updateCalibration(value)
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
                trackingService?.requestHsvSample(point.x, point.y)
                statusView.text = "Sampling marker color patch on next frame…"
            }
            TapMode.NONE -> Unit
        }
        return true
    }

    private fun toggleAnalysis() {
        val service = trackingService ?: return
        analysisEnabled = !analysisEnabled
        service.setAnalysisEnabled(analysisEnabled)
        toggleButton.text = if (analysisEnabled) "Pause analysis" else "Resume analysis"
        if (!analysisEnabled) {
            statsView.text = "Analysis paused; foreground service remains active."
            detectionView.text = "Marker detection paused."
            trackingView.text = "Tracker paused with analysis."
        } else {
            statsView.text = "Analysis resumed; collecting frame statistics…"
        }
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

    private enum class TapMode { NONE, SET_CENTER, SAMPLE_MARKER }

    companion object {
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
    }
}
