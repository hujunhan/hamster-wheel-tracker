from dataclasses import dataclass
from typing import Optional

from hamster_tracker.vision.geometry import TAU


@dataclass(frozen=True)
class SessionRecord:
    start_ts: float
    end_ts: float
    duration_s: float
    moving_duration_s: float
    distance_m: float
    equivalent_revolutions: float
    avg_speed_m_s: float
    max_speed_m_s: float


@dataclass(frozen=True)
class SessionUpdate:
    running: bool
    session_open: bool
    completed_session: Optional[SessionRecord]


class SessionTracker:
    """Group wheel motion into logical running sessions with short-pause tolerance."""

    def __init__(
        self,
        start_speed_m_s: float = 0.05,
        stop_speed_m_s: float = 0.03,
        stop_hold_seconds: float = 1.0,
        session_gap_seconds: float = 10.0,
    ):
        if stop_speed_m_s > start_speed_m_s:
            raise ValueError("stop_speed_m_s must be <= start_speed_m_s")
        self.start_speed_m_s = start_speed_m_s
        self.stop_speed_m_s = stop_speed_m_s
        self.stop_hold_seconds = stop_hold_seconds
        self.session_gap_seconds = session_gap_seconds
        self.reset()

    def reset(self) -> None:
        self.running = False
        self._below_stop_since: Optional[float] = None
        self._last_update_ts: Optional[float] = None
        self._session_start_ts: Optional[float] = None
        self._last_motion_ts: Optional[float] = None
        self._distance_m = 0.0
        self._angular_travel_rad = 0.0
        self._moving_duration_s = 0.0
        self._max_speed_m_s = 0.0

    def update(
        self,
        timestamp: float,
        speed_m_s: float,
        distance_delta_m: float,
        angular_travel_delta_rad: float,
    ) -> SessionUpdate:
        if self._last_update_ts is not None and timestamp < self._last_update_ts:
            raise ValueError("timestamp must be monotonic")

        completed = self._close_if_gap_elapsed(timestamp)
        dt = 0.0 if self._last_update_ts is None else timestamp - self._last_update_ts

        if not self.running:
            if speed_m_s >= self.start_speed_m_s:
                self.running = True
                self._below_stop_since = None
        else:
            if speed_m_s <= self.stop_speed_m_s:
                if self._below_stop_since is None:
                    self._below_stop_since = timestamp
                elif timestamp - self._below_stop_since >= self.stop_hold_seconds:
                    self.running = False
            else:
                self._below_stop_since = None

        motion_present = distance_delta_m > 0.0 or self.running
        if motion_present:
            if self._session_start_ts is None:
                self._session_start_ts = timestamp
            self._last_motion_ts = timestamp
            self._distance_m += max(0.0, distance_delta_m)
            self._angular_travel_rad += max(0.0, angular_travel_delta_rad)
            self._max_speed_m_s = max(self._max_speed_m_s, max(0.0, speed_m_s))
            if self.running:
                self._moving_duration_s += max(0.0, dt)

        self._last_update_ts = timestamp
        return SessionUpdate(self.running, self._session_start_ts is not None, completed)

    def flush(self, timestamp: float, force: bool = False) -> Optional[SessionRecord]:
        if self._session_start_ts is None:
            return None
        if not force and self._last_motion_ts is not None:
            if timestamp - self._last_motion_ts < self.session_gap_seconds:
                return None
        return self._finalize_session()

    def _close_if_gap_elapsed(self, timestamp: float) -> Optional[SessionRecord]:
        if self._session_start_ts is None or self._last_motion_ts is None:
            return None
        if timestamp - self._last_motion_ts < self.session_gap_seconds:
            return None
        return self._finalize_session()

    def _finalize_session(self) -> SessionRecord:
        assert self._session_start_ts is not None
        assert self._last_motion_ts is not None
        duration = max(0.0, self._last_motion_ts - self._session_start_ts)
        avg_speed = self._distance_m / self._moving_duration_s if self._moving_duration_s > 0 else 0.0
        record = SessionRecord(
            start_ts=self._session_start_ts,
            end_ts=self._last_motion_ts,
            duration_s=duration,
            moving_duration_s=self._moving_duration_s,
            distance_m=self._distance_m,
            equivalent_revolutions=self._angular_travel_rad / TAU,
            avg_speed_m_s=avg_speed,
            max_speed_m_s=self._max_speed_m_s,
        )

        self.running = False
        self._below_stop_since = None
        self._session_start_ts = None
        self._last_motion_ts = None
        self._distance_m = 0.0
        self._angular_travel_rad = 0.0
        self._moving_duration_s = 0.0
        self._max_speed_m_s = 0.0
        return record
