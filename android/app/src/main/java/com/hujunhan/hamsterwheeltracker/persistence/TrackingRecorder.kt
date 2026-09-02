package com.hujunhan.hamsterwheeltracker.persistence

import com.hujunhan.hamsterwheeltracker.tracking.SessionRecord
import com.hujunhan.hamsterwheeltracker.tracking.TrackerSnapshot
import com.hujunhan.hamsterwheeltracker.tracking.TrackingState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** Wall-clock wrapper used by the LAN dashboard without touching Room on the camera thread. */
data class LiveTrackerSnapshot(
    val wallClockEpochMs: Long,
    val snapshot: TrackerSnapshot,
)

/**
 * Receives per-frame tracker snapshots, turns cumulative counters into one-second
 * additive samples, and writes them on a dedicated single-thread executor.
 */
class TrackingRecorder(
    private val dao: TrackingDao,
) {
    private val accumulator = ActivityAccumulator()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tracking-persistence")
    }
    private val closed = AtomicBoolean(false)
    private val latest = AtomicReference<LiveTrackerSnapshot?>(null)

    fun record(snapshot: TrackerSnapshot, wallClockEpochMs: Long = System.currentTimeMillis()) {
        latest.set(LiveTrackerSnapshot(wallClockEpochMs, snapshot))
        if (closed.get()) return
        executor.execute {
            accumulator.add(wallClockEpochMs, snapshot).forEach(dao::addActivitySample)
            snapshot.completedSession?.let { session ->
                dao.putSession(session.toEntity(snapshot.timestampSec, wallClockEpochMs))
            }
        }
    }

    fun latest(): LiveTrackerSnapshot? = latest.get()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute {
            accumulator.flush()?.let(dao::addActivitySample)
        }
        executor.shutdown()
    }

    private fun SessionRecord.toEntity(referenceTimestampSec: Double, referenceEpochMs: Long): SessionEntity {
        fun toEpochMs(timestampSec: Double): Long = referenceEpochMs +
            ((timestampSec - referenceTimestampSec) * 1000.0).roundToLong()

        return SessionEntity(
            startEpochMs = toEpochMs(startTimestampSec),
            endEpochMs = toEpochMs(endTimestampSec),
            durationSec = durationSec,
            movingDurationSec = movingDurationSec,
            distanceM = distanceM,
            revolutions = equivalentRevolutions,
            averageSpeedMS = averageSpeedMS,
            maxSpeedMS = maxSpeedMS,
        )
    }
}

/** Pure Kotlin aggregation core so it can be unit-tested without Android/Room. */
internal class ActivityAccumulator {
    private data class Point(
        val wallClockEpochMs: Long,
        val trackerTimestampSec: Double,
        val totalDistanceM: Double,
        val totalRevolutions: Double,
        val state: TrackingState,
        val rawSpeedMS: Double,
    )

    private data class MutableBucket(
        val startEpochMs: Long,
        var distanceM: Double = 0.0,
        var revolutions: Double = 0.0,
        var movingDurationSec: Double = 0.0,
        var uncertainDurationSec: Double = 0.0,
        var maxSpeedMS: Double = 0.0,
    ) {
        fun freeze() = ActivitySampleEntity(
            bucketStartEpochMs = startEpochMs,
            distanceM = distanceM,
            revolutions = revolutions,
            movingDurationSec = movingDurationSec,
            uncertainDurationSec = uncertainDurationSec,
            maxSpeedMS = maxSpeedMS,
        )
    }

    private var previous: Point? = null
    private val buckets = linkedMapOf<Long, MutableBucket>()

    fun add(wallClockEpochMs: Long, snapshot: TrackerSnapshot): List<ActivitySampleEntity> {
        val current = Point(
            wallClockEpochMs = wallClockEpochMs,
            trackerTimestampSec = snapshot.timestampSec,
            totalDistanceM = snapshot.totalDistanceM,
            totalRevolutions = snapshot.equivalentRevolutions,
            state = snapshot.trackingState,
            rawSpeedMS = snapshot.rawSpeedMS,
        )
        val old = previous
        previous = current
        val currentBucket = bucketStart(wallClockEpochMs)
        if (old == null) return finalizeBefore(currentBucket)

        val wallDurationMs = current.wallClockEpochMs - old.wallClockEpochMs
        val trackerDurationSec = current.trackerTimestampSec - old.trackerTimestampSec
        val wallDurationSec = wallDurationMs / 1000.0

        // A lifecycle pause or clock discontinuity should not manufacture hours of
        // activity/uncertainty. Resume from a fresh baseline instead.
        if (
            wallDurationMs <= 0 ||
            trackerDurationSec <= 0.0 ||
            trackerDurationSec > 2.0 ||
            abs(wallDurationSec - trackerDurationSec) > 1.0
        ) {
            return finalizeBefore(currentBucket)
        }

        val distanceDelta = (current.totalDistanceM - old.totalDistanceM).coerceAtLeast(0.0)
        val revolutionDelta = (current.totalRevolutions - old.totalRevolutions).coerceAtLeast(0.0)
        val movingDuration = if (distanceDelta > 0.0) trackerDurationSec else 0.0
        val uncertainDuration = if (
            current.state == TrackingState.UNCERTAIN || old.state == TrackingState.UNCERTAIN
        ) trackerDurationSec else 0.0

        var cursorMs = old.wallClockEpochMs
        while (cursorMs < current.wallClockEpochMs) {
            val start = bucketStart(cursorMs)
            val end = start + 1000L
            val overlapEnd = minOf(end, current.wallClockEpochMs)
            val overlapMs = overlapEnd - cursorMs
            val ratio = overlapMs.toDouble() / wallDurationMs.toDouble()
            val bucket = buckets.getOrPut(start) { MutableBucket(start) }
            bucket.distanceM += distanceDelta * ratio
            bucket.revolutions += revolutionDelta * ratio
            bucket.movingDurationSec += movingDuration * ratio
            bucket.uncertainDurationSec += uncertainDuration * ratio
            bucket.maxSpeedMS = max(bucket.maxSpeedMS, current.rawSpeedMS)
            cursorMs = overlapEnd
        }

        return finalizeBefore(currentBucket)
    }

    fun flush(): ActivitySampleEntity? {
        val lastKey = buckets.keys.minOrNull() ?: return null
        return buckets.remove(lastKey)?.freeze()
    }

    private fun finalizeBefore(bucketStartEpochMs: Long): List<ActivitySampleEntity> {
        val completedKeys = buckets.keys.filter { it < bucketStartEpochMs }
        return completedKeys.mapNotNull { key -> buckets.remove(key)?.freeze() }
    }

    private fun bucketStart(epochMs: Long): Long = Math.floorDiv(epochMs, 1000L) * 1000L
}
