import math

import pytest

cv2 = pytest.importorskip("cv2")
pytest.importorskip("numpy")

from hamster_tracker.sim.frames import render_wheel_frame
from hamster_tracker.tracking.engine import TrackerEngine, TrackingState
from hamster_tracker.tracking.rotation_tracker import RotationTracker
from hamster_tracker.tracking.session_tracker import SessionTracker
from hamster_tracker.vision.geometry import TAU, WheelGeometry
from hamster_tracker.vision.marker_detector import MarkerDetector, MarkerDetectorConfig


def _pipeline():
    geometry = WheelGeometry(160.0, 90.0, 70.0)
    marker_radius = geometry.radius_px * 0.75
    detector = MarkerDetector(
        geometry,
        MarkerDetectorConfig(
            hsv_lower=(40, 80, 50),
            hsv_upper=(80, 255, 255),
            expected_radius_px=marker_radius,
            radius_tolerance_px=10.0,
            min_area_px=20.0,
            max_area_px=500.0,
            morphology_kernel=3,
        ),
    )
    rotation = RotationTracker(geometry)
    engine = TrackerEngine(rotation, SessionTracker(), max_short_gap_s=0.20)
    return geometry, detector, rotation, engine


def _run(
    revolutions: float,
    rps: float,
    fps: int = 30,
    brightness: float = 1.0,
    blur_kernel: int = 0,
    noise_sigma: float = 0.0,
    missing_frames=None,
):
    geometry, detector, rotation, engine = _pipeline()
    missing_frames = set(missing_frames or [])
    total_frames = round(revolutions / rps * fps) + 1
    detections = 0
    states = []

    for index in range(total_frames):
        timestamp = index / fps
        angle = TAU * rps * timestamp
        visible = index not in missing_frames
        frame = render_wheel_frame(
            geometry,
            angle,
            width=320,
            height=180,
            marker_radius_ratio=0.75,
            marker_size_px=6,
            marker_visible=visible,
            brightness=brightness,
            blur_kernel=blur_kernel,
            noise_sigma=noise_sigma,
            distractor=True,
            noise_seed=index,
        )
        detection = detector.detect(frame)
        if detection is None:
            snapshot = engine.process_missing(timestamp)
        else:
            detections += 1
            snapshot = engine.process_marker(
                timestamp,
                detection.x_px,
                detection.y_px,
                detection.score,
            )
        states.append(snapshot.tracking_state)

    return rotation, detections / total_frames, states


def test_detector_ignores_same_color_distractor_outside_annulus():
    geometry, detector, _, _ = _pipeline()
    angle = 0.7
    frame = render_wheel_frame(
        geometry,
        angle,
        width=320,
        height=180,
        marker_radius_ratio=0.75,
        marker_size_px=6,
        distractor=True,
    )
    detection = detector.detect(frame)
    assert detection is not None
    expected_x = geometry.center_x_px + geometry.radius_px * 0.75 * math.cos(angle)
    expected_y = geometry.center_y_px + geometry.radius_px * 0.75 * math.sin(angle)
    assert math.hypot(detection.x_px - expected_x, detection.y_px - expected_y) < 1.5


def test_pixel_pipeline_counts_dim_blurred_rotation():
    rotation, detection_rate, states = _run(
        revolutions=5.0,
        rps=2.0,
        brightness=0.35,
        blur_kernel=5,
    )
    assert detection_rate > 0.99
    assert TrackingState.UNCERTAIN not in states
    assert rotation.equivalent_revolutions == pytest.approx(5.0, abs=0.02)


def test_moderate_speed_short_occlusion_recovers_without_losing_rotation():
    rotation, detection_rate, states = _run(
        revolutions=5.0,
        rps=2.0,
        missing_frames=range(30, 33),
    )
    assert detection_rate > 0.95
    assert TrackingState.PREDICTING in states
    assert TrackingState.UNCERTAIN not in states
    assert rotation.equivalent_revolutions == pytest.approx(5.0, abs=0.02)


def test_high_speed_short_occlusion_is_marked_ambiguous_instead_of_wrong_unwrap():
    geometry, detector, rotation, engine = _pipeline()
    fps = 30
    rps = 5.0
    total_frames = 121
    missing = set(range(30, 33))
    signed_before_gap = None
    signed_after_reacquire = None
    saw_uncertain = False

    for index in range(total_frames):
        timestamp = index / fps
        angle = TAU * rps * timestamp
        frame = render_wheel_frame(
            geometry,
            angle,
            width=320,
            height=180,
            marker_size_px=6,
            marker_visible=index not in missing,
            distractor=True,
        )
        detection = detector.detect(frame)
        if index == 29:
            signed_before_gap = rotation.signed_angle_rad
        if detection is None:
            snapshot = engine.process_missing(timestamp)
            saw_uncertain = saw_uncertain or snapshot.tracking_state == TrackingState.UNCERTAIN
        else:
            snapshot = engine.process_marker(
                timestamp,
                detection.x_px,
                detection.y_px,
                detection.score,
            )
            if index == 33:
                signed_after_reacquire = rotation.signed_angle_rad

    assert saw_uncertain
    assert signed_before_gap is not None
    assert signed_after_reacquire is not None
    # Reacquisition must never invent a backwards delta merely because the visible
    # phase crossed more than pi while the marker was hidden.
    assert signed_after_reacquire >= signed_before_gap - 1e-6

    expected_revolutions = (total_frames - 1) / fps * rps
    undercount = expected_revolutions - rotation.equivalent_revolutions
    assert 0.5 < undercount < 1.0
