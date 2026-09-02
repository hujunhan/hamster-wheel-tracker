# Tracking design

## Goal

Measure how far and how fast the hamster runs on a physical wheel using one colored marker observed by the Android phone camera.

The design favors robust under-counting over fabricated motion.

## Geometry

Calibration stores the wheel center and radius in resolution-independent coordinates. The wheel radius is normalized by the short frame dimension so calibration remains stable when CameraX selects 1280×960 instead of 1280×720.

The marker is expected to move near a calibrated path radius inside the wheel. A radial tolerance creates an annulus that rejects same-colored objects elsewhere in the frame.

For an accepted marker centroid `(x, y)` and wheel center `(cx, cy)`:

```text
θ = atan2(y - cy, x - cx)
```

For two consecutive valid phases, the basic signed increment is the shortest wrapped difference in `[-π, π)`.

Distance is accumulated from absolute angular travel:

```text
Δs = |Δθ| × R_effective
```

where `R_effective` comes from the configured effective wheel diameter.

Equivalent revolutions are:

```text
revolutions = total_abs_angle / (2π)
```

## Marker detection

The physical marker should be visually distinctive and fixed to the wheel face. The current detector uses HSV color segmentation because the scene geometry is simple and deterministic.

Processing per frame:

1. convert RGBA to HSV;
2. threshold configured HSV range;
3. apply small morphology cleanup;
4. find contours;
5. reject contours outside practical area bounds;
6. reject candidates outside the expected wheel-path annulus;
7. score remaining candidates and choose the best one.

The maximum contour-area bound scales with wheel size so moving the camera closer does not incorrectly reject the same physical sticker.

## Color calibration

The UI can sample an 11×11 patch from the live marker. The sampled median HSV creates intentionally broad bounds. Spatial annulus filtering is the stronger constraint, so the color threshold does not need to be excessively tight.

## Missing observations

A missing marker is normal: blur, hamster occlusion, reflection or threshold variation can hide it briefly.

The tracker preserves recent angular velocity and can bridge short gaps only when the projected hidden travel remains phase-unambiguous.

If a gap could plausibly conceal angular travel of `π` radians or more, a shortest-angle interpretation can alias the true direction/travel. In that case the tracker enters `UNCERTAIN`, discards hidden travel, and safely reinitializes phase when the marker is reacquired.

This means:

- no guessed hidden revolutions;
- no false reverse turn caused by phase aliasing;
- ambiguous intervals can under-count but do not inflate distance.

## Speed

Raw speed is based on accepted angular travel over elapsed tracker time. Display speed can apply stabilization/deadband appropriate for the UI.

Average session speed is based on moving time rather than total wall-clock session duration, so pauses do not artificially depress the value.

## Sessions

Session logic groups motion into running bouts with hysteresis and short-pause tolerance. A completed session records:

- start/end time;
- total duration;
- moving duration;
- distance;
- equivalent revolutions;
- average speed;
- maximum speed.

## Persistence semantics

The camera pipeline produces per-frame cumulative tracker snapshots. `TrackingRecorder` converts deltas between snapshots into one-second additive activity buckets.

Large lifecycle/clock gaps are not back-filled. The next observation establishes a new baseline instead of manufacturing distance or uncertainty for time that was not observed.

The database intentionally stores aggregated metrics, not per-frame images or video.
