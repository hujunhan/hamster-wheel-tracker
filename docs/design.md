# MVP Design

## 1. Product Definition

Hamster Wheel Tracker is a local, camera-based activity monitor for a fixed hamster wheel. A Jetson Nano continuously watches a colored wheel marker, converts marker motion into wheel rotation, derives activity metrics, stores them locally, and serves a mobile-friendly dashboard over the home LAN.

The MVP optimizes for:

1. Reliable overnight use
2. Simple physical setup
3. Explainable classical CV
4. Low compute/storage requirements
5. Clean architecture suitable for future extension

The MVP does not require hamster detection, pose estimation, object detection, neural networks, or video recording.

## 2. Physical Geometry

### 2.1 Camera Placement

The camera should face the circular side of the wheel as close to head-on as practical.

Desired geometry:

- camera optical axis approximately parallel to wheel rotation axis
- wheel plane approximately parallel to image plane
- wheel center near image center
- complete wheel visible with margin
- wheel diameter occupies approximately 70-80% of image height

This keeps circular wheel motion close to circular image motion and minimizes the need for perspective correction.

### 2.2 Marker Placement

Recommended marker-center radius:

```text
r_marker ~= 0.75 * R_wheel
```

Reasoning:

- farther from the center gives better angular sensitivity for a given centroid error
- very near the outer rim increases risk of clipping, occlusion, distortion, and mechanical interference
- approximately 0.70-0.85 R is a practical working range

The marker should be high saturation and visually distinct under actual nighttime lighting.

## 3. Calibration Model

Store at least:

```text
wheel_center_px: (cx, cy)
wheel_radius_px: R_px
marker_radius_px: r_marker_px
marker_radius_tolerance_px
marker_hsv_lower
marker_hsv_upper
wheel_effective_diameter_mm
roi
```

The calibration UI should allow the user to:

1. inspect a live camera frame
2. click wheel center
3. click wheel edge
4. click/sample marker color
5. see the expected marker annulus
6. preview accepted/rejected marker detections
7. save calibration

Calibration is done under lighting representative of normal nighttime operation.

## 4. Marker Detection

Initial detector:

1. crop wheel ROI
2. convert BGR/RGB frame to HSV
3. threshold within calibrated HSV bounds
4. apply small morphology operations if needed
5. find connected components / contours
6. score candidates
7. select best valid marker

Candidate filters may include:

- minimum / maximum area
- distance from calibrated wheel center
- expected annulus membership
- optional circularity / compactness
- temporal proximity to predicted location

The annulus constraint is especially valuable because a color match elsewhere in the cage should not be treated as the wheel marker.

## 5. Angular Tracking

For a detected marker centroid `(mx, my)`:

```text
theta_t = atan2(my - cy, mx - cx)
```

Raw angle is periodic in `[-pi, pi]`, so consecutive observations require local phase unwrapping.

For consecutive trusted observations:

```text
delta_theta = wrapped_shortest_difference(theta_t, theta_prev)
```

The tracker should reject implausible jumps using a maximum angular velocity / acceleration model rather than blindly accepting every unwrap.

### 5.1 Partial Rotations

The tracker never waits for a complete revolution. Every valid incremental angle contributes immediately.

Example:

```text
0 deg -> 90 deg
```

represents one quarter of a revolution and contributes one quarter of the corresponding wheel travel.

### 5.2 Distance vs Net Rotation

Two distinct quantities are useful:

```text
net_angle = sum(delta_theta)
total_angular_travel = sum(abs(delta_theta))
```

Running distance uses total angular travel:

```text
distance = effective_running_radius * total_angular_travel
```

Equivalent revolutions can be defined as:

```text
equivalent_revolutions = total_angular_travel / (2*pi)
```

This preserves partial turns and direction reversals.

## 6. Effective Wheel Radius

The nominal wheel diameter is 9 inches / 228.6 mm.

For MVP configuration we can start with:

```text
nominal_diameter_mm = 228.6
```

However, physical running distance is more accurately related to the hamster's effective running path than to an arbitrary plastic edge. Therefore the design should call this a configurable `effective_running_diameter_mm` and allow later measurement/calibration.

## 7. Speed Estimation

Instantaneous angular velocity:

```text
omega = delta_theta / delta_t
```

Linear speed:

```text
v = abs(omega) * effective_running_radius
```

Raw frame-to-frame speed can be noisy. The MVP should support a short temporal smoother such as:

- median over recent samples, or
- short EMA

Filtering must not be applied in a way that changes accumulated distance.

## 8. Marker Loss / Occlusion

Tracking states:

```text
SEARCHING
TRACKING
PREDICTING
UNCERTAIN
```

Suggested behavior:

### SEARCHING

No trusted marker history. Detect a marker candidate and initialize state.

### TRACKING

Marker is visible and passes geometry / motion checks. Accumulate angle and motion.

### PREDICTING

Marker disappears briefly. Preserve the recent phase relationship for a short, configurable interval. If the marker reappears quickly enough that the incremental angle remains unambiguous, continue tracking normally.

### UNCERTAIN

Gap is too long or reacquisition is ambiguous. When a marker is confidently seen again, reinitialize the observable wheel phase **without adding guessed distance** for the hidden interval.

A core principle is: **prefer undercounting an ambiguous interval over silently inventing multiple revolutions.**

## 9. Running State and Sessions

Define `running` using speed thresholds and hysteresis.

Example conceptual thresholds:

```text
start_running if speed > start_threshold
stop_running if speed < stop_threshold for T seconds
```

A session is a logical burst of activity. Short pauses should not necessarily split sessions.

Initial configurable session gap:

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
- forward / backward travel if stable

`moving_duration` is based on observed wheel motion rather than the full hysteresis window, so a pause does not inflate average-speed calculations.

## 10. Storage Model

Do not persist every video frame.

Tracker operates at camera frame rate, while storage uses aggregation intervals (initially 1 second).

Suggested tables:

### activity_samples

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

`moving_duration_s` preserves sub-second activity. For example, if the hamster moves for 0.3 seconds inside a 1-second storage bucket, the database stores 0.3 seconds rather than rounding the bucket up to a full second.

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

### daily stats

```text
date
distance_m
moving_duration_s
equivalent_revolutions
avg_speed_m_s
max_speed_m_s
longest_session_s
session_count
```

SQLite is sufficient for MVP scale.

## 11. Tracker Engine

The hardware-independent `TrackerEngine` is the integration boundary between a marker source and the statistics/storage system.

Conceptually:

```text
marker observation / missing marker
        -> TrackerEngine
             -> RotationTracker
             -> tracking state machine
             -> SessionTracker
             -> 1 s ActivityAggregator
             -> SQLite
```

The eventual camera loop should only need to provide timestamps plus either a trusted marker centroid or a missing-marker event. This keeps camera/Jetson details outside the core motion logic.

## 12. Synthetic Development Path

Before camera hardware is available, the same engine can be driven by synthetic trajectories.

The synthetic generator supports piecewise motion segments with:

- idle periods
- forward rotation
- reverse rotation
- different angular speeds
- centroid jitter
- short marker occlusion
- long ambiguous occlusion

A demo scenario passes these observations through the production rotation/session/aggregation/database path. This makes the mathematical and persistence layers testable without Jetson hardware.

## 13. Web Architecture

Use FastAPI with server-rendered HTML and lightweight JavaScript.

Suggested endpoints:

```text
GET /                     dashboard
GET /calibration          calibration UI
GET /api/status           current tracker state
GET /api/today            current-day/night statistics
GET /api/history          daily history
GET /api/sessions         session history
GET /api/camera/frame     calibration/debug frame
POST /api/calibration     save calibration
```

The UI should be usable from a phone browser on the same LAN.

## 14. Dashboard MVP

Current-night view:

- total distance
- running time
- equivalent revolutions
- average speed
- maximum speed
- longest session
- current running/stopped state
- current speed
- distance-by-hour chart
- speed/activity timeline

History:

- daily distance
- daily running time
- session list

## 15. Suggested Package Layout

```text
src/hamster_tracker/
  camera/
    capture.py
  vision/
    marker_detector.py
    geometry.py
  tracking/
    rotation_tracker.py
    motion_filter.py
    session_tracker.py
    engine.py
  storage/
    aggregation.py
    database.py
    models.py
  sim/
    trajectory.py
  web/
    app.py
    api.py
  config.py

scripts/
  camera_test.py
  marker_test.py
  rotation_test.py
  simulate_night.py

tests/
```

Keep modules small and test the geometry/tracking logic independently of real camera hardware.

## 16. Test Strategy

The rotation tracker and engine should have synthetic tests before relying on the live camera.

Important cases:

- clockwise full revolution
- counterclockwise full revolution
- quarter revolution only
- forward then backward motion
- crossing `+pi/-pi`
- stationary marker with centroid jitter
- brief dropped frames
- implausible marker jump
- long ambiguous marker loss
- different frame intervals
- end-to-end trajectory -> sessions -> SQLite
- pauses do not inflate moving duration
- partial storage buckets preserve exact moving time

## 17. MVP Acceptance Criteria

The MVP is successful when:

1. Jetson Nano can run the tracker continuously overnight.
2. Marker is detected reliably under normal nighttime lighting.
3. Partial wheel rotations contribute correctly to distance.
4. Direction reversals do not cancel physical running distance.
5. Short detection gaps do not routinely corrupt rotation count.
6. Long ambiguous gaps are surfaced rather than silently guessed.
7. Sessions and daily metrics are stored in SQLite.
8. A phone on the same LAN can view current and historical metrics.
9. Calibration can be performed from the browser without editing source code.

## 18. Non-Goals for MVP

- hamster identity recognition
- pose estimation
- behavior classification beyond wheel activity
- cloud service
- remote internet access
- video archive
- React frontend
- CNN / transformer inference
- multi-wheel support
