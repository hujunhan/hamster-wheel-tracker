import math
from typing import Optional, Tuple

from hamster_tracker.vision.geometry import WheelGeometry


def render_wheel_frame(
    geometry: WheelGeometry,
    angle_rad: float,
    width: int = 1280,
    height: int = 720,
    marker_radius_ratio: float = 0.75,
    marker_size_px: int = 12,
    marker_visible: bool = True,
    brightness: float = 1.0,
    blur_kernel: int = 0,
    noise_sigma: float = 0.0,
    distractor: bool = False,
    marker_bgr: Tuple[int, int, int] = (0, 255, 0),
    noise_seed: Optional[int] = None,
):
    """Render a simple synthetic BGR camera frame for end-to-end vision tests.

    The renderer deliberately stays simple. It is not intended to be photorealistic;
    its purpose is to exercise the real HSV detector and tracking pipeline with
    controllable illumination, blur, noise, occlusion, and same-color distractors.
    """
    try:
        import cv2
        import numpy as np
    except ImportError as exc:  # pragma: no cover - optional dev dependency
        raise RuntimeError(
            "Synthetic frame rendering requires OpenCV and NumPy."
        ) from exc

    if width <= 0 or height <= 0:
        raise ValueError("width and height must be positive")
    if marker_size_px <= 0:
        raise ValueError("marker_size_px must be positive")
    if brightness <= 0:
        raise ValueError("brightness must be positive")
    if blur_kernel < 0 or (blur_kernel > 1 and blur_kernel % 2 == 0):
        raise ValueError("blur_kernel must be zero/one or a positive odd integer")
    if noise_sigma < 0:
        raise ValueError("noise_sigma cannot be negative")

    frame = np.full((height, width, 3), 30, dtype=np.uint8)
    center = (int(round(geometry.center_x_px)), int(round(geometry.center_y_px)))
    wheel_radius = int(round(geometry.radius_px))
    cv2.circle(frame, center, wheel_radius, (75, 75, 75), 3)
    cv2.circle(frame, center, max(4, wheel_radius // 12), (90, 90, 90), -1)

    marker_radius = geometry.radius_px * marker_radius_ratio
    if marker_visible:
        x = int(round(geometry.center_x_px + marker_radius * math.cos(angle_rad)))
        y = int(round(geometry.center_y_px + marker_radius * math.sin(angle_rad)))
        cv2.circle(frame, (x, y), marker_size_px, marker_bgr, -1)

    if distractor:
        # Same-color object deliberately placed far outside the expected annulus.
        dx = max(marker_size_px + 2, int(round(width * 0.08)))
        dy = max(marker_size_px + 2, int(round(height * 0.12)))
        cv2.circle(frame, (dx, dy), marker_size_px + 2, marker_bgr, -1)

    if brightness != 1.0:
        frame = np.clip(frame.astype(np.float32) * brightness, 0, 255).astype(np.uint8)

    if noise_sigma > 0:
        rng = np.random.default_rng(noise_seed)
        noise = rng.normal(0.0, noise_sigma, frame.shape).astype(np.float32)
        frame = np.clip(frame.astype(np.float32) + noise, 0, 255).astype(np.uint8)

    if blur_kernel > 1:
        frame = cv2.GaussianBlur(frame, (blur_kernel, blur_kernel), 0)

    return frame
