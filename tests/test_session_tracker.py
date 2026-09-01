import math

import pytest

from hamster_tracker.tracking.session_tracker import SessionTracker


def test_short_pause_stays_in_same_session():
    tracker = SessionTracker(
        start_speed_m_s=0.05,
        stop_speed_m_s=0.02,
        stop_hold_seconds=0.5,
        session_gap_seconds=3.0,
    )

    tracker.update(0.0, 0.2, 0.0, 0.0)
    tracker.update(1.0, 0.2, 0.2, math.pi)
    tracker.update(2.0, 0.0, 0.0, 0.0)
    tracker.update(3.0, 0.0, 0.0, 0.0)
    update = tracker.update(3.5, 0.2, 0.1, math.pi / 2)

    assert update.completed_session is None
    assert update.session_open


def test_long_gap_completes_previous_session():
    tracker = SessionTracker(session_gap_seconds=2.0, stop_hold_seconds=0.5)
    tracker.update(0.0, 0.2, 0.0, 0.0)
    tracker.update(1.0, 0.2, 0.2, math.pi)
    tracker.update(2.0, 0.0, 0.0, 0.0)
    tracker.update(3.0, 0.0, 0.0, 0.0)
    update = tracker.update(4.1, 0.0, 0.0, 0.0)

    assert update.completed_session is not None
    assert update.completed_session.distance_m == pytest.approx(0.2)
    assert update.completed_session.equivalent_revolutions == pytest.approx(0.5)


def test_force_flush_returns_open_session():
    tracker = SessionTracker(session_gap_seconds=10.0)
    tracker.update(0.0, 0.2, 0.0, 0.0)
    tracker.update(1.0, 0.2, 0.2, math.pi)
    session = tracker.flush(1.0, force=True)
    assert session is not None
    assert session.distance_m == pytest.approx(0.2)
