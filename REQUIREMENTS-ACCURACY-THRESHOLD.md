# REQUIREMENTS — User-set accuracy threshold for pushing location

Repo: `~/projects/beakon-android` (branch **`main`**). Do not change `minSdk`/`targetSdk`.

## Goal

Only push a location fix whose reported accuracy is good enough. The user chooses the
threshold in the app; **default 30 m**.

The app already captures accuracy — `tracking/LocationTrackingService.kt` builds
`LocationReport(acc = if (it.hasAccuracy()) it.accuracy.toDouble() else null, …)` — it just
never acts on it. Nothing server-side changes: the payload already carries `acc`.

## Follow the existing pattern

The reporting interval is the model to copy. It is already implemented as
pref -> ViewModel -> UI, so mirror it exactly rather than inventing a second style:

- `data/AppPreferences.kt` — `intervalSeconds` with `DEFAULT_INTERVAL_SECONDS = 90`,
  `MIN_INTERVAL_SECONDS = 60`, key `interval_seconds`.
- `ui/AppViewModel.kt` — `intervalSeconds` (line ~116) and `setInterval(seconds)` (~125),
  plus the backing property that reads/writes the pref (~371).
- `ui/screens/StatusScreen.kt` — a `Card` with a title, a `bodySmall` explanation, and a `Row`
  of `FilterChip`s (60/90/120/300 s) using `viewModel.intervalSeconds` / `setInterval`.

## Required changes

1. **Preference** — in `AppPreferences.kt`:
   - `accuracyThresholdM: Int`, key `accuracy_threshold_m`.
   - `DEFAULT_ACCURACY_THRESHOLD_M = 30`.
   - Bound it. Pick sensible `MIN`/`MAX` and coerce like `intervalSeconds` does
     (`coerceAtLeast`). Say what you chose and why.

2. **ViewModel** — expose `accuracyThresholdM` and `setAccuracyThreshold(m)`, mirroring the
   interval pair exactly (including the state/StateFlow handling).

3. **UI** — a new `Card` in `StatusScreen.kt` next to the interval card:
   - Title, and a short `bodySmall` explanation that fixes worse than the threshold are held
     back and the map keeps showing the last good position.
   - A `Row` of `FilterChip`s including **30 m as the default**, with a range around it
     (e.g. 10 / 20 / 30 / 50 / 100 m — your choice, but 30 must be there and selected by
     default on a fresh install).

4. **The gate** — in `tracking/LocationTrackingService.kt`, before calling
   `coordinator.report(report)`:
   - Put the decision in a **pure function** in its own small file (e.g.
     `tracking/AccuracyGate.kt`) so it is unit-testable without Android:
     given `acc: Double?` and `thresholdM: Int`, return whether to push.
   - **`acc == null` (the fix reports no accuracy) is PUSHED.** The app cannot judge it, and
     dropping it would silently remove the user from the map entirely. Make this explicit in
     the code comment and cover it with a test.
   - `acc <= threshold` pushes; `acc > threshold` holds.
   - Use `<=` so a fix exactly at the threshold is pushed.

5. **Never fail silently** — this is a standing rule for this project. When a fix is held:
   - The notification must say so with the numbers, e.g.
     `Held: fix accurate to 42 m, threshold 30 m` — not a generic "waiting".
   - The tracking state must record the hold so `StatusScreen` can show it.
   - A user who sets a strict threshold indoors will otherwise watch the map go stale with no
     explanation. That is the failure mode this requirement exists to prevent.

## Tests

- `AccuracyGate`: pushes at/below threshold, holds above, pushes when accuracy is unknown.
  Prove red-capability for the unknown-accuracy case.
- `AppPreferences`: default is 30 when unset; a stored value round-trips; out-of-range values
  are coerced.
- `AppViewModel`: the setter writes through to the pref (match how the interval is tested).
- Existing suite still passes — report the count before and after.

## Do not break

- The interval setting, the boot-start setting, the notification text for the normal path
  ("Location reported successfully.", "Waiting for a fix…"), the rate-limit backoff, and the
  certificate pinning behaviour.
- Do not touch the server or the web UI.

## Build and report
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/hermes/android-sdk ./gradlew assembleDebug`
- Report the APK path, sha256, and the test count before and after.
- **Nothing can be tested on a device** — say so plainly. Whether a real GPS actually reports
  the accuracies you assume is unverified here.

## Honesty
State what you verified versus what you could not, and any deviation and why.
