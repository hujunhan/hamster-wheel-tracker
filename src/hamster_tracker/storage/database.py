from dataclasses import dataclass
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


class Database:
    def __init__(self, path: str):
        self.path = path
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path)
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
                detection_quality REAL
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
        self.connection.commit()

    def insert_activity(self, sample: ActivitySample) -> None:
        self.connection.execute(
            """
            INSERT INTO activity_samples (
                timestamp, interval_s, distance_delta_m, signed_angle_delta_rad,
                angular_travel_delta_rad, speed_m_s, running, tracking_state,
                detection_quality
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                COALESCE(SUM(CASE WHEN running = 1 THEN interval_s ELSE 0 END), 0.0) AS moving_duration_s,
                COALESCE(MAX(speed_m_s), 0.0) AS max_speed_m_s
            FROM activity_samples
            WHERE timestamp >= ? AND timestamp < ?
            """,
            (start_ts, end_ts),
        ).fetchone()
        distance = float(row["distance_m"])
        moving = float(row["moving_duration_s"])
        angular_travel = float(row["angular_travel_rad"])
        return {
            "distance_m": distance,
            "moving_duration_s": moving,
            "equivalent_revolutions": angular_travel / (2.0 * 3.141592653589793),
            "avg_speed_m_s": distance / moving if moving > 0 else 0.0,
            "max_speed_m_s": float(row["max_speed_m_s"]),
        }

    def sessions(self, start_ts: float, end_ts: float, limit: int = 100) -> List[Dict[str, float]]:
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
