# Beakon Companion — analysis and prioritised fix plan

Static analysis of the app in this repo, plus real test/build runs. No application
code was changed and nothing was committed. There is **no device or emulator on
this machine**: every finding below is code-level evidence (`file:line`), and
anything that requires runtime observation is marked *(unverified at runtime)*
rather than implied to work.

## What was actually run

Environment: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`,
`ANDROID_HOME=/home/hermes/android-sdk`.

- **`./gradlew clean test`** — `BUILD SUCCESSFUL`. Parsed the JUnit XML in
  `app/build/test-results/testDebugUnitTest/`: **14 suites, 73 tests,
  0 failures, 0 errors, 0 skipped.** Suite breakdown: AppPreferencesTest 7,
  MockServerIntegrationTest 8, BeakonUrlTest 5, CertificateFingerprintTest 5,
  LocationsResponseDecodeTest 3, PlatformTrustTest 9,
  RecordLocationRequestBuilderTest 5, AccuracyGateTest 5, BootStartDecisionTest
  5, RateLimitBackoffTest 4, TrackingStateStoreTest 2,
  AppViewModelDispatcherTest 8, AppViewModelSettingsTest 3, HistoryListKeyTest 4.
  The mock integration test ran for real (8/8, not skipped — `python3` was
  available; the skip path at
  `app/src/test/java/com/beakon/companion/integration/MockServerIntegrationTest.kt:41`
  was not taken).
- **`./gradlew assembleDebug`** — `BUILD SUCCESSFUL`, producing
  `app/build/outputs/apk/debug/app-debug.apk`, **12,730,558 bytes**, sha256
  `6500092272459d5208368380d3cb5e305a4cc2af72a31a7c0e06e0863a9bfb60`.
  One benign toolchain note: `Unable to strip ... libandroidx.graphics.path.so`
  (packaged unstripped).
- `grep -rn "WakeLock|PowerManager|setExactAndAllowWhileIdle|AlarmManager|WorkManager"
  app/src/main` — **no matches** (inputs to finding P1-4).
- `grep -rn "usesCleartextTraffic|TrustAllCerts|ALLOW_ALL_HOSTNAME" app/src` —
  **no matches** (acceptance check from `REQUIREMENTS.md:157` passes).
- `./gradlew lint` was **not** run (the brief asked only for tests and
  `assembleDebug`); lint status is therefore unverified here. Lint config is at
  `app/build.gradle.kts:54-58` (`abortOnError = true`).

## Standing constraints — respected by this plan

- `DEFAULT_INTERVAL_SECONDS = 90` (`app/src/main/java/com/beakon/companion/data/AppPreferences.kt:98`)
  is not proposed for change anywhere in this plan. P1-4 proposes holding a wake
  lock around the *existing* 90 s tick, never changing the interval.
- TLS: no trust-all and no cleartext exist today (verified by grep and by reading
  `net/TlsSupport.kt` and `res/xml/network_security_config.xml`), and no fix below
  weakens that. Fingerprint pinning stays user-driven and fallback-only.
- Accuracy gate: default 30 m (`AppPreferences.kt:106`), `acc == null` pushes
  (`app/src/main/java/com/beakon/companion/tracking/AccuracyGate.kt:26-29`). No fix
  below touches this.
- minSdk 26 (`app/build.gradle.kts:14`) — but note finding B-1: the brief states
  compileSdk/targetSdk 37 while the module builds with 35
  (`app/build.gradle.kts:10` and `app/build.gradle.kts:15`).

## What is healthy (so the plan doesn't churn it)

- TLS model: platform trust first, exact-SHA-256 fallback only, trust-all absent
  (`net/TlsSupport.kt:114-144`); cleartext disabled
  (`app/src/main/res/xml/network_security_config.xml:10`); no user/system CAs added.
- Credential storage: API secret and session cookie in
  `EncryptedSharedPreferences` with a Keystore-backed master key
  (`app/src/main/java/com/beakon/companion/data/SecureSecretStore.kt:37-48`).
- Backup fully disabled both ways (`app/src/main/AndroidManifest.xml:19-21`,
  `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`).
- Exported surface is minimal and defensible: launcher activity
  (`AndroidManifest.xml:28-36`), `BOOT_COMPLETED`-only receiver with an action
  check (`AndroidManifest.xml:48-55`,
  `tracking/BootReceiver.kt:27`); the service is not exported
  (`AndroidManifest.xml:38-41`).
- Blocking network calls are provably off the main thread
  (`app/src/test/java/com/beakon/companion/ui/AppViewModelDispatcherTest.kt:45-289`
  — 8 passing dispatcher tests).
- Token-overwrite is disclosed pre-login with a required checkbox
  (`app/src/main/java/com/beakon/companion/ui/screens/SetupScreen.kt:58-68`,
  `124-127`).
- Rate-limit backoff exists and is tested (`tracking/RateLimitBackoff.kt`,
  4 passing tests), and the accuracy gate is tested including the `acc == null`
  push case (5 passing tests).

---

# P0 — crash, data loss, security hole

**None identified.** No trust-all manager, no cleartext, credentials are
encrypted at rest, nothing exported that shouldn't be, and the 73-test suite is
green. The realistic crash risks are P1 below: they need an unexpected exception
on a narrow path rather than an ever-present defect.

---

# P1 — wrong behaviour / plausible crash

### P1-1 — An unexpected exception in the tick loop crashes the process and ends tracking silently

- **Symptom:** any `RuntimeException` thrown inside a tracking tick propagates out
  of the coroutine child. The scope is supervisor-based
  (`app/src/main/java/com/beakon/companion/tracking/LocationTrackingService.kt:37`),
  a `launch` child failure there goes to the default uncaught-exception handler →
  process death. The persistent notification disappears and, because
  `trackingEnabled` stays `true`, the app later *says* it is tracking while it is
  not (see P1-5).
- **Evidence:** the loop has no guard —
  `LocationTrackingService.kt:74-80` (`scope.launch { while (isActive) { runTick(); delay(...) } }`).
  Concrete exception sources: `requestLocationUpdates` throws `SecurityException`
  synchronously if the permission is revoked while the service runs on a
  no-Play-services device (`app/src/main/java/com/beakon/companion/tracking/LocationSource.kt:94`);
  the fused path analog is safe (failures become `null`,
  `LocationSource.kt:49-57`); OkHttp/serialization can throw unchecked exceptions
  outside the `IOException`/`SSLException` catches
  (`app/src/main/java/com/beakon/companion/net/BeakonClient.kt:130-144`).
- **Root cause:** only two checked exception types are caught per call; the tick
  loop itself has no catch-all.
- **Proposed fix:** wrap each tick in `runCatching`/try-catch inside the loop;
  on failure record a last-error in `TrackingStateStore` and update the persistent
  notification ("Never fail silently"), never let it escape the coroutine. Keep
  the `delay` cadence unchanged.
- **Effort:** S. *(Runtime frequency unverified; code-level risk is real.)*

### P1-2 — The fallback location source can push a stale fix with a fresh server timestamp

- **Symptom:** on devices without Play services, when no live fix arrives, the
  newest *last-known* location is reported regardless of age — it could be hours
  old — while the server stamps it as "now". A wrong, confident-looking track
  results. Indoors with GPS off and a stale network-provider fix, the accuracy
  gate may still pass it.
- **Evidence:** `LocationSource.kt:72-75` — `lastKnown` is picked by
  `maxByOrNull { it.time }` and returned with **no age check**; only the
  fused path is freshness-aware via `getCurrentLocation`
  (`LocationSource.kt:50-57`).
- **Root cause:** no max-age filter on `getLastKnownLocation` results.
- **Proposed fix:** reject last-known fixes older than a bound (e.g. 2× the
  configured interval) and fall through to the live-fix path; if none, report
  `NoFix` and show the existing "waiting for a fix" message rather than
  inventing a position.
- **Effort:** S. *(Unverified at runtime — provider staleness can't be observed
  without a device; the missing check is verifiable in code.)*

### P1-3 — `Retry-After` is parsed but ignored; backoff always skips exactly two ticks

- **Symptom:** if the server ever answers `429` with a wait longer than two 90 s
  ticks, the app resumes after ~180 s and gets `429` again, looping. The parsed
  value only decorates the message text.
- **Evidence:** parsing at `BeakonClient.kt:136` and `BeakonClient.kt:189-190`;
  ignored at `app/src/main/java/com/beakon/companion/tracking/RateLimitBackoff.kt:36-42`
  (`skipRemaining = skipTicksAfterRateLimit`, never consulted with
  `retryAfterSeconds`).
- **Root cause:** the backoff is tick-count-based, not time-based.
- **Proposed fix:** honour `Retry-After` when present (delay until that many
  seconds have elapsed, compare with a clock), keeping the two-tick default when
  the header is absent. This does **not** change the 90 s interval — it changes
  only how long backoff lasts after a 429.
- **Effort:** S.

### P1-4 — The 90 s cadence is not protected against Doze; no wake lock, no exact alarms, no WorkManager

- **Symptom:** cadence holds while the device is awake, but under Doze the CPU
  sleeps and a coroutine `delay()` fires late (maintenance windows), so reports
  slip from 90 s to whatever the OS allows. A tick that spans a sleep (location
  acquisition + HTTPS POST) can also stall mid-tick.
- **Evidence:** the loop is plain `delay()` between ticks
  (`LocationTrackingService.kt:74-80`); a repo-wide grep for
  `WakeLock|PowerManager|AlarmManager|setExactAndAllowWhileIdle|WorkManager` in
  `app/src/main` returns **no matches**. The fused request itself
  (`LocationSource.kt:53`) may wake the device briefly for a fix; everything
  around it is unguarded.
- **Root cause:** a foreground service keeps the *process* alive but does not
  keep the *CPU* awake or make handler-style delays fire in deep idle.
- **Proposed fix:** take a short partial wake lock around each tick (acquire
  before location acquisition, release after the POST finishes), and either
  accept Doze-slip as documented behaviour or drive ticks via
  `setAndAllowWhileIdle`; keep the interval at 90 s. State the choice in the
  README. Consider battery honestly: a 90 s wake-lock cadence all day is a real
  cost, so "document the slip" may be the right call for this app — but it
  should be a decision, not an accident.
- **Effort:** M. **(Unverified at runtime — there is no device to observe Doze;
  this is a code-architecture finding, not a measured one.)**

### P1-5 — The Status toggle can claim "tracking on" with no live service

- **Symptom:** after a force-stop, or after P1-1's crash, the service never
  restarts, yet the Status screen shows Tracking = ON because the persisted
  preference is restored. The user believes they are sharing location when
  nothing is running — the "never fail silently" rule broken in spirit.
- **Evidence:** restore at `app/src/main/java/com/beakon/companion/BeakonApplication.kt:24-26`
  (`setTracking(preferences.trackingEnabled)`; the comment itself says the
  service is "never started automatically"); the toggle reads that state
  (`app/src/main/java/com/beakon/companion/ui/screens/StatusScreen.kt:126-131`).
  `START_STICKY` (`LocationTrackingService.kt:55-62`) masks this for ordinary
  memory-pressure kills but not for force-stop or a fatal tick exception.
- **Root cause:** persisted *intent* ("user wants tracking on") is displayed as
  *state* ("tracking is running") with nothing reconciling the two.
- **Proposed fix:** at app start (and/or when the Status screen appears), if
  `trackingEnabled == true` but the service is not running, show an explicit
  "Tracking was on but isn't running — tap to resume" state instead of a green
  toggle, or simply auto-restart the service from the UI.
- **Effort:** S–M. *(Service-liveness behaviour unverified at runtime.)*

---

# P2 — polish, maintainability, smaller risks

### Security / TLS

- **P2-1 — The certificate probe does no SNI and no hostname check.**
  `net/CertificateProbe.kt:52-55` creates an unconnected `SSLSocket` and
  connects manually — no SNI hostname is sent, so on SNI-based virtual hosting
  the *wrong* (default-vhost) certificate is shown, fingerprinted, and
  potentially pinned; and the `trustedByPlatform` verdict
  (`CertificateProbe.kt:69-71`) can't detect hostname mismatches at all. The
  real connection path is unaffected (OkHttp verifies hostnames), so impact is
  confined to the diagnostic/fingerprint flow. Fix: create the socket with
  `socketFactory.createSocket(host, port)` (enables SNI) and state in the UI
  that hostname verification happens on the real request. **Effort S.**

### Reliability / UX

- **P2-2 — No empty states.** History shows a blank screen on a successful
  zero-point fetch — no "No points yet" text (`ui/screens/HistoryScreen.kt:55-58`
  shows only errors; the list at `HistoryScreen.kt:87-94` renders nothing).
  Map with no points sits centred on osmdroid's default (0,0) at zoom 14 with
  no explanation (`ui/screens/MapScreen.kt:34-41`, `62-84`; a message appears
  only on error, `MapScreen.kt:91-99`). Fix: add an explicit empty state on both
  screens. **Effort S.**
- **P2-3 — The map re-centres on every history refresh.** `MapScreen.kt:81`
  (`view.controller.setCenter(latest)`) runs in `AndroidView.update`, so any new
  point snaps the camera away from wherever the user panned. Fix: centre once on
  first load (or only when the user hasn't interacted). **Effort S.**
- **P2-4 — "Latest" is inferred from list order, and the API's `latest` field is
  decoded then thrown away.** `MapScreen.kt:73` takes `geoPoints.last()`;
  `ui/AppViewModel.kt:343-345` stores `result.locations` and drops
  `result.latest`, which the model carries
  (`net/Models.kt:37-41`). If the server ever returns a different order, the
  marker and the polyline head are wrong. Fix: keep `latest` in the state and
  use it for the marker. **Effort S.**
- **P2-5 — Logout strands the user.** Logging out hides the bottom bar
  (`ui/BeakonApp.kt:41-65`) but does not navigate; the user remains on the
  Status screen (`ui/screens/StatusScreen.kt:243-251`,
  `ui/AppViewModel.kt:314-321`) until they tap "Open Setup". Fix: navigate to
  `setup` on logout. **Effort S.**
- **P2-6 — Notification taps stack `MainActivity` instances.** No `launchMode`
  on the activity (`AndroidManifest.xml:28-36`) and the content intent sets no
  activity flags (`tracking/NotificationHelper.kt:65-73`), so each tap can push
  another instance. Fix: `singleTask` launch mode (or `singleTop` + a
  `FLAG_ACTIVITY_SINGLE_TOP` intent). **Effort S.**
- **P2-7 — Error notifications go nowhere when `POST_NOTIFICATIONS` is denied.**
  The boot-restart failure and the start-failure path surface errors *only* via
  notifications (`tracking/BootReceiver.kt:43-47`,
  `tracking/NotificationHelper.kt:52-63`,
  `LocationTrackingService.kt:180-183`). With the permission denied these are
  silently dropped. The user is warned up front that notifications may be hidden
  (`StatusScreen.kt:51-57`), which partially mitigates it. Fix: persist the last
  tracking-impairing error in state and show it at app start. **Effort S.**
- **P2-8 — History and Map re-fetch on every tab entry.**
  `LaunchedEffect(Unit) { viewModel.loadHistory() }` fires on each composition
  entry (`ui/screens/HistoryScreen.kt:31`, `ui/screens/MapScreen.kt:32`).
  Switching tabs repeatedly means repeated network fetches. Fix: fetch only
  when data is older than a TTL or absent. **Effort S.** (Battery/cost-relevant.)

### Battery and cost

- **P2-9 — Fallback live-fix request streams at maximum rate for up to 20 s.**
  `LocationSource.kt:94` passes `minTime = 0, minDistance = 0`. Bounded by the
  20 s timeout (`LocationSource.kt:108`) and fallback-only, but it's the
  worst-case radio/GPS cost in the app. Fix: pass sane min values; the first
  callback wins anyway. **Effort S. * (Unverified at runtime.)***
- Notes, not findings: the 90 s interval is fixed by constraint
  (`AppPreferences.kt:96-98`); `PRIORITY_BALANCED_POWER_ACCURACY`
  (`LocationSource.kt:53`) is the right choice for it; the persistent
  notification uses `IMPORTANCE_LOW` (`tracking/NotificationHelper.kt:22-29`).
  The dominant battery cost is GPS acquisition once per 90 s tick plus the
  wake-lock decision in P1-4 — that is inherent to the spec, not a defect.

### Test coverage

- **P2-10 — `SimpleCookieJar` has no dedicated unit test.** Expiry eviction and
  seeding govern whether a persisted session survives process death
  (`net/SimpleCookieJar.kt:12-57`, especially `:29`, `:36`, `:41-51`); today it
  is exercised only indirectly through the mock login tests. **Effort S.**
- **P2-11 — The service tick wiring is untested.** Accuracy-hold bookkeeping,
  the NotConfigured path, and notification-text selection live in
  `LocationTrackingService.kt:91-163` and are reachable only on Android. The
  highest-value logic (`recordFix` + `recordAccuracyHold` ordering, lines
  130-137) could be extracted pure like `AccuracyGate` was. **Effort M.**
- **P2-12 — `TrackingStateStore` is thinly tested (2 tests, holds only).**
  `recordResult` success/error transitions and the day-rollover reset
  (`tracking/TrackingStateStore.kt:57-103`) are covered only via the
  integration test (`MockServerIntegrationTest.kt:80-115`);
  `TrackingStateStoreTest.kt:11-35` asserts nothing about them. **Effort S.**
- **P2-13 — `logout` and `loadHistory` error branches are untested.**
  `ui/AppViewModel.kt:314-321` and the non-success history paths
  `AppViewModel.kt:342-351`; dispatcher tests cover the happy paths only.
  **Effort S.**

### Build config

- **B-1 — SDK level mismatch with the brief's constraint.** The brief pins
  "compileSdk/targetSdk 37", but the module declares `compileSdk = 35`
  (`app/build.gradle.kts:10`) and `targetSdk = 35` (`app/build.gradle.kts:15`),
  and the README documents the 35 toolchain
  (`README.md:41-42`). Decide which is authoritative; if 37 is intended, bump
  both and re-verify. **Effort S.**
- **B-2 — Release builds are signed with the debug keystore.** Deliberate and
  documented for install-over-debug (`app/build.gradle.kts:27`,
  `README.md:54-69`), but anyone holding the debug key can sign "updates", and
  the APK must never be distributed as-is. No change proposed; keep it a
  conscious, revisited decision. **Effort S (policy).**
- **B-3 — `security-crypto` is an alpha on the credential path.**
  `gradle/libs.versions.toml:12` pins `1.1.0-alpha06`. It works (tests green),
  but an alpha on the secret store deserves a tracking eye toward a stable
  release. **Effort S.**
- **B-4 — `versionCode`/`versionName` are frozen at 1/1.0**
  (`app/build.gradle.kts:16-17`). Side-loaded same-key installs still apply,
  but there is no version signal. Bump per build that leaves the machine.
  **Effort S.**
- Notes, not findings: Gradle wrapper pinned to 8.9
  (`gradle/wrapper/gradle-wrapper.properties`); AGP 8.7.3 / Kotlin 2.0.21
  pinned (`gradle/libs.versions.toml:2-3`); JVM target 17
  (`app/build.gradle.kts:40-47`) builds fine under JDK 21; minification is off
  on purpose with serialization keep-rules already staged
  (`app/build.gradle.kts:32`, `app/proguard-rules.pro`); lint aborts on error
  (`app/build.gradle.kts:54-58`) but was **not run here**.

---

# Ordered fix plan

1. **P1-1** — guard the tick loop against uncaught exceptions (S).
2. **P1-2** — age-check last-known fixes in the fallback source (S).
3. **P1-3** — honour `Retry-After` in the backoff (S).
4. **P1-5** — reconcile "tracking on" with a dead service at app start (S–M);
   pairs naturally with #1.
5. **P1-4** — make a deliberate Doze/wake-lock decision around the existing
   90 s tick (M). *Needs on-device verification the environment can't provide —
   treat as design work + device test, not a code-only fix.*
6. **P2-1** — probe socket with SNI (S).
7. **P2-2 … P2-7 (batch, all S)** — empty states; stop re-centring the map;
   use the API `latest`; navigate to Setup on logout; `singleTask`; persist
   last-impairing error for the Status screen.
8. **P2-8, P2-9 (batch, S)** — fetch caching/TTL; sane min values on the
   fallback fix request.
9. **P2-10 … P2-13 (batch, S/M)** — cookie-jar tests; store tests; extract and
   test tick wiring; logout/history-error tests.
10. **B-1 … B-4 (batch, S)** — resolve the SDK-37 constraint; bump versions;
    track security-crypto stable; re-affirm the debug-key release policy.

# Explicitly not verified (no device/emulator here)

- That the foreground service starts, stays alive, survives process death, and
  that `START_STICKY` restarts it (`LocationTrackingService.kt:55-62`).
- Anything about Doze, wake behaviour, GPS radio behaviour, or real accuracy
  values (P1-2, P1-4, P2-9).
- The runtime permission flow and its rationale dialogs
  (`ui/screens/StatusScreen.kt:47-107`).
- `BOOT_COMPLETED` delivery and the boot-restart path end-to-end
  (`tracking/BootReceiver.kt:26-49`) — verified by manifest inspection, the
  unit-tested decision function, and compilation only.
- Osmdroid tile fetching, caching, and the polyline/marker rendering
  (`ui/screens/MapScreen.kt:28-101`).
- Compose rendering, scrolling smoothness, and any frame timing.
- Whether `SecurityException` paths actually fire (`LocationTrackingService.kt:165-186`,
  `LocationSource.kt:94`).
- `./gradlew lint` (not run) and the release build (`assembleRelease` not run
  here; config reviewed at `app/build.gradle.kts:20-38`).
