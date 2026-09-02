package com.hujunhan.hamsterwheeltracker.tracking

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

private const val TAU = 2.0 * PI

enum class TrackingState {
    SEARCHING,
    TRACKING,
    PREDICTING,
    UNCERTAIN,
}

data class MarkerObservation(
    val angleRad: Double,
    val quality: Float? = null,
)

data class TrackerSnapshot(
    val timestampSec: Double,
    val trackingState: TrackingState,
    val markerVisible: Boolean,
    val running: Boolean,
    val rawSpeedMS: Double,
    val displaySpeedMS: Double,
    val totalDistanceM: Double,
    val equivalentRevolutions: Double,
    val signedAngleRad: Double,
    val lastReason: String,
    val detectionQuality: Float?,
    val completedSession: SessionRecord? = null,
)

data class SessionRecord(
    val startTimestampSec: Double,
    val endTimestampSec: Double,
    val durationSec: Double,
    val movingDurationSec: Double,
    val distanceM: Double,
    val equivalentRevolutions: Double,
    val averageSpeedMS: Double,
    val maxSpeedMS: Double,
)

/**
 * Kotlin behavioral port of the Python RotationTracker + TrackerEngine core.
 *
 * The public API intentionally accepts only a trusted marker angle or a missing
 * observation. Camera/HSV details stay outside this class, which also makes the
 * tracker deterministic and easy to regression-test without Android hardware.
 */
class WheelTracker(
    effectiveDiameterMm: Double = 228.6,
    private val maxAngularSpeedRadS: Double = 45.0,
    private val angularDeadbandRad: Double = 0.008,
    private val maxShortGapSec: Double = 0.20,
    displaySpeedTimeConstantSec: Double = 0.25,
) {
    private val rotation = RotationAccumulator(
        effectiveDiameterMm = effectiveDiameterMm,
        maxAngularSpeedRadS = maxAngularSpeedRadS,
        angularDeadbandRad = angularDeadbandRad,
    )
    private val sessions = SessionTracker()
    private val speedFilter = DisplaySpeedFilter(displaySpeedTimeConstantSec)

    var state: TrackingState = TrackingState.SEARCHING
        private set

    private var lastSeenSec: Double? = null
    private var lastUpdateSec: Double? = null
    private var lastAngularVelocityRadS = 0.0
    private var lastRawSpeedMS = 0.0
    private var lastDisplaySpeedMS = 0.0
    private var lastReason = "not_initialized"
    private var lastQuality: Float? = null

    fun setEffectiveDiameterMm(value: Double) {
        rotation.setEffectiveDiameterMm(value)
    }

    fun reset() {
        rotation.reset()
        sessions.reset()
        speedFilter.reset()
        state = TrackingState.SEARCHING
        lastSeenSec = null
        lastUpdateSec = null
        lastAngularVelocityRadS = 0.0
        lastRawSpeedMS = 0.0
        lastDisplaySpeedMS = 0.0
        lastReason = "not_initialized"
        lastQuality = null
    }

    fun process(timestampSec: Double, observation: MarkerObservation?): TrackerSnapshot {
        checkTimestamp(timestampSec)
        return if (observation == null) {
            processMissing(timestampSec)
        } else {
            processMarker(timestampSec, observation)
        }
    }

    fun snapshot(timestampSec: Double = lastUpdateSec ?: 0.0): TrackerSnapshot = TrackerSnapshot(
        timestampSec = timestampSec,
        trackingState = state,
        markerVisible = state == TrackingState.TRACKING,
        running = sessions.running,
        rawSpeedMS = lastRawSpeedMS,
        displaySpeedMS = lastDisplaySpeedMS,
        totalDistanceM = rotation.totalDistanceM,
        equivalentRevolutions = rotation.equivalentRevolutions,
        signedAngleRad = rotation.signedAngleRad,
        lastReason = lastReason,
        detectionQuality = lastQuality,
    )

    private fun processMarker(timestampSec: Double, observation: MarkerObservation): TrackerSnapshot {
        val longGap = lastSeenSec?.let { timestampSec - it > maxShortGapSec } ?: false
        val phaseAmbiguous = phaseGapIsAmbiguous(timestampSec)

        val sample = if (
            state == TrackingState.SEARCHING ||
            state == TrackingState.UNCERTAIN ||
            longGap ||
            phaseAmbiguous
        ) {
            rotation.reinitializePhase(observation.angleRad, timestampSec)
        } else {
            rotation.update(observation.angleRad, timestampSec)
        }

        state = if (sample.accepted) TrackingState.TRACKING else TrackingState.UNCERTAIN
        lastSeenSec = timestampSec
        lastQuality = observation.quality
        lastRawSpeedMS = if (sample.accepted) sample.speedMS else 0.0
        lastAngularVelocityRadS = if (sample.accepted) sample.angularVelocityRadS else 0.0
        lastReason = sample.reason
        lastDisplaySpeedMS = speedFilter.update(timestampSec, lastRawSpeedMS)

        val sessionUpdate = sessions.update(
            timestampSec = timestampSec,
            speedMS = lastRawSpeedMS,
            distanceDeltaM = if (sample.accepted) sample.distanceDeltaM else 0.0,
            angularTravelDeltaRad = if (sample.accepted) abs(sample.deltaAngleRad) else 0.0,
        )

        lastUpdateSec = timestampSec
        return snapshot(timestampSec).copy(
            markerVisible = true,
            completedSession = sessionUpdate.completedSession,
        )
    }

    private fun processMissing(timestampSec: Double): TrackerSnapshot {
        state = if (lastSeenSec == null) {
            TrackingState.SEARCHING
        } else {
            val gap = timestampSec - lastSeenSec!!
            if (gap <= maxShortGapSec && !phaseGapIsAmbiguous(timestampSec)) {
                TrackingState.PREDICTING
            } else {
                TrackingState.UNCERTAIN
            }
        }

        val sessionUpdate = sessions.update(
            timestampSec = timestampSec,
            speedMS = 0.0,
            distanceDeltaM = 0.0,
            angularTravelDeltaRad = 0.0,
        )
        lastRawSpeedMS = 0.0
        lastDisplaySpeedMS = speedFilter.update(timestampSec, 0.0)
        lastQuality = null
        lastReason = "marker_missing"
        lastUpdateSec = timestampSec

        return snapshot(timestampSec).copy(
            markerVisible = false,
            completedSession = sessionUpdate.completedSession,
        )
    }

    private fun phaseGapIsAmbiguous(timestampSec: Double): Boolean {
        val lastSeen = lastSeenSec ?: return false
        if (lastAngularVelocityRadS == 0.0) return false
        val gap = (timestampSec - lastSeen).coerceAtLeast(0.0)
        return abs(lastAngularVelocityRadS) * gap >= PI
    }

    private fun checkTimestamp(timestampSec: Double) {
        val previous = lastUpdateSec
        require(previous == null || timestampSec >= previous) { "timestamp must be monotonic" }
    }
}

private data class RotationSample(
    val accepted: Boolean,
    val reason: String,
    val deltaAngleRad: Double,
    val angularVelocityRadS: Double,
    val speedMS: Double,
    val distanceDeltaM: Double,
)

private class RotationAccumulator(
    effectiveDiameterMm: Double,
    private val maxAngularSpeedRadS: Double,
    private val angularDeadbandRad: Double,
) {
    private var previousAngleRad: Double? = null
    private var previousTimestampSec: Double? = null
    private var effectiveRadiusM = effectiveDiameterMm / 2000.0

    var signedAngleRad: Double = 0.0
        private set
    var angularTravelRad: Double = 0.0
        private set
    var totalDistanceM: Double = 0.0
        private set

    val equivalentRevolutions: Double
        get() = angularTravelRad / TAU

    init {
        require(maxAngularSpeedRadS > 0.0) { "maxAngularSpeedRadS must be positive" }
        require(angularDeadbandRad >= 0.0) { "angularDeadbandRad cannot be negative" }
        require(effectiveDiameterMm > 0.0) { "effectiveDiameterMm must be positive" }
    }

    fun setEffectiveDiameterMm(value: Double) {
        require(value > 0.0) { "effectiveDiameterMm must be positive" }
        effectiveRadiusM = value / 2000.0
    }

    fun reset() {
        previousAngleRad = null
        previousTimestampSec = null
        signedAngleRad = 0.0
        angularTravelRad = 0.0
        totalDistanceM = 0.0
    }

    fun reinitializePhase(angleRad: Double, timestampSec: Double): RotationSample {
        previousAngleRad = angleRad
        previousTimestampSec = timestampSec
        return sample(true, "reinitialized", 0.0, 0.0)
    }

    fun update(angleRad: Double, timestampSec: Double): RotationSample {
        val previousAngle = previousAngleRad ?: return reinitializePhase(angleRad, timestampSec)
        val previousTimestamp = previousTimestampSec ?: return reinitializePhase(angleRad, timestampSec)
        val dt = timestampSec - previousTimestamp
        if (dt <= 0.0) return sample(false, "non_monotonic_timestamp", 0.0, 0.0)

        val rawDelta = wrappedAngleDelta(angleRad, previousAngle)
        val rawAngularVelocity = rawDelta / dt
        if (abs(rawAngularVelocity) > maxAngularSpeedRadS) {
            return sample(false, "implausible_angular_speed", 0.0, 0.0)
        }

        val delta = if (abs(rawDelta) <= angularDeadbandRad) 0.0 else rawDelta
        val angularVelocity = delta / dt
        val distanceDelta = effectiveRadiusM * abs(delta)

        signedAngleRad += delta
        angularTravelRad += abs(delta)
        totalDistanceM += distanceDelta
        previousAngleRad = angleRad
        previousTimestampSec = timestampSec
        return sample(true, if (delta == 0.0) "deadband" else "ok", delta, angularVelocity, distanceDelta)
    }

    private fun sample(
        accepted: Boolean,
        reason: String,
        delta: Double,
        angularVelocity: Double,
        distanceDelta: Double = 0.0,
    ) = RotationSample(
        accepted = accepted,
        reason = reason,
        deltaAngleRad = delta,
        angularVelocityRadS = angularVelocity,
        speedMS = abs(angularVelocity) * effectiveRadiusM,
        distanceDeltaM = distanceDelta,
    )
}

private class DisplaySpeedFilter(
    private val timeConstantSec: Double,
) {
    private var previousTimestampSec: Double? = null
    private var value = 0.0

    init {
        require(timeConstantSec > 0.0) { "timeConstantSec must be positive" }
    }

    fun reset() {
        previousTimestampSec = null
        value = 0.0
    }

    fun update(timestampSec: Double, target: Double): Double {
        val previousTimestamp = previousTimestampSec
        if (previousTimestamp == null) {
            previousTimestampSec = timestampSec
            value = target
            return value
        }
        val dt = (timestampSec - previousTimestamp).coerceAtLeast(0.0)
        previousTimestampSec = timestampSec
        val alpha = 1.0 - exp(-dt / timeConstantSec)
        value += alpha * (target - value)
        return value
    }
}

private fun wrappedAngleDelta(currentRad: Double, previousRad: Double): Double {
    var value = (currentRad - previousRad + PI) % TAU
    if (value < 0.0) value += TAU
    return value - PI
}
