from dataclasses import dataclass
from typing import Optional

from hamster_tracker.vision.geometry import TAU, WheelGeometry, wrapped_angle_delta


@dataclass(frozen=True)
class RotationSample:
    timestamp: float
    accepted: bool
    reason: str
    angle_rad: float
    delta_angle_rad: float
    angular_velocity_rad_s: float
    speed_m_s: float
    distance_delta_m: float
    total_distance_m: float
    signed_angle_rad: float
    angular_travel_rad: float
    equivalent_revolutions: float


class RotationTracker:
    """Convert marker positions into incremental wheel rotation and distance."""

    def __init__(
        self,
        geometry: WheelGeometry,
        max_angular_speed_rad_s: float = 45.0,
        angular_deadband_rad: float = 0.008,
    ):
        if max_angular_speed_rad_s <= 0:
            raise ValueError("max_angular_speed_rad_s must be positive")
        if angular_deadband_rad < 0:
            raise ValueError("angular_deadband_rad cannot be negative")

        self.geometry = geometry
        self.max_angular_speed_rad_s = max_angular_speed_rad_s
        self.angular_deadband_rad = angular_deadband_rad
        self.reset()

    def reset(self) -> None:
        self._previous_angle: Optional[float] = None
        self._previous_timestamp: Optional[float] = None
        self.signed_angle_rad = 0.0
        self.angular_travel_rad = 0.0
        self.total_distance_m = 0.0

    @property
    def equivalent_revolutions(self) -> float:
        return self.angular_travel_rad / TAU

    def update(self, x_px: float, y_px: float, timestamp: float) -> RotationSample:
        angle = self.geometry.angle_of(x_px, y_px)

        if self._previous_angle is None:
            self._previous_angle = angle
            self._previous_timestamp = timestamp
            return self._sample(timestamp, True, "initialized", angle, 0.0, 0.0)

        assert self._previous_timestamp is not None
        dt = timestamp - self._previous_timestamp
        if dt <= 0:
            return self._sample(timestamp, False, "non_monotonic_timestamp", angle, 0.0, 0.0)

        raw_delta = wrapped_angle_delta(angle, self._previous_angle)
        angular_velocity = raw_delta / dt

        if abs(angular_velocity) > self.max_angular_speed_rad_s:
            return self._sample(timestamp, False, "implausible_angular_speed", angle, 0.0, 0.0)

        delta = 0.0 if abs(raw_delta) <= self.angular_deadband_rad else raw_delta
        angular_velocity = delta / dt
        distance_delta = self.geometry.effective_running_radius_m * abs(delta)

        self.signed_angle_rad += delta
        self.angular_travel_rad += abs(delta)
        self.total_distance_m += distance_delta
        self._previous_angle = angle
        self._previous_timestamp = timestamp

        return self._sample(
            timestamp,
            True,
            "ok" if delta else "deadband",
            angle,
            delta,
            angular_velocity,
            distance_delta,
        )

    def _sample(
        self,
        timestamp: float,
        accepted: bool,
        reason: str,
        angle: float,
        delta: float,
        angular_velocity: float,
        distance_delta: float = 0.0,
    ) -> RotationSample:
        return RotationSample(
            timestamp=timestamp,
            accepted=accepted,
            reason=reason,
            angle_rad=angle,
            delta_angle_rad=delta,
            angular_velocity_rad_s=angular_velocity,
            speed_m_s=abs(angular_velocity) * self.geometry.effective_running_radius_m,
            distance_delta_m=distance_delta,
            total_distance_m=self.total_distance_m,
            signed_angle_rad=self.signed_angle_rad,
            angular_travel_rad=self.angular_travel_rad,
            equivalent_revolutions=self.equivalent_revolutions,
        )
