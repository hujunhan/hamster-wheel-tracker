# Android Product

This directory contains the primary Android implementation of Hamster Wheel Tracker for the dedicated Android 12 phone.

## Current runtime

```text
CameraX ImageAnalysis (foreground service)
        ↓
OpenCV HSV marker detector
        ↓
Wheel tracker
        ↓
Room/SQLite 1-second activity + sessions
        ↓
LAN dashboard on :8080
```

The visible `MainActivity` owns only the preview/calibration UI. Long-running analysis is owned by `TrackingService`, so backgrounding the UI or turning the screen off does not intentionally stop tracking.

## Target device and toolchain

- Android 12 target/minimum: API 31
- compile SDK 34
- Android Gradle Plugin 8.5.2
- Gradle 8.7
- Kotlin 1.9.24
- JDK 17
- CameraX 1.4.2
- OpenCV 4.10.0
- Room 2.6.1

`compileSdk = 34` is only the build API surface; the dedicated phone remains Android 12 / target API 31.

## Always-on behavior

When **Start tracking** is active:

- a camera foreground service owns `ImageAnalysis`;
- Android shows a persistent `Hamster wheel tracking` notification;
- a partial wake lock keeps CPU analysis alive while the screen is off;
- the Activity uses `FLAG_KEEP_SCREEN_ON`, so normal screen timeout is disabled while the UI is visible;
- manually locking the phone/backgrounding the app leaves tracking in the foreground service;
- the notification has a **Stop** action that releases camera analysis, recorder, dashboard server, executor, and wake lock;
- Room history survives Activity/process recreation;
- the LAN dashboard remains available while the tracking service is active.

The phone is intended to remain plugged in overnight. For the dedicated Motorola device, also set Android battery usage for Hamster Wheel Tracker to **Unrestricted** if the OS offers that option; OEM battery policy can otherwise be more aggressive than standard Android foreground-service behavior.

No camera images or video are stored by default.

## Run on the Motorola phone

1. Open `android/` in Android Studio.
2. Use Gradle wrapper 8.7 and JDK 17.
3. Enable USB debugging and connect the Android 12 phone.
4. Run the `app` configuration and grant camera permission.
5. Confirm the persistent tracking notification appears.
6. Confirm the debug UI shows analysis near the expected FPS and a LAN dashboard URL.
7. Lock the screen for several minutes, unlock, and verify frame/tracker counters continued rather than restarting from zero.
8. For overnight acceptance, leave it plugged in and verify Room/dashboard history the next morning.

## Dashboard

While tracking is active, the app shows a URL similar to:

```text
http://192.168.1.123:8080/
```

Open it from another phone/tablet/computer on the same Wi-Fi to view current-night and historical statistics.

## Command-line build

With JDK 17 and Android SDK 34 installed:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

See [`../docs/android.md`](../docs/android.md) for the larger Android architecture.
