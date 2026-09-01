from dataclasses import dataclass
import random
from typing import Iterable, List, Optional

from hamster_tracker.vision.geometry import WheelGeometry


@dataclass(frozen=True)
class MotionSegment:
    duration_s: float
    angular_velocity_rad_s: float = 0.0
    visible: bool = True
    label: str = "segment"
    jitter_px: float = 0.0


@dataclass(frozen=True)
class SyntheticObservation:
    timestamp: float
    x_px: Optional[float]
    y_px: Optional[float]
    visible: bool
    true_angle_rad: float
    true_angular_velocity_rad_s: float
    label: str


class TrajectoryGenerator:
    """Generate marker observations from piecewise-constant wheel motion."""

    def __init__(
        self,
        geometry: WheelGeometry,
        marker_radius_ratio: float = 0.75,
        fps: float = 30.0,
        seed: int = 1,
    ):
        if fps <= 0:
            raise ValueError("fps must be positive")
        self.geometry = geometry
        self.marker_radius_px = geometry.radius_px * marker_radius_ratio
        self.fps = fps
        self.random = random.Random(seed)

    def generate(
        self,
        segments: Iterable[MotionSegment],
        start_ts: float = 0.0,
        start_angle_rad: float = 0.0,
    ) -> List[SyntheticObservation]:
        dt = 1.0 / self.fps
        timestamp = start_ts
        angle = start_angle_rad
        observations: List[SyntheticObservation] = []

        for segment in segments:
            if segment.duration_s < 0:
                raise ValueError("segment duration cannot be negative")
            steps = int(round(segment.duration_s * self.fps))
            for _ in range(steps):
                if segment.visible:
                    x_px, y_px = self.geometry.point_at(angle, self.marker_radius_px)
                    if segment.jitter_px > 0:
                        x_px += self.random.gauss(0.0, segment.jitter_px)
                        y_px += self.random.gauss(0.0, segment.jitter_px)
                else:
                    x_px = None
                    y_px = None

                observations.append(
                    SyntheticObservation(
                        timestamp=timestamp,
                        x_px=x_px,
                        y_px=y_px,
                        visible=segment.visible,
                        true_angle_rad=angle,
                        true_angular_velocity_rad_s=segment.angular_velocity_rad_s,
                        label=segment.label,
                    )
                )
                angle += segment.angular_velocity_rad_s * dt
                timestamp += dt

        # Add a final visible observation so motion from the last generated interval
        # is observable by an incremental tracker.
        x_px, y_px = self.geometry.point_at(angle, self.marker_radius_px)
        observations.append(
            SyntheticObservation(
                timestamp=timestamp,
                x_px=x_px,
                y_px=y_px,
                visible=True,
                true_angle_rad=angle,
                true_angular_velocity_rad_s=0.0,
                label="final",
            )
        )
        return observations


def demo_night_segments() -> List[MotionSegment]:
    """Short 'night' scenario exercising pauses, reversals, jitter, and occlusion."""
    return [
        MotionSegment(5.0, 0.0, True, "idle"),
        MotionSegment(18.0, 7.0, True, "run_forward", jitter_px=0.25),
        MotionSegment(3.0, 0.0, True, "short_pause", jitter_px=0.25),
        MotionSegment(12.0, 9.0, True, "run_fast", jitter_px=0.25),
        MotionSegment(0.10, 9.0, False, "brief_occlusion"),
        MotionSegment(8.0, 9.0, True, "resume", jitter_px=0.25),
        MotionSegment(6.0, -5.0, True, "reverse", jitter_px=0.25),
        MotionSegment(14.0, 0.0, True, "long_pause"),
        MotionSegment(10.0, 6.0, True, "second_session", jitter_px=0.25),
        MotionSegment(1.0, 6.0, False, "long_occlusion"),
        MotionSegment(6.0, 6.0, True, "after_long_occlusion", jitter_px=0.25),
        MotionSegment(12.0, 0.0, True, "done"),
    ]
