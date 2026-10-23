# REQUIREMENTS — Make scrolling smooth

Repo: `~/projects/beakon-android` (branch **`main`**). 90 tests passing. Owner reports the
Status and history screens scroll slowly and feel laggy.

## Evidence already gathered — use it, do not re-derive it

Compose compiler reports are now emitted (the `composeCompiler {}` block in
`app/build.gradle.kts`; reports land in `app/build/compose_reports/`). Force them with:

```
./gradlew compileDebugKotlin --no-daemon --rerun-tasks
```

The compiler currently reports:
- `HistoryScreen`, `StatusScreen` and `HistoryRow` are all **restartable AND skippable**.
- `HistoryRow`'s parameter `point: LocationDto` is **stable**.
- `TrackingStatus` is **stable**. `AppViewModel` is **unstable** (it holds `mutableStateOf`
  fields) — but an identical instance still compares equal, so that alone is not the cause.
- There is **no timer/ticker** in `TrackingStateStore`, so status does not emit per frame.
- `Format` is fine: one shared `DateFormat`, and `serverTime` is a passthrough.

**Conclusion: recomposition is not obviously broken.** Do not invent a recomposition theory
that the reports contradict. The dominant cause is most likely that this ships as a **debug**
build — see requirement 1.

## 1. Ship an optimized (release) build — the main fix

`beakon-companion-debug.apk` is `debuggable=true` and unminified. ART does not run its
optimizing compiler for debuggable apps, and `debugImplementation(ui-tooling)` compiles the
Compose inspector in. This is the largest single lever available.

- Add a **`release` variant that is installable over the currently installed app** by signing it
  with the **debug keystore** (`signingConfig = signingConfigs.getByName("debug")`), so the owner
  does not have to uninstall and lose their settings. Say plainly in the README that this is a
  release-optimized build signed with the debug key, and why.
- Keep `isMinifyEnabled = false` **unless** you can show the minified build still works — this
  app uses kotlinx.serialization and Compose, and a wrong keep rule becomes a runtime crash the
  owner has to find. If you do enable R8, add the keep rules and say what you verified.
- The result must have `debuggable=false` — **prove it** with `aapt2 dump badging` (or by
  checking the manifest flag in the built APK), not by quoting the gradle file.
- Report both APKs' sizes; the release one should be meaningfully smaller.

## 2. Fix the genuine Compose issues

These are real even if they are not the dominant cost:

1. **`HistoryScreen`**: `items(viewModel.history.asReversed())` allocates a new list wrapper on
   every composition of the list content, and `items` is given **no `key`**, so item identity is
   positional. Give items a stable `key` (a location's identity — timestamp plus coordinates, or
   whatever the DTO guarantees unique), and do not call `asReversed()` inside composition.
2. **`StatusScreen`**: it uses `Column` + `verticalScroll`, which composes and lays out every
   child eagerly. That is acceptable for its current size — do **not** convert it to a lazy list
   blindly, since a `LazyColumn` inside a `Column` needs `weight(1f)` to be measured correctly
   and converting changes scroll semantics. Only change it if you can point to a concrete cost.
3. Read state at the lowest sensible level. `HistoryScreen` reads `viewModel.history`,
   `historyLoading` and `historyMessage` in the screen composable, so any of the three changing
   recomposes the whole screen including the list. Scope those reads so a loading-flag change
   does not invalidate the list.
4. If any composable takes an unstable parameter purely to read two fields, pass the fields
   instead. Do not blanket-annotate classes `@Stable`/`@Immutable` to silence the compiler —
   only annotate a type that genuinely is immutable, and say why.

## 3. Prove it with the compiler reports

Attach before/after evidence from `app/build/compose_reports/`:
- The count of skippable composables, before and after.
- Which composables changed skippability, if any.
- Note explicitly that the reports **cannot** show runtime frame times — they show
  recomposition structure only. Do not claim a measured improvement.

## 4. Do not break
- The interval setting, the accuracy threshold, boot-start, notification texts, rate-limit
  backoff, certificate pinning, and the tracking service behaviour.
- Keep the existing test suite green. Report the count before and after.

## Build and report
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/hermes/android-sdk ./gradlew assembleDebug assembleRelease --no-daemon`
- Report: both APK paths, sizes and sha256s; the `debuggable` flag of each as read from the APK;
  test count before and after; and the compiler-report evidence from requirement 3.

## Honesty
**There is no device or emulator here, so no frame timing can be measured.** You cannot claim
the app now scrolls smoothly — only that it is built in a mode that should be substantially
faster and that specific structural issues are fixed. Say exactly that, and state any deviation.
