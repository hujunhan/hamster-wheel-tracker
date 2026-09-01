"""Run the rotation tracker with synthetic marker positions (no camera required)."""

import math

from hamster_tracker.tracking.rotation_tracker import RotationTracker
from hamster_tracker.vision.geometry import WheelGeometry


def main():
    geometry = WheelGeometry(320, 240, 180, effective_running_diameter_mm=228.6)
    tracker = RotationTracker(geometry, angular_deadband_rad=0.0)
    marker_radius = 0.75 * geometry.radius_px

    for index in range(17):
        theta = index * (math.pi / 8.0)
        x, y = geometry.point_at(theta, marker_radius)
        sample = tracker.update(x, y, index * 0.1)
        print(
            "t={:.1f}s angle={:+.2f} rev={:.3f} distance={:.3f}m speed={:.2f}m/s".format(
                sample.timestamp,
                sample.angle_rad,
                sample.equivalent_revolutions,
                sample.total_distance_m,
                sample.speed_m_s,
            )
        )


if __name__ == "__main__":
    main()
