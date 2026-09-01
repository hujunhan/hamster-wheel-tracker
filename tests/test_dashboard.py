from datetime import datetime

import pytest
from fastapi.testclient import TestClient

from hamster_tracker.storage.database import ActivitySample
from hamster_tracker.tracking.session_tracker import SessionRecord
from hamster_tracker.web.app import _night_bounds, create_app


def ts(year, month, day, hour, minute=0, second=0):
    return datetime(year, month, day, hour, minute, second).timestamp()


def test_night_bounds_roll_over_at_18_local_time():
    start, end = _night_bounds(ts(2026, 9, 2, 2, 30))
    assert datetime.fromtimestamp(start) == datetime(2026, 9, 1, 18, 0)
    assert datetime.fromtimestamp(end) == datetime(2026, 9, 2, 18, 0)

    start, end = _night_bounds(ts(2026, 9, 2, 20, 0))
    assert datetime.fromtimestamp(start) == datetime(2026, 9, 2, 18, 0)
    assert datetime.fromtimestamp(end) == datetime(2026, 9, 3, 18, 0)


def test_dashboard_api_returns_night_summary_hourly_timeline_and_sessions(tmp_path):
    app = create_app(str(tmp_path / "dashboard.db"))
    db = app.state.database
    start = ts(2026, 9, 1, 18, 0)

    db.insert_activity(
        ActivitySample(
            timestamp=start + 60,
            interval_s=1.0,
            distance_delta_m=2.0,
            signed_angle_delta_rad=1.0,
            angular_travel_delta_rad=1.0,
            speed_m_s=0.4,
            running=True,
            tracking_state="TRACKING",
            detection_quality=0.95,
            moving_duration_s=0.5,
        )
    )
    db.insert_activity(
        ActivitySample(
            timestamp=start + 3660,
            interval_s=1.0,
            distance_delta_m=3.0,
            signed_angle_delta_rad=1.5,
            angular_travel_delta_rad=1.5,
            speed_m_s=0.6,
            running=True,
            tracking_state="UNCERTAIN",
            detection_quality=0.4,
            moving_duration_s=0.25,
        )
    )
    db.insert_session(
        SessionRecord(
            start_ts=start + 50,
            end_ts=start + 150,
            duration_s=100.0,
            moving_duration_s=80.0,
            distance_m=4.5,
            equivalent_revolutions=6.2,
            avg_speed_m_s=0.05625,
            max_speed_m_s=0.6,
        )
    )

    with TestClient(app) as client:
        response = client.get("/api/dashboard?night=2026-09-01")
        assert response.status_code == 200
        payload = response.json()
        assert payload["summary"]["distance_m"] == pytest.approx(5.0)
        assert payload["summary"]["moving_duration_s"] == pytest.approx(0.75)
        assert payload["summary"]["max_speed_m_s"] == pytest.approx(0.6)
        assert payload["summary"]["uncertain_duration_s"] == pytest.approx(1.0)
        assert payload["summary"]["session_count"] == pytest.approx(1.0)
        assert payload["summary"]["longest_session_s"] == pytest.approx(100.0)
        assert len(payload["hourly"]) == 24
        assert payload["hourly"][0]["distance_m"] == pytest.approx(2.0)
        assert payload["hourly"][1]["distance_m"] == pytest.approx(3.0)
        assert len(payload["timeline"]) >= 2
        assert any(point["uncertain"] for point in payload["timeline"])
        assert len(payload["sessions"]) == 1


def test_dashboard_html_and_invalid_night(tmp_path):
    app = create_app(str(tmp_path / "web.db"))
    with TestClient(app) as client:
        page = client.get("/")
        assert page.status_code == 200
        assert "Distance this night" in page.text
        assert "Speed timeline" in page.text
        assert "Recent nights" in page.text

        invalid = client.get("/api/dashboard?night=not-a-date")
        assert invalid.status_code == 400


def test_history_endpoint_returns_requested_number_of_nights(tmp_path):
    app = create_app(str(tmp_path / "history.db"))
    with TestClient(app) as client:
        response = client.get("/api/history?days=5")
        assert response.status_code == 200
        rows = response.json()
        assert len(rows) == 5
        assert all("distance_m" in row for row in rows)
