import math

import pytest

from hamster_tracker.tracking.rotation_tracker import RotationTracker
from hamster_tracker.vision.geometry import WheelGeometry, wrapped_angle_delta


@pytest.fixture
def geometry():
    return WheelGeometry(100.0, 100.0, 80.0, effective_running_diameter_mm=228.6)


def point(geometry, angle):
    return geometry.point_at(angle, 60.0)


def feed(tracker, geometry, angles, dt=0.1):
    result = None
    for i, angle in enumerate(angles):
        x, y = point(geometry, angle)
        result = tracker.update(x, y, i * dt)
    return result


def test_quarter_revolution_is_counted_immediately(geometry):
    tracker = RotationTracker(geometry, angular_deadband_rad=0.0)
    result = feed(tracker, geometry, [0.0, math.pi / 4, math.pi / 2])
    assert result.equivalent_revolutions == pytest.approx(0.25)
    assert result.total_distance_m == pytest.approx(geometry.circumference_m * 0.25)


def test_full_revolution_crosses_pi_boundary(geometry):
    tracker = RotationTracker(geometry, angular_deadband_rad=0.0)
    result = feed(tracker, geometry, [0.0, math.pi / 2, math.pi, -math.pi / 2, 0.0])
    assert result.equivalent_revolutions == pytest.approx(1.0)
    assert result.signed_angle_rad == pytest.approx(2 * math.pi)


def test_forward_then_backward_does_not_cancel_distance(geometry):
    tracker = RotationTracker(geometry, angular_deadband_rad=0.0)
    result = feed(tracker, geometry, [0.0, math.pi / 2, 0.0])
    assert result.signed_angle_rad == pytest.approx(0.0)
    assert result.equivalent_revolutions == pytest.approx(0.5)


def test_wrapped_delta_handles_boundary():
    previous = math.radians(170)
    current = math.radians(-170)
    assert wrapped_angle_delta(current, previous) == pytest.approx(math.radians(20))


def test_implausible_jump_is_rejected_without_adding_distance(geometry):
    tracker = RotationTracker(geometry, max_angular_speed_rad_s=20.0, angular_deadband_rad=0.0)
    x0, y0 = point(geometry, 0.0)
    tracker.update(x0, y0, 0.0)
    x1, y1 = point(geometry, math.pi / 2)
    result = tracker.update(x1, y1, 0.01)
    assert not result.accepted
    assert result.reason == "implausible_angular_speed"
    assert result.total_distance_m == 0.0


def test_deadband_suppresses_small_stationary_jitter(geometry):
    tracker = RotationTracker(geometry, angular_deadband_rad=0.01)
    result = feed(tracker, geometry, [0.0, 0.004, -0.003, 0.005, 0.0])
    assert result.total_distance_m == pytest.approx(0.0)


def test_irregular_frame_times_use_real_dt(geometry):
    tracker = RotationTracker(geometry, angular_deadband_rad=0.0)
    x0, y0 = point(geometry, 0.0)
    tracker.update(x0, y0, 1.0)
    x1, y1 = point(geometry, math.pi / 2)
    result = tracker.update(x1, y1, 1.5)
    assert result.angular_velocity_rad_s == pytest.approx(math.pi)
