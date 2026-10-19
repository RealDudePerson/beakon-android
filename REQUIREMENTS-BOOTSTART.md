# Add "start tracking at boot" to the beakon companion app

Repo: `/home/hermes/projects/beakon-android`. Current state: version 1.0,
compileSdk/targetSdk **35** (Android 15), minSdk 26.

Today the app tracks location in a foreground service
(`tracking/LocationTrackingService`, `foregroundServiceType="location"`,
`START_STICKY`) started by the user. After a device reboot, tracking **stops and
never resumes** — the user has to open the app and start it again. Fix that with a
user-facing setting.

## What to build

### 1. A setting: "Start tracking at boot"
- Persist it in `data/AppPreferences.kt` alongside the existing preferences.
- **Default OFF.** Do not silently opt users into boot-start.
- Expose a toggle in the UI. `ui/screens/StatusScreen.kt` is the natural home
  (it is where tracking state lives); if that screen is not a settings surface, put
  it in `SetupScreen` instead and say which you chose and why.
- The toggle must reflect the persisted value on every recomposition — no local-only
  state that can drift from storage.

### 2. Boot receiver
- Add `android.permission.RECEIVE_BOOT_COMPLETED` to the manifest.
- Add a receiver for `android.intent.action.BOOT_COMPLETED`
  (`android:exported="true"` is required for system broadcasts — that is correct
  here, and only for this action).
- On boot, start `LocationTrackingService` **only if**:
  - the boot-start setting is ON, **and**
  - the app is actually configured (has a server URL and credentials) — starting a
    service that cannot report anything would just produce a permanent failure
    notification.
  Otherwise do nothing, silently and without an error notification.
- Do **not** add `directBootAware`/`LOCKED_BOOT_COMPLETED` — credential-encrypted
  storage is not available then and it would add a failure mode for no benefit.

### 3. Android 15 (SDK 35) foreground-service-from-boot restrictions — VERIFY, DO NOT ASSUME
Android 15 restricts which foreground service types may be started from
`BOOT_COMPLETED`. **Check the actual restriction against the SDK/docs for API 35 and
report what you find with a citation** (SDK source, `android.jar` docs, or the
official behaviour-changes page). Do not assert "location is fine" without evidence.

- If `location` is permitted from boot on API 35 → start the service directly.
- If it is **not** permitted → do not attempt an illegal start. Implement the
  permitted alternative and say which you chose (e.g. post a notification inviting
  the user to resume tracking with one tap). **Never leave the user with a
  background service that silently fails to start.**

### 4. Make the decision testable
A `BroadcastReceiver` cannot be exercised on the JVM, so **extract the decision into
a pure function** — e.g. `shouldStartTrackingOnBoot(settingEnabled: Boolean,
serverUrl: String?, hasCredentials: Boolean): Boolean` — and have the receiver call
it. The unit tests target that function, not the receiver.

## Hard constraints — violating any fails the task

1. **The tracking interval stays 90 seconds.** Do not change it. The server rate
   limits `/api/recordlocation` to 5 requests / 300s per IP, and 90s is the value
   that was deliberately chosen.
2. **TLS rules are non-negotiable**: no trust-all `TrustManager`, no cleartext
   traffic. The existing user-driven SHA-256 fingerprint pinning stays exactly as-is.
3. **Never fail silently.** Any failure path the user could hit must surface
   something actionable. This applies to the boot path too.
4. Do not change the tracking service's core behaviour, the 90s cadence, or the
   existing API calls.
5. Do not touch anything outside this repo.

## Acceptance criteria — run these yourself and report real output

1. **Full test suite passes.** Report the count. It was 33 before this work.
2. **New tests for the pure decision function** covering at minimum:
   - setting ON + configured → true
   - setting ON + **not** configured → false (and no service start)
   - setting OFF + configured → false
   - setting OFF + not configured → false
   Prove at least the first two red-capable (break the function, show red, restore).
3. **The setting persists**: add a test that writing then reading the preference
   round-trips, including the default being OFF when never set. If `AppPreferences`
   needs a `Context`, follow whatever pattern the existing preference tests use; if
   there are none, say so and test the pure part.
4. **Manifest is correct**: report the exact `<uses-permission>` and `<receiver>`
   lines you added, and confirm the receiver's `<intent-filter>` names
   `android.intent.action.BOOT_COMPLETED`.
5. **The APK still builds**: `./gradlew assembleDebug` succeeds. Report the APK path,
   byte size, and sha256.
6. **No regressions**: the app still compiles and the existing 33 tests pass.

## Rules
- Work on branch `feat/boot-start-tracking`. Commit there. Do NOT merge, do NOT
  deploy, do NOT install anything on a device.
- **You cannot test on a real device** — do not claim boot behaviour was observed.
  State plainly that the receiver path is verified by manifest inspection, unit
  tests of the extracted decision, and compilation only.
- Report honestly: what passed, what you could not verify, and anything you chose
  differently and why.
