import math

import pytest

from hamster_tracker.sim.trajectory import MotionSegment, TrajectoryGenerator
from hamster_tracker.storage.database import Database
from hamster_tracker.tracking.engine import TrackerEngine, TrackingState
from hamster_tracker.tracking.rotation_tracker import RotationTracker
from hamster_tracker.tracking.session_tracker import SessionTracker
from hamster_tracker.vision.geometry import WheelGeometry


def build_engine(db=None, max_short_gap_s=0.2):
    geometry = WheelGeometry(320.0, 240.0, 180.0, 228.6)
    rotation = RotationTracker(
        geometry,
        max_angular_speed_rad_s=30.0,
        angular_deadband_rad=0.0,
    )
    sessions = SessionTracker(
        start_speed_m_s=0.03,
        stop_speed_m_s=0.02,
        stop_hold_seconds=0.5,
        session_gap_seconds=2.0,
    )
    return geometry, TrackerEngine(
        rotation,
        sessions,
        db,
        storage_interval_s=1.0,
        max_short_gap_s=max_short_gap_s,
    )


def feed(engine, observations):
    snapshot = None
    for observation in observations:
        if observation.visible:
            snapshot = engine.process_marker(
                observation.timestamp,
                observation.x_px,
                observation.y_px,
                detection_quality=0.95,
            )
        else:
            snapshot = engine.process_missing(observation.timestamp)
    return snapshot


def test_engine_pipeline_matches_synthetic_motion_without_occlusion():
    db = Database(":memory:")
    geometry, engine = build_engine(db)
    generator = TrajectoryGenerator(geometry, fps=20.0, seed=4)
    segments = [
        MotionSegment(1.0, 0.0, True, "idle"),
        MotionSegment(2.0, 2.0 * math.pi, True, "one_rev_per_sec"),
        MotionSegment(0.5, 0.0, True, "pause"),
        MotionSegment(1.0, -math.pi, True, "reverse_half_rev_per_sec"),
        MotionSegment(3.0, 0.0, True, "finish"),
    ]
    observations = generator.generate(segments)
    snapshot = feed(engine, observations)
    engine.finalize(observations[-1].timestamp)

    expected_angular_travel = 2.0 * (2.0 * math.pi) + math.pi
    expected_distance = geometry.effective_running_radius_m * expected_angular_travel
    assert snapshot.total_distance_m == pytest.approx(expected_distance, rel=1e-5)
    assert snapshot.equivalent_revolutions == pytest.approx(2.5, rel=1e-5)

    summary = db.summary(0.0, 20.0)
    assert summary["distance_m"] == pytest.approx(expected_distance, rel=1e-5)
    assert summary["equivalent_revolutions"] == pytest.approx(2.5, rel=1e-5)
    assert db.activity_count() >= 6
    assert len(db.sessions(0.0, 20.0)) >= 1


def test_long_occlusion_reinitializes_without_inventing_hidden_rotation():
    geometry, engine = build_engine(max_short_gap_s=0.15)
    radius = geometry.radius_px * 0.75

    x0, y0 = geometry.point_at(0.0, radius)
    engine.process_marker(0.0, x0, y0)
    x1, y1 = geometry.point_at(0.2, radius)
    before_gap = engine.process_marker(0.1, x1, y1)
    assert before_gap.total_distance_m > 0.0

    engine.process_missing(0.2)
    missing = engine.process_missing(0.4)
    assert missing.tracking_state == TrackingState.UNCERTAIN

    # Reappearance after an ambiguous interval establishes phase only.
    x2, y2 = geometry.point_at(2.8, radius)
    reacquired = engine.process_marker(0.5, x2, y2)
    assert reacquired.last_reason == "reinitialized"
    assert reacquired.total_distance_m == pytest.approx(before_gap.total_distance_m)


def test_brief_occlusion_preserves_tracking_and_counts_reacquired_delta():
    geometry, engine = build_engine(max_short_gap_s=0.2)
    radius = geometry.radius_px * 0.75
    x0, y0 = geometry.point_at(0.0, radius)
    engine.process_marker(0.0, x0, y0)
    x1, y1 = geometry.point_at(0.3, radius)
    engine.process_marker(0.1, x1, y1)
    state = engine.process_missing(0.15)
    assert state.tracking_state == TrackingState.PREDICTING
    x2, y2 = geometry.point_at(0.6, radius)
    recovered = engine.process_marker(0.2, x2, y2)
    assert recovered.tracking_state == TrackingState.TRACKING
    assert recovered.last_reason == "ok"
    assert recovered.signed_angle_rad == pytest.approx(0.6)
