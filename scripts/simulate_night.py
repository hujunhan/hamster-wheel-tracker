"""Generate a synthetic hamster-wheel night and persist it through the real tracker stack."""

import argparse
from pathlib import Path
import time

from hamster_tracker.sim.trajectory import TrajectoryGenerator, demo_night_segments
from hamster_tracker.storage.database import Database
from hamster_tracker.tracking.engine import TrackerEngine
from hamster_tracker.tracking.rotation_tracker import RotationTracker
from hamster_tracker.tracking.session_tracker import SessionTracker
from hamster_tracker.vision.geometry import WheelGeometry


def build_engine(database: Database) -> tuple:
    geometry = WheelGeometry(
        center_x_px=640.0,
        center_y_px=360.0,
        radius_px=270.0,
        effective_running_diameter_mm=228.6,
    )
    rotation = RotationTracker(
        geometry,
        max_angular_speed_rad_s=45.0,
        angular_deadband_rad=0.008,
    )
    sessions = SessionTracker(
        start_speed_m_s=0.05,
        stop_speed_m_s=0.03,
        stop_hold_seconds=1.0,
        session_gap_seconds=10.0,
    )
    engine = TrackerEngine(
        rotation,
        sessions,
        database=database,
        storage_interval_s=1.0,
        max_short_gap_s=0.20,
    )
    return geometry, engine


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", default="data/synthetic-night.db")
    parser.add_argument("--fps", type=float, default=30.0)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()

    path = Path(args.db)
    if args.overwrite and path.exists():
        path.unlink()

    database = Database(str(path))
    geometry, engine = build_engine(database)
    generator = TrajectoryGenerator(geometry, fps=args.fps, seed=args.seed)
    start_ts = time.time()
    observations = generator.generate(demo_night_segments(), start_ts=start_ts)

    last = None
    for observation in observations:
        if observation.visible:
            last = engine.process_marker(
                observation.timestamp,
                observation.x_px,
                observation.y_px,
                detection_quality=0.95,
            )
        else:
            last = engine.process_missing(observation.timestamp)

    engine.finalize(observations[-1].timestamp)
    summary = database.summary(start_ts, observations[-1].timestamp + 1.0)
    session_rows = database.sessions(start_ts, observations[-1].timestamp + 1.0)

    print("Synthetic night complete")
    print("  database: {}".format(path))
    print("  observations: {}".format(len(observations)))
    print("  stored activity samples: {}".format(database.activity_count()))
    print("  sessions: {}".format(len(session_rows)))
    print("  distance: {:.3f} m".format(summary["distance_m"]))
    print("  equivalent revolutions: {:.2f}".format(summary["equivalent_revolutions"]))
    print("  moving time: {:.1f} s".format(summary["moving_duration_s"]))
    print("  max speed: {:.2f} m/s".format(summary["max_speed_m_s"]))
    if last is not None:
        print("  final tracking state: {}".format(last.tracking_state.value))

    database.close()


if __name__ == "__main__":
    main()
