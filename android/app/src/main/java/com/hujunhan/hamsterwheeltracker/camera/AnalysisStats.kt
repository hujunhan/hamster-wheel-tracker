package com.hujunhan.hamsterwheeltracker.camera

import kotlin.math.max

/**
 * Lightweight camera-analysis telemetry. This intentionally contains no wheel or marker logic.
 */
class AnalysisStats(
    private val reportIntervalNs: Long = 500_000_000L,
) {
    data class Snapshot(
        val totalFrames: Long,
        val fps: Double,
        val width: Int,
        val height: Int,
        val latestGapMs: Double,
        val maxGapMs: Double,
    )

    private var totalFrames = 0L
    private var windowFrames = 0L
    private var windowStartNs = 0L
    private var previousTimestampNs = 0L
    private var latestGapNs = 0L
    private var maxGapNs = 0L

    @Synchronized
    fun reset() {
        totalFrames = 0L
        windowFrames = 0L
        windowStartNs = 0L
        previousTimestampNs = 0L
        latestGapNs = 0L
        maxGapNs = 0L
    }

    @Synchronized
    fun onFrame(timestampNs: Long, width: Int, height: Int): Snapshot? {
        if (windowFrames == 0L) {
            windowStartNs = timestampNs
        }

        if (previousTimestampNs != 0L && timestampNs > previousTimestampNs) {
            latestGapNs = timestampNs - previousTimestampNs
            maxGapNs = max(maxGapNs, latestGapNs)
        }
        previousTimestampNs = timestampNs

        totalFrames += 1
        windowFrames += 1

        val elapsedNs = timestampNs - windowStartNs
        if (elapsedNs < reportIntervalNs || windowFrames < 2) {
            return null
        }

        val fps = (windowFrames - 1).toDouble() * 1_000_000_000.0 / elapsedNs.toDouble()
        val snapshot = Snapshot(
            totalFrames = totalFrames,
            fps = fps,
            width = width,
            height = height,
            latestGapMs = latestGapNs / 1_000_000.0,
            maxGapMs = maxGapNs / 1_000_000.0,
        )

        windowStartNs = timestampNs
        windowFrames = 1L
        maxGapNs = 0L
        return snapshot
    }
}
