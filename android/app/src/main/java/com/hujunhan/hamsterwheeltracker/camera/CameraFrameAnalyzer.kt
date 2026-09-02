package com.hujunhan.hamsterwheeltracker.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class CameraFrameAnalyzer(
    private val onStats: (AnalysisStats.Snapshot) -> Unit,
) : ImageAnalysis.Analyzer {
    private val stats = AnalysisStats()

    @Volatile
    private var enabled = true

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) {
            stats.reset()
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!enabled) return

            stats.onFrame(
                timestampNs = image.imageInfo.timestamp,
                width = image.width,
                height = image.height,
            )?.let(onStats)
        } finally {
            image.close()
        }
    }
}
