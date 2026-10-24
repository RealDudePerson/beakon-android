# Brief: analyse beakon-android, produce a fix plan

## Goal

Analyse the Android app in this repo and produce a **prioritised plan of fixes**.

**The deliverable is a document. Do not change application code.** This is analysis and
planning, not implementation.

## Output

Write `ANALYSIS.md` at the repo root.

## What to cover

1. **Correctness bugs** — code that produces wrong behaviour.
2. **Crash risks** — lifecycle, threading, nullability, work done off the main thread,
   Compose state misuse.
3. **Reliability** — network/service/retry handling, background work, Doze and wake locks,
   behaviour when the OS kills the process.
4. **Security** — TLS configuration and pinning, credential storage, exported components,
   intent handling, backup settings.
5. **Battery and cost** — the tracking interval, wake locks, sensor use, network frequency.
6. **UX gaps** — loading/empty/error states. Failures must be visible to the user, never
   silent. Missing permissions and missing data must explain themselves.
7. **Test coverage** — what is untested that actually matters, and which tests are weak.
8. **Build config** — toolchain pinning, signing, release settings, R8/minification risk.

## Rules

- Every finding **must cite `file:line`** that you actually opened. No speculation, no
  inventing file paths or line numbers. If you are unsure, say so.
- Rank by severity: **P0** (crash, data loss, security hole) → **P1** (wrong behaviour) →
  **P2** (polish, maintainability).
- For each finding give: symptom, evidence (`file:line`), root cause where known,
  proposed fix, and effort (S/M/L).
- Be explicit about what you could **not** verify. There is no device or emulator here, so
  anything requiring runtime observation is unverified — say that rather than implying
  you tested it.
- Do **not** modify app code. Do **not** commit or push anything.

## Run these and report the real numbers

- The unit test suite: how many tests, all passing? Report the actual count.
- `./gradlew assembleDebug` — does it build? Report the result.

Environment for the build:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/hermes/android-sdk
```

## Standing constraints (the plan must respect these)

- **Tracking interval stays 90s** — `DEFAULT_INTERVAL_SECONDS = 90` in `AppPreferences.kt`.
  Do not propose changing it.
- **Android TLS:** no trust-all `TrustManager`, no cleartext. User-driven fingerprint
  pinning only, as a fallback.
- **Accuracy gate:** default 30 m, user-settable. A fix with no accuracy value must still
  push. Preserve this behaviour.
- **minSdk 26**, compileSdk/targetSdk 37.

## Definition of done

`ANALYSIS.md` exists, every finding cites a real `file:line`, severities are assigned,
the plan is ordered, and the test and build numbers are real ones you ran.
