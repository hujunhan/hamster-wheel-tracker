# Android architecture

Hamster Wheel Tracker is an Android-only application for a dedicated Android 12 phone mounted in front of the wheel.

## Ownership model

The key design decision is that the UI does not own tracking.

`TrackingService` is a started + bound foreground service and owns:

- CameraX `ImageAnalysis` and the optional preview use case;
- OpenCV marker detection;
- wheel/session tracking;
- Room persistence;
- embedded LAN dashboard;
- a partial wake lock while tracking;
- the persistent tracking notification.

`MainActivity` owns only the visible controls, overlay and `PreviewView`. When visible it attaches the preview surface to the service; when stopped it detaches the surface without stopping analysis.

This makes explicit screen-off/background operation a normal runtime state instead of a lifecycle accident.

## Camera pipeline

CameraX uses the Camera2 backend with:

- rear camera;
- approximately 1280×720 requested analysis resolution (the target phone currently selects 1280×960);
- `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`;
- RGBA output for OpenCV processing;
- one dedicated analysis executor.

The measured target-phone rate is approximately 30 FPS.

## Vision

The marker detector converts each frame to HSV and applies:

1. configured hue/saturation/value thresholds;
2. morphology;
3. contour extraction;
4. minimum/maximum contour area filtering;
5. expected radial-annulus filtering around the calibrated wheel center;
6. candidate scoring and selection.

The UI can sample an HSV patch directly from the live marker. The annulus constraint is intentionally strong, so HSV bounds can remain relatively forgiving under phone ISP/AWB changes.

## Tracking

The accepted marker centroid becomes an angular observation:

```text
angle = atan2(markerY - centerY, markerX - centerX)
```

`WheelTracker` unwraps plausible inter-frame motion, accumulates absolute angular travel, computes speed, handles short marker gaps, and enters `UNCERTAIN` when a gap could conceal more than an unambiguous phase change.

The safety rule is important: when hidden motion cannot be inferred uniquely, the tracker reacquires phase without inventing revolutions. This can under-count an ambiguous interval, but cannot fabricate distance.

`SessionTracker` groups moving intervals into user-visible running sessions using hysteresis/pause handling.

## Persistence

`TrackingRecorder` receives tracker snapshots and converts cumulative tracker state into additive one-second buckets on a dedicated persistence executor.

Room stores:

- one-second activity samples;
- completed sessions.

Stored metrics include distance, equivalent revolutions, moving duration, uncertainty duration and maximum speed. A lifecycle or wall-clock discontinuity does not manufacture activity across the gap.

Reporting uses local 18:00 → next-day 18:00 night boundaries.

## Dashboard

An embedded NanoHTTPD server listens on port 8080 while tracking is active. It serves a read-only, self-contained mobile web dashboard over the LAN. No cloud service is required.

The server exposes current-night summary/live state, hourly activity, sessions and recent-night history. Camera frames are not stored or served.

## Power and lifecycle

While tracking:

- Android foreground-service notification remains visible;
- service type is `camera`;
- a partial wake lock keeps CPU analysis work available while the screen is off;
- Activity screen timeout is disabled while the UI is visible;
- stopping tracking releases camera use cases, wake lock, persistence worker and dashboard server.

For the dedicated device, Android battery optimization should be set to Unrestricted and the phone is expected to remain plugged in overnight.
