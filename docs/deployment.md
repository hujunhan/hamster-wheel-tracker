# Dedicated-phone deployment

The product runs directly on the Android phone; there is no Jetson/Raspberry Pi/Python service deployment path.

## One-time phone setup

Target: dedicated Motorola phone running Android 12.

1. Enable Developer options and USB debugging.
2. Build/install the app from Android Studio.
3. Grant camera permission.
4. Connect the phone to the home Wi-Fi used by dashboard viewers.
5. In Android settings, set Hamster Wheel Tracker battery usage to **Unrestricted**.
6. Keep the phone on external power for overnight tracking.

## Start a tracking night

1. Mount the phone so the wheel is approximately front-on and stable.
2. Open the app.
3. Verify the wheel overlay matches the wheel center/radius.
4. Verify the colored marker is detected throughout a manual wheel rotation.
5. Sample marker HSV again if lighting or marker color changed.
6. Confirm the effective wheel diameter.
7. Tap **Start tracking**.
8. Verify the persistent `Hamster wheel counter` notification is present.

The visible Activity keeps the screen awake during setup. After setup, the screen may be explicitly locked: tracking continues in `TrackingService`.

## Verify screen-off operation

A short commissioning test should confirm:

1. note current frame/distance/revolution values;
2. lock the phone for several minutes;
3. rotate the wheel during the locked interval;
4. unlock the phone;
5. confirm distance/revolutions increased;
6. confirm the LAN dashboard remained reachable while the screen was off.

The target Motorola phone has already completed an overnight screen-off run successfully.

## LAN dashboard

While tracking is active, the app starts a read-only HTTP server on port 8080 and displays its LAN URL, typically:

```text
http://192.168.x.x:8080/
```

Open this URL from another phone/computer on the same Wi-Fi network.

If it stops being reachable, first check whether the phone's Wi-Fi address changed, whether the two devices are on the same LAN, and whether tracking is still active.

## Power behavior

The runtime uses an Android foreground service and partial wake lock. This keeps camera analysis/CPU work alive when the display is off, but the dedicated phone should still be configured conservatively:

- battery mode: Unrestricted;
- external power overnight;
- avoid vendor task-killer/cleaner features for this app;
- do not manually Force Stop the app;
- keep Wi-Fi enabled for the dashboard.

## Stopping

Use **Stop tracking** in the app or the **Stop** action in the foreground-service notification.

Stopping releases the camera, wake lock, dashboard server and persistence worker cleanly.

## Updating the app

Pull the latest repository changes and run the Android app again from Android Studio. Room persistence remains in the app's private storage unless the app is uninstalled or its data is cleared.

For normal development/builds use:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Use JDK 17.
