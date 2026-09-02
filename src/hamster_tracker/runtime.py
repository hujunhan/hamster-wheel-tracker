from dataclasses import asdict, dataclass
import os
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Union

from hamster_tracker.config import AppConfig
from hamster_tracker.storage.database import Database


DEFAULT_CONFIG_PATH = "config.json"
DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8000
DEFAULT_LOG_LEVEL = "info"
VALID_LOG_LEVELS = {"critical", "error", "warning", "info", "debug", "trace"}


PathLike = Union[str, os.PathLike]


def _value(explicit: Optional[Any], environment: Mapping[str, str], key: str, default: Any) -> Any:
    if explicit is not None:
        return explicit
    env_value = environment.get(key)
    return env_value if env_value not in (None, "") else default


@dataclass(frozen=True)
class RuntimeSettings:
    """Resolved process settings for the long-running tracker/web service.

    Configuration/calibration data belongs in ``config_path`` while activity history
    belongs in ``database_path``.  Both are resolved to absolute paths so the service
    behaves the same regardless of systemd's working directory.
    """

    config_path: Path
    database_path: Path
    host: str = DEFAULT_HOST
    port: int = DEFAULT_PORT
    log_level: str = DEFAULT_LOG_LEVEL

    @classmethod
    def resolve(
        cls,
        config_path: Optional[PathLike] = None,
        database_path: Optional[PathLike] = None,
        host: Optional[str] = None,
        port: Optional[int] = None,
        log_level: Optional[str] = None,
        environ: Optional[Mapping[str, str]] = None,
    ) -> "RuntimeSettings":
        environment = os.environ if environ is None else environ

        config_value = _value(
            str(config_path) if config_path is not None else None,
            environment,
            "HAMSTER_TRACKER_CONFIG",
            DEFAULT_CONFIG_PATH,
        )
        resolved_config = Path(str(config_value)).expanduser().resolve()
        app_config = AppConfig.load_or_default(str(resolved_config))

        database_value = _value(
            str(database_path) if database_path is not None else None,
            environment,
            "HAMSTER_TRACKER_DB",
            app_config.storage.database_path,
        )
        resolved_database = Path(str(database_value)).expanduser()
        if not resolved_database.is_absolute():
            # Relative storage paths are intentionally anchored to the config file,
            # not the process working directory.  This makes systemd and manual runs
            # use the same database.
            resolved_database = resolved_config.parent / resolved_database
        resolved_database = resolved_database.resolve()

        resolved_host = str(_value(host, environment, "HAMSTER_TRACKER_HOST", DEFAULT_HOST)).strip()
        if not resolved_host:
            raise ValueError("runtime host cannot be empty")

        port_value = _value(port, environment, "HAMSTER_TRACKER_PORT", DEFAULT_PORT)
        try:
            resolved_port = int(port_value)
        except (TypeError, ValueError) as exc:
            raise ValueError("runtime port must be an integer") from exc
        if not 1 <= resolved_port <= 65535:
            raise ValueError("runtime port must be between 1 and 65535")

        resolved_log_level = str(
            _value(log_level, environment, "HAMSTER_TRACKER_LOG_LEVEL", DEFAULT_LOG_LEVEL)
        ).lower()
        if resolved_log_level not in VALID_LOG_LEVELS:
            raise ValueError(
                "runtime log level must be one of {}".format(
                    ", ".join(sorted(VALID_LOG_LEVELS))
                )
            )

        return cls(
            config_path=resolved_config,
            database_path=resolved_database,
            host=resolved_host,
            port=resolved_port,
            log_level=resolved_log_level,
        )

    def prepare(self) -> None:
        """Create/validate persistent runtime state before starting the server."""

        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        self.database_path.parent.mkdir(parents=True, exist_ok=True)

        if self.config_path.exists():
            AppConfig.load(str(self.config_path))
        else:
            config = AppConfig()
            config.storage.database_path = str(self.database_path)
            config.save(str(self.config_path))

        # Opening the database both validates writability and runs schema migrations.
        database = Database(str(self.database_path))
        database.close()

    def doctor(self) -> Dict[str, Any]:
        """Run non-camera deployment checks and return a machine-readable report."""

        report: Dict[str, Any] = {
            "ok": False,
            "config_path": str(self.config_path),
            "database_path": str(self.database_path),
            "host": self.host,
            "port": self.port,
            "log_level": self.log_level,
            "config_valid": False,
            "database_ready": False,
        }
        try:
            self.prepare()
            AppConfig.load(str(self.config_path))
            report["config_valid"] = True
            database = Database(str(self.database_path))
            database.close()
            report["database_ready"] = True
            report["ok"] = True
        except Exception as exc:  # doctor should report rather than hide deployment failures
            report["error"] = "{}: {}".format(type(exc).__name__, exc)
        return report

    def as_dict(self) -> Dict[str, Any]:
        raw = asdict(self)
        raw["config_path"] = str(self.config_path)
        raw["database_path"] = str(self.database_path)
        return raw
