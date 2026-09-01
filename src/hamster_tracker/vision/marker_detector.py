from dataclasses import dataclass
from typing import Optional, Tuple

from .geometry import WheelGeometry


@dataclass(frozen=True)
class MarkerDetection:
    x_px: float
    y_px: float
    area_px: float
    radial_distance_px: float
    score: float


@dataclass(frozen=True)
class MarkerDetectorConfig:
    hsv_lower: Tuple[int, int, int]
    hsv_upper: Tuple[int, int, int]
    expected_radius_px: float
    radius_tolerance_px: float
    min_area_px: float = 30.0
    max_area_px: float = 5000.0
    morphology_kernel: int = 3


class MarkerDetector:
    """HSV blob detector constrained to the marker's expected wheel annulus."""

    def __init__(self, geometry: WheelGeometry, config: MarkerDetectorConfig):
        self.geometry = geometry
        self.config = config

    def detect(self, bgr_frame) -> Optional[MarkerDetection]:
        try:
            import cv2
            import numpy as np
        except ImportError as exc:  # pragma: no cover - depends on target environment
            raise RuntimeError(
                "MarkerDetector requires OpenCV and NumPy. Install the 'vision' extra "
                "for desktop development, or use the Jetson system OpenCV build."
            ) from exc

        hsv = cv2.cvtColor(bgr_frame, cv2.COLOR_BGR2HSV)
        lower = np.array(self.config.hsv_lower, dtype=np.uint8)
        upper = np.array(self.config.hsv_upper, dtype=np.uint8)
        mask = cv2.inRange(hsv, lower, upper)

        kernel_size = max(1, int(self.config.morphology_kernel))
        if kernel_size > 1:
            kernel = np.ones((kernel_size, kernel_size), dtype=np.uint8)
            mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
            mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)

        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        best = None

        for contour in contours:
            area = float(cv2.contourArea(contour))
            if area < self.config.min_area_px or area > self.config.max_area_px:
                continue

            moments = cv2.moments(contour)
            if moments["m00"] == 0:
                continue

            x = float(moments["m10"] / moments["m00"])
            y = float(moments["m01"] / moments["m00"])
            radial = self.geometry.radial_distance_px(x, y)
            radial_error = abs(radial - self.config.expected_radius_px)
            if radial_error > self.config.radius_tolerance_px:
                continue

            radial_score = 1.0 - radial_error / max(self.config.radius_tolerance_px, 1e-6)
            area_score = min(1.0, area / max(self.config.min_area_px * 4.0, 1.0))
            score = 0.8 * radial_score + 0.2 * area_score

            detection = MarkerDetection(x, y, area, radial, score)
            if best is None or detection.score > best.score:
                best = detection

        return best
