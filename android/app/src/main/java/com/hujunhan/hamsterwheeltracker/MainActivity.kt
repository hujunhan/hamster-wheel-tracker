package com.hujunhan.hamsterwheeltracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var toggleButton: Button

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var frameAnalyzer: CameraFrameAnalyzer
    private var analysisEnabled = true
    private var cameraProvider: ProcessCameraProvider? = null

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
        analysisExecutor = Executors.newSingleThreadExecutor()
        frameAnalyzer = CameraFrameAnalyzer { snapshot ->
            runOnUiThread { renderStats(snapshot) }
        }

        buildUi()
        requestCameraOrStart()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            previewView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(Color.rgb(24, 24, 24))
        }

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            text = "Starting camera…"
        }
        panel.addView(statusView)

        statsView = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            text = "Waiting for analysis frames…"
        }
        panel.addView(statsView)

        toggleButton = Button(this).apply {
            text = "Pause analysis"
            gravity = Gravity.CENTER
            setOnClickListener { toggleAnalysis() }
        }
        panel.addView(
            toggleButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(panel)
        setContentView(root)
    }

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
            append("Rear camera active")
            append(" · flash: ").append(flash)
            append(" · exposure comp: ")
            append(exposureRange.lower).append("…").append(exposureRange.upper)
            append("\nBackpressure: KEEP_ONLY_LATEST (CameraX does not expose an exact dropped-frame count)")
        }
    }

    private fun renderStats(snapshot: AnalysisStats.Snapshot) {
        statsView.text = String.format(
            Locale.US,
            "Analysis: %.1f FPS · %d×%d\nFrames: %d · latest gap: %.1f ms · max gap/window: %.1f ms",
            snapshot.fps,
            snapshot.width,
            snapshot.height,
            snapshot.totalFrames,
            snapshot.latestGapMs,
            snapshot.maxGapMs,
        )
    }

    private fun toggleAnalysis() {
        analysisEnabled = !analysisEnabled
        frameAnalyzer.setEnabled(analysisEnabled)
        toggleButton.text = if (analysisEnabled) "Pause analysis" else "Resume analysis"
        if (!analysisEnabled) {
            statsView.text = "Analysis paused; preview remains active."
        } else {
            statsView.text = "Analysis resumed; collecting frame statistics…"
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
