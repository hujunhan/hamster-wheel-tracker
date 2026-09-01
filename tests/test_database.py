import math

import pytest

from hamster_tracker.storage.database import ActivitySample, Database
from hamster_tracker.tracking.session_tracker import SessionRecord


def test_activity_summary_and_session_persistence():
    db = Database(":memory:")
    db.insert_activity(ActivitySample(100.0, 1.0, 0.3, math.pi, math.pi, 0.3, True))
    db.insert_activity(ActivitySample(101.0, 1.0, 0.2, -math.pi / 2, math.pi / 2, 0.2, True))
    db.insert_activity(ActivitySample(102.0, 1.0, 0.0, 0.0, 0.0, 0.0, False))

    summary = db.summary(99.0, 200.0)
    assert summary["distance_m"] == pytest.approx(0.5)
    assert summary["moving_duration_s"] == pytest.approx(2.0)
    assert summary["equivalent_revolutions"] == pytest.approx(0.75)
    assert summary["avg_speed_m_s"] == pytest.approx(0.25)
    assert summary["max_speed_m_s"] == pytest.approx(0.3)

    db.insert_session(SessionRecord(100.0, 110.0, 10.0, 8.0, 1.5, 2.0, 0.1875, 0.4))
    rows = db.sessions(99.0, 200.0)
    assert len(rows) == 1
    assert rows[0]["distance_m"] == pytest.approx(1.5)
    db.close()


def test_explicit_partial_moving_duration_is_not_rounded_to_full_bucket():
    db = Database(":memory:")
    db.insert_activity(
        ActivitySample(
            timestamp=100.0,
            interval_s=1.0,
            distance_delta_m=0.12,
            signed_angle_delta_rad=1.0,
            angular_travel_delta_rad=1.0,
            speed_m_s=0.4,
            running=True,
            moving_duration_s=0.3,
        )
    )
    summary = db.summary(99.0, 200.0)
    assert summary["moving_duration_s"] == pytest.approx(0.3)
    assert summary["avg_speed_m_s"] == pytest.approx(0.4)
    db.close()
