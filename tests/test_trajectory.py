import pytest

from hamster_tracker.sim.trajectory import MotionSegment, TrajectoryGenerator
from hamster_tracker.vision.geometry import WheelGeometry


def test_trajectory_generator_outputs_missing_marker_for_occlusion():
    geometry = WheelGeometry(100, 100, 80)
    generator = TrajectoryGenerator(geometry, fps=10.0, seed=3)
    observations = generator.generate(
        [
            MotionSegment(0.2, 1.0, True, "visible"),
            MotionSegment(0.2, 1.0, False, "hidden"),
        ]
    )
    hidden = [observation for observation in observations if observation.label == "hidden"]
    assert len(hidden) == 2
    assert all(
        not observation.visible
        and observation.x_px is None
        and observation.y_px is None
        for observation in hidden
    )


def test_trajectory_integrates_true_angle():
    geometry = WheelGeometry(100, 100, 80)
    generator = TrajectoryGenerator(geometry, fps=20.0)
    observations = generator.generate([MotionSegment(1.0, 2.5, True)])
    assert observations[-1].true_angle_rad == pytest.approx(2.5)
