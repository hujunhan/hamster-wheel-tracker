# Android Product

This directory contains the primary Android implementation of Hamster Wheel Tracker.

## Current M0 scope

The first Android milestone intentionally stops at camera acquisition and telemetry:

```text
CameraX Preview
    +
ImageAnalysis (1280x720 target, KEEP_ONLY_LATEST)
    -> frame timestamp / size / FPS diagnostics
```

No marker detection or wheel tracking runs in the Android app yet. Those come in #15 and #16. Keeping the camera source independent is deliberate so real, recorded, or synthetic frames can drive the same downstream detector later.

## Toolchain

- Android Gradle Plugin 9.3.1
- Gradle 9.5.0
- JDK 17
- built-in Kotlin support from AGP 9
- CameraX 1.6.2
- compile SDK 37
- target SDK 36
- minimum SDK 26

`compileSdk` is newer than `targetSdk` deliberately: current AndroidX Core requires API 37 at compile time, while the app has not yet opted into Android 37 target-runtime behavior.

The repository does not check in a Gradle wrapper binary yet. Android Studio can import `android/` as a Gradle project, while CI installs Gradle 9.5.0 explicitly.

## Run on the Motorola phone

1. Open the `android/` directory in Android Studio.
2. Let Gradle sync and install Android SDK 37 if prompted.
3. Enable Developer options and USB debugging on the phone.
4. Connect the phone by USB and accept the debugging authorization dialog.
5. Select the Motorola device and run the `app` configuration.
6. Grant camera permission.

The debug screen should show:

- live rear-camera preview
- actual `ImageAnalysis` FPS
- actual analysis frame width/height
- total analyzed frame count
- latest/max inter-frame gap
- flash availability and exposure-compensation range
- a Pause/Resume Analysis button while preview stays active

CameraX uses `STRATEGY_KEEP_ONLY_LATEST`. CameraX does not expose an exact count of internally dropped frames, so the UI reports measured frame gaps instead of inventing a drop count.

## Command-line build

With JDK 17, Android SDK 37, and Gradle 9.5 installed:

```bash
gradle -p android :app:testDebugUnitTest :app:assembleDebug
```

The Android GitHub Actions job executes the same test/build path and uploads the debug APK as an artifact.

## Physical acceptance still required

CI can prove that the project compiles and unit tests pass, but #14 stays open until the real Motorola phone confirms:

- preview and analysis work on-device
- practical measured FPS/resolution
- 30-minute camera/analyzer soak test
- no analyzer stall/leak
- screen off/on lifecycle does not corrupt state

Long-running camera use while the app is backgrounded belongs to #17 (foreground tracking service), not this milestone.

See [`../docs/android.md`](../docs/android.md) for the larger Android architecture.
