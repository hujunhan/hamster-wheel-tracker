# Hamster Wheel Tracker

Android-only hamster wheel activity tracker for a dedicated Android 12 phone.

The phone watches a colored marker on the wheel, estimates angular motion, converts it to distance and speed, stores one-second activity aggregates locally, and serves a read-only dashboard to other devices on the same Wi-Fi network.

## Current status

The Android implementation has been validated on the target Motorola phone, including an overnight run with screen-off/background tracking.

## Stack

- Kotlin / Android Views
- CameraX (`Preview` + `ImageAnalysis`)
- OpenCV 4.10 for HSV marker detection
- wheel rotation/session tracker in Kotlin
- Room / SQLite persistence
- Android foreground service + partial wake lock for overnight tracking
- embedded NanoHTTPD dashboard on port `8080`

The target device is Android 12:

- `minSdk = 31`
- `targetSdk = 31`
- `compileSdk = 34`
- AGP 8.5.2
- Gradle 8.7
- Kotlin 1.9.24
- JDK 17
- CameraX 1.4.2

## Architecture

```text
Rear camera
   ↓
CameraX ImageAnalysis
   ↓
OpenCV HSV marker detector
   ↓
angle = atan2(marker - wheel center)
   ↓
WheelTracker + SessionTracker
   ↓
1-second aggregation
   ↓
Room / SQLite
   ├── local Android status/calibration UI
   └── LAN dashboard :8080
```

`TrackingService` owns the camera-analysis pipeline, tracker, persistence, dashboard and wake lock. `MainActivity` is the preview/calibration/control UI. Tracking therefore continues when the Activity is backgrounded or the screen is explicitly turned off.

## Run on the phone

1. Open `android/` in Android Studio.
2. Use JDK 17 and the project Gradle wrapper (8.7).
3. Connect the Android 12 phone with USB debugging enabled.
4. Run the `app` configuration and grant camera permission.
5. Calibrate wheel center/radius, marker path and marker HSV if needed.
6. Tap **Start tracking**.

For dedicated overnight use, set the app battery mode to **Unrestricted** and normally leave the phone plugged in.

When tracking is active, the app shows the dashboard URL. Open that address from another device on the same Wi-Fi network, for example:

```text
http://192.168.x.x:8080/
```

## Tracking behavior

- Marker position is filtered by HSV color, contour area and expected radial annulus.
- Rotation uses wrapped angular differences.
- Short marker dropouts can recover from recent motion.
- Ambiguous gaps do **not** invent hidden revolutions; the tracker enters `UNCERTAIN` and safely reacquires phase.
- Distance is accumulated from absolute angular travel using the configured effective wheel diameter.
- Data storage is aggregated; camera images/video are not stored by default.
- Reporting nights use local `18:00 → next-day 18:00` boundaries.

## Repository

```text
android/     Android application and Android tests
docs/        architecture, tracking design and deployment notes
.github/     Android CI
```

The original Python prototype has been retired. Git history preserves it if it is ever useful for archaeology, but it is no longer a maintained implementation or test oracle.

See [docs/android.md](docs/android.md), [docs/design.md](docs/design.md), and [docs/deployment.md](docs/deployment.md).
