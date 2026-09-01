# KMP WorkManager — Roadmap

Companion to `CHANGELOG.md` and the v2.4.3 architecture review. This is the
forward-looking commitment; the changelog is the rear-view mirror.

Status legend: ✅ done · 🚧 in progress · ⏳ planned · 💭 idea / unscheduled.

---

## Next up (post-3.3.1) — the "irreplaceable, even for native-only teams" bar

**Theme:** every milestone through 3.3.1 below made the library more correct or
more DX-friendly for teams *already* using it across both platforms. These two
are chosen against a sharper bar: would a team building for **one platform
only** — no shared-code motive at all — still pick this library over the
platform's raw API? "Shared code" is worth nothing to that team, so the pitch
has to come from somewhere else.

- ⏳ **Flutter parity Group 2 — token refresh on 401 + HMAC request signing**
  ([#81](https://github.com/brewkits/kmpworkmanager/issues/81)). Pitch: WorkManager/BGTaskScheduler give you
  primitives; this ships a tested implementation of the auth-refresh and
  request-signing logic most teams under-build (or skip) themselves. Promoted
  out of the deferred v2.6 #6 slot below — see that entry for the full spec,
  now superseded by #81 as the tracking issue.
- ⏳ **Android FGS-type diagnosability** ([#82](https://github.com/brewkits/kmpworkmanager/issues/82)).
  Pitch: on iOS, `@Worker(bgTaskId=…)` + the KSP-generated `BgTaskIdProvider`
  already `require()`-crashes loudly at `KmpWorkManager.initialize()` if a
  `bgTaskId` is missing from `Info.plist` — Android has no equivalent
  registration-time hook for a missing/mismatched `android:foregroundServiceType`.
  `KmpHeavyWorker.doWork()` already catches and logs the `setForeground()`
  exception (see `KmpHeavyWorker.kt`), but the message names only what the
  worker *declared*, not what the manifest actually has, and on API 29-33
  a mismatch doesn't throw at all (`ForegroundServiceTypeException` is
  API 34+) — a real silent-failure window on those OS versions. #82
  originally proposed a Gradle plugin generating/validating manifest entries
  from `@Worker` annotations at build time; investigation found `@Worker` is
  `SOURCE`-retention (invisible to a Gradle plugin without a new KSP→Gradle
  pipeline) and `foregroundServiceType` is a computed `open val`, not a
  literal KSP can resolve — so static analysis can't reliably cover this.
  Revised to reading the actual merged manifest via
  `PackageManager.getServiceInfo()` inside `doWork()`: enrich the existing
  exception log with the manifest's declared type, and add a proactive
  `Logger.w` on API 29-33 where nothing throws today. No new module,
  diagnostics-only (never fails a worker on its own). See #82 for the
  corrected scope.

Both selected over other v2.6/v3.0 candidates (per-task QoS profiles, threat
model docs, `ChainExecutor` state machine, `wasmJs` target) specifically
because those don't clear the bar above — a native-only team can build QoS
profiles or a state machine themselves without losing much, and docs alone
don't create lock-in.

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

### P0.6 — second-pass QA double-check fixes (4 critical bugs)

A double-check audit after the initial QA review surfaced four more critical
bugs that the first pass missed. All four are real and verified against the
source; all are fixed in v2.5.0 before publish:

- ✅ **Queue full-reset on CRC corruption** (data loss, `AppendOnlyQueue.dequeue`).
  When `readSingleRecordWithValidation` set `corruptionOffset` precisely at the
  corrupt record, the `dequeue` else-branch unconditionally overwrote it to
  `0UL`, forcing `truncateAtCorruptionPoint` to wipe the entire queue. Fix:
  added `else if (isQueueCorrupt)` guard so the precise offset is preserved.
  Coverage: `AppendOnlyQueueCrcCorruptionTest`.
- ✅ **`BackgroundDownloadStateStore.getSync` cache stale-write race**. The
  pre-fix code `cache ?: readUnlocked().also { cache = it }` had a window where
  a concurrent `put`/`remove` could land between observing `cache == null` and
  the write-back; the write-back would then clobber the fresh cache with a
  stale disk snapshot — re-introducing the cold-launch orphan bug v2.5 was
  supposed to fix. Fix: compare-and-set publish (only-if-still-null). Coverage:
  `BackgroundDownloadStateStoreTest.getSync_doesNotClobberCacheFromConcurrentPut`.
- ✅ **`ChainExecutor` battery monitoring side-effect**. The chain executor
  unconditionally set `UIDevice.batteryMonitoringEnabled = true; …; = false`,
  clobbering any host-app prior state. Fix: capture
  `hostHadMonitoringEnabled` and only toggle if the host had it off.
- ✅ **`ParallelHttpDownloadWorker` retry storm on disk-full**. Pre-fix the
  merge-failure catch kept `.partN` files; on retry `downloadOneChunk` would
  skip the network (parts match expected sizes) and `mergeChunks` would fail
  again with the same error, looping forever until the user freed disk space.
  Fix: (a) merge-failure catch now purges `.partN` as well so retry must
  re-download, (b) outer Retry now carries `attemptCap = 4` to cap the loop
  at ~1 min regardless. Coverage:
  `ParallelHttpDownloadWorkerTest.parallel_mergeFailure_cleansUpAllParts_breaksRetryStorm`
  + `parallel_outerRetryHasAttemptCap_toBreakInfiniteLoop`.

### P0.5 — critical bug fix after QA/QC review (Senior Dev lens)

- ✅ `IosBackgroundUrlSessionManager` cold-launch survival. The pre-v2.5.0
  manager kept `savePaths`/`taskNames` only in process memory. When iOS killed
  the app and cold-launched it to deliver a `URLSession` delegate callback, the
  in-memory map was empty and the downloaded file was orphaned in
  `NSTemporaryDirectory` while no completion event was ever emitted.
  v2.5.0 backs the mapping with a JSON file in Application Support
  (`BackgroundDownloadStateStore`). The store keys by `(sessionIdentifier,
  taskIdentifier)` to disambiguate iOS's per-session task ID recycling.
  Synchronous disk read in the delegate ensures the file move completes before
  iOS reclaims the temp file. Atomic writes (`writeToURL(atomically=true)`)
  survive power loss. Adversarial coverage:
  `BackgroundDownloadStateStoreTest` proves: round-trip, simulated cold-launch
  via cache invalidation, `(session, task)` disambiguation, idempotent remove,
  stale-entry sweep.

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

## v2.5.1 — polish patch (P2/P3 follow-up to the 16-bug audit)

**Theme:** ship 2.5.0 as-is, then land these polish items as a patch once the
mainline release has been dogfooded for ~1–2 weeks. Neither is a correctness
blocker, but both are technically-improvable structural choices surfaced
during the late-cycle audit reviews.

### 1. Replace `elapsedMs < timeout` heuristic with `withTimeoutOrNull` — ✅ shipped in 3.3.0

- ✅ **What**: the BUG 10 / BUG 11 fixes used `elapsedNow().inWholeMilliseconds
  < chainTimeout` (resp. `taskTimeout`) inside `catch (TimeoutCancellationException)`
  to disambiguate inner-vs-outer cancellation. The heuristic was correct under
  normal load but could be defeated by CPU starvation / iOS process throttling
  that pauses the thread between the outer-TCE arrival and the inner catch's
  `.elapsedNow()` read — pushing `elapsed` past `chainTimeout` even when the
  outer scope was the actual canceller.
- ✅ **Fix**: `ChainExecutor.executeChain` and the task-level execution inside
  `executeStep` now wrap their inner blocks in `withTimeoutOrNull(chainTimeout)` /
  `withTimeoutOrNull(taskTimeout)` instead of `withTimeout(...)`. `null` return
  = inner timer fired (handled as chain/task timeout); a
  `TimeoutCancellationException` reaching the catch block can now only have
  come from an outer scope, since kotlinx.coroutines identity-checks the
  exception against the coroutine `withTimeoutOrNull` created internally
  before ever converting it to `null`. No timing heuristic left anywhere on
  this path.
- ✅ **Coverage**: `V250NestedTimeoutMisattributionTest` and
  `V250TaskTimeoutMisattributionTest` continue to pass unchanged (their
  docstrings now point at the new mechanism). `V330TimeoutIdentityDisambiguationTest`
  pins the underlying kotlinx.coroutines guarantee directly — the
  CPU-starvation-style race can't be reproduced cheaply as a test (it needs a
  real multi-second scheduling stall), so this proves the mechanism has no
  window for it instead of trying to reproduce the race itself.

### 2. File-backed `OverflowFileRegistry` for Android multi-process apps — ✅ shipped in 3.3.0

- ✅ **What**: `OverflowFileRegistry` (Android) stored `taskId → overflow
  path` mappings in a `SharedPreferences` (`MODE_PRIVATE`). On hosts with
  separate processes (`:background`, `:push`, …), each process holds its own
  in-RAM cache of the prefs file. A `register()` in process A and a
  `consumeAndDelete()` in process B could race: B's cached view doesn't see A's
  write → returns null → overflow file leaks in `cacheDir` until the 24h
  janitor sweeps it.
- ✅ **Fix**: replaced SharedPreferences with a file-backed registry under
  `cacheDir/overflow_registry/<encoded taskId>.path` — one file per entry,
  atomic temp-file-then-rename for writes, single `delete()` for consumption.
  Process-local caching is a non-issue because there is no cache — every read
  goes straight to the shared filesystem. Task ids are caller-supplied
  (`BackgroundTaskScheduler.enqueue(id: String, ...)`), so the filename is
  derived via an injective percent-encoding (not a hash — a collision there
  would silently merge two unrelated tasks' entries) that also blocks path
  traversal (`../../etc/passwd`-shaped ids can't escape the registry dir).
- ✅ **Migration**: zero-config. The first `register`/`consumeAndDelete` call
  in a process reads any legacy `SharedPreferences` entries once, writes them
  into the new file layout, and clears the prefs — idempotent and safe to run
  redundantly from multiple processes.
- ✅ **Coverage**: `OverflowFileRegistryTest` gained cases for legacy-prefs
  migration (including idempotent re-migration), collision-free encoding for
  similar-looking ids, and hostile/path-traversal ids being contained and
  still round-tripping correctly through the public API. The genuine
  multi-process race is not reproducible in-process (Robolectric can't spawn
  a second real process) — the fix removes the caching layer the race
  depended on entirely rather than attempting to reproduce it.

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

### 3. iOS Live Activity helper — ✅ shipped in 3.2.0
- ✅ `IosLiveActivityBridge` (expect/actual) — lightweight Kotlin/Native bridge that subscribes
  to `TaskProgressBus` and delivers `WorkerProgress` events to Swift callbacks on the main
  thread. Swift host owns all `ActivityKit` types; no ActivityKit symbols leak into Kotlin.
  iOS actual uses `CoroutineScope(Dispatchers.Main + SupervisorJob)`; Android is a no-op stub.
  See [`docs/IOS_LIVE_ACTIVITIES.md`](./IOS_LIVE_ACTIVITIES.md) for full Swift integration guide.

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

### 5. DI-agnostic init — 🚧 moved to its own milestone
Promoted out of v2.6 and rescoped after [discussion #66](https://github.com/brewkits/kmpworkmanager/discussions/66).
See [v3.3 — DI-agnostic init](#v33--di-agnostic-init-koin-removal) below.

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

### 7. iOS ZIP compression via zlib cinterop — ✅ shipped in 3.2.0
- ✅ `FileCompressionWorker.ios.kt` rewritten with a real PKZIP/DEFLATE writer backed by
  system `platform.zlib` — no external Swift packages. Streams input in 64 KiB chunks via
  `deflate()` for O(1) RAM footprint. Output passes PKZIP local-header magic (`0x04034b50`)
  and EOCD signature checks. `allowIosUncompressedFallback` deprecated and ignored.
  `FileCompressionWorker` is now **Stable on both platforms**.

---

## v3.3 — DI-agnostic init (Koin removal) — ✅ shipped in 3.3.0

**Theme:** a background-task library should not force a DI framework onto its
consumers. Raised by an outside library author in
[discussion #66](https://github.com/brewkits/kmpworkmanager/discussions/66); the
complaint is correct on the facts — `koin-core:4.0.0` ships at `runtime` scope in
the published `.pom`/`.module` for the root, `-android` and all three iOS variants,
so consumers who never touch Koin still carry it.

**Where the coupling actually is (audited at 3.2.0):**
- **Android** is already Koin-free at the API surface. `KmpWorkManager.initialize()`
  exposes no Koin types; behind it sits a *private* `koinApplication {}` (never
  `startKoin` / `GlobalContext`), covered by `KoinIsolationTest`. The Koin-exposing
  `kmpWorkerModule()` overload has been `@Deprecated` since 2.2.2.
- **iOS** is genuinely coupled. `kmpWorkerModule()` is the only documented iOS init
  path, and it is declared in commonMain as
  `expect fun kmpWorkerModule(...): org.koin.core.module.Module` — a public function
  returning a type from an `implementation`-scoped dependency, which forces consumers
  to declare koin-core themselves just to call it.
- The entire koin-core surface is 5 files: `KoinModule.kt`, `KoinModule.android.kt`,
  `KoinModule.ios.kt`, `KmpWorkManagerKoin.kt` (+ `KoinIsolationTest.kt`).
- `KoinModule.ios.kt` uses **no** DI-container feature — no scopes, qualifiers,
  `parametersOf`, or lazy graph. Everything meaningful (logger config,
  `KmpWorkManagerRuntime.configure`, the `IosWorkerFactory` type check, the Info.plist
  `BGTaskSchedulerPermittedIdentifiers` validation) runs at module-*construction* time,
  and all six wired classes are already public. Extraction is mechanical.

### Step 1 — patch (3.2.1) — ✅ done
- ✅ Dropped `koin-android`. No production code in `androidMain` imports
  `org.koin.android.*`; the dependency was only surviving because it transitively
  supplied `androidx.core` (`NotificationCompat` in `KmpWorker` / `KmpHeavyWorker`).
  `androidx.core:core-ktx` is now declared directly, and `koin-android` moved to the
  `androidInstrumentedTest` source set where `KoinIsolationTest` actually uses it.
  `kmpworkmanager-android`'s POM now carries only `koin-core-jvm`.

### Step 2 — minor (3.3.0) — ✅ done, the actual fix
Removal and cleanup land together. A 3.3.0 that only *deprecated* `kmpWorkerModule()`
would leave koin-core in core's POM — `kmpWorkerModule` is an `expect fun` declared in
core's commonMain whose iOS `actual` calls `module { }`, so core keeps
`implementation(koin-core)` for as long as that declaration lives there. Deprecation does
not remove a dependency, and the POM is the thing consumers are complaining about. So the
declaration goes in the same release.

- ✅ Replaced the internal private `koinApplication` with a plain internal
  `AndroidServiceRegistry` / `IosServiceRegistry`. Touches the 5 bindings, the 3 service-locator call sites
  (`BaseKmpWorker.kt:52`, `KmpWorker.kt:32`, `KmpHeavyWorker.kt:43`) and the
  `internal constructor(Koin)` of the public `KmpWorkManagerInstance`.
  The registry must reproduce **both** resolution modes the Koin module relies on:
  lazy singletons *and* eager ones — `IosEventStore`'s provider calls
  `TaskEventManager.initialize(store)` as a resolution side effect, and Android's
  `ExecutionHistoryStore` is deliberately `createdAtStart = true`. Getting this wrong
  silently shifts `TaskEventManager` init timing. The iOS path must also preserve the
  `migrationComplete` ordering documented in `CLAUDE.md`.
- ✅ Added a Koin-free `KmpWorkManager.initialize(workerFactory, config, iosTaskIds)` on
  iOS, mirroring the Android entry point.
- ✅ Deleted `kmpWorkerModule()` / `kmpWorkerCoreModule()` and the `KoinModule*.kt` files.
  **koin-core is gone from all five publications** — verified against the generated POMs.
- ✅ `docs/MIGRATION_V3.3.0.md` — the migration is ~4 lines in one file:
  ```kotlin
  // before
  startKoin { modules(kmpWorkerModule(workerFactory = MyWorkerFactory())) }
  // after
  KmpWorkManager.initialize(workerFactory = MyWorkerFactory())
  startKoin { modules(module { single { KmpWorkManager.getInstance().backgroundTaskScheduler } }) }
  ```
  Koin and Hilt users keep working via that snippet; it is documentation, not an artifact.
- ✅ Invariant tests `V330KoinFreeInitTest` (iOS) + `V330AndroidRegistryTest` (Robolectric)
  pin the wiring: singleton identity, eager store registration, and that
  `requireRegistry()` still throws `IllegalStateException` — the deprecated worker
  constructors catch exactly that type to rethrow their actionable message.
- ✅ `KoinIsolationTest` retired along with its premise, and the `koin-android` test
  dependency with it.
- ✅ Docs sweep: `README.md` (iOS setup, the primary offender), `KmpWorkManagerConfig.kt`
  KDoc, `quickstart.md`, `platform-setup.md`, `examples.md`, `api-reference.md`,
  `kmpworker-ksp/README.md`, and the `composeApp` iOS sample.

**Deliberately not doing:** a `kmpworkmanager-koin` bridge artifact, or a
`KmpWorkManagerHiltModule`. Both were on the earlier version of this plan. Once
`initialize()` exists, wrapping it in a Koin module or a Hilt `@Provides` is a
three-line snippet in the consumer's own code; publishing a module × 4 targets × GPG
signing × manual Central upload to save those three lines is a permanent maintenance
cost for a one-time convenience.

**Why a minor and not a major:** deleting a public API is a breaking change by strict
semver, but this project has never held that line — v2.2.2, a *patch*, shipped "now
requires WorkerFactory parameter" with `docs/MIGRATION_V2.2.2.md`. A major bump for a
single dependency removal is a signal out of proportion to the work, and it would push
the fix consumers are asking for further away for no benefit.

---

## v3.3.1 — senior mobile QA/QC review pass — ✅ shipped

**Theme:** a full-library review (correctness, architecture, test-coverage gaps,
iOS/Android lifecycle & threading pitfalls) turned up six findings on top of
[issue #71](https://github.com/brewkits/kmpworkmanager/issues/71) (iOS single tasks not
persisting event/history, filed during 3.3.0 verification). None are regressions —
either narrow-trigger latent gaps or, in one case, code written for 3.3.0 that hadn't
reached Maven Central yet when the bug was found.

- ✅ **iOS: `SingleTaskExecutor` didn't persist events/history for non-chained tasks**
  (#71). Routed through `TaskEventManager.emit()` + `ExecutionHistoryStore.save()`,
  mirroring `ChainExecutor`/`BaseKmpWorker`. `V331SingleTaskPersistenceTest`.
- ✅ **iOS: `SingleTaskExecutor` used wall-clock for `ExecutionRecord.durationMs`** — the
  same NTP-drift risk `ChainExecutor` already guards against, found in the fix above
  before it shipped. Switched to `TimeSource.Monotonic`, matching `ChainExecutor`'s split.
- ✅ **KSP: colliding `@Worker` name/alias silently overwrote one worker in the generated
  factory** — no compile error, no warning. `WorkerProcessor` now `logger.error()`s.
  `WorkerProcessorDuplicateKeyTest` calls the validation directly, since
  `WorkerProcessorTest`'s entire 21-test compile-testing harness is `@Ignore`d
  (kctfork 0.6.0 never invokes the processor for in-memory sources — none of those tests
  run in CI today).
- ✅ **iOS: task/chain ids used unsanitized as filenames** at 13 `IosFileStorage` call
  sites — `safeAppend` guards only NPE, not traversal. Fixed via
  `String.encodeAsPathComponent()`, deliberately narrow (only `/`, bare `.`/`..`, and `%`
  for injectivity) so ordinary ids keep their exact pre-fix on-disk filename.
  `V331PathTraversalTest` covers both the pure encoder and the real `IosFileStorage` API.
- ✅ **`kmpworkmanager-http`: `User-Agent` hardcoded `"KmpWorkManager/2.3.4"`**, 13
  releases stale. `:kmpworker-http` now generates `LIBRARY_VERSION` from `VERSION_NAME`
  on every build.
- ✅ **`kmpworkmanager-http`: SSRF-aware redirect-following interceptor duplicated
  verbatim** between the Android and iOS `HttpClientProvider`s. Extracted to
  `HttpClient.installSecureRedirectFollowing()` in commonMain — one copy of
  security-critical logic instead of two that could silently drift apart.

**Verification:** 887 iOS + 503 Android + 29 KSP + 38×2 `kmpworker-http` tests, 0
failures across all of #71/finding-1/finding-2/finding-3/findings-4-5's PRs
(#72, #73, #74).

---

## v3.0 — long-term (P3)

**Theme:** the library's foundation can be sturdier. These are non-trivial
projects and warrant a major-version bump. None are scheduled — call this a
"directional roadmap."

### 1. iOS 26 — `BGContinuedProcessingTask` support

> **Status: ⏳ Planned — pending iOS 26 GM & Kotlin/Native binding availability.**
> iOS 26 was announced at WWDC 2025. API is in Developer Beta; not stable until Q4 2026.

`BGContinuedProcessingTask` (new in `BackgroundTasks` framework, iOS 26) is the premier
background API for **user-initiated, long-running jobs** — file exports, video transcoding,
bulk photo upload — that must complete even after the user backgrounds the app.

**Why it matters for KMP WorkManager:**
- Unlike `BGProcessingTask`, this task fires **immediately** when a user-initiated action
  sends the app to the background — no opportunistic scheduling delay.
- Conforms to `NSProgressReporting` — **mandatory progress reporting**, which maps
  naturally onto our existing `WorkerProgress` + `TaskProgressBus` contract.
- Optional **GPU access** (`requiredResources = .gpu`) — critical for camera-app video
  encoding pipelines that today must stay in foreground.
- iOS system displays task progress to the user and allows manual cancellation.

**Planned implementation:**
- `IosBackgroundContinuedTaskWorker` — new built-in worker wrapping `BGContinuedProcessingTask`.
- `BGContinuedProcessingTaskConfig` — config with `localizedTitle`, `localizedSubtitle`,
  `requiresGpu: Boolean`, `submissionStrategy` (fail / queue).
- `BGTaskScheduler` handler registration mirrors the existing `IosBackgroundDownloadWorker`
  pattern but uses the new request type.
- Progress relay: `task.progress` (NSProgress) ← `WorkerProgress.percentage` via
  `IosLiveActivityBridge` so Dynamic Island updates flow automatically.
- Expiration handler: saves `ChainProgress` snapshot and calls `task.setTaskCompletedWithSuccess(false)`.
- **Not supported on iOS Simulator** — physical device required (same constraint as
  `IosBackgroundDownloadWorker`).

**Blockers before implementation:**
1. iOS 26 GM / public release (expected Q4 2026).
2. Kotlin/Native interop headers for `BGContinuedProcessingTask` (will ship with
   Kotlin 2.2+ native toolchain update targeting iOS 26 SDK).
3. Verification that `BGTaskScheduler.shared.supportedResources` is queryable from
   Kotlin/Native without crashing on iOS < 26.

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
