from dataclasses import dataclass
import math
from typing import Tuple


TAU = 2.0 * math.pi


@dataclass(frozen=True)
class WheelGeometry:
    center_x_px: float
    center_y_px: float
    radius_px: float
    effective_running_diameter_mm: float = 228.6

    @property
    def effective_running_radius_m(self) -> float:
        return self.effective_running_diameter_mm / 2000.0

    @property
    def circumference_m(self) -> float:
        return math.pi * self.effective_running_diameter_mm / 1000.0

    def angle_of(self, x_px: float, y_px: float) -> float:
        return math.atan2(y_px - self.center_y_px, x_px - self.center_x_px)

    def radial_distance_px(self, x_px: float, y_px: float) -> float:
        return math.hypot(x_px - self.center_x_px, y_px - self.center_y_px)

    def point_at(self, angle_rad: float, radius_px: float = None) -> Tuple[float, float]:
        radius = self.radius_px if radius_px is None else radius_px
        return (
            self.center_x_px + radius * math.cos(angle_rad),
            self.center_y_px + radius * math.sin(angle_rad),
        )


def wrapped_angle_delta(current_rad: float, previous_rad: float) -> float:
    """Return the shortest signed angular difference in [-pi, pi)."""
    return (current_rad - previous_rad + math.pi) % TAU - math.pi
