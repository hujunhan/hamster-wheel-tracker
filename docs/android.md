# Android Implementation Plan

Android is the primary product platform for Hamster Wheel Tracker.

The goal is not to rewrite the project from scratch. The existing Python implementation defines the tracking semantics and supplies simulation, reference outputs, failure cases, and debug tooling. Android replaces the deployment/runtime/UI layer and ports the required real-time vision/tracking behavior.

## 1. Responsibilities

### Android / Kotlin

Owns:

- CameraX preview and `ImageAnalysis`
- Camera2 interop when exposure/AWB/focus control is needed and supported
- runtime permissions and lifecycle
- foreground tracking service
- persistent notification / Start / Stop controls
- Room / SQLite persistence
- native calibration/debug UI
- native dashboard/history UI
- app/process recreation behavior

### Tracker / vision core

Owns platform-independent behavior:

- calibrated wheel geometry
- HSV marker candidate selection
- annulus filtering
- angular tracking
- partial-distance accumulation
- direction reversals
- plausibility checks
- `SEARCHING` / `TRACKING` / `PREDICTING` / `UNCERTAIN`
- high-speed gap phase-ambiguity handling
- speed/session semantics

The preferred initial implementation is a small C++17 core behind JNI where that provides clean reuse and performance. This is not a requirement to port every Python class to C++: Kotlin is appropriate for simple orchestration when it keeps the boundary cleaner.

### Python reference stack

Continues to own:

- fast algorithm prototyping
- deterministic coordinate simulations
- rendered BGR synthetic-frame simulations
- regression/failure-mode tests
- reference statistics and expected outputs
- optional FastAPI/SQLite debugging tools

## 2. Target Runtime Flow

```text
Android activity / UI
        |
        +---- calibration/debug
        |
        +---- Start Tracking
                   |
                   v
        camera foreground service
                   |
              CameraX
                   |
             ImageAnalysis
                   |
          marker detector/core
                   |
            tracker snapshot
                   |
          aggregation/sessions
                   |
             Room/SQLite
                   |
        dashboard/history UI
```

The camera source must remain replaceable so recorded or synthetic frames can drive the same analyzer during development.

## 3. Frame Strategy

Do not assume that the phone's full sensor resolution should be processed.

Initial target:

- analysis resolution around 720p class
- 15–30 FPS
- keep only the latest useful frame if processing falls behind
- avoid unnecessary frame copies/conversions

The actual choice should be measured on the Motorola phone using centroid stability, motion blur, CPU load, and sustained thermal behavior.

## 4. Camera Controls

The marker detector benefits more from stable imaging than from sophisticated CV.

Where supported, evaluate:

- exposure/ISO stability
- auto-exposure lock or bounded manual exposure
- white-balance lock or stable AWB behavior
- fixed/appropriate focus
- frame duration / FPS consistency

The product should not assume every Motorola model exposes the same manual-camera capabilities. Calibration/debug UI should reveal enough state to diagnose drift.

## 5. Color/Frame Representation

CameraX `ImageAnalysis` commonly exposes YUV camera frames. The implementation should minimize conversions.

Possible paths:

1. convert the analysis ROI to RGB/BGR/HSV for a straightforward first implementation
2. later optimize conversion/thresholding if profiling shows it matters
3. keep the tracking resolution modest before adding native optimization complexity

Correctness and debuggability are higher priority than early micro-optimization.

## 6. Native Core Decision

Preferred first architecture:

```text
Kotlin CameraX / service / Room / UI
                 |
                JNI
                 |
          C++17 vision/tracker core
```

Benefits:

- clean future reuse on Linux/Raspberry Pi/Jetson if desired
- natural OpenCV/C++ implementation path
- separates Android lifecycle from mathematical tracking behavior

Costs:

- NDK/CMake/JNI build complexity
- Android OpenCV packaging complexity
- debugging across the language boundary

Decision rule: keep JNI narrow. If the native boundary becomes more complex than the tracker itself, implement the simple tracker logic in Kotlin and retain cross-platform test vectors as the source of truth.

## 7. Persistence Mapping

The Python schema/queries provide the semantic reference.

Android will use Room/SQLite for:

### Activity samples

- timestamp
- interval duration
- moving duration
- distance delta
- signed-angle delta
- angular-travel delta
- max/display speed as defined by the model
- running flag
- tracking state
- detection quality

### Sessions

- start/end
- duration
- moving duration
- distance
- equivalent revolutions
- average speed
- maximum speed

### Calibration/config

- frame/wheel geometry
- effective running diameter
- marker annulus
- HSV bounds and detector thresholds
- runtime/calibration metadata as needed

The default reporting night remains local 18:00 -> next-day 18:00 until made configurable.

## 8. Background Behavior

The product model is user-started continuous tracking, not an unrestricted Linux daemon.

Expected flow:

```text
open app
-> Start Tracking
-> camera foreground service + persistent notification
-> screen may turn off / UI may background
-> tracking continues
-> Stop Tracking releases camera/service
```

Do not design around silently starting the camera at boot. Respect Android camera/foreground-service restrictions and make the active camera state visible to the user.

For the intended home setup, the tracking phone normally remains connected to power.

## 9. Cross-Platform Parity

Android should not rely on subjective visual comparison to decide whether the port is correct.

Shared/reference cases should cover:

- quarter turn
- full clockwise/counterclockwise turns
- wrap boundary
- forward + reverse travel
- stationary centroid jitter
- irregular timestamps
- implausible jumps
- short safe occlusion
- high-speed ambiguous occlusion
- long ambiguous occlusion
- session pause grouping
- exact moving-duration aggregation
- reporting-night summaries

The high-speed marker-gap regression discovered by the rendered-frame simulation is mandatory: Android must never interpret an ambiguous >pi hidden phase change as a confident reverse motion.

## 10. Android Milestones

### #14 — CameraX frame pipeline

First usable Android code:

- Gradle/Kotlin project
- live preview
- `ImageAnalysis`
- FPS/size diagnostics
- sustained capture test

### #15 — Real-camera marker detection + calibration

- detector on Motorola frames
- native geometry/HSV calibration
- marker color sampling
- accepted/rejected overlay
- actual nighttime tuning

### #16 — Tracker-core parity

- Android/native implementation of tracker semantics
- deterministic parity cases
- display-speed smoothing
- ambiguity regressions

### #17 — Foreground service + Room

- screen-off/background tracking after explicit user start
- persistent notification
- aggregated storage
- sessions/history
- process-restart persistence

### #18 — Native dashboard/history

- current-night metrics
- hourly/timeline views
- sessions/history
- uncertainty UX
- navigation to calibration/service controls

## 11. Repository Plan

```text
android/
  Android Gradle project (created in #14)

src/hamster_tracker/
  Python reference implementation

scripts/
  reference simulations and stress tools

tests/
  Python reference/regression tests

docs/
  platform-neutral and Android design docs

deploy/
  optional/deferred Linux systemd backend
```

Do not perform a large directory migration just for appearance. Keeping the tested Python package paths stable avoids unnecessary regression risk while Android is being established.

## 12. First Development Checkpoint

The first meaningful Android checkpoint is deliberately small:

1. build/install the app
2. show the real Motorola camera preview
3. receive 720p-class analysis frames at a measured stable rate
4. keep analysis alive for 30 minutes
5. save one debug frame / expose frame metadata if useful

Only after that should real detector tuning dictate the Camera2 exposure/AWB requirements.
