from dataclasses import dataclass
from typing import Optional

from hamster_tracker.storage.database import ActivitySample


@dataclass
class _Bucket:
    start_ts: float
    end_ts: float
    distance_m: float = 0.0
    signed_angle_rad: float = 0.0
    angular_travel_rad: float = 0.0
    max_speed_m_s: float = 0.0
    running_seconds: float = 0.0
    quality_weighted: float = 0.0
    quality_weight: float = 0.0
    state: str = "SEARCHING"


class ActivityAggregator:
    """Aggregate high-rate tracker updates into fixed-duration storage samples."""

    def __init__(self, interval_s: float = 1.0):
        if interval_s <= 0:
            raise ValueError("interval_s must be positive")
        self.interval_s = interval_s
        self._bucket: Optional[_Bucket] = None
        self._last_ts: Optional[float] = None

    def update(
        self,
        timestamp: float,
        distance_delta_m: float,
        signed_angle_delta_rad: float,
        angular_travel_delta_rad: float,
        speed_m_s: float,
        running: bool,
        tracking_state: str,
        detection_quality: Optional[float],
    ) -> Optional[ActivitySample]:
        if self._last_ts is not None and timestamp < self._last_ts:
            raise ValueError("timestamp must be monotonic")

        emitted = None
        if self._bucket is None:
            self._bucket = self._new_bucket(timestamp)
        elif timestamp >= self._bucket.end_ts:
            emitted = self._emit(self._bucket)
            self._bucket = self._new_bucket(timestamp)

        dt = 0.0 if self._last_ts is None else max(0.0, timestamp - self._last_ts)
        bucket = self._bucket
        bucket.distance_m += max(0.0, distance_delta_m)
        bucket.signed_angle_rad += signed_angle_delta_rad
        bucket.angular_travel_rad += max(0.0, angular_travel_delta_rad)
        bucket.max_speed_m_s = max(bucket.max_speed_m_s, max(0.0, speed_m_s))
        if running:
            bucket.running_seconds += min(dt, self.interval_s)
        bucket.state = tracking_state
        if detection_quality is not None:
            weight = max(dt, 1e-6)
            bucket.quality_weighted += detection_quality * weight
            bucket.quality_weight += weight

        self._last_ts = timestamp
        return emitted

    def flush(self) -> Optional[ActivitySample]:
        if self._bucket is None:
            return None
        emitted = self._emit(self._bucket)
        self._bucket = None
        return emitted

    def _new_bucket(self, timestamp: float) -> _Bucket:
        start = (timestamp // self.interval_s) * self.interval_s
        return _Bucket(start_ts=start, end_ts=start + self.interval_s)

    def _emit(self, bucket: _Bucket) -> ActivitySample:
        quality = (
            bucket.quality_weighted / bucket.quality_weight
            if bucket.quality_weight > 0
            else None
        )
        return ActivitySample(
            timestamp=bucket.start_ts,
            interval_s=self.interval_s,
            distance_delta_m=bucket.distance_m,
            signed_angle_delta_rad=bucket.signed_angle_rad,
            angular_travel_delta_rad=bucket.angular_travel_rad,
            speed_m_s=bucket.max_speed_m_s,
            running=bucket.running_seconds > 0.0,
            tracking_state=bucket.state,
            detection_quality=quality,
        )
