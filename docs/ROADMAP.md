# KMP WorkManager — Roadmap

Companion to `CHANGELOG.md` and the v2.4.3 architecture review. This is the
forward-looking commitment; the changelog is the rear-view mirror.

Status legend: ✅ done · 🚧 in progress · ⏳ planned · 💭 idea / unscheduled.

---

## v2.5 — production hardening + Flutter parity (Group 1)

**Theme:** unblock production camera-app adoption. Everything in this milestone
is either (a) a correctness fix for a bug discovered in the v2.4.3 architecture
review, or (b) a Flutter-parity feature that camera workflows depend on. The
[Flutter `native_workmanager`](https://github.com/Brewkits/native_workmanager)
already has these features in production; KMP catches up here.

### P0 — shipped in 2.5

- ✅ `FileCompressionWorker` (iOS) — opt-in fallback, fail-fast default. The
  default behavior used to silently copy the file uncompressed. See
  `FileCompressionConfig.allowIosUncompressedFallback`.
- ✅ README built-in worker matrix — `HttpDownloadWorker` /
  `HttpUploadWorker` flagged as experimental; iOS `FileCompressionWorker`
  status documented honestly.
- ✅ `PendingIntent` request code unified on CRC32 (`PendingIntentCodes`) —
  `String.hashCode()` collisions on UUID-style IDs were splitting
  `FLAG_UPDATE_CURRENT` alarms across reboots. Adversarial test
  (`PendingIntentCodesAdversarialTest`) proves the collision exists for the
  canonical `"Aa"`/`"BB"` pair and that CRC32 distinguishes them.
- ✅ `BaseAlarmReceiver` migrated to a structured `SupervisorJob` + per-call
  scope + `withTimeout(workTimeoutMs)`. The previous `CoroutineScope(IO).launch`
  pattern leaked work past the BroadcastReceiver lifetime.

### P1 — landing in 2.5

**Correctness (architecture review fallout)**
- ✅ `WorkerResult.Retry(reason, delayMs, attemptCap)` — explicit retry signal
  alongside the legacy `Failure(shouldRetry = true)`. Android maps to
  `Result.retry()` with an attempt-cap ceiling; iOS captures into telemetry.
- ✅ `HttpDownloadWorker` resumable downloads via `<savePath>.partial` +
  HTTP `Range`. Camera-media downloads on cellular survive process kill / retry
  loops without restarting from byte 0.
- ✅ CI matrix — Android API 28/30/33/35 instrumented, iOS 16/17/18 simulator,
  Robolectric unit tests on Ubuntu, KSP processor tests isolated.
- ✅ Static analysis — CodeQL (`java-kotlin`) on every PR + weekly schedule;
  Dependabot grouping `kotlin-toolchain` / `ktor` / `coroutines` / `androidx` /
  `compose`, ignoring major bumps that need coordinated migration.
- ✅ Maven Central bundle — `generateFullMavenZip` produces a signed bundle
  (3 modules × 4 platforms × .asc/.md5/.sha1/.sha256/.sha512) ready for manual
  upload via the Sonatype Central Portal UI. Upload remains a maintainer-driven
  click; CI does not push automatically.
- ✅ SSRF blocklist — RFC 6598 CGNAT `100.64.0.0/10` (Tailscale + ISP CGNAT)
  and `0.0.0.0/8` ("this network") blocked. Tests pin the /10 boundary.

**Flutter parity — built-in workers (Group 1)**
- ✅ Checksum verification for `HttpDownloadWorker` — `expectedChecksum` +
  `ChecksumAlgorithm` (MD5 / SHA-1 / SHA-256 / SHA-512) on top of Okio's
  `HashingSource`. Mismatch deletes the partial and Fails (not Retry — the
  bytes on disk are demonstrably wrong, CDN cache pinning is the usual root cause).
- ✅ `DuplicatePolicy` enum on `HttpDownloadConfig` — `OVERWRITE` (default,
  preserves pre-v2.5 behaviour), `SKIP` (return Success without network call),
  `RENAME` (append `_1`, `_2`, … to the stem). Bounded at 10 000 suffix probes
  so a directory full of `photo_*.jpg` cannot hang the worker.
- ✅ `ParallelHttpDownloadWorker` — splits a file into N (1..16, default 4)
  HTTP `Range` chunks, downloads concurrently, persists `.partN` for per-chunk
  resume, merges into the final file. Automatic sequential fallback when the
  server returns no `Content-Length` or no `Accept-Ranges: bytes`. Per-chunk
  resume skips parts whose `.partN` matches the expected slice size exactly
  (proven by `parallel_resumesPreviousAttempt_whenPartFilesAreComplete`).
- ✅ `ParallelHttpUploadWorker` — one POST per file with `maxConcurrent`
  (1..16, default 3) per-host limit, `maxRetries` (0..5, default 1) on 5xx /
  network errors only (4xx is never retried), per-file `ParallelUploadFileResult`
  surfaced through `WorkerResult.Success.data.fileResults`.
- ✅ `IosBackgroundDownloadWorker` + `IosBackgroundUrlSessionManager` —
  experimental scaffold for downloads that survive **full app termination**
  via `URLSessionConfiguration.background`. Host integration required, see
  [`docs/IOS_BACKGROUND_URL_SESSION.md`](./IOS_BACKGROUND_URL_SESSION.md).
  The worker returns `Success` as soon as the OS accepts the request;
  completion is delivered later via `TaskEventBus`.

### P1.6 — pulled in after QA/QC review (Senior Dev lens)

- ✅ File-size-based compaction trigger for `AppendOnlyQueue` — addresses the
  enqueue-heavy workload edge case where the ratio-based trigger (80 % processed)
  never fires. New trigger: file > 5 MB AND ≥ 20 % processed AND ≥ 50 processed
  items. Pinned by `QueueScaleStressTest.fileSizeCompaction_reclaimsSpaceAfterDequeue`.
- ✅ Backward-compatibility regression net — `BackwardCompatibilityTest` pins
  that v2.4.3-shaped `ChainProgress` JSON files load on v2.5.0 without data
  loss (additive `stepRetryCounts` defaults to `emptyMap()`), tolerates
  hypothetical v2.6+ unknown fields, and self-heals corrupt input rather than
  throwing.
- ✅ Scale stress test — `QueueScaleStressTest.enqueue_2k_dequeue_2k_correctnessAtScale`
  guards against accidental O(N²) regressions (would push runtime past 5 min
  ceiling) and complements the existing 200-op test in `AppendOnlyQueueTest`.
- ✅ `docs/APPLE_APP_STORE_REVIEW_GUIDELINES.md` — App Store §2.5.4 compliance
  guide for dynamic task dispatch under one `BGTaskScheduler` identifier.

### P1.5 — pulled into 2.5 after architecture re-review

- ✅ iOS chain retry semantics — `WorkerResult.Retry.delayMs` / `attemptCap`
  honored at the executor level. `ChainProgress.stepRetryCounts` tracks per-step
  attempts across BGTask invocations; `ChainExecutor.requestedNextBgTaskDelayMs`
  exposes the max delay hint so Swift hosts can re-arm `BGProcessingTaskRequest`
  with `earliestBeginDate = now + delayMs`. Adversarial coverage:
  `ChainProgressRetryTest` proves step counter is independent from chain-level
  `retryCount`.
- ✅ `KmpHeavyWorker.foregroundServiceType` is now overrideable. Camera-app
  transcoders can set `FGS_MEDIA_PROCESSING` (Android 15+) instead of inheriting
  the silently-wrong `dataSync` default that would trigger Play Store policy
  flags. Companion-object aliases (`FGS_DATA_SYNC`, `FGS_MEDIA_PROCESSING`,
  `FGS_CAMERA`, …) avoid forcing host code to import
  `android.content.pm.ServiceInfo`. Coverage: `KmpHeavyWorkerFgsTypeTest`.
  Manifest snippets per type: [`docs/ANDROID_FGS_GUIDE.md`](./ANDROID_FGS_GUIDE.md).

### Tracked but deferred to 2.6 (v2.5 stretch ↛ not shipped)

- 🚧 `IosFileStorage` SRP split — stage 0 design lock-in
  (`docs/internal/IOS_FILE_STORAGE_SPLIT.md`) + `storage/BaseDirectory.kt`
  scaffold committed; per-store extraction across stages 1–5 still pending.
- ⏳ `IosBackgroundDownloadWorker` polish — authentication challenges, TLS
  pinning hook, upload variant (background URL session uploads).

---

## v2.6 — DX & operability (P2 — "nice to have")

**Theme:** make the library easy to operate. The functionality already works;
v2.6 polishes the rough edges that surface during on-call.

### 1. Foreground service guidance (Android 14 / 15) — ✅ shipped in 2.5
- ✅ `docs/ANDROID_FGS_GUIDE.md` with manifest snippets per FGS type
  (`mediaProcessing`, `dataSync`, `connectedDevice`, …).
- ✅ Runtime permission table for `FOREGROUND_SERVICE_*` siblings introduced
  in API 34, including the Android 15 6-hour `dataSync` cap.
- ✅ `KmpHeavyWorker.foregroundServiceType` is overrideable via
  `protected open val`, with companion-object aliases (`FGS_DATA_SYNC`,
  `FGS_MEDIA_PROCESSING`, `FGS_CAMERA`, …). Coverage:
  `KmpHeavyWorkerFgsTypeTest`.
- ⏳ Stretch: lint-style runtime check that the host manifest declares a
  matching `<service android:foregroundServiceType=…>` entry.

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

### 6. Flutter parity — Group 2 built-in workers
- ⏳ **HMAC-SHA256 request signing** (`request_signing.dart` parity) —
  canonical format `METHOD\nURL\nBODY\nTIMESTAMP` → HMAC-SHA256 → header
  `X-Signature` + optional `X-Timestamp`. Configurable secret key (min
  16 chars), header name, prefix (`sha256=` for GitHub webhook style),
  `signBody` and `includeTimestamp` flags.
- ⏳ **Token refresh on 401** (`token_refresh_config.dart` parity) — when a
  request returns 401, POST a configurable refresh endpoint, extract the new
  token via dot-notation key (`auth.access_token`), retry the original
  request. Mirrors the Flutter config 1-to-1.
- ⏳ **Bandwidth throttling** — token-bucket on download/upload bytes-per-second.
  Less critical than the others; Android already exposes
  `Constraints.requiresUnmeteredNetwork` for the "Wi-Fi only" axis.

### 7. iOS ZIP compression via zlib cinterop
- ⏳ Replace the `allowIosUncompressedFallback` opt-in with a real ZIP
  implementation backed by `/usr/lib/libz.dylib` (zlib is part of the iOS SDK,
  no third-party Swift package needed). Plan: small Kotlin wrapper that emits
  the ZIP container (local file headers + central directory + EOCD) and
  delegates the compressed payload to `deflate`. ~150 lines + cinterop stub.
  This closes the camera-app blocker called out in
  [`docs/IOS_BGTASK_LIMITS.md`](./IOS_BGTASK_LIMITS.md) §4.

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

### 4. Flutter parity — Group 3 built-in workers (long tail)
- 💭 **Image processing worker** — resize (maxWidth/maxHeight + maintain aspect
  ratio), crop(x, y, w, h), format convert (JPEG ↔ PNG ↔ WEBP), quality 0-100.
  Requires platform-specific image decoder bindings (`UIImage` on iOS,
  `BitmapFactory` on Android) — non-trivial cinterop scope.
- 💭 **Typed result classes** — `DownloadResult`, `ParallelUploadResult` as
  data classes with computed properties (`successCount`, `failedCount`,
  `totalBytes`) instead of raw `JsonObject?`. Cleaner consumer ergonomics but
  requires a parallel typed-result surface and a deserialization story for
  cross-process delivery.

---

## How to suggest a change

1. Open an issue tagged `roadmap` with a 3-line summary.
2. Link the closest in-repo prior art (file path or test name).
3. If it's a P0/P1, propose how it should be tested.

The bar for P0 is "we have evidence a production user hit this." Everything
else lives under P1 / P2 / P3 until a real signal appears.
