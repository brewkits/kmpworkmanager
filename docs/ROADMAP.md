# KMP WorkManager — Roadmap

Companion to `CHANGELOG.md` and the v2.4.3 architecture review. This is the
forward-looking commitment; the changelog is the rear-view mirror.

Status legend: ✅ done · 🚧 in progress · ⏳ planned · 💭 idea / unscheduled.

---

## v2.5 — production hardening (in flight)

**Theme:** unblock production camera-app adoption. Everything in this milestone
is either a correctness fix or removes a footgun that we have evidence has bit
real users.

### P0 — shipped in 2.5
- ✅ `FileCompressionWorker` (iOS) — opt-in fallback, fail-fast default. The
  default behavior used to silently copy the file uncompressed. See
  `FileCompressionConfig.allowIosUncompressedFallback`.
- ✅ README built-in worker matrix — `HttpDownloadWorker` /
  `HttpUploadWorker` flagged as experimental; iOS `FileCompressionWorker`
  status documented honestly.
- ✅ `PendingIntent` request code unified on CRC32 (`PendingIntentCodes`) —
  `String.hashCode()` collisions on UUID-style IDs were splitting
  `FLAG_UPDATE_CURRENT` alarms across reboots.
- ✅ `BaseAlarmReceiver` migrated to a structured `SupervisorJob` + per-call
  scope + `withTimeout(workTimeoutMs)`. The previous `CoroutineScope(IO).launch`
  pattern leaked work past the BroadcastReceiver lifetime.

### P1 — landing in 2.5
- ✅ `WorkerResult.Retry(reason, delayMs, attemptCap)` — explicit retry signal
  alongside the legacy `Failure(shouldRetry = true)`. Android maps to
  `Result.retry()` with an attempt-cap ceiling; iOS captures into telemetry
  (chain executor honoring is tracked under "iOS chain retry semantics" below).
- ✅ `HttpDownloadWorker` resumable downloads via `<savePath>.partial` +
  HTTP `Range`. Camera-media uploads on cellular survive process kill / retry
  loops without restarting from byte 0.
- ✅ CI matrix — Android API 28/30/33/35 instrumented, iOS 16/17/18 simulator,
  Robolectric unit tests on Ubuntu, KSP processor tests isolated.
- ✅ Static analysis — CodeQL (`java-kotlin`) on every PR + weekly schedule;
  Dependabot grouping `kotlin-toolchain` / `ktor` / `coroutines` / `androidx` /
  `compose`, ignoring major bumps that need coordinated migration.
- ✅ Maven Central auto-publish — Sonatype Central Portal API integrated into
  `release.yml`. `USER_MANAGED` by default (one manual click in the portal);
  `AUTOMATIC` opt-in via workflow input.
- ✅ SSRF blocklist — RFC 6598 CGNAT `100.64.0.0/10` (Tailscale + ISP CGNAT)
  and `0.0.0.0/8` ("this network") blocked. Tests pin the /10 boundary.
- 🚧 `IosFileStorage` SRP split — stage 0 design lock-in
  (`docs/internal/IOS_FILE_STORAGE_SPLIT.md`) + `storage/BaseDirectory.kt`
  scaffold committed; per-store extraction across stages 1–5 still pending.

### Tracked but not yet started (v2.5 stretch goals)
- ⏳ iOS chain retry semantics — `WorkerResult.Retry.delayMs` / `attemptCap`
  honored at the executor level (re-arm `BGProcessingTaskRequest` with
  `earliestBeginDate = now + delayMs`; persist attempt counter per step).
- ⏳ `HttpUploadWorker` chunked / resumable upload (`Content-Range` writes,
  server-side ETag continuation).

---

## v2.6 — DX & operability (P2 — "nice to have")

**Theme:** make the library easy to operate. The functionality already works;
v2.6 polishes the rough edges that surface during on-call.

### 1. Foreground service guidance (Android 14 / 15)
- ⏳ Add a `docs/ANDROID_FGS_GUIDE.md` with manifest snippets per FGS type
  (`mediaProcessing`, `dataSync`, `connectedDevice`, `shortService`).
- ⏳ Document the runtime permission table for `FOREGROUND_SERVICE_*` siblings
  introduced in API 34.
- ⏳ Add a `KmpHeavyWorker` constructor parameter (or `KmpWorkManagerConfig`
  setting) to declare the FGS type, with a lint-style runtime check that the
  host manifest matches.
- 💭 Optional: pre-baked `BaseFgsForegroundService` that consumers extend, so
  the library hosts the foreground notification instead of every app
  re-implementing it.

### 2. Threat model + SRE runbook
- ⏳ `docs/THREAT_MODEL.md` — STRIDE table for the scheduler, persistence
  layer, and built-in HTTP workers. Spell out what the library does and does
  NOT defend against (DNS rebinding, malicious worker, App Group container
  cross-app reads, …).
- ⏳ `docs/SRE_RUNBOOK.md` — "task X is silently not running, what do I check?"
  decision tree. Should cover: low-power-mode rejection, BGTaskScheduler
  budget exhaustion, alarm permission revocation, FGS type mismatch on
  Android 14+.

### 3. iOS Live Activity helper
- ⏳ Wrap `ActivityKit.Activity` from Kotlin/Native so camera apps can show
  upload progress on the Lock Screen / Dynamic Island while a chain is
  running. KMP-friendly contract: `LiveActivityChannel` flow that the host
  app subscribes to from Swift (no `ActivityKit` Kotlin types — let the host
  own the UI).
- 💭 Companion `LiveActivityTaskCompletionEvent` on `TaskEventBus` so the
  host app does not have to track chain IDs manually.

### 4. Per-task QoS profiles
- ⏳ Introduce `TaskQoSProfile` enum:
  - `MEDIA_UPLOAD_OVER_WIFI` — `requiresUnmeteredNetwork = true`,
    `priority = HIGH`, FGS type `dataSync` on Android.
  - `META_SYNC_BACKGROUND` — `requiresNetwork = true`, `priority = LOW`,
    cellular OK, no FGS.
  - `CRITICAL_DEFERRED` — `priority = CRITICAL`, retries forever within
    scheduler quota.
- ⏳ Profile maps to `Constraints` + `TaskPriority` so existing API stays
  source-compatible.

### 5. DI-agnostic init
- ⏳ Replace internal Koin dependency with a private `ServiceLocator` that
  consumers can populate without bringing Koin into their classpath. Koin
  module stays as an opt-in convenience.
- ⏳ Provide a Hilt-friendly `KmpWorkManagerHiltModule` for Android consumers
  who already use Hilt.

---

## v3.0 — long-term (P3)

**Theme:** the library's foundation can be sturdier. These are non-trivial
projects and warrant a major-version bump. None are scheduled — call this a
"directional roadmap."

### 1. ChainExecutor → explicit state machine
- 💭 The current `ChainExecutor` (1,505 lines) hides its lifecycle inside
  imperative coroutine code; recovery from process death depends on subtle
  ordering of progress writes vs. step execution. A typed state machine
  (`enum ChainState { PENDING, EXECUTING_STEP(idx), AWAITING_RETRY(idx, until), … }`)
  with a transition log unlocks:
  - **Fuzz testing** — generate random transition sequences and assert that
    every reachable state is recoverable from disk.
  - **Observability** — a single transition log line per state change for
    `SRE_RUNBOOK.md`.
  - **Reasoning** — invariants (e.g. "progress is persisted before
    `EXECUTING_STEP(i)` advances to `EXECUTING_STEP(i+1)`") become explicit
    rather than implicit in code order.

### 2. wasmJs target
- 💭 Add `wasmJs()` target so the Compose-Web demo app can use the same
  scheduler/contracts as Android/iOS. The web scheduler would be a thin
  `setTimeout`-backed implementation that respects the same `Constraints` API
  but obviously cannot persist past page reload. Useful for documenting the
  API in a runnable playground.

### 3. Gradle plugin `io.brewkits.kmpworker`
- 💭 Eliminate the manual `Info.plist` + `AndroidManifest.xml` declarations.
  The plugin reads `@Worker(bgTaskId = …)` annotations and emits the iOS
  permitted-identifiers array + the Android `<service android:foregroundServiceType=…>`
  block. Today these are easy to forget, and the failure mode is silent
  (tasks "just don't run").
- 💭 Stretch: synthesize the boot receiver + Hilt module so consumers can drop
  the plugin and have zero manual wiring.

---

## How to suggest a change

1. Open an issue tagged `roadmap` with a 3-line summary.
2. Link the closest in-repo prior art (file path or test name).
3. If it's a P0/P1, propose how it should be tested.

The bar for P0 is "we have evidence a production user hit this." Everything
else lives under P1 / P2 / P3 until a real signal appears.
