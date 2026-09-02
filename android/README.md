# Android Product

This directory is reserved for the primary Android implementation of Hamster Wheel Tracker.

The actual Gradle/Kotlin project will be created in [#14](https://github.com/hujunhan/hamster-wheel-tracker/issues/14) rather than checking in an unverified generated scaffold during the roadmap pivot.

Planned responsibilities:

```text
Android app
├── CameraX / Camera2 frame source
├── calibration + detector debug UI
├── tracker-core integration
├── foreground tracking service
├── Room / SQLite persistence
└── activity dashboard + history
```

The existing Python package under `src/hamster_tracker/` remains the reference implementation and simulation/test oracle.

See [`../docs/android.md`](../docs/android.md) for architecture and milestones.
