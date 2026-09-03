package com.hujunhan.hamsterwheeltracker.camera

import com.hujunhan.hamsterwheeltracker.tracking.TrackerSnapshot
import com.hujunhan.hamsterwheeltracker.tracking.TrackingState

enum class AnalysisPowerMode {
    ACTIVE,
    IDLE,
}

data class AnalysisPowerState(
    val mode: AnalysisPowerMode,
    val reason: String,
    val motionMeanAbsLumaDelta: Double = 0.0,
    val motionChangedFraction: Double = 0.0,
)

/**
 * Conservative power-state policy.
 *
 * ACTIVE -> IDLE is allowed only after a trusted, visible marker has remained
 * stationary for a long hold. Missing/uncertain tracking therefore stays ACTIVE.
 * IDLE -> ACTIVE is deliberately sensitive: a false wake costs some power, while
 * a missed wake can lose real wheel travel.
 */
internal class AnalysisPowerController(
    private val idleHoldNs: Long = 20_000_000_000L,
    private val stationarySpeedThresholdMS: Double = 0.003,
) {
    var state: AnalysisPowerState = AnalysisPowerState(
        mode = AnalysisPowerMode.ACTIVE,
        reason = "starting",
    )
        private set

    private var stationarySinceNs: Long? = null

    fun onTrackerFrame(timestampNs: Long, snapshot: TrackerSnapshot): AnalysisPowerState? {
        if (state.mode != AnalysisPowerMode.ACTIVE) return null

        val trustedStationary =
            snapshot.trackingState == TrackingState.TRACKING &&
                snapshot.markerVisible &&
                !snapshot.running &&
                snapshot.rawSpeedMS <= stationarySpeedThresholdMS

        if (!trustedStationary) {
            stationarySinceNs = null
            return null
        }

        val since = stationarySinceNs
        if (since == null) {
            stationarySinceNs = timestampNs
            return null
        }
        if (timestampNs - since < idleHoldNs) return null

        state = AnalysisPowerState(
            mode = AnalysisPowerMode.IDLE,
            reason = "trusted_stationary_hold",
        )
        stationarySinceNs = null
        return state
    }

    fun onIdleMotion(sample: IdleMotionSample): AnalysisPowerState? {
        if (state.mode != AnalysisPowerMode.IDLE || !sample.motionDetected) return null
        state = AnalysisPowerState(
            mode = AnalysisPowerMode.ACTIVE,
            reason = "wheel_region_motion",
            motionMeanAbsLumaDelta = sample.meanAbsLumaDelta,
            motionChangedFraction = sample.changedFraction,
        )
        stationarySinceNs = null
        return state
    }

    fun forceActive(reason: String): AnalysisPowerState? {
        stationarySinceNs = null
        if (state.mode == AnalysisPowerMode.ACTIVE) return null
        state = AnalysisPowerState(
            mode = AnalysisPowerMode.ACTIVE,
            reason = reason,
        )
        return state
    }

    fun reset(): AnalysisPowerState {
        stationarySinceNs = null
        state = AnalysisPowerState(
            mode = AnalysisPowerMode.ACTIVE,
            reason = "reset",
        )
        return state
    }
}
