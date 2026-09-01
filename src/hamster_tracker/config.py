from dataclasses import asdict, dataclass, field
import json
from pathlib import Path
from typing import Any, Dict, Tuple


@dataclass
class WheelConfig:
    center_px: Tuple[float, float] = (640.0, 360.0)
    radius_px: float = 270.0
    effective_running_diameter_mm: float = 228.6
    marker_radius_ratio: float = 0.75
    marker_radius_tolerance_ratio: float = 0.12


@dataclass
class MarkerConfig:
    hsv_lower: Tuple[int, int, int] = (40, 80, 80)
    hsv_upper: Tuple[int, int, int] = (80, 255, 255)
    min_area_px: float = 30.0
    max_area_px: float = 5000.0
    morphology_kernel: int = 3


@dataclass
class TrackerConfig:
    max_angular_speed_rad_s: float = 45.0
    angular_deadband_rad: float = 0.008


@dataclass
class SessionConfig:
    start_speed_m_s: float = 0.05
    stop_speed_m_s: float = 0.03
    stop_hold_seconds: float = 1.0
    session_gap_seconds: float = 10.0


@dataclass
class StorageConfig:
    database_path: str = "data/tracker.db"


@dataclass
class AppConfig:
    wheel: WheelConfig = field(default_factory=WheelConfig)
    marker: MarkerConfig = field(default_factory=MarkerConfig)
    tracker: TrackerConfig = field(default_factory=TrackerConfig)
    session: SessionConfig = field(default_factory=SessionConfig)
    storage: StorageConfig = field(default_factory=StorageConfig)

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    def save(self, path: str) -> None:
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(self.to_dict(), indent=2), encoding="utf-8")

    @classmethod
    def load(cls, path: str) -> "AppConfig":
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
        wheel = raw.get("wheel", {})
        marker = raw.get("marker", {})
        tracker = raw.get("tracker", {})
        session = raw.get("session", {})
        storage = raw.get("storage", {})

        if "center_px" in wheel:
            wheel["center_px"] = tuple(wheel["center_px"])
        for key in ("hsv_lower", "hsv_upper"):
            if key in marker:
                marker[key] = tuple(marker[key])

        return cls(
            wheel=WheelConfig(**wheel),
            marker=MarkerConfig(**marker),
            tracker=TrackerConfig(**tracker),
            session=SessionConfig(**session),
            storage=StorageConfig(**storage),
        )
