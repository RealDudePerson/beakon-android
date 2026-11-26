# REQUIREMENTS — Location-sharing widget

**Status: DRAFT — awaiting user approval. No code until approved.**

Add a home-screen widget to Beakon Companion showing the live location of someone
who shares their location with you.

## Goal

A widget that, at a glance, answers: *"Where is <person> right now, and how stale
is that?"* — without opening the app.

## The data source (already exists on the server — do not add endpoints)

```
GET /api/locations/shared          auth: `secret: <api token>` header
-> { "people": [ {
       "username": str, "display_name": str,
       "latest": { "lat": float, "lon": float,
                   "timestamp": str,          # display string, may be absent
                   "timestamp_unix": int,     # epoch seconds
                   "age_seconds": int,        # ONLY this endpoint provides it
                   "batt": int, "ischarging": bool },
       "trail": [ { "lat", "lon", "timestamp", "timestamp_unix" }, ... ]
     } ] }
```

Facts established by reading `src/app.py` on the server (`ee20e9b`):

- The endpoint is **fail-closed**: it returns exactly the `sharing_permission`
  rows whose `shared_with_id` is the caller. Never widen this client-side.
- A permitted person **with no locations is omitted entirely** — not returned
  with nulls. The widget must handle "shared with me but never reported".
- `age_seconds` exists **only here**. Do not try to derive age from the
  `timestamp` display string.
- On failure the server returns HTTP 500 with `{"people": [], "error": ...}`.
  Treat that as an error state, **not** as "nobody shares with you".

## Constraints (inherited — do not violate)

- **No cleartext, no trust-all TrustManager.** The widget's HTTP must go through
  the same TLS path as the rest of the app (platform trust store by default;
  fingerprint pinning only as the existing explicit fallback).
- **Reuse the existing `secret` header auth** and the existing stored token
  (`SecureSecretStore`). Do not add a second credential store.
- **Never fail silently** — every error state must be visible in the widget.
- **Android widgets are `RemoteViews`.** They cannot host a `WebView`. The map
  must therefore be a **bitmap**: fetch the tile, composite the marker in code,
  push the resulting image. This is the single biggest design constraint.
- The **90 s reporting interval is untouched** — this feature only *reads*.

## Design decisions (made by the agent; flag if you disagree)

| # | Decision | Rationale |
|---|---|---|
| D1 | **One widget = one person**, chosen at placement time | Server supports several sharers; per-widget config lets you pin each. Falls back to "the first sharer" if the chosen person stops sharing. |
| D2 | **Refresh every 15 minutes** via `WorkManager` periodic work | The Android/WorkManager floor — freshest possible without a foreground service. **Verified:** the 5 req/300 s limit is keyed to `recordlocation` only (`rate_key = 'recordlocation:' + _client_address()`); `/api/locations/shared` carries no rate limiter, so widget reads do not consume the reporting budget. |
| D3 | **Tap opens the app at that person's map** (`/map/<username>`) | Simplest "prompt to view" that works with a `RemoteViews` tap target. |
| D4 | **OSM tile grid + marker composited to a bitmap** | The only way to show a map in a widget. A grid of tiles stitched around the point (not one tile — a point near its tile's edge left most of the frame grey in 1.6). The bitmap is sized from the widget's actual width at refresh time. Reuse the existing descriptive User-Agent and the app's existing tile cache policy. |
| D5 | **Always render the map; age text always shown, stale fixes dimmed** | Staleness is conveyed by the text ("4 min ago" / "3 h ago" / "25 days ago") *and* by dimming the map and muting the age past 10 minutes, with a "Stale location" note — rather than hiding data. |
| D6 | **Reuse the app's existing HTTP/TLS client** for the widget's fetch | One code path to audit, not two. |

## Confirmed by the user (2026-11-21)

Answered "1" to the five-question batch — i.e. **the first option in each**:

- **Person:** configurable per widget, chosen at placement time (= D1).
- **Refresh:** every **15 minutes** (= D2, updated from 30).
- **Prompt:** tapping the widget opens the app at that person's map (= D3).
- **Map:** OSM tile-grid bitmap with a marker pin composited on top (= D4), sized from the widget's actual width.
- **Staleness:** always show the map; the age text conveys how stale it is (= D5), and fixes older than 10 minutes are dimmed with a "Stale location" note.

## Resolved (2026-11-21) — no open questions remain

- **Trail:** latest point only. A single marker, nothing drawn behind it.
- **Battery:** not shown. The widget is map + display name + age.

Spec is complete and approved. Ready to build.

## Acceptance criteria

Verified by unit test + compilation + manifest inspection only. **No device is
available, so device-observed behaviour must never be claimed.**

1. `AppWidgetProvider` is declared in `AndroidManifest.xml` with
   `android:exported="false"` and an `appwidget-provider` XML metadata resource.
2. The widget renders: map bitmap, person's display name, and an age string
   derived from `age_seconds` ("just now" / "N min ago" / "N h ago" / "N days ago").
3. A pure function maps `age_seconds` -> display string, unit-tested at
   boundaries (0, 59, 60, 3599, 3600, large, days), plus a pure staleness
   decision past 600 seconds.
4. A pure function maps `(people, chosenUsername)` -> the widget's view state,
   including: person present; person absent but shared-before; empty list;
   HTTP 500. Unit-tested for all four.
5. `age_seconds` missing or null does not crash — falls back to
   `timestamp_unix`, then to "unknown age".
6. WorkManager periodic work is scheduled with a 15-minute interval and a
   network constraint, and survives reboot (the app already has a
   `BOOT_COMPLETED` receiver).
7. Tile fetch failure yields a widget showing text + a placeholder, never a
   crash and never a blank widget.
8. No new cleartext permission; no trust-all TrustManager introduced.
9. `./gradlew assembleDebug` succeeds and the full existing test suite still
   passes (currently 370+ tests — none may regress).

## Out of scope

- Any new server endpoint or server change.
- Sharing *out* (granting others access to your location).
- Live/continuous tracking of the other person — refresh is periodic only.
- Widget for your *own* location (the app's existing screens cover that).

## Estimated work

New files: `widget/BeakonWidgetProvider.kt`, `widget/WidgetState.kt` (pure),
`widget/WidgetRenderer.kt` (bitmap compositing), `widget/WidgetWorker.kt`,
`res/xml/beakon_widget_info.xml`, `res/layout/beakon_widget.xml`, plus tests.
Modified: `AndroidManifest.xml`, `AppContainer.kt`, `app/build.gradle.kts`
(WorkManager + a bitmap/map dependency), `AppPreferences.kt` (chosen person).
