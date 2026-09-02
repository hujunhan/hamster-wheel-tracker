# Hamster Wheel Tracker Design

## 1. Product Definition

Hamster Wheel Tracker is a local, camera-based activity monitor for a fixed hamster wheel. A camera observes a colored wheel marker, the tracker converts marker motion into wheel rotation, activity metrics are stored locally, and the user can inspect current and historical activity.

The primary product platform is now **Android**. The existing Python implementation remains the reference/simulation stack, and Linux/Jetson support is optional/deferred.

The design optimizes for:

1. reliable overnight use
2. simple physical setup
3. explainable classical computer vision
4. low compute/storage requirements
5. explicit uncertainty rather than fabricated precision
6. deterministic behavior that can be validated across implementations

The MVP does not require hamster detection, pose estimation, object detection, neural networks, cloud services, or video recording.

## 2. Platform Boundaries

The tracker should be split at the frame/observation boundary rather than around a specific operating system.

```text
FrameSource
   -> MarkerDetector
   -> marker observation / missing marker
   -> TrackerCore
   -> activity/session events
   -> Storage
   -> Product UI
```

### Android product

```text
CameraX / Camera2
   -> Android frame analyzer
   -> marker detector
   -> tracker core
   -> Room / SQLite
   -> foreground tracking service
   -> native dashboard / calibration UI
```

### Python reference stack

```text
synthetic frames / trajectories
   -> OpenCV marker detector
   -> TrackerEngine
   -> SQLite
   -> FastAPI reference/debug UI
```

The Python stack is an active behavioral oracle. Android should match its defined tracking semantics through shared deterministic test cases rather than reinterpreting the math independently.

## 3. Physical Geometry

### 3.1 Camera placement

The camera should face the circular side of the wheel as close to head-on as practical.

Desired geometry:

- camera optical axis approximately parallel to the wheel rotation axis
- wheel plane approximately parallel to the image plane
- complete wheel visible with margin
- wheel center reasonably near the frame center
- wheel diameter occupies most of the analysis frame

This keeps circular wheel motion close to circular image motion and minimizes perspective correction.

The Android product should analyze a practical downscaled stream rather than the phone's full sensor resolution unless higher resolution is shown to improve centroid stability.

### 3.2 Marker placement

Recommended marker-center radius:

```text
r_marker ~= 0.75 * R_wheel
```

A practical working range is roughly `0.70–0.85 R`.

Reasoning:

- farther from the center improves angular sensitivity for a given centroid error
- very near the rim increases clipping/occlusion/distortion risk
- the marker should remain inaccessible to the hamster when possible

The marker should be high saturation and visually distinct under the actual nighttime lighting.

## 4. Calibration Model

Store at least:

```text
wheel_center_px: (cx, cy)
wheel_radius_px: R_px
marker_radius_ratio
marker_radius_tolerance_ratio
marker_hsv_lower
marker_hsv_upper
wheel_effective_diameter_mm
optional ROI / frame metadata
```

The product calibration UI should allow the user to:

1. inspect the live camera frame
2. select the wheel center
3. select the wheel edge/radius
4. select/sample the marker color
5. see the valid marker annulus
6. preview accepted/rejected marker candidates
7. inspect detection quality/state
8. save calibration persistently

Calibration should be performed under lighting representative of normal nighttime operation.

The existing FastAPI/browser calibration page remains a useful interaction/reference prototype; Android will provide the primary native calibration flow.

## 5. Marker Detection

Initial detector:

1. obtain/crop the calibrated wheel region
2. convert the analysis frame to HSV
3. threshold within calibrated HSV bounds
4. apply small morphology operations if useful
5. find connected components / contours
6. score candidates
7. select the best valid marker

Candidate filters may include:

- minimum / maximum area
- distance from calibrated wheel center
- expected annulus membership
- optional circularity / compactness
- temporal proximity to recent trusted motion

The annulus constraint is particularly important: a similar-colored object elsewhere in the cage should not become the wheel marker.

The detector must be allowed to return **no marker**. It should never fabricate a centroid merely to keep the tracker alive.

## 6. Angular Tracking

For a detected marker centroid `(mx, my)`:

```text
theta_t = atan2(my - cy, mx - cx)
```

Raw angle is periodic in `[-pi, pi]`, so consecutive trusted observations require local phase unwrapping.

For ordinary consecutive observations:

```text
delta_theta = wrapped_shortest_difference(theta_t, theta_prev)
```

The tracker rejects implausible motion using configured angular-speed limits.

### 6.1 Partial rotations

The tracker never waits for a complete revolution. Every trusted incremental angle contributes immediately.

Example:

```text
0 deg -> 90 deg
```

is 0.25 equivalent revolutions and contributes one quarter of the corresponding wheel travel.

### 6.2 Distance vs net rotation

Two distinct values are useful:

```text
net_angle = sum(delta_theta)
total_angular_travel = sum(abs(delta_theta))
```

Running distance uses total angular travel:

```text
distance = effective_running_radius * total_angular_travel
```

Equivalent revolutions:

```text
equivalent_revolutions = total_angular_travel / (2*pi)
```

Forward/backward motion therefore does not cancel physical running distance.

## 7. Phase Ambiguity and Marker Loss

Tracking states:

```text
SEARCHING
TRACKING
PREDICTING
UNCERTAIN
```

### SEARCHING

No trusted marker history. A valid detection initializes observable phase without adding distance.

### TRACKING

Marker is visible and passes geometry/motion checks. Incremental motion is accumulated.

### PREDICTING

Marker disappears briefly. Preserve recent phase only while reacquisition remains unambiguous.

### UNCERTAIN

The gap is too long, the projected hidden motion can cross a phase ambiguity boundary, or a candidate is otherwise unsafe. When the marker is confidently seen again, reinitialize observable phase **without adding guessed hidden distance**.

### 7.1 High-speed short-gap rule

A short wall-clock gap is not automatically safe.

The synthetic pixel-level pipeline exposed the case where a 5 rev/s wheel at 30 FPS loses only a few frames. The true marker displacement can exceed `pi` radians, while the shortest wrapped angle points in the wrong direction.

Therefore the tracker uses recent accepted angular velocity to detect when projected hidden phase travel reaches the half-turn ambiguity boundary. Such a gap becomes `UNCERTAIN` even if it is shorter than the nominal short-gap timeout.

Core principle:

> Prefer explicit undercounting of an ambiguous hidden interval over silently inventing a backward or extra revolution.

## 8. Effective Wheel Radius

The nominal wheel diameter is 9 inches / 228.6 mm.

Initial configuration:

```text
nominal_diameter_mm = 228.6
```

Physical running distance is more accurately related to the hamster's effective running path than to an arbitrary plastic edge, so the product stores a configurable:

```text
effective_running_diameter_mm
```

## 9. Speed Estimation

Instantaneous angular velocity:

```text
omega = delta_theta / delta_t
```

Linear speed:

```text
v = abs(omega) * effective_running_radius
```

Raw frame-to-frame speed can be noisy. Product-facing speed should use a short smoother such as a median window or EMA.

**Filtering must not change accumulated distance.** Distance remains based on raw accepted angular increments.

## 10. Running State and Sessions

Use speed thresholds plus hysteresis.

Conceptually:

```text
start_running if speed > start_threshold
stop_running if speed < stop_threshold for T seconds
```

A session is a logical burst of activity. Short pauses do not necessarily split sessions.

Initial session-gap default:

```text
session_gap_seconds ~= 10
```

Per-session statistics:

- start timestamp
- end timestamp
- duration
- moving duration
- distance
- equivalent revolutions
- average moving speed
- maximum speed
- optional forward/backward travel when stable

`moving_duration` is based on observed wheel motion rather than the full hysteresis window.

## 11. Storage Model

Do not persist video frames or per-frame records by default.

Tracking runs at camera frame rate; storage uses aggregated samples (initially approximately 1 second).

### activity samples

```text
timestamp
interval_s
moving_duration_s
distance_delta_m
signed_angle_delta_rad
angular_travel_delta_rad
speed_m_s
running
detection_quality
tracking_state
```

### sessions

```text
id
start_time
end_time
duration_s
moving_duration_s
distance_m
equivalent_revolutions
avg_speed_m_s
max_speed_m_s
```

### reporting-night summary

The current reporting window is local `18:00 -> 18:00 next day`, so normal overnight hamster activity is not split at midnight.

Summary fields include:

```text
distance_m
moving_duration_s
equivalent_revolutions
avg_speed_m_s
max_speed_m_s
longest_session_s
session_count
uncertain_duration_s
```

Android will use Room/SQLite. The Python reference implementation uses SQLite directly. Query semantics should remain equivalent where practical.

## 12. Tracker Core Boundary

The important platform-independent interface is conceptually:

```text
process_marker(timestamp, x, y, quality)
process_missing(timestamp)
     -> snapshot / activity / session updates
```

The core owns:

- wheel-angle math
- partial distance
- direction reversal semantics
- plausibility checks
- phase/occlusion state
- session behavior
- aggregation semantics

Camera lifecycle, Android service state, persistence framework, and UI should stay outside this boundary.

### Preferred Android implementation

The preferred initial direction is:

- Kotlin for CameraX/Camera2, foreground service, Room, and UI
- C++17/JNI for reusable/performance-sensitive tracking/vision code where the boundary remains small and testable

This is deliberately not a requirement that every existing Python class be line-for-line ported to C++. Correct behavior and test parity take priority over language purity.

## 13. Synthetic and Cross-Platform Testing

The Python stack supports both coordinate-level trajectories and rendered pixel-level BGR frames.

Synthetic cases include:

- idle periods
- forward rotation
- reverse rotation
- multiple angular speeds
- centroid jitter
- brightness changes
- blur/noise
- same-color distractors
- short marker occlusion
- long ambiguous occlusion
- high-speed phase ambiguity

Android should consume shared deterministic observations/test vectors and reproduce expected outputs within documented tolerance.

Important parity cases:

- quarter revolution
- clockwise/counterclockwise full revolution
- `+pi/-pi` crossing
- forward then backward
- stationary jitter
- irregular timestamps
- implausible jump
- brief dropped frames
- high-speed ambiguous gap
- long marker loss
- session pause grouping
- exact moving duration
- reporting-night aggregation

## 14. Android Runtime

The user explicitly starts tracking from the app.

While tracking:

- camera analysis runs through a camera foreground service
- a persistent notification communicates active tracking state
- the UI may be backgrounded or the screen may be off
- activity is stored continuously in Room/SQLite

The implementation must respect current Android foreground-service/camera restrictions instead of assuming Linux-style boot-time daemon behavior.

A phone normally remains connected to power during overnight tracking.

## 15. Product UI

Android is the primary product UI.

### Tracking/dashboard

- current running/stopped/tracking state
- current/display speed
- current-night distance and moving time
- equivalent revolutions
- average/max speed
- longest session and session count
- hourly activity/distance
- speed/activity timeline
- recent-night history
- session history
- explicit uncertainty warnings

### Calibration/debug

- live camera preview
- wheel center/radius selection
- marker sampling/HSV tuning
- marker annulus overlay
- accepted/rejected candidate overlay
- detector quality/state

The existing FastAPI UI remains a development/reference tool, not the primary product surface.

## 16. Repository Architecture

Current/target roles:

```text
android/
  Android product implementation

src/hamster_tracker/
  Python reference implementation

scripts/
  simulations and reference/debug tools

tests/
  Python reference/regression tests

docs/
  design and platform documentation

deploy/
  preserved optional Linux/systemd backend
```

Do not move the Python stack to `legacy/`: it remains an active reference and CI target.

## 17. Android Roadmap

- #14 — app scaffold + CameraX frame pipeline
- #15 — real-camera marker detector + calibration
- #16 — tracker core port + cross-platform parity
- #17 — foreground service + Room persistence
- #18 — native dashboard + history

Jetson-specific open issues were closed as `not planned` for the current roadmap. They may be reopened if Linux/Jetson becomes a useful secondary target.

## 18. MVP Acceptance Criteria

The Android MVP is successful when:

1. the Motorola phone can acquire/analyze the wheel camera continuously under intended lighting
2. marker detection is stable and calibrated on-device
3. partial rotations contribute correctly to distance
4. direction reversals do not cancel physical running distance
5. short safe gaps do not corrupt rotation count
6. ambiguous gaps are surfaced rather than guessed
7. tracking continues through an overnight screen-off foreground-service run after the user starts it
8. sessions/history persist across process/app restart
9. calibration can be performed from the Android UI without editing source code
10. current and historical metrics are usable directly on the phone
11. Android tracker results match the reference test vectors within documented tolerance

## 19. Non-Goals for MVP

- hamster identity recognition
- pose estimation
- behavior classification beyond wheel activity
- cloud service
- remote public-internet access
- video archive
- CNN / transformer inference
- multiple simultaneous wheels
- automatic boot-time camera tracking that violates Android platform restrictions
