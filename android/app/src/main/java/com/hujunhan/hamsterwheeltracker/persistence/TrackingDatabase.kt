package com.hujunhan.hamsterwheeltracker.persistence

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlin.math.max

@Entity(tableName = "activity_samples")
data class ActivitySampleEntity(
    @PrimaryKey
    @ColumnInfo(name = "bucket_start_epoch_ms")
    val bucketStartEpochMs: Long,
    @ColumnInfo(name = "distance_m")
    val distanceM: Double,
    @ColumnInfo(name = "revolutions")
    val revolutions: Double,
    @ColumnInfo(name = "moving_duration_sec")
    val movingDurationSec: Double,
    @ColumnInfo(name = "uncertain_duration_sec")
    val uncertainDurationSec: Double,
    @ColumnInfo(name = "max_speed_m_s")
    val maxSpeedMS: Double,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "start_epoch_ms")
    val startEpochMs: Long,
    @ColumnInfo(name = "end_epoch_ms")
    val endEpochMs: Long,
    @ColumnInfo(name = "duration_sec")
    val durationSec: Double,
    @ColumnInfo(name = "moving_duration_sec")
    val movingDurationSec: Double,
    @ColumnInfo(name = "distance_m")
    val distanceM: Double,
    @ColumnInfo(name = "revolutions")
    val revolutions: Double,
    @ColumnInfo(name = "average_speed_m_s")
    val averageSpeedMS: Double,
    @ColumnInfo(name = "max_speed_m_s")
    val maxSpeedMS: Double,
)

data class ActivitySummaryRow(
    @ColumnInfo(name = "distance_m") val distanceM: Double,
    @ColumnInfo(name = "revolutions") val revolutions: Double,
    @ColumnInfo(name = "moving_duration_sec") val movingDurationSec: Double,
    @ColumnInfo(name = "uncertain_duration_sec") val uncertainDurationSec: Double,
    @ColumnInfo(name = "max_speed_m_s") val maxSpeedMS: Double,
)

data class HourlyActivityRow(
    @ColumnInfo(name = "hour_start_epoch_ms") val hourStartEpochMs: Long,
    @ColumnInfo(name = "distance_m") val distanceM: Double,
    @ColumnInfo(name = "moving_duration_sec") val movingDurationSec: Double,
    @ColumnInfo(name = "max_speed_m_s") val maxSpeedMS: Double,
)

@Dao
abstract class TrackingDao {
    @Query("SELECT * FROM activity_samples WHERE bucket_start_epoch_ms = :bucketStartEpochMs LIMIT 1")
    protected abstract fun activitySample(bucketStartEpochMs: Long): ActivitySampleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun putActivitySample(sample: ActivitySampleEntity)

    /**
     * Merge partial writes into the same one-second bucket. This matters when the
     * activity or process restarts in the middle of a second: old distance is
     * preserved rather than replaced by the new process's partial bucket.
     */
    @Transaction
    open fun addActivitySample(sample: ActivitySampleEntity) {
        val old = activitySample(sample.bucketStartEpochMs)
        putActivitySample(
            if (old == null) {
                sample
            } else {
                sample.copy(
                    distanceM = old.distanceM + sample.distanceM,
                    revolutions = old.revolutions + sample.revolutions,
                    movingDurationSec = old.movingDurationSec + sample.movingDurationSec,
                    uncertainDurationSec = old.uncertainDurationSec + sample.uncertainDurationSec,
                    maxSpeedMS = max(old.maxSpeedMS, sample.maxSpeedMS),
                )
            },
        )
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun putSession(session: SessionEntity)

    @Query(
        """
        SELECT
            COALESCE(SUM(distance_m), 0.0) AS distance_m,
            COALESCE(SUM(revolutions), 0.0) AS revolutions,
            COALESCE(SUM(moving_duration_sec), 0.0) AS moving_duration_sec,
            COALESCE(SUM(uncertain_duration_sec), 0.0) AS uncertain_duration_sec,
            COALESCE(MAX(max_speed_m_s), 0.0) AS max_speed_m_s
        FROM activity_samples
        WHERE bucket_start_epoch_ms >= :startEpochMs
          AND bucket_start_epoch_ms < :endEpochMs
        """,
    )
    abstract fun summaryBetween(startEpochMs: Long, endEpochMs: Long): ActivitySummaryRow

    @Query(
        """
        SELECT
            (bucket_start_epoch_ms / 3600000) * 3600000 AS hour_start_epoch_ms,
            SUM(distance_m) AS distance_m,
            SUM(moving_duration_sec) AS moving_duration_sec,
            MAX(max_speed_m_s) AS max_speed_m_s
        FROM activity_samples
        WHERE bucket_start_epoch_ms >= :startEpochMs
          AND bucket_start_epoch_ms < :endEpochMs
        GROUP BY hour_start_epoch_ms
        ORDER BY hour_start_epoch_ms ASC
        """,
    )
    abstract fun hourlyBetween(startEpochMs: Long, endEpochMs: Long): List<HourlyActivityRow>

    @Query(
        """
        SELECT * FROM sessions
        WHERE end_epoch_ms >= :startEpochMs
          AND start_epoch_ms < :endEpochMs
        ORDER BY start_epoch_ms DESC
        """,
    )
    abstract fun sessionsBetween(startEpochMs: Long, endEpochMs: Long): List<SessionEntity>
}

@Database(
    entities = [ActivitySampleEntity::class, SessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TrackingDatabase : RoomDatabase() {
    abstract fun trackingDao(): TrackingDao

    companion object {
        @Volatile
        private var instance: TrackingDatabase? = null

        fun get(context: Context): TrackingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TrackingDatabase::class.java,
                "hamster-wheel-tracker.db",
            ).build().also { instance = it }
        }
    }
}
