#!/usr/bin/env python3
import argparse
import math

from hamster_tracker.sim.frames import render_wheel_frame
from hamster_tracker.tracking.engine import TrackerEngine
from hamster_tracker.tracking.rotation_tracker import RotationTracker
from hamster_tracker.tracking.session_tracker import SessionTracker
from hamster_tracker.vision.geometry import TAU, WheelGeometry
from hamster_tracker.vision.marker_detector import MarkerDetector, MarkerDetectorConfig


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run the real HSV detector + tracker against rendered camera frames."
    )
    parser.add_argument("--revolutions", type=float, default=100.0)
    parser.add_argument("--rps", type=float, default=5.0)
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=720)
    parser.add_argument("--brightness", type=float, default=0.50)
    parser.add_argument("--blur", type=int, default=0)
    parser.add_argument("--noise", type=float, default=0.0)
    parser.add_argument(
        "--occlusion-frames",
        type=int,
        default=1,
        help="number of hidden frames for each periodic synthetic occlusion",
    )
    parser.add_argument(
        "--occlusion-every",
        type=int,
        default=120,
        help="insert an occlusion every N frames; zero disables occlusions",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.revolutions <= 0 or args.rps <= 0 or args.fps <= 0:
        raise SystemExit("revolutions, rps, and fps must be positive")

    center_x = args.width / 2.0
    center_y = args.height / 2.0
    wheel_radius = min(args.width, args.height) * 0.375
    geometry = WheelGeometry(center_x, center_y, wheel_radius)
    marker_radius = wheel_radius * 0.75
    detector = MarkerDetector(
        geometry,
        MarkerDetectorConfig(
            hsv_lower=(40, 80, 50),
            hsv_upper=(80, 255, 255),
            expected_radius_px=marker_radius,
            radius_tolerance_px=wheel_radius * 0.12,
            min_area_px=30.0,
            max_area_px=5000.0,
            morphology_kernel=3,
        ),
    )
    rotation = RotationTracker(geometry)
    engine = TrackerEngine(rotation, SessionTracker(), max_short_gap_s=0.20)

    total_frames = round(args.revolutions / args.rps * args.fps) + 1
    detections = 0
    uncertain_frames = 0

    for index in range(total_frames):
        timestamp = index / args.fps
        angle = TAU * args.rps * timestamp
        hidden = False
        if args.occlusion_every > 0 and index > 0:
            phase = index % args.occlusion_every
            hidden = phase < args.occlusion_frames

        # Slowly vary illumination around the requested level so a run exercises
        # thresholding rather than seeing one perfectly constant synthetic value.
        brightness = args.brightness * (0.82 + 0.18 * (0.5 + 0.5 * math.sin(index * 0.05)))
        frame = render_wheel_frame(
            geometry,
            angle,
            width=args.width,
            height=args.height,
            marker_radius_ratio=0.75,
            marker_size_px=max(5, int(round(wheel_radius * 0.045))),
            marker_visible=not hidden,
            brightness=brightness,
            blur_kernel=args.blur,
            noise_sigma=args.noise,
            distractor=True,
            noise_seed=index,
        )
        detection = detector.detect(frame)
        if detection is None:
            snapshot = engine.process_missing(timestamp)
        else:
            detections += 1
            snapshot = engine.process_marker(
                timestamp,
                detection.x_px,
                detection.y_px,
                detection.score,
            )
        if snapshot.tracking_state.value == "UNCERTAIN":
            uncertain_frames += 1

    expected_revolutions = (total_frames - 1) / args.fps * args.rps
    error_revolutions = rotation.equivalent_revolutions - expected_revolutions
    error_percent = 100.0 * error_revolutions / expected_revolutions
    expected_distance_m = expected_revolutions * geometry.circumference_m

    print("Synthetic pixel-level vision stress test")
    print("  resolution:         {}x{}".format(args.width, args.height))
    print("  frames:             {}".format(total_frames))
    print("  detection rate:     {:.3f}%".format(100.0 * detections / total_frames))
    print("  uncertain frames:   {}".format(uncertain_frames))
    print("  expected rev:       {:.6f}".format(expected_revolutions))
    print("  measured rev:       {:.6f}".format(rotation.equivalent_revolutions))
    print("  revolution error:   {:+.6f} ({:+.4f}%)".format(error_revolutions, error_percent))
    print("  expected distance:  {:.6f} m".format(expected_distance_m))
    print("  measured distance:  {:.6f} m".format(rotation.total_distance_m))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
