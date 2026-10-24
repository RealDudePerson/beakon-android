# Requirements — implement the P1 fixes (beakon-android)

Source of truth: **`ANALYSIS.md` in this repo.** Read it first, especially the P1 section.
Implement **exactly these five P1 findings**. Do **not** implement the P2 or build-config
items — a separate pass handles those.

## P1-1 — Guard the tick loop

- No exception may escape the tracking coroutine.
- On failure: record the error in `TrackingStateStore` and surface it in the persistent
  notification. Failures must never be silent.
- The `delay` cadence (90 s) stays unchanged.
- **Acceptance:** a test that makes `runTick` throw proves the loop survives and the error is
  recorded.

## P1-2 — Age-check last-known fixes

- In the non-fused fallback source, reject `getLastKnownLocation` results older than a bound
  (use 2× the configured interval).
- If nothing is fresh, fall through to the live-fix path. If that also yields nothing, report
  `NoFix` and keep the existing "waiting for a fix" message. **Never invent a position.**
- **Acceptance:** unit tests with a fake clock covering fresh / stale / no-fix.

## P1-3 — Honour `Retry-After`

- When a 429 carries `Retry-After`, back off for **at least** that long, time-based against a
  clock. Keep the existing two-tick default when the header is absent.
- Guard against absurd values (cap them).
- **This is backoff duration only.** Do not change the 90 s tracking interval.
- **Acceptance:** tests for header present, absent, and absurd.

## P1-4 — Make Doze a deliberate decision

- Take a short **partial wake lock** around each tick: acquire before location acquisition,
  release after the POST completes (including on failure).
- Document the choice and its battery cost in the README.
- Keep the interval at 90 s. Do **not** add WorkManager or exact alarms.
- **Acceptance:** code review + README section. Real Doze behaviour cannot be verified on this
  machine — state that plainly rather than implying it was tested.

## P1-5 — Reconcile "tracking on" with a dead service

- At app start, if `trackingEnabled` is true but the service is not actually running, show an
  explicit **"Tracking was on but isn't running — tap to resume"** state instead of a green
  toggle.
- **Acceptance:** a test for the decision logic; the UI state must be reachable.

## Constraints — these must still hold

- `DEFAULT_INTERVAL_SECONDS` stays **90**.
- **No** trust-all `TrustManager`, **no** cleartext.
- `AccuracyGate`: default **30 m**, and `acc == null` still **pushes**.
- minSdk 26, compileSdk **35**, targetSdk **35**. Do **not** change these to 37 — the 37 value in
  an earlier brief was an error and `ANALYSIS.md` B-1 records it.
- All **73** existing tests must still pass. Add tests for each fix.

## Do not

- Do not commit or push.
- Do not touch the P2 items.

## Done when

- `./gradlew test` passes with **more than 73** tests and **0 failures** — report the real count.
- `./gradlew assembleDebug` succeeds.
- You report exactly what you changed, the real test numbers, and anything you could not verify.

Environment:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/hermes/android-sdk
```
