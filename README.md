# Beakon Companion — Android app

An Android client for a self-hosted [Beakon](https://github.com/) instance: sign in,
report the device's location in the background, and view your own location history
as a list and on a map. See `REQUIREMENTS.md` for the full specification.

## What it does

- **Setup / Login** — instance URL, username and password. The server
  certificate is trusted through the device's platform trust store; a CA-signed
  server needs no certificate step at all. Fingerprint acceptance is an optional
  fallback offered only for a certificate the platform rejects.
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

Trust uses the device's **platform trust store** by default: a certificate whose
chain validates against the trusted roots (for example Let's Encrypt) connects
with no user interaction, and no fingerprint is stored or consulted. This means a
CA-signed server survives certificate renewal without asking the user again.

Fingerprint pinning remains only as an **explicit fallback**: if the platform
rejects the certificate (for example the adhoc self-signed certificate on the
app's own port), the user can inspect the presented certificate and accept its
SHA-256 fingerprint. The fingerprint is stored per host and only then is that one
untrusted certificate accepted. There is **no** trust-all trust manager and
cleartext HTTP is disabled (`network_security_config.xml`).

## Build

Requires JDK 17+ and the Android SDK (`platforms;android-35`,
`build-tools;35.0.0`). Point `local.properties` at the SDK, or set `ANDROID_HOME`.

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # -> app/build/outputs/apk/release/app-release.apk
./gradlew lint
./gradlew test               # JVM unit tests, including the local-mock integration test
```

The Gradle wrapper is committed, so no system Gradle is required.

### Use the release build for everyday use

The debug APK is slow on purpose: it is `debuggable=true`, so ART skips its
optimizing compiler, and it includes the Compose UI inspector
(`debugImplementation(ui-tooling)`). The **release** build is the one to install
if the app feels sluggish. It is `debuggable=false`, leaves the inspector out,
and is therefore substantially smaller.

So that it can be installed **over the existing app without an uninstall** (which
would wipe the saved login, interval, accuracy threshold and fingerprint pins),
the release build is deliberately **signed with the debug keystore**
(`signingConfig = signingConfigs.getByName("debug")`). It is the same
application id and signing key as the debug APK, so installing it is a normal
update. This is not a store-release key and must not be used for distribution —
it exists so the owner can move from the slow debug build to the fast release
build without losing settings.

Minification (`isMinifyEnabled`) is **off** in the release build. This app uses
kotlinx.serialization and Compose, where a missing keep rule shows up as a
runtime crash rather than a build error, and there is no device here to catch
one; the optimizing compiler, `debuggable=false` and the dropped inspector are
the wins, without taking on R8 keep-rule risk.

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
accepted certificate, a platform-trusted handshake that consults no fingerprint,
a self-signed certificate refused with no accepted fingerprint, a `201`, a `401`,
a `429` with backoff, and a changed fingerprint. It is skipped (not failed) if
`python3` is unavailable.

```bash
./gradlew test --tests '*MockServerIntegrationTest'
```
