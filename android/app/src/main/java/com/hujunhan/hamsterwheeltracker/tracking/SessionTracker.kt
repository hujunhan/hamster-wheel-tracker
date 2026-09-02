package com.hujunhan.hamsterwheeltracker.tracking

import kotlin.math.PI
import kotlin.math.max

internal data class SessionUpdate(
    val running: Boolean,
    val sessionOpen: Boolean,
    val completedSession: SessionRecord?,
)

/** Behavioral port of the Python session hysteresis / short-pause grouping. */
internal class SessionTracker(
    private val startSpeedMS: Double = 0.05,
    private val stopSpeedMS: Double = 0.03,
    private val stopHoldSeconds: Double = 1.0,
    private val sessionGapSeconds: Double = 10.0,
) {
    var running: Boolean = false
        private set

    private var belowStopSince: Double? = null
    private var lastUpdateSec: Double? = null
    private var sessionStartSec: Double? = null
    private var lastMotionSec: Double? = null
    private var distanceM = 0.0
    private var angularTravelRad = 0.0
    private var movingDurationSec = 0.0
    private var maxSpeedMS = 0.0

    init {
        require(stopSpeedMS <= startSpeedMS) { "stopSpeedMS must be <= startSpeedMS" }
    }

    fun reset() {
        running = false
        belowStopSince = null
        lastUpdateSec = null
        sessionStartSec = null
        lastMotionSec = null
        distanceM = 0.0
        angularTravelRad = 0.0
        movingDurationSec = 0.0
        maxSpeedMS = 0.0
    }

    fun update(
        timestampSec: Double,
        speedMS: Double,
        distanceDeltaM: Double,
        angularTravelDeltaRad: Double,
    ): SessionUpdate {
        val previousTimestamp = lastUpdateSec
        require(previousTimestamp == null || timestampSec >= previousTimestamp) { "timestamp must be monotonic" }

        val completed = closeIfGapElapsed(timestampSec)
        val dt = if (previousTimestamp == null) 0.0 else timestampSec - previousTimestamp

        if (!running) {
            if (speedMS >= startSpeedMS) {
                running = true
                belowStopSince = null
            }
        } else {
            if (speedMS <= stopSpeedMS) {
                val belowSince = belowStopSince
                if (belowSince == null) {
                    belowStopSince = timestampSec
                } else if (timestampSec - belowSince >= stopHoldSeconds) {
                    running = false
                }
            } else {
                belowStopSince = null
            }
        }

        val motionPresent = distanceDeltaM > 0.0 || running
        if (motionPresent) {
            if (sessionStartSec == null) sessionStartSec = timestampSec
            lastMotionSec = timestampSec
            distanceM += max(0.0, distanceDeltaM)
            angularTravelRad += max(0.0, angularTravelDeltaRad)
            maxSpeedMS = max(maxSpeedMS, max(0.0, speedMS))
            if (distanceDeltaM > 0.0) {
                movingDurationSec += max(0.0, dt)
            }
        }

        lastUpdateSec = timestampSec
        return SessionUpdate(
            running = running,
            sessionOpen = sessionStartSec != null,
            completedSession = completed,
        )
    }

    fun flush(timestampSec: Double, force: Boolean = false): SessionRecord? {
        if (sessionStartSec == null) return null
        val lastMotion = lastMotionSec
        if (!force && lastMotion != null && timestampSec - lastMotion < sessionGapSeconds) return null
        return finalizeSession()
    }

    private fun closeIfGapElapsed(timestampSec: Double): SessionRecord? {
        val start = sessionStartSec ?: return null
        val lastMotion = lastMotionSec ?: return null
        @Suppress("UNUSED_VARIABLE")
        val ignored = start
        if (timestampSec - lastMotion < sessionGapSeconds) return null
        return finalizeSession()
    }

    private fun finalizeSession(): SessionRecord {
        val start = checkNotNull(sessionStartSec)
        val end = checkNotNull(lastMotionSec)
        val duration = max(0.0, end - start)
        val averageSpeed = if (movingDurationSec > 0.0) distanceM / movingDurationSec else 0.0
        val record = SessionRecord(
            startTimestampSec = start,
            endTimestampSec = end,
            durationSec = duration,
            movingDurationSec = movingDurationSec,
            distanceM = distanceM,
            equivalentRevolutions = angularTravelRad / (2.0 * PI),
            averageSpeedMS = averageSpeed,
            maxSpeedMS = maxSpeedMS,
        )

        running = false
        belowStopSince = null
        sessionStartSec = null
        lastMotionSec = null
        distanceM = 0.0
        angularTravelRad = 0.0
        movingDurationSec = 0.0
        maxSpeedMS = 0.0
        return record
    }
}
