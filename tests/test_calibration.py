import json

from fastapi.testclient import TestClient

from hamster_tracker.config import AppConfig
from hamster_tracker.web.app import create_app


def test_calibration_page_and_default_config(tmp_path):
    config_path = tmp_path / "tracker-config.json"
    app = create_app(str(tmp_path / "tracker.db"), str(config_path))

    with TestClient(app) as client:
        page = client.get("/calibration")
        assert page.status_code == 200
        assert "Set wheel center" in page.text
        assert "Set wheel edge" in page.text
        assert "Set marker radius" in page.text
        assert "Synthetic preview" in page.text

        response = client.get("/api/calibration")
        assert response.status_code == 200
        payload = response.json()
        assert payload["saved"] is False
        assert payload["camera_available"] is False
        assert payload["config"]["wheel"]["effective_running_diameter_mm"] == 228.6
        assert payload["config"]["wheel"]["marker_radius_ratio"] == 0.75
        assert not config_path.exists()


def test_calibration_save_persists_and_reloads(tmp_path):
    config_path = tmp_path / "tracker-config.json"
    app = create_app(str(tmp_path / "tracker.db"), str(config_path))
    raw = AppConfig().to_dict()
    raw["wheel"]["center_px"] = [501.25, 312.5]
    raw["wheel"]["radius_px"] = 244.75
    raw["wheel"]["effective_running_diameter_mm"] = 228.6
    raw["wheel"]["marker_radius_ratio"] = 0.78
    raw["wheel"]["marker_radius_tolerance_ratio"] = 0.08
    raw["marker"]["hsv_lower"] = [52, 100, 90]
    raw["marker"]["hsv_upper"] = [76, 255, 255]

    with TestClient(app) as client:
        response = client.post("/api/calibration", json=raw)
        assert response.status_code == 200
        assert response.json()["config"]["wheel"]["center_px"] == [501.25, 312.5]
        assert config_path.exists()

        on_disk = json.loads(config_path.read_text())
        assert on_disk["wheel"]["radius_px"] == 244.75
        assert on_disk["marker"]["hsv_lower"] == [52, 100, 90]

        reloaded = client.get("/api/calibration").json()
        assert reloaded["saved"] is True
        assert reloaded["config"]["wheel"]["marker_radius_ratio"] == 0.78
        assert reloaded["config"]["marker"]["hsv_upper"] == [76, 255, 255]


def test_invalid_calibration_is_rejected_without_overwriting_valid_file(tmp_path):
    config_path = tmp_path / "tracker-config.json"
    good = AppConfig()
    good.save(str(config_path))
    original = config_path.read_text()
    app = create_app(str(tmp_path / "tracker.db"), str(config_path))

    invalid = good.to_dict()
    invalid["wheel"]["radius_px"] = -1
    invalid["marker"]["hsv_lower"] = [150, 80, 80]
    invalid["marker"]["hsv_upper"] = [20, 255, 255]

    with TestClient(app) as client:
        response = client.post("/api/calibration", json=invalid)
        assert response.status_code == 400
        assert config_path.read_text() == original


def test_invalid_saved_config_is_reported(tmp_path):
    config_path = tmp_path / "tracker-config.json"
    config_path.write_text('{"wheel":{"radius_px":0}}')
    app = create_app(str(tmp_path / "tracker.db"), str(config_path))

    with TestClient(app) as client:
        response = client.get("/api/calibration")
        assert response.status_code == 500
        assert "invalid saved configuration" in response.json()["detail"]
