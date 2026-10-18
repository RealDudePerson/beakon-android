# Beakon Companion — Android app

An Android client for a self-hosted [Beakon](https://github.com/) instance: sign in,
report the device's location in the background, and view your own location history
as a list and on a map. See `REQUIREMENTS.md` for the full specification.

## What it does

- **Setup / Login** — instance URL, username, password, and deliberate
  acceptance of the server's self-signed certificate (SHA-256 fingerprint).
- **Status** — tracking on/off, last fix, last successful send, points sent today,
  battery/charging, and a specific message for the current error.
- **Map** — osmdroid + OSM raster tiles, own history as a polyline with the
  latest point marked (descriptive User-Agent, cached tiles).
- **History** — recent points: time, coordinates, battery/charging.

Tracking runs in a foreground service with a persistent notification, started only
by an explicit user action. Location comes from `FusedLocationProviderClient`
(Google Play services) with a fallback to `LocationManager`. The default reporting
interval is **90 s** (the server allows 5 requests / 300 s / IP); a `429` skips the
next two ticks.

## TLS

The instance uses an adhoc self-signed certificate. On first connection the app
reads the presented certificate and shows its SHA-256 fingerprint, subject and
issuer. The user must accept it before credentials are sent; the fingerprint is
stored per host. Later connections refuse to proceed if the fingerprint changes.
There is **no** trust-all trust manager and cleartext HTTP is disabled
(`network_security_config.xml`).

## Build

Requires JDK 17+ and the Android SDK (`platforms;android-35`,
`build-tools;35.0.0`). Point `local.properties` at the SDK, or set `ANDROID_HOME`.

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew lint
./gradlew test               # JVM unit tests, including the local-mock integration test
```

The Gradle wrapper is committed, so no system Gradle is required.

## Local mock server

`mock/beakon_mock.py` is a standard-library-only mock of the Beakon API. It
serves HTTPS with a checked-in self-signed certificate so the app's
fingerprint-acceptance flow can be exercised, and implements:

- `POST /login` — success sets a `session` cookie and redirects; failure re-renders
  the form (no cookie).
- `POST /api/updatetoken` — session-authenticated; overwrites the API secret.
- `POST /api/recordlocation` — `username`/`secret` headers; `201` / `400` / `401` /
  `429` (with `Retry-After`). The rate limit defaults to 5 requests / 300 s per IP
  and counts every attempt.
- `GET /api/locations` — session-authenticated; `{"locations": [...], "latest": ...}`.

Run it:

```bash
python3 mock/beakon_mock.py --port 8443
# defaults: user demo / password correct-horse, cert mock/cert.pem + mock/key.pem
```

Options: `--host`, `--port`, `--cert`, `--key`, `--user USER:PASSWORD`
(repeatable), `--rate-limit-max`, `--rate-limit-window`. It prints
`LISTENING <host> <port>` once ready. `mock/cert2.pem` + `mock/key2.pem` are a
second, different self-signed pair for testing "fingerprint changed" behaviour.

The JVM integration test (`app/src/test/.../MockServerIntegrationTest.kt`) starts
this mock itself and exercises bad-password login, good-password login with an
accepted certificate, a `201`, a `401`, a `429` with backoff, and a changed
fingerprint. It is skipped (not failed) if `python3` is unavailable.

```bash
./gradlew test --tests '*MockServerIntegrationTest'
```
