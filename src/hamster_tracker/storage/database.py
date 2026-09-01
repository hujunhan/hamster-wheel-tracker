from dataclasses import dataclass
import math
from pathlib import Path
import sqlite3
from typing import Dict, List, Optional

from hamster_tracker.tracking.session_tracker import SessionRecord


@dataclass(frozen=True)
class ActivitySample:
    timestamp: float
    interval_s: float
    distance_delta_m: float
    signed_angle_delta_rad: float
    angular_travel_delta_rad: float
    speed_m_s: float
    running: bool
    tracking_state: str = "TRACKING"
    detection_quality: Optional[float] = None
    moving_duration_s: Optional[float] = None


class Database:
    def __init__(self, path: str):
        self.path = path
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path, check_same_thread=False)
        self.connection.row_factory = sqlite3.Row
        self.initialize()

    def close(self) -> None:
        self.connection.close()

    def initialize(self) -> None:
        self.connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS activity_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp REAL NOT NULL,
                interval_s REAL NOT NULL,
                distance_delta_m REAL NOT NULL,
                signed_angle_delta_rad REAL NOT NULL,
                angular_travel_delta_rad REAL NOT NULL,
                speed_m_s REAL NOT NULL,
                running INTEGER NOT NULL,
                tracking_state TEXT NOT NULL,
                detection_quality REAL,
                moving_duration_s REAL NOT NULL DEFAULT 0.0
            );

            CREATE INDEX IF NOT EXISTS idx_activity_timestamp
            ON activity_samples(timestamp);

            CREATE TABLE IF NOT EXISTS sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_ts REAL NOT NULL,
                end_ts REAL NOT NULL,
                duration_s REAL NOT NULL,
                moving_duration_s REAL NOT NULL,
                distance_m REAL NOT NULL,
                equivalent_revolutions REAL NOT NULL,
                avg_speed_m_s REAL NOT NULL,
                max_speed_m_s REAL NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_session_start
            ON sessions(start_ts);
            """
        )
        columns = {
            row["name"]
            for row in self.connection.execute("PRAGMA table_info(activity_samples)")
        }
        if "moving_duration_s" not in columns:
            self.connection.execute(
                "ALTER TABLE activity_samples "
                "ADD COLUMN moving_duration_s REAL NOT NULL DEFAULT 0.0"
            )
        self.connection.commit()

    def insert_activity(self, sample: ActivitySample) -> None:
        moving_duration = (
            sample.moving_duration_s
            if sample.moving_duration_s is not None
            else (sample.interval_s if sample.running else 0.0)
        )
        self.connection.execute(
            """
            INSERT INTO activity_samples (
                timestamp, interval_s, distance_delta_m, signed_angle_delta_rad,
                angular_travel_delta_rad, speed_m_s, running, tracking_state,
                detection_quality, moving_duration_s
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                sample.timestamp,
                sample.interval_s,
                sample.distance_delta_m,
                sample.signed_angle_delta_rad,
                sample.angular_travel_delta_rad,
                sample.speed_m_s,
                int(sample.running),
                sample.tracking_state,
                sample.detection_quality,
                moving_duration,
            ),
        )
        self.connection.commit()

    def insert_session(self, session: SessionRecord) -> int:
        cursor = self.connection.execute(
            """
            INSERT INTO sessions (
                start_ts, end_ts, duration_s, moving_duration_s, distance_m,
                equivalent_revolutions, avg_speed_m_s, max_speed_m_s
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                session.start_ts,
                session.end_ts,
                session.duration_s,
                session.moving_duration_s,
                session.distance_m,
                session.equivalent_revolutions,
                session.avg_speed_m_s,
                session.max_speed_m_s,
            ),
        )
        self.connection.commit()
        return int(cursor.lastrowid)

    def summary(self, start_ts: float, end_ts: float) -> Dict[str, float]:
        row = self.connection.execute(
            """
            SELECT
                COALESCE(SUM(distance_delta_m), 0.0) AS distance_m,
                COALESCE(SUM(angular_travel_delta_rad), 0.0) AS angular_travel_rad,
                COALESCE(SUM(moving_duration_s), 0.0) AS moving_duration_s,
                COALESCE(MAX(speed_m_s), 0.0) AS max_speed_m_s,
                COALESCE(SUM(
                    CASE WHEN tracking_state = 'UNCERTAIN' THEN interval_s ELSE 0 END
                ), 0.0) AS uncertain_duration_s
            FROM activity_samples
            WHERE timestamp >= ? AND timestamp < ?
            """,
            (start_ts, end_ts),
        ).fetchone()
        session_row = self.connection.execute(
            """
            SELECT
                COUNT(*) AS session_count,
                COALESCE(MAX(duration_s), 0.0) AS longest_session_s
            FROM sessions
            WHERE start_ts >= ? AND start_ts < ?
            """,
            (start_ts, end_ts),
        ).fetchone()
        distance = float(row["distance_m"])
        moving = float(row["moving_duration_s"])
        angular_travel = float(row["angular_travel_rad"])
        return {
            "distance_m": distance,
            "moving_duration_s": moving,
            "equivalent_revolutions": angular_travel / (2.0 * math.pi),
            "avg_speed_m_s": distance / moving if moving > 0 else 0.0,
            "max_speed_m_s": float(row["max_speed_m_s"]),
            "uncertain_duration_s": float(row["uncertain_duration_s"]),
            "session_count": float(session_row["session_count"]),
            "longest_session_s": float(session_row["longest_session_s"]),
        }

    def sessions(
        self, start_ts: float, end_ts: float, limit: int = 100
    ) -> List[Dict[str, float]]:
        rows = self.connection.execute(
            """
            SELECT * FROM sessions
            WHERE start_ts >= ? AND start_ts < ?
            ORDER BY start_ts DESC
            LIMIT ?
            """,
            (start_ts, end_ts, limit),
        ).fetchall()
        return [dict(row) for row in rows]

    def latest_activity(self) -> Optional[Dict[str, object]]:
        row = self.connection.execute(
            """
            SELECT timestamp, speed_m_s, running, tracking_state, detection_quality
            FROM activity_samples
            ORDER BY timestamp DESC
            LIMIT 1
            """
        ).fetchone()
        if row is None:
            return None
        result = dict(row)
        result["running"] = bool(result["running"])
        return result

    def hourly_activity(self, start_ts: float, end_ts: float) -> List[Dict[str, float]]:
        if end_ts <= start_ts:
            return []
        bin_seconds = 3600.0
        bin_count = int(math.ceil((end_ts - start_ts) / bin_seconds))
        rows = self.connection.execute(
            """
            SELECT
                CAST((timestamp - ?) / ? AS INTEGER) AS bin_index,
                COALESCE(SUM(distance_delta_m), 0.0) AS distance_m,
                COALESCE(SUM(moving_duration_s), 0.0) AS moving_duration_s,
                COALESCE(MAX(speed_m_s), 0.0) AS max_speed_m_s
            FROM activity_samples
            WHERE timestamp >= ? AND timestamp < ?
            GROUP BY bin_index
            ORDER BY bin_index
            """,
            (start_ts, bin_seconds, start_ts, end_ts),
        ).fetchall()
        by_index = {int(row["bin_index"]): row for row in rows}
        result = []
        for index in range(bin_count):
            row = by_index.get(index)
            result.append(
                {
                    "start_ts": start_ts + index * bin_seconds,
                    "distance_m": float(row["distance_m"]) if row else 0.0,
                    "moving_duration_s": float(row["moving_duration_s"]) if row else 0.0,
                    "max_speed_m_s": float(row["max_speed_m_s"]) if row else 0.0,
                }
            )
        return result

    def activity_timeline(
        self,
        start_ts: float,
        end_ts: float,
        max_points: int = 240,
    ) -> List[Dict[str, object]]:
        if end_ts <= start_ts or max_points <= 0:
            return []
        bucket_seconds = max(1.0, (end_ts - start_ts) / float(max_points))
        rows = self.connection.execute(
            """
            SELECT
                CAST((timestamp - ?) / ? AS INTEGER) AS bin_index,
                COALESCE(MAX(speed_m_s), 0.0) AS speed_m_s,
                COALESCE(SUM(distance_delta_m), 0.0) AS distance_m,
                COALESCE(SUM(moving_duration_s), 0.0) AS moving_duration_s,
                MAX(running) AS running,
                MAX(CASE WHEN tracking_state = 'UNCERTAIN' THEN 1 ELSE 0 END) AS uncertain
            FROM activity_samples
            WHERE timestamp >= ? AND timestamp < ?
            GROUP BY bin_index
            ORDER BY bin_index
            """,
            (start_ts, bucket_seconds, start_ts, end_ts),
        ).fetchall()
        return [
            {
                "timestamp": start_ts + int(row["bin_index"]) * bucket_seconds,
                "speed_m_s": float(row["speed_m_s"]),
                "distance_m": float(row["distance_m"]),
                "moving_duration_s": float(row["moving_duration_s"]),
                "running": bool(row["running"]),
                "uncertain": bool(row["uncertain"]),
            }
            for row in rows
        ]

    def activity_count(self) -> int:
        return int(
            self.connection.execute("SELECT COUNT(*) FROM activity_samples").fetchone()[0]
        )
