from pathlib import Path
import math

from hamster_tracker.tracking.engine import TrackerEngine, TrackingState
from hamster_tracker.tracking.rotation_tracker import RotationTracker
from hamster_tracker.tracking.session_tracker import SessionTracker
from hamster_tracker.vision.geometry import WheelGeometry


VECTORS = Path(__file__).resolve().parents[1] / "shared" / "test-vectors" / "tracker_sequences.tsv"


def _rows():
    for raw in VECTORS.read_text(encoding="utf-8").splitlines():
        if not raw or raw.startswith("#"):
            continue
        name, observations, expected_revs, expected_signed, expected_state = raw.split("|")
        yield (
            name,
            observations.split(";"),
            float(expected_revs),
            float(expected_signed),
            TrackingState(expected_state),
        )


def test_shared_tracker_vectors_match_python_reference():
    geometry = WheelGeometry(320.0, 240.0, 180.0, 228.6)
    marker_radius = 0.75 * geometry.radius_px

    for name, observations, expected_revs, expected_signed, expected_state in _rows():
        engine = TrackerEngine(
            RotationTracker(geometry),
            SessionTracker(),
            max_short_gap_s=0.20,
        )
        snapshot = engine.snapshot()

        for token in observations:
            timestamp_text, value = token.split(":")
            timestamp = float(timestamp_text)
            if value == "MISSING":
                snapshot = engine.process_missing(timestamp)
                continue

            angle = float(value)
            x, y = geometry.point_at(angle, marker_radius)
            snapshot = engine.process_marker(timestamp, x, y, detection_quality=0.9)

        assert math.isclose(snapshot.equivalent_revolutions, expected_revs, abs_tol=1e-7), name
        assert math.isclose(snapshot.signed_angle_rad, expected_signed, abs_tol=1e-7), name
        assert snapshot.tracking_state == expected_state, name
        expected_distance = expected_revs * geometry.circumference_m
        assert math.isclose(snapshot.total_distance_m, expected_distance, abs_tol=1e-7), name
