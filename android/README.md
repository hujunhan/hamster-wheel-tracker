# Android app

This is the only maintained implementation of Hamster Wheel Tracker.

## Target

Dedicated Motorola phone running Android 12 (API 31).

- minSdk 31
- targetSdk 31
- compileSdk 34
- AGP 8.5.2
- Gradle 8.7
- Kotlin 1.9.24
- JDK 17
- CameraX 1.4.2
- OpenCV 4.10.0
- Room 2.6.1

## Runtime

`TrackingService` owns the long-running pipeline:

```text
CameraX ImageAnalysis
  -> OpenCV marker detector
  -> WheelTracker / SessionTracker
  -> one-second TrackingRecorder
  -> Room database
  -> LAN dashboard :8080
```

The foreground service also holds a partial wake lock while tracking. The Activity attaches/detaches the preview surface and provides calibration controls, but does not own the tracking lifetime.

`MainActivity` uses `FLAG_KEEP_SCREEN_ON` while visible. If the user explicitly locks the screen or leaves the Activity, tracking continues in the foreground service.

## Build and run

Open this `android/` directory in Android Studio, select JDK 17, let Gradle sync, connect the phone by USB debugging, and run `app`.

Command line:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## Calibration

The UI supports:

- wheel center
- wheel radius
- expected marker path radius
- radial tolerance
- marker HSV sampling
- effective wheel diameter

Calibration is persisted on-device.

## Persistence and dashboard

Room stores one-second additive activity samples and completed sessions. The embedded dashboard summarizes current-night distance, speed/activity timeline, hourly totals, sessions and recent-night history.

No images or video are stored by default.

## Dedicated-device setup

For overnight use:

- keep the phone plugged in;
- set app battery usage to **Unrestricted**;
- keep Wi-Fi connected if the LAN dashboard is needed;
- start tracking and verify the persistent foreground-service notification is visible.

The target phone has completed an overnight screen-off tracking test successfully.
