# Beakon Companion — Android app

**Status:** draft for approval. Nothing is built until this is signed off.

## Goal

An Android app that logs into a self-hosted Beakon instance, keeps reporting the
device's location in the background, and shows the user their own location history.

## Scope

**In:**

- Log in to a Beakon instance by base URL + username + password.
- Obtain/rotate the per-user API secret needed by the location endpoint.
- Report location in the background, surviving app close and screen off.
- Include battery level and charging state with each report.
- Show current status: last fix, last successful send, count sent, any error.
- Show own location history as a list and on a map.
- Handle a self-signed TLS certificate deliberately (see TLS below).
- Build a sideloadable debug APK.

**Out (do not build without asking):**

- Other users' locations, sharing, or presence — this is own-location only.
- Registration, account editing, admin, geofences, known places.
- Play Store readiness: signing config, listing assets, privacy policy.
- Mapbox. The household token is currently rejected for tiles; OSM is used instead.

## Decisions already made (do not relitigate)

| Decision | Value |
|---|---|
| Language / UI | Kotlin, Jetpack Compose |
| Map | osmdroid with OSM raster tiles, no API key |
| Delivery | Sideloaded debug APK (`assembleDebug`) |
| Tracking | Background, via a foreground service with a persistent notification |
| minSdk / target | minSdk 26 (Android 8), target the current stable SDK |
| Build | Gradle with the Kotlin DSL, wrapper committed |

## The Beakon API contract (verified against the running instance)

Base URL is user-supplied, e.g. `https://location2.navettas.com`.

**Login** — `POST /login`, form-encoded `username` + `password`. Session cookie
(`session`, HttpOnly). Success redirects; failure re-renders the form. The cookie is
what authenticates everything else.

**Set the API secret** — `POST /api/updatetoken`, form-encoded `secret=<value>`,
session-authenticated. This **overwrites the user's existing API token**, which may
be in use by another client. The app must say so plainly in the UI before doing it,
and must not do it silently. After this, `secret` is the credential for reporting.

**Report a location** — `POST /api/recordlocation`, JSON body:

```json
{"lat": 40.7128, "lon": -74.0060, "acc": 8.0, "batt": 77, "ischarging": false}
```

Authenticated by **headers**, not a bearer token: `username: <username>` and
`secret: <api token>`. Responses: `201` created, `400` missing lat/lon, `401` auth
failure (deliberately uniform — it does not distinguish an unknown user from a bad
token), `429` rate limited.

**Rate limit — important.** `/api/recordlocation` allows **5 requests per 300
seconds per client IP**. A naive 60-second interval sits exactly on that ceiling. The
app must default to a 90-second interval and must back off on `429` (e.g. skip the
next one or two ticks) rather than hammering. Do not raise the default to 60s.

**Read history** — `GET /api/locations` (session-authenticated), optional `?date=`.
Returns `{"locations":[{lat,lon,timestamp,batt,ischarging}...], "latest":{...}|null}`.

## TLS: the instance uses a self-signed certificate

Beakon runs Flask with an adhoc self-signed certificate, so the default TLS trust
store will reject it. The app must handle this **deliberately**:

- On first connection to an instance, fetch the presented certificate and show the
  user its **SHA-256 fingerprint**, plus subject and issuer.
- The user explicitly accepts it before any credentials are sent. Store the accepted
  fingerprint, scoped to that host, in app-private storage.
- On later connections, verify the presented certificate matches the stored
  fingerprint and refuse to proceed (with a clear message) if it does not.
- **A blanket "trust all certificates" TrustManager is forbidden.** So is
  `android:usesCleartextTraffic="true"` — HTTPS only, and a certificate must be
  pinned by the user before credentials go anywhere.

## Tracking design

- A **foreground service** with a persistent notification is the only way this
  survives; start it on the user's explicit action, never automatically.
- Location via `FusedLocationProviderClient` (Google Play services) with a fallback
  to `LocationManager` if Play services is absent. Request `PRIORITY_BALANCED_POWER_ACCURACY`.
- Default interval **90 seconds**; user-selectable in Settings. Minimum offered: 60s.
- Each tick: get a fix, read battery % and charging state, POST, record the outcome.
- **Never fail silently.** Every failed send must surface a specific, actionable
  message (auth rejected / rate limited / certificate changed / no network / no fix),
  not just a red dot. This mirrors the lesson from the Roku client.
- Queue behaviour when offline: keep the newest fix only. Do not accumulate a
  backlog of stale points and flush them later — a stale trail is worse than a gap.
- Stop the service cleanly on logout and on the user's toggle-off. Restore state
  (tracking on/off, instance URL, username) across process death; the secret goes in
  `EncryptedSharedPreferences`, never in plain prefs.

## Permissions

`INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`,
`ACCESS_BACKGROUND_LOCATION` (API 29+), `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_LOCATION` (API 34+), `POST_NOTIFICATIONS` (API 33+),
`RECEIVE_BOOT_COMPLETED` only if auto-resume is implemented — which it is not in
scope, so omit it.

Request in the right order and explain *why* before each prompt. Background location
must be requested after foreground location, and the UI must handle a denial
gracefully — foreground-only tracking is a valid degraded mode, not an error state.

## Screens

1. **Setup / Login** — instance URL, username, password, certificate fingerprint
   acceptance. Validates the URL, tells the user plainly that continuing will
   overwrite any existing API token.
2. **Status** — tracking on/off, last fix time, last successful send time, points
   sent today, current error if any, battery. Big obvious toggle.
3. **Map** — osmdroid view, own history as a polyline with the latest point marked.
   Must send a descriptive OSM `User-Agent` (OSM tile policy requires it) and cache
   tiles.
4. **History** — list of recent points: time, coordinates, accuracy, battery.

## Build

Gradle wrapper committed. `./gradlew assembleDebug` must produce
`app/build/outputs/apk/debug/app-debug.apk`. The APK must be reproducible from a
clean checkout with only the Android SDK present.

**Prerequisite (handled outside this task):** the Android SDK is incomplete on this
machine — `/usr/lib/android-sdk` has only `platform-tools`. `cmdline-tools`,
`platform-tools`, `build-tools` and a platform must be installed and licences
accepted before anything can compile. Do not attempt to work around this by
vendoring SDK pieces into the repo.

## Acceptance criteria — run these and report honestly

1. `./gradlew assembleDebug` succeeds and produces the APK at the path above.
2. `./gradlew lint` reports no errors.
3. Unit tests (JVM) cover, and pass for:
   - the recordlocation request builder: correct headers, JSON body, and that an
     absent battery/charging value is omitted rather than sent as `null`;
   - the 429 backoff rule: after a `429`, the next send is skipped;
   - the certificate fingerprint comparison: a changed fingerprint is rejected and
     a matching one accepted.
4. Against a **local mock** of the beakon API (a small script in the repo is fine):
   - login with a bad password surfaces an auth error, not a crash;
   - login with a good password + accepted certificate proceeds;
   - a 201 response updates "last successful send";
   - a 401 response surfaces an auth-rejected message;
   - a 429 response triggers backoff and surfaces a rate-limit message.
5. `grep -rn "TrustAllCerts\|ALLOW_ALL_HOSTNAME\|usesCleartextTraffic" app/src` returns
   nothing.
6. The app declares no permissions beyond those listed above.

## Reporting

Report what passed, what could not be verified, and anything you had to decide that
this spec did not cover. Do not claim a device-level behaviour was verified unless it
was; a compiling APK is not proof that background tracking works on a real phone.
