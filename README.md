# Hamster Wheel Tracker

A vision-based hamster wheel activity tracker that measures wheel motion from a single colored marker.

**Android is now the primary deployment target.** An unused Motorola phone provides the camera, compute, storage, battery backup, Wi-Fi, and user interface in one device. The existing Python implementation remains in this repository as a **reference implementation, simulator, test oracle, and optional Linux backend**.

The project is intended to be useful as a real home monitoring tool first, while also remaining a clean computer-vision / embedded-systems portfolio project.

## Current Direction

```text
                    Hamster Wheel Tracker
                           |
             +-------------+-------------+
             |                           |
       Android product              Python reference
             |                           |
     CameraX / Camera2              simulation / tests
     Kotlin app/runtime             OpenCV reference CV
     native calibration            SQLite + FastAPI tools
     foreground service            synthetic stress tests
     Room / SQLite
             |
      tracker behavior
     validated against the
       reference stack
```

Platform status:

- **Android / Motorola phone — primary product path**
- **Python — active reference/simulation/debug stack**
- **Jetson/Linux — optional/deferred backend; existing work is preserved**

The platform pivot does not change the core tracking model. Wheel geometry, marker detection rules, phase ambiguity handling, session semantics, reporting-night statistics, and synthetic test cases remain valid.

## Why Android

For this workload, a spare Android phone is a strong embedded-vision platform:

- integrated and vendor-tuned camera/ISP
- sufficient CPU for classical CV at 720p-class analysis resolution
- built-in Wi-Fi, storage, display, battery, and power management
- no separate CSI camera, SD card, Wi-Fi adapter, or JetPack compatibility requirement
- native UI can combine tracking, calibration, history, and diagnostics

The main Android-specific engineering challenges are camera lifecycle/control and reliable long-running foreground-service behavior rather than compute performance.

## Physical Setup

- Fixed 9-inch wheel (228.6 mm nominal diameter)
- Camera faces the circular side of the wheel approximately head-on
- Camera optical axis approximately parallel to the wheel rotation axis
- Wheel occupies most of the analysis frame while remaining fully visible
- One high-saturation colored marker on the camera-visible side
- Recommended marker-center radius: roughly **0.75 × wheel radius**
- Stable low-to-moderate nighttime lighting

The effective running diameter remains configurable because the hamster's physical running path may differ slightly from the nominal plastic-wheel diameter.

## Tracking Principle

For every trusted marker centroid `(mx, my)`, calculate its angle around the calibrated wheel center `(cx, cy)`:

```text
angle = atan2(my - cy, mx - cx)
```

A full revolution is **not required**. Partial rotation contributes immediately.

Running distance uses total angular travel rather than net rotation:

```text
total angular travel = sum(abs(delta_angle))
distance = effective_radius * total angular travel
```

Forward and backward motion therefore both contribute to physical running distance.

### Ambiguous marker loss

The tracker explicitly distinguishes:

```text
SEARCHING
TRACKING
PREDICTING
UNCERTAIN
```

Short gaps may preserve phase when the motion remains unambiguous. If a hidden interval can cross a half-turn phase boundary, or the gap becomes otherwise ambiguous, the tracker enters `UNCERTAIN` and reacquires without inventing hidden revolutions.

The project deliberately prefers **explicit undercounting over fabricated distance**.

## Product Pipeline

The Android target is:

```text
CameraX / Camera2
  -> analysis frame
  -> HSV marker segmentation
  -> morphology / candidates
  -> wheel-annulus validity checks
  -> marker centroid
  -> angular tracker + uncertainty state machine
  -> speed / sessions / one-second aggregation
  -> Room / SQLite
  -> native Android dashboard + calibration UI
```

No neural network is required for the MVP.

## Android Architecture

The Android application/runtime layer will be Kotlin.

The current preferred core strategy is a narrow C++17/JNI boundary for reusable vision/tracking logic where it provides clear value, while keeping camera lifecycle, foreground service, persistence, and UI native to Android. This is a preference rather than a hard constraint: deterministic parity with the reference implementation matters more than maximizing native-code percentage.

See [`docs/android.md`](docs/android.md) for the implementation plan.

### Android roadmap

- [#14](https://github.com/hujunhan/hamster-wheel-tracker/issues/14) — CameraX app scaffold and frame pipeline
- [#15](https://github.com/hujunhan/hamster-wheel-tracker/issues/15) — real-camera marker detection and calibration
- [#16](https://github.com/hujunhan/hamster-wheel-tracker/issues/16) — tracker-core port and cross-platform parity
- [#17](https://github.com/hujunhan/hamster-wheel-tracker/issues/17) — foreground tracking service and Room persistence
- [#18](https://github.com/hujunhan/hamster-wheel-tracker/issues/18) — native activity dashboard and history UI

## Reusable Python Reference Stack

The existing implementation under `src/hamster_tracker/` remains intentionally active. It provides:

- wheel geometry and wrapped angular differences
- HSV marker detector with expected-annulus filtering
- partial-rotation and direction-aware distance tracking
- safe phase reinitialization and uncertainty handling
- session hysteresis and short-pause grouping
- one-second activity aggregation with exact moving duration
- SQLite persistence and summary/history queries
- FastAPI dashboard and browser calibration reference UI
- coordinate-level synthetic trajectories
- pixel-level synthetic BGR frame rendering
- OpenCV detector/tracker regression tests

It is not throwaway prototype code. It serves as a behavioral oracle for the Android implementation and remains useful for algorithm experiments and failure-mode reproduction.

## Run the Reference Tests

Install the development dependencies and run the hardware-independent suite:

```bash
python -m pip install -e ".[dev]"
python -m pytest -q
```

Run the synthetic night simulation:

```bash
python scripts/simulate_night.py --overwrite
```

Run the pixel-level OpenCV stress simulation:

```bash
python -m pip install -e ".[vision]"
python scripts/simulate_vision.py
```

The pixel-level path renders actual BGR frames and exercises:

```text
BGR frame
  -> HSV
  -> threshold / morphology
  -> contour centroid
  -> annulus filtering
  -> TrackerEngine
```

This test infrastructure has already exposed a real high-speed marker-dropout phase-ambiguity bug and now protects that behavior with regression tests.

## Reference Dashboard

The FastAPI dashboard remains useful for synthetic-data inspection and algorithm debugging:

```bash
HAMSTER_TRACKER_DB=data/synthetic-night.db \
HAMSTER_TRACKER_CONFIG=data/dev-config.json \
uvicorn hamster_tracker.web.app:app --host 0.0.0.0 --port 8000
```

Then open:

```text
http://<computer-ip>:8000
http://<computer-ip>:8000/calibration
```

The reference dashboard includes current-night distance/moving time, equivalent revolutions, average/max speed, hourly activity, speed timeline, sessions, history, and explicit uncertainty warnings.

A reporting **night** currently runs from local **18:00 to 18:00 the next day** so overnight hamster activity is not split at midnight.

## Metrics

- Total distance
- Equivalent revolutions
- Running / moving time
- Average running speed
- Maximum speed
- Hourly activity/distance
- Speed/activity timeline
- Running sessions
- Longest session
- Per-session distance, duration, average speed, and maximum speed
- Tracking uncertainty intervals
- Forward/backward wheel motion where reliably detectable

## Repository Roles

```text
android/
  Android product implementation (starts with roadmap/scaffold docs)

src/hamster_tracker/
  Python reference implementation and debug backend

scripts/
  simulations, stress tests, and reference tooling

tests/
  Python reference/regression tests

docs/
  platform-neutral design, Android plan, and preserved Linux/Jetson deployment notes

deploy/
  existing optional Linux/systemd deployment support
```

The Python source is intentionally not moved into a `legacy/` directory: it remains an active reference and CI target.

## Project Status

Completed and merged:

- platform-independent wheel geometry and rotation model
- partial-distance and direction-reversal accounting
- marker-loss/uncertainty state machine
- sessions and exact moving-duration aggregation
- SQLite statistics/history model
- reference FastAPI dashboard and browser calibration flow
- synthetic trajectory testing
- pixel-level OpenCV vision simulation and regression CI
- optional Linux/systemd runtime/deployment layer

Primary next step: **Android M0 / #14 — create the Kotlin app and prove CameraX frame analysis on the Motorola phone.**

Jetson-specific camera/runtime issues have been closed as `not planned` for the current roadmap rather than deleted; the implementation and engineering history remain available if that backend is revisited.

See [`docs/design.md`](docs/design.md) for the platform-neutral tracking design and [`docs/android.md`](docs/android.md) for the Android implementation plan.
