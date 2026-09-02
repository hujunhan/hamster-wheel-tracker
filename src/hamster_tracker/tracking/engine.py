from dataclasses import dataclass
from enum import Enum
import math
from typing import Optional

from hamster_tracker.storage.aggregation import ActivityAggregator
from hamster_tracker.storage.database import Database
from hamster_tracker.tracking.rotation_tracker import RotationSample, RotationTracker
from hamster_tracker.tracking.session_tracker import SessionTracker


class TrackingState(str, Enum):
    SEARCHING = "SEARCHING"
    TRACKING = "TRACKING"
    PREDICTING = "PREDICTING"
    UNCERTAIN = "UNCERTAIN"


@dataclass(frozen=True)
class EngineSnapshot:
    timestamp: float
    tracking_state: TrackingState
    marker_visible: bool
    running: bool
    speed_m_s: float
    total_distance_m: float
    equivalent_revolutions: float
    signed_angle_rad: float
    last_reason: str
    detection_quality: Optional[float]


class TrackerEngine:
    """Hardware-independent orchestration for rotation, sessions, and storage."""

    def __init__(
        self,
        rotation_tracker: RotationTracker,
        session_tracker: SessionTracker,
        database: Optional[Database] = None,
        storage_interval_s: float = 1.0,
        max_short_gap_s: float = 0.20,
    ):
        if max_short_gap_s < 0:
            raise ValueError("max_short_gap_s cannot be negative")
        self.rotation = rotation_tracker
        self.sessions = session_tracker
        self.database = database
        self.aggregator = ActivityAggregator(storage_interval_s)
        self.max_short_gap_s = max_short_gap_s
        self.state = TrackingState.SEARCHING
        self._last_seen_ts: Optional[float] = None
        self._last_update_ts: Optional[float] = None
        self._last_speed_m_s = 0.0
        self._last_angular_velocity_rad_s = 0.0
        self._last_reason = "not_initialized"
        self._last_quality: Optional[float] = None

    def process_marker(
        self,
        timestamp: float,
        x_px: float,
        y_px: float,
        detection_quality: Optional[float] = None,
    ) -> EngineSnapshot:
        self._check_timestamp(timestamp)
        had_long_gap = (
            self._last_seen_ts is not None
            and timestamp - self._last_seen_ts > self.max_short_gap_s
        )
        had_phase_ambiguous_gap = self._phase_gap_is_ambiguous(timestamp)

        if (
            self.state in (TrackingState.SEARCHING, TrackingState.UNCERTAIN)
            or had_long_gap
            or had_phase_ambiguous_gap
        ):
            sample = self.rotation.reinitialize_phase(x_px, y_px, timestamp)
            self.state = TrackingState.TRACKING
        else:
            sample = self.rotation.update(x_px, y_px, timestamp)
            self.state = TrackingState.TRACKING if sample.accepted else TrackingState.UNCERTAIN

        self._last_seen_ts = timestamp
        self._last_quality = detection_quality
        self._last_speed_m_s = sample.speed_m_s if sample.accepted else 0.0
        self._last_angular_velocity_rad_s = (
            sample.angular_velocity_rad_s if sample.accepted else 0.0
        )
        self._last_reason = sample.reason
        self._consume_motion(timestamp, sample, detection_quality)
        self._last_update_ts = timestamp
        return self.snapshot(timestamp, marker_visible=True)

    def process_missing(self, timestamp: float) -> EngineSnapshot:
        self._check_timestamp(timestamp)
        if self._last_seen_ts is None:
            self.state = TrackingState.SEARCHING
        else:
            gap = timestamp - self._last_seen_ts
            self.state = (
                TrackingState.PREDICTING
                if gap <= self.max_short_gap_s and not self._phase_gap_is_ambiguous(timestamp)
                else TrackingState.UNCERTAIN
            )

        session_update = self.sessions.update(timestamp, 0.0, 0.0, 0.0)
        if session_update.completed_session is not None and self.database is not None:
            self.database.insert_session(session_update.completed_session)

        emitted = self.aggregator.update(
            timestamp=timestamp,
            distance_delta_m=0.0,
            signed_angle_delta_rad=0.0,
            angular_travel_delta_rad=0.0,
            speed_m_s=0.0,
            running=session_update.running,
            tracking_state=self.state.value,
            detection_quality=None,
        )
        if emitted is not None and self.database is not None:
            self.database.insert_activity(emitted)

        self._last_speed_m_s = 0.0
        self._last_quality = None
        self._last_reason = "marker_missing"
        self._last_update_ts = timestamp
        return self.snapshot(timestamp, marker_visible=False)

    def finalize(self, timestamp: Optional[float] = None) -> None:
        ts = timestamp if timestamp is not None else (self._last_update_ts or 0.0)
        session = self.sessions.flush(ts, force=True)
        if session is not None and self.database is not None:
            self.database.insert_session(session)
        sample = self.aggregator.flush()
        if sample is not None and self.database is not None:
            self.database.insert_activity(sample)

    def snapshot(
        self,
        timestamp: Optional[float] = None,
        marker_visible: Optional[bool] = None,
    ) -> EngineSnapshot:
        ts = self._last_update_ts if timestamp is None else timestamp
        if ts is None:
            ts = 0.0
        visible = self.state == TrackingState.TRACKING if marker_visible is None else marker_visible
        return EngineSnapshot(
            timestamp=ts,
            tracking_state=self.state,
            marker_visible=visible,
            running=self.sessions.running,
            speed_m_s=self._last_speed_m_s,
            total_distance_m=self.rotation.total_distance_m,
            equivalent_revolutions=self.rotation.equivalent_revolutions,
            signed_angle_rad=self.rotation.signed_angle_rad,
            last_reason=self._last_reason,
            detection_quality=self._last_quality,
        )

    def _consume_motion(
        self,
        timestamp: float,
        sample: RotationSample,
        quality: Optional[float],
    ) -> None:
        distance = sample.distance_delta_m if sample.accepted else 0.0
        signed_angle = sample.delta_angle_rad if sample.accepted else 0.0
        angular_travel = abs(signed_angle)
        speed = sample.speed_m_s if sample.accepted else 0.0

        session_update = self.sessions.update(timestamp, speed, distance, angular_travel)
        if session_update.completed_session is not None and self.database is not None:
            self.database.insert_session(session_update.completed_session)

        emitted = self.aggregator.update(
            timestamp=timestamp,
            distance_delta_m=distance,
            signed_angle_delta_rad=signed_angle,
            angular_travel_delta_rad=angular_travel,
            speed_m_s=speed,
            running=session_update.running,
            tracking_state=self.state.value,
            detection_quality=quality,
        )
        if emitted is not None and self.database is not None:
            self.database.insert_activity(emitted)

    def _phase_gap_is_ambiguous(self, timestamp: float) -> bool:
        if self._last_seen_ts is None or self._last_angular_velocity_rad_s == 0.0:
            return False
        gap = max(0.0, timestamp - self._last_seen_ts)
        projected_travel = abs(self._last_angular_velocity_rad_s) * gap
        return projected_travel >= math.pi

    def _check_timestamp(self, timestamp: float) -> None:
        if self._last_update_ts is not None and timestamp < self._last_update_ts:
            raise ValueError("timestamp must be monotonic")
