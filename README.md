# Hamster Wheel Tracker

A lightweight computer-vision system that runs on a **Jetson Nano** and measures hamster wheel activity from a single colored marker attached to the wheel.

The project is intended to be useful as a real home monitoring tool first, while also remaining a clean computer-vision / embedded-systems portfolio project.

## MVP Goal

Run continuously on a Jetson Nano, observe a fixed 9-inch hamster wheel with a camera, estimate wheel rotation from a colored marker, store activity statistics, and expose a mobile-friendly dashboard over the local network.

No neural network is required for the MVP. The core tracker uses classical computer vision and wheel geometry.

## Target Hardware

- Jetson Nano
- Camera mounted approximately along the wheel rotation axis
- Fixed 9-inch wheel (228.6 mm nominal diameter)
- One high-saturation colored marker attached to the wheel
- Low-to-moderate continuous nighttime lighting

## Recommended Physical Setup

- Camera faces the **circular side of the wheel head-on**.
- Camera optical axis should be approximately parallel to the wheel rotation axis.
- Wheel center should be near the image center.
- Wheel diameter should occupy roughly 70-80% of the image height.
- Marker should be placed on the camera-visible side at roughly **0.75 × wheel radius** from the center.
- Avoid placing the marker near the hub or exactly on the outer rim.
- Prefer a marker location where the hamster cannot easily touch or obscure it.

## Tracking Principle

For every frame, detect the colored marker centroid `(mx, my)` and calculate its angle relative to the calibrated wheel center `(cx, cy)`:

```text
angle = atan2(my - cy, mx - cx)
```

The tracker unwraps consecutive angles and estimates incremental wheel motion.

A full revolution is **not required**. Partial rotations are measured continuously.

For hamster running distance, the important quantity is total wheel motion:

```text
total angular travel = sum(abs(delta_angle))
distance = effective_radius * total angular travel
```

This intentionally differs from net rotation. For example, if the wheel rotates forward and then backward, both motions contribute to physical running distance.

## MVP Metrics

- Total distance
- Total revolutions / equivalent revolutions
- Running time
- Average running speed
- Maximum speed
- Distance by minute / hour
- Speed timeline
- Running sessions
- Longest continuous session
- Session distance, duration, average speed, and maximum speed
- Forward / backward wheel motion where reliably detectable

## Proposed Pipeline

```text
Camera
  -> ROI / calibrated wheel region
  -> HSV color segmentation
  -> marker centroid
  -> geometric validity checks
  -> angular tracking + unwrap
  -> motion / speed estimation
  -> session tracking
  -> time aggregation
  -> SQLite
  -> FastAPI
  -> mobile dashboard
```

## Calibration

The MVP will include a browser-based calibration page. Initial calibration should support:

1. Selecting the wheel center
2. Selecting the wheel edge / radius
3. Sampling the marker color
4. Previewing the valid marker annulus
5. Verifying live marker detection

Calibration should be performed under lighting similar to actual nighttime operation.

## Robustness Principles

- Do not count only complete revolutions.
- Do not estimate wheel distance by summing marker pixel displacement.
- Reject detections far from the expected marker-radius annulus.
- Treat short marker dropouts as recoverable tracking gaps.
- Do not invent rotation during long ambiguous occlusions.
- Prefer physical camera placement and lighting that reduce ambiguity before adding algorithmic complexity.

## Planned Stack

- Python
- OpenCV
- NumPy
- SQLite
- FastAPI
- HTML / CSS / lightweight JavaScript
- Jetson Nano camera capture stack

React and neural-network inference are intentionally out of scope for the MVP.

## Development Without Camera Hardware

The tracking/storage path can be developed and tested with synthetic marker trajectories before the Jetson camera is available.

```bash
python -m pip install -e ".[dev]"
python -m pytest -q
python scripts/simulate_night.py --overwrite
```

The simulator exercises forward running, stops, direction reversal, pixel jitter, short marker occlusion, and a deliberately ambiguous long occlusion. It passes observations through the same `TrackerEngine`, session logic, one-second aggregation, and SQLite persistence intended for the real camera pipeline.

Start the mobile dashboard against the generated database:

```bash
HAMSTER_TRACKER_DB=data/synthetic-night.db \
uvicorn hamster_tracker.web.app:app --host 0.0.0.0 --port 8000
```

Then open `http://<computer-ip>:8000` from a phone or browser on the same network.

The dashboard currently includes:

- current tracker/running state
- current-night distance and moving time
- equivalent revolutions
- average and maximum speed
- longest session and session count
- distance-by-hour chart
- speed timeline
- session list
- recent-night history
- explicit warning for intervals where tracking was `UNCERTAIN`

A reporting **night** currently runs from local **18:00 to 18:00 the next day**. This prevents a single overnight hamster session from being split at midnight. The rollover hour can become a user setting later.

Useful JSON endpoints include:

```text
GET /api/dashboard
GET /api/dashboard?night=YYYY-MM-DD
GET /api/status
GET /api/history?days=7
GET /api/sessions
GET /api/health
```

## Development Milestones

### M0 - Camera and Geometry

- Reliable Jetson camera capture
- Debug preview
- Wheel ROI / geometry representation

### M1 - Marker Detection

- HSV marker segmentation
- Marker centroid extraction
- Detection quality filtering
- Debug overlay

### M2 - Rotation Tracker

- Angular tracking
- Angle unwrap
- Partial-rotation measurement
- Equivalent revolution count
- RPM / angular speed
- Distance accumulation

### M3 - Robust Tracking and Sessions

- Marker-loss handling
- Short-gap recovery
- Outlier rejection
- Running / stopped state
- Session segmentation

### M4 - Persistence and Statistics

- SQLite schema
- One-second (or similar) aggregation
- Session records
- nightly summaries

### M5 - Calibration and Mobile Web UI

- Mobile current-night dashboard (hardware-independent path implemented)
- Night/session history (hardware-independent path implemented)
- Camera preview
- Interactive calibration

### M6 - Jetson Deployment

- Automatic startup
- Crash restart
- LAN access
- Stable long-running operation

## Project Status

The hardware-independent tracker, synthetic simulator, SQLite persistence, API, and mobile dashboard are implemented on the current feature branch. Camera bring-up, real low-light marker tuning, and browser calibration preview remain hardware-dependent.

See [`docs/design.md`](docs/design.md) for the detailed MVP design and engineering decisions.
