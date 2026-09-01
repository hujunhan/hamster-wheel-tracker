from dataclasses import asdict, dataclass, field
import json
import os
from pathlib import Path
from typing import Any, Dict, Tuple


def _tuple3(value: Any, name: str) -> Tuple[int, int, int]:
    if not isinstance(value, (list, tuple)) or len(value) != 3:
        raise ValueError("{} must contain exactly three values".format(name))
    result = tuple(int(v) for v in value)
    return result  # type: ignore[return-value]


@dataclass
class WheelConfig:
    center_px: Tuple[float, float] = (640.0, 360.0)
    radius_px: float = 270.0
    effective_running_diameter_mm: float = 228.6
    marker_radius_ratio: float = 0.75
    marker_radius_tolerance_ratio: float = 0.12

    def validate(self) -> None:
        if len(self.center_px) != 2:
            raise ValueError("wheel.center_px must contain x and y")
        if self.radius_px <= 0:
            raise ValueError("wheel.radius_px must be positive")
        if self.effective_running_diameter_mm <= 0:
            raise ValueError("wheel.effective_running_diameter_mm must be positive")
        if not 0.05 <= self.marker_radius_ratio <= 1.0:
            raise ValueError("wheel.marker_radius_ratio must be between 0.05 and 1.0")
        if not 0.0 < self.marker_radius_tolerance_ratio <= 0.5:
            raise ValueError("wheel.marker_radius_tolerance_ratio must be in (0, 0.5]")


@dataclass
class MarkerConfig:
    hsv_lower: Tuple[int, int, int] = (40, 80, 80)
    hsv_upper: Tuple[int, int, int] = (80, 255, 255)
    min_area_px: float = 30.0
    max_area_px: float = 5000.0
    morphology_kernel: int = 3

    def validate(self) -> None:
        if not all(0 <= value <= 255 for value in self.hsv_lower + self.hsv_upper):
            raise ValueError("marker HSV values must be between 0 and 255")
        if self.hsv_lower[0] > 179 or self.hsv_upper[0] > 179:
            raise ValueError("OpenCV HSV hue must be between 0 and 179")
        if any(low > high for low, high in zip(self.hsv_lower, self.hsv_upper)):
            raise ValueError("marker.hsv_lower must not exceed marker.hsv_upper")
        if self.min_area_px <= 0 or self.max_area_px <= 0:
            raise ValueError("marker areas must be positive")
        if self.min_area_px > self.max_area_px:
            raise ValueError("marker.min_area_px must be <= marker.max_area_px")
        if self.morphology_kernel < 1 or self.morphology_kernel % 2 == 0:
            raise ValueError("marker.morphology_kernel must be a positive odd integer")


@dataclass
class TrackerConfig:
    max_angular_speed_rad_s: float = 45.0
    angular_deadband_rad: float = 0.008

    def validate(self) -> None:
        if self.max_angular_speed_rad_s <= 0:
            raise ValueError("tracker.max_angular_speed_rad_s must be positive")
        if self.angular_deadband_rad < 0:
            raise ValueError("tracker.angular_deadband_rad cannot be negative")


@dataclass
class SessionConfig:
    start_speed_m_s: float = 0.05
    stop_speed_m_s: float = 0.03
    stop_hold_seconds: float = 1.0
    session_gap_seconds: float = 10.0

    def validate(self) -> None:
        if self.start_speed_m_s < 0 or self.stop_speed_m_s < 0:
            raise ValueError("session speeds cannot be negative")
        if self.stop_speed_m_s > self.start_speed_m_s:
            raise ValueError("session.stop_speed_m_s must be <= session.start_speed_m_s")
        if self.stop_hold_seconds < 0 or self.session_gap_seconds < 0:
            raise ValueError("session timing values cannot be negative")


@dataclass
class StorageConfig:
    database_path: str = "data/tracker.db"

    def validate(self) -> None:
        if not self.database_path:
            raise ValueError("storage.database_path cannot be empty")


@dataclass
class AppConfig:
    wheel: WheelConfig = field(default_factory=WheelConfig)
    marker: MarkerConfig = field(default_factory=MarkerConfig)
    tracker: TrackerConfig = field(default_factory=TrackerConfig)
    session: SessionConfig = field(default_factory=SessionConfig)
    storage: StorageConfig = field(default_factory=StorageConfig)

    def validate(self) -> None:
        self.wheel.validate()
        self.marker.validate()
        self.tracker.validate()
        self.session.validate()
        self.storage.validate()

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    def save(self, path: str) -> None:
        self.validate()
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".tmp")
        temporary.write_text(json.dumps(self.to_dict(), indent=2) + "\n", encoding="utf-8")
        os.replace(str(temporary), str(target))

    @classmethod
    def from_dict(cls, raw: Dict[str, Any]) -> "AppConfig":
        wheel = dict(raw.get("wheel", {}))
        marker = dict(raw.get("marker", {}))
        tracker = dict(raw.get("tracker", {}))
        session = dict(raw.get("session", {}))
        storage = dict(raw.get("storage", {}))

        if "center_px" in wheel:
            center = wheel["center_px"]
            if not isinstance(center, (list, tuple)) or len(center) != 2:
                raise ValueError("wheel.center_px must contain x and y")
            wheel["center_px"] = (float(center[0]), float(center[1]))
        for key in ("hsv_lower", "hsv_upper"):
            if key in marker:
                marker[key] = _tuple3(marker[key], "marker.{}".format(key))

        config = cls(
            wheel=WheelConfig(**wheel),
            marker=MarkerConfig(**marker),
            tracker=TrackerConfig(**tracker),
            session=SessionConfig(**session),
            storage=StorageConfig(**storage),
        )
        config.validate()
        return config

    @classmethod
    def load(cls, path: str) -> "AppConfig":
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise ValueError("configuration root must be an object")
        return cls.from_dict(raw)

    @classmethod
    def load_or_default(cls, path: str) -> "AppConfig":
        target = Path(path)
        return cls.load(path) if target.exists() else cls()
