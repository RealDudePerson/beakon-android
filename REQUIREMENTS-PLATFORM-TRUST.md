# Trust the platform certificate store by default

Repo: `/home/hermes/projects/beakon-android`.

## The problem (confirmed)

The production server `https://location2.navettas.com` is signed by **Let's Encrypt**
and already validates against the device's system trust store:

```
subject = CN = location2.navettas.com
issuer  = C = US, O = Let's Encrypt, CN = YE2
Verify return code: 0 (ok)
```

But the app is built around mandatory user-driven fingerprint pinning
(`net/TlsSupport.kt` — "deliberate, user-driven certificate pinning"; `net/CertificateProbe.kt`).
The consequence:

1. Setup forces the user to fetch and accept a certificate fingerprint **before**
   they can log in, even though the platform already trusts the certificate.
2. The certificate renews (Let's Encrypt, ~60-90 days), the pinned fingerprint stops
   matching, and the user is put through the acceptance flow again — repeatedly.

Owner's instruction, verbatim: *"The tls certificate should be implicitly trusted if
its root certificates are trusted on the device. Asking to retrieve the certificate
before logging in is unnecessary."*

## What to build

### 1. Platform trust is the default path
- Connections use the **platform/default `X509TrustManager`** (`TlsSupport.defaultTrustManager()`
  already exists). If the chain validates against the device's trusted roots, the
  connection **succeeds with no user interaction at all**.
- **No pin is required, and none is checked, when the platform already trusts the
  certificate.** This is the fix for the recurring refresh burden.

### 2. Pinning becomes a fallback, not a gate
- If — and only if — the platform **rejects** the certificate (e.g. the self-signed
  adhoc cert on the app's own port, `O = Dummy Certificate`), then offer the existing
  fingerprint acceptance flow as an **explicit opt-in**.
- The fallback must be reachable only after a real verification failure, and the UI
  must make clear that the certificate is **not** trusted by the device and that the
  user is choosing to trust it by fingerprint.
- Keep the existing SHA-256 fingerprint comparison for that path.

### 3. Remove the mandatory pre-login probe
- The Setup flow must **not** require retrieving/accepting a certificate before the
  user can log in. A normal CA-signed server needs **zero** certificate steps.
- Keep the probe **available** (e.g. a "check certificate" affordance) so the
  fingerprint can still be inspected or accepted deliberately — just never mandatory.

## Hard constraints — violating any fails the task

1. **Do NOT add a trust-all `TrustManager`.** Accepting an untrusted certificate is
   still only possible via an exact SHA-256 fingerprint match that the user
   explicitly confirms. This is not a loosening of trust: platform trust is
   *stricter* than a hand-pinned leaf.
2. **No cleartext traffic.** `usesCleartextTraffic` stays false / network security
   config unchanged.
3. **The tracking interval stays 90 seconds.**
4. **Never fail silently.** A TLS failure must surface an actionable message naming
   the reason (untrusted chain, hostname mismatch, expired, etc.) — not a generic
   "could not connect".
5. Do not change the API calls, the 90s cadence, or the boot-start behaviour just
   added.

## Acceptance criteria — run these yourself and report real output

1. **Full suite passes.** Report the count. It was 41 before this work.
2. **Platform-trusted certificate needs no pin**: add a test proving that when the
   platform trust manager **accepts** the chain, the client connects successfully
   **without** any fingerprint being stored or consulted. Prove it red-capable.
3. **Untrusted certificate is still refused by default**: add a test proving that a
   self-signed cert **fails** unless a matching fingerprint has been explicitly
   accepted. Prove it red-capable.
4. **Wrong fingerprint is refused**: a stored fingerprint that does not match the
   presented certificate must fail, with an actionable message.
5. **No trust-all**: state plainly that you did not add one, and show the code that
   enforces fingerprint matching on the fallback path.
6. **Setup no longer blocks on a certificate**: the login path completes against a
   CA-signed server without any certificate interaction. Add a test for the flow if
   it is testable; if it is not, say so explicitly rather than claiming it.
7. **The existing mock-server integration test still passes**, including the
   self-signed case (which must still work via the explicit fallback).
8. **APK still builds**: `./gradlew assembleDebug`. Report path, size, sha256.

## Rules
- Work on branch `fix/platform-trust-default`. Commit there. Do NOT merge to main
  (the owner merges) and do NOT push.
- You cannot test on a device — do not claim device behaviour was observed.
- Report honestly: what passed, what you could not verify, and anything you chose
  differently and why.
