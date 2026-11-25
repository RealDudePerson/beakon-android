# beakon-android — state

Durable detail lives in the wiki: `~/wiki/domains/beakon-android.md`.

- **Branch: `main`** (the server repo is `master` — do not mix them up).
- GitHub: `RealDudePerson/beakon-android` (**private**). It had **no remote at all**
  until 2026-10-19.
- Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug`
  with `ANDROID_HOME=~/android-sdk`.
  **JDK 17 is NOT installed on this host** (only JDK 8 and 21 are). Any doc that says
  `java-17-openjdk-amd64` is stale and the command fails at step one. The project targets
  Java 17 but compiles and tests clean under JDK 21.
- Release: `./gradlew assembleRelease` →
  `app/build/outputs/apk/release/app-release.apk`. Minify is off, so no ProGuard risk.
- APKs go to the SharePoint outbox (`agents:hermes/outbox/`).
- Specs in-repo: `REQUIREMENTS.md`, `REQUIREMENTS-BOOTSTART.md`,
  `REQUIREMENTS-PLATFORM-TRUST.md`, `REQUIREMENTS-WIDGET.md`.
- Shipped: background tracking service; boot-start (opt-in, default OFF); platform
  trust by default with fingerprint pinning only as an explicit fallback;
  **location-sharing home-screen widget (1.6)**.
- **Do not** change the 90s interval (server rate-limits 5 req/300s per IP), and never
  add a trust-all `TrustManager` or cleartext.
- **Never claim device behaviour was observed** — no device is available; boot and UI
  paths are verified by manifest inspection, unit tests and compilation only.

## Widget (1.6, versionCode 11)

- Data comes from the **existing** `GET /api/locations/shared` (fail-closed) via the
  `secret` header and the app's stored token. **`age_seconds` is computed server-side** —
  never derive age from the `timestamp` display string.
- Three distinct states must stay distinct: "nobody shares with me" (empty `people`),
  "they share but have never reported" (person omitted), and "the request failed"
  (HTTP 500 with `{"people": []}`). The last one is an **error**, never an empty state.
- Android widgets are `RemoteViews`: the map is a **composited bitmap**, never a WebView.
- 15-minute `WorkManager` refresh with a network constraint; tap opens the app at that
  person's map. The tap target rejects cleartext and foreign instances.
- The server's 5 req/300 s limit is keyed to `recordlocation` (writes) only; reads are
  not rate-limited.
- Verified by **231 unit tests** + manifest inspection. **Device behaviour is NOT verified.**

## Signing

- The build uses a permanent keystore when
  `~/projects/beakon-signing/signing.properties` exists; otherwise it **silently falls
  back to the debug keystore**. Always confirm with
  `apksigner verify --print-certs <apk>` — a green build says nothing about the key.
- **A NEW key was generated 2026-11-24** at `~/projects/beakon-signing/beakon-release.jks`
  (alias `beakon`, RSA 4096, validity 10950 days, `CN=Beakon Companion, O=Beakon, C=US`).
  `./gradlew signingReport` should show `Config: permanent` for every variant.
- **The keystore AND its password are backed up in Vaultwarden** as
  *"Beakon Companion signing keystore"* (org agents / Default collection), with the `.jks`
  attached. Restore from there if this host is rebuilt. The previous key was never backed
  up anywhere and was lost — that is why 1.6 was re-keyed.
- **Consequence of the re-key:** every release up to and including 1.5.1 was signed with a
  *different* key, so **installing 1.6 over an older build requires an uninstall first**,
  which clears app settings (server URL and credentials must be re-entered). Future
  releases signed with this key will upgrade in place normally.
- Build code resolves `../beakon-signing` relative to the repo, i.e.
  `~/projects/beakon-signing`. An older comment and the README said
  `~/workspace/beakon-signing` — both are wrong.

## Build evidence

- **A Gradle run reporting `up-to-date` is not a test run.** It reuses cached results.
  Use `./gradlew test --rerun-tasks` when the result is your evidence, and check the
  test-result XML mtime and count.
