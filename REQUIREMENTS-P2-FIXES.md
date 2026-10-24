# Requirements — implement the P2 fixes and version bump (beakon-android)

Source of truth: **`ANALYSIS.md`** in this repo (P2 section + build config). The five P1 fixes
are already implemented; do not redo them.

Implement **all** P2 items below, plus **B-4**. Do **not** implement B-1, B-2 or B-3 — the
reasons are stated below.

## P2-1 — Certificate probe should send SNI

- Create the probe socket with `socketFactory.createSocket(host, port)` so SNI is sent, instead
  of an unconnected socket + manual connect.
- Where the UI explains the probe result, state that hostname verification happens on the real
  request, not in the probe.
- **Acceptance:** probe tests still pass; a test asserting the socket is created host-aware.

## P2-2 — Empty states

- History: when a fetch succeeds with zero points, show an explicit "No points yet" state.
- Map: when there are no points, explain it instead of sitting on osmdroid's default (0,0).
- **Acceptance:** tests for the empty-state decision in the view model / UI state.

## P2-3 — Stop the map re-centring on every refresh

- Centre once on first load. Do not snap the camera away on subsequent updates.

## P2-4 — Use the API's `latest` field

- Keep `latest` in state (`LocationsResponse.latest`, currently dropped) and use it for the
  "latest" marker instead of inferring from list order.
- **Acceptance:** test that a differing order still marks the correct latest point.

## P2-5 — Navigate to Setup on logout

- Logging out must take the user to the setup screen, not strand them on Status.

## P2-6 — Stop notification taps stacking activities

- `singleTask` launch mode on the activity (or `singleTop` + the matching intent flag).

## P2-7 — Persist the last tracking-impairing error

- When notifications are denied, errors currently vanish. Persist the last tracking-impairing
  error in state and show it at app start.

## P2-8 — Don't refetch on every tab entry

- Fetch only when data is absent or older than a TTL. Repeated tab switching must not re-request.

## P2-9 — Sane min values on the fallback fix request

- `minTime`/`minDistance` currently `0`/`0` for up to 20 s. Pass sane values; the first callback
  still wins.

## P2-10 — `SimpleCookieJar` tests

- Cover expiry eviction and seeding, since they decide whether a persisted session survives
  process death.

## P2-11 — Extract and test the tick wiring

- The `recordFix` / `recordAccuracyHold` ordering and notification-text selection should be
  extractable as pure logic and unit-tested. Do not restructure the service beyond what that needs.

## P2-12 — `TrackingStateStore` tests

- Cover `recordResult` success/error transitions and the day-rollover reset.

## P2-13 — Test the untested error branches

- `logout` and the non-success history paths.

## B-4 — Bump the version

- `versionCode` 1 → **2**, `versionName` `1.0` → **1.1**. An update is being published, so the
  version must move.

## Explicitly NOT doing

- **B-1** — the 35-vs-37 mismatch was an error in an earlier brief; 35 is correct. Do not change
  the SDK levels.
- **B-2** — debug-key release signing is deliberate. Keep it.
- **B-3** — leave `security-crypto` where it is; note it, don't bump it.

## Constraints — these must still hold

- `DEFAULT_INTERVAL_SECONDS` stays **90**.
- **No** trust-all `TrustManager`, **no** cleartext.
- `AccuracyGate`: default **30 m**, `acc == null` still **pushes**.
- minSdk 26, compileSdk **35**, targetSdk **35**.

## Do not

- Do not commit or push.
- Do not regress any P1 fix.

## Done when

- `./gradlew test` passes, 0 failures, with a higher count than the P1 pass — report the real count.
- `./gradlew assembleDebug` **and** `./gradlew assembleRelease` both succeed.
- Report the release APK's path, byte size and sha256, and confirm `debuggable=false`.
- Nothing is committed.

Environment:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/hermes/android-sdk
```
