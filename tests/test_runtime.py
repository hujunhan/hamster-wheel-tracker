import json
from pathlib import Path

import pytest

from hamster_tracker.cli import main
from hamster_tracker.config import AppConfig
from hamster_tracker.runtime import RuntimeSettings


def test_config_relative_database_path_is_stable(tmp_path):
    config_path = tmp_path / "state" / "config.json"
    config_path.parent.mkdir()
    config = AppConfig()
    config.storage.database_path = "history/tracker.db"
    config.save(str(config_path))

    settings = RuntimeSettings.resolve(config_path=str(config_path), environ={})

    assert settings.config_path == config_path.resolve()
    assert settings.database_path == (config_path.parent / "history/tracker.db").resolve()


def test_environment_overrides_config_storage(tmp_path):
    config_path = tmp_path / "config.json"
    config = AppConfig()
    config.storage.database_path = "ignored.db"
    config.save(str(config_path))

    settings = RuntimeSettings.resolve(
        environ={
            "HAMSTER_TRACKER_CONFIG": str(config_path),
            "HAMSTER_TRACKER_DB": str(tmp_path / "override.db"),
            "HAMSTER_TRACKER_HOST": "127.0.0.1",
            "HAMSTER_TRACKER_PORT": "8123",
            "HAMSTER_TRACKER_LOG_LEVEL": "debug",
        }
    )

    assert settings.database_path == (tmp_path / "override.db").resolve()
    assert settings.host == "127.0.0.1"
    assert settings.port == 8123
    assert settings.log_level == "debug"


def test_prepare_creates_default_config_and_database(tmp_path):
    settings = RuntimeSettings.resolve(
        config_path=str(tmp_path / "state" / "config.json"),
        database_path=str(tmp_path / "state" / "tracker.db"),
        environ={},
    )
    settings.prepare()

    assert settings.config_path.exists()
    assert settings.database_path.exists()
    saved = AppConfig.load(str(settings.config_path))
    assert Path(saved.storage.database_path) == settings.database_path


def test_doctor_command_reports_ready_state(tmp_path, capsys):
    config_path = tmp_path / "config.json"
    database_path = tmp_path / "tracker.db"

    result = main(
        [
            "doctor",
            "--config",
            str(config_path),
            "--database",
            str(database_path),
            "--host",
            "127.0.0.1",
            "--port",
            "9001",
        ]
    )

    assert result == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["ok"] is True
    assert payload["config_valid"] is True
    assert payload["database_ready"] is True
    assert payload["port"] == 9001


@pytest.mark.parametrize("port", [0, 65536])
def test_invalid_port_is_rejected(tmp_path, port):
    with pytest.raises(ValueError):
        RuntimeSettings.resolve(
            config_path=str(tmp_path / "config.json"),
            port=port,
            environ={},
        )
