# Migrating to v2.5.0

This guide covers upgrading from any v2.4.x release to v2.5.0.

> **TL;DR.** v2.5.0 is **source-compatible for almost all users**. The two
> places you might need to touch are `FileCompressionWorker` on iOS (default
> behaviour changed from silent-copy to fail-fast) and `BaseAlarmReceiver`
> subclasses on Android (now bounded by `withTimeout`). Everything else is
> additive.

---

## Bump the version

```kotlin
// build.gradle.kts
commonMain.dependencies {
    implementation("dev.brewkits:kmpworkmanager:2.5.0")
}
```

If you use KSP-generated `WorkerFactory`:

```kotlin
dependencies {
    ksp("dev.brewkits:kmpworker-ksp:2.5.0")
    commonMain.implementation("dev.brewkits:kmpworker-annotations:2.5.0")
}
```

---

## Breaking changes (you must check these)

### 1. `FileCompressionWorker` on iOS now fails fast by default

**Before (v2.4.x):** the iOS implementation silently `copyItemAtPath`-copied the
file uncompressed (because Kotlin/Native has no ZIP codec) and reported
`Success`. The "compressed" file on the server was actually the original.

**After (v2.5.0):** the worker returns `WorkerResult.Failure` with an
actionable message. The default favours correctness over the silent-success
trap.

**Impact**: any task chain that depended on `FileCompressionWorker` on iOS
now fails. Three options to handle:

- **Recommended — fix the upload path.** If your server doesn't strictly
  require ZIP and the media is already-compressed (JPEG, HEIC, H.264 MP4),
  skip the compression step entirely on iOS. Use a platform-aware chain.
- **Opt back into the legacy behaviour.** Set
  `FileCompressionConfig.allowIosUncompressedFallback = true`. The worker
  still copies; you accept that the file is not actually compressed.
- **Implement compression in your Swift host.** Use ZIPFoundation or
  `Compression` framework on the iOS side, then invoke the KMP chain only
  for the upload step.

```kotlin
val config = FileCompressionConfig(
    sourcePath = "/var/Documents/photo.heic",
    destinationPath = "/var/Documents/photo.zip",
    allowIosUncompressedFallback = true,  // opt in to the v2.4.x behaviour
)
```

A real ZIP implementation via `libz.dylib` cinterop is planned for v2.6 — see
[ROADMAP.md](ROADMAP.md) §7.

### 2. `BaseAlarmReceiver` on Android now bounds work with `withTimeout`

**Before (v2.4.x):** subclasses overrode `doAlarmWork` and the receiver
launched it via `CoroutineScope(Dispatchers.IO).launch{}` — no timeout, no
scope cancellation. A hung network call leaked the coroutine past the
`BroadcastReceiver`'s ~10 s budget.

**After (v2.5.0):** the receiver wraps `doAlarmWork` in
`withTimeout(workTimeoutMs)` (default 8 s, slightly under the OS budget) and
cancels the scope in `finally`. `TimeoutCancellationException` is logged as a
warning and the receiver finalises cleanly.

**Impact**: if your subclass intentionally runs longer than 8 s (e.g. a chain
of HTTP requests waiting on slow APIs), it now gets cancelled.

```kotlin
class MySyncReceiver : BaseAlarmReceiver() {
    // Raise the budget. Anything above ~9 s is unsafe — the OS will kill the
    // receiver process before yours.
    override val workTimeoutMs: Long = 8_500L

    override suspend fun doAlarmWork(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?,
    ) {
        // Your work — must still finish before workTimeoutMs.
    }
}
```

**The correct fix** in most cases is not to bump the timeout but to off-load
the work to a `WorkManager` chain via `BaseKmpWorker`. The receiver should
only enqueue, not execute.

### 3. `KmpHeavyWorker` is now `open`

**Before (v2.4.x):** `final class KmpHeavyWorker` — could not be subclassed.
**After (v2.5.0):** `open class KmpHeavyWorker` so subclasses can override
`foregroundServiceType`.

**Impact**: source-compatible. Anyone who was using `KmpHeavyWorker` directly
(without subclassing) is unaffected.

### 4. `HttpDownloadWorker` retry semantics

**Before (v2.4.x):** 5xx HTTP responses returned
`WorkerResult.Failure(shouldRetry = true)`. Parse errors also retried.

**After (v2.5.0):** 5xx returns `WorkerResult.Retry(delayMs = 30_000)`. Parse
and config errors return `Failure` (no retry — the input is bad, not the
server).

**Impact**: behaviour-equivalent on Android (`Result.retry()` either way) but
the `delayMs` is now honored on iOS via `BGProcessingTaskRequest.earliestBeginDate`.
If you observed `TaskCompletionEvent` and treated `Failure.shouldRetry == true`
specifically, switch to checking `WorkerResult.Retry` instead.

### 5. `BaseKmpWorker` exception-to-result classification narrowed (Android)

**Before (v2.4.x):** the catch-all `Exception` handler in `BaseKmpWorker.doWorkInternal`
treated **all** of these as *permanent* failures (no retry, returns `Result.failure()`):

- `SerializationException`
- `ClassNotFoundException`
- `IllegalArgumentException`
- `NullPointerException`
- `NumberFormatException`
- `InvocationTargetException`
- `InstantiationException`

**After (v2.5.0):** only true programming/config errors are permanent:

- `SerializationException` (schema mismatch)
- `ClassNotFoundException` (worker class removed from binary)
- `InvocationTargetException` (reflection wiring broken)
- `InstantiationException` (worker can't be constructed)

`IllegalArgumentException`, `NullPointerException`, and `NumberFormatException`
are now treated as **transient** — `Result.retry()` is returned, subject to the
`WorkRequest` backoff policy.

**Impact**: workers that threw `NPE`/`IAE`/`NFE` on transient state (third-party
SDK init race, JSON parser hitting a temporarily empty field, etc.) now retry
instead of failing permanently. This is the safe direction — data preservation
beats wasted quota — but if your code *relied* on "throwing NPE = no retry":

- Replace the throw with `return WorkerResult.Failure(shouldRetry = false)`.
  Explicit is better than relying on exception classification.

**Rationale**: incorrectly marking a transient failure as permanent loses user
data; incorrectly marking a permanent failure as transient wastes bounded retry
quota. Asymmetric cost → bias toward retry.

### 6. iOS dedicated-identifier tasks now auto-retry on `Retry` / `Failure(shouldRetry=true)`

**Before (v2.4.x):** `IosBackgroundTaskHandler.handleSingleTask` — the entry point
for BGTasks scheduled with a dedicated Info.plist identifier (the common iOS
path) — checked `result is WorkerResult.Success` and silently dropped anything
else. Workers returning `Retry` or `Failure(shouldRetry = true)` had no pending
BGTaskRequest left, so iOS never re-fired the work.

**After (v2.5.0):** dedicated-identifier tasks follow the same retry contract as
WorkManager on Android (mirrors §6 below for dynamic tasks):

- `Success` → drop metadata, do not re-submit
- `Failure(shouldRetry = false)` → drop metadata (terminal)
- `Failure(shouldRetry = true)` → re-submit a new BGTaskRequest with incremented
  attempt counter
- `Retry(attemptCap = N)` → re-submit, abandon after N total attempts
- `Retry(attemptCap = null)` → re-submit, abandon after 5 attempts (default cap)
- `Retry(delayMs = X)` → re-submit with `earliestBeginDate = now + X`

The attempt counter is persisted under the internal `kmpAttemptCount` key so a
process kill between re-submit and the next run cannot reset it.

**Impact**: same as §7 (dynamic tasks) below — your workers must be idempotent
across up to 5 invocations by default. Return `Failure(shouldRetry = false)` for
one-shot work, or `Retry(attemptCap = N)` to override the default cap.

### 7. iOS dynamic tasks now auto-retry on `Retry` / `Failure(shouldRetry=true)`

**Before (v2.4.x):** `DynamicTaskDispatcher` on iOS dequeued each one-time task
and executed it. The result was logged but **the task was never re-enqueued**,
regardless of what the worker returned. A worker returning
`WorkerResult.Retry("network blip")` was silently dropped.

**After (v2.5.0):** dynamic tasks follow the same retry contract as
WorkManager on Android:

- `Success` → drop metadata, do not re-enqueue
- `Failure(shouldRetry = false)` → drop metadata (terminal failure)
- `Failure(shouldRetry = true)` → re-enqueue with incremented attempt counter
- `Retry(attemptCap = N)` → re-enqueue, abandon after `N` total attempts
- `Retry(attemptCap = null)` → re-enqueue, abandon after `5` total attempts
  (the default `DEFAULT_ATTEMPT_CAP`, mirrors WorkManager's default backoff
  budget)

The attempt counter is persisted in task metadata under the internal key
`kmpAttemptCount` so a process kill between re-enqueue and the next run cannot
reset it to 1.

**Impact**:

- iOS background work that previously vanished on transient errors now retries.
  **Your workers must be idempotent** — the same task may run up to 5 times by
  default before being abandoned.
- If your worker must run *exactly once*, return `WorkerResult.Failure(shouldRetry = false)`
  or `WorkerResult.Retry(attemptCap = 1)` (the latter is rejected at construction;
  use `Failure` for "one-shot, no retry").
- If you need a higher cap, return `WorkerResult.Retry(attemptCap = N)` from the
  worker — `N` overrides the default of 5 for that specific failure.

### 8. Chain tasks honor `Failure(shouldRetry = false)` as permanent-abandon (iOS)

**Before (v2.4.x):** `ChainExecutor` mapped every `WorkerResult.Failure` to a
generic step failure, ignoring the `shouldRetry` flag. A worker explicitly
saying "this is permanent" still consumed the chain-level retry budget
(`MAX_RETRIES = 3` by default), wasting BGTask quota on guaranteed re-failures
and delaying the eventual abandonment by 3 BGTask invocations.

**After (v2.5.0):** `Failure(shouldRetry = false)` triggers immediate chain
abandonment — the chain definition + progress files are deleted on the first
attempt. `Failure(shouldRetry = true)` and `Retry(...)` continue to consume the
chain-level retry budget as before.

**Impact**: behaviour change for workers that return `Failure("message")`
without an explicit `shouldRetry`. Per the data class's default,
`shouldRetry = false` — so any such worker now aborts the chain on the first
attempt instead of getting 3 chances.

- If your worker meant "this failure is permanent" (bad input, schema
  mismatch): the new behaviour is what you wanted. No action.
- If your worker meant "transient — please retry": change the return to
  `WorkerResult.Failure(message, shouldRetry = true)` or
  `WorkerResult.Retry(reason)`.

Side effect of this fix: `IosFileStorage.deleteChainProgress` became `suspend`
(now evicts the in-memory progress buffer in addition to deleting the file —
fixes a separate latent bug where a buffered ChainProgress would be re-flushed
to disk after abandonment, effectively un-abandoning the chain). Any caller
that holds a reference to that function must now invoke it from a coroutine.

### 9. iOS battery-guard is now opt-in via `UIDevice.isBatteryMonitoringEnabled`

**Before (v2.4.x):** `ChainExecutor` toggled
`UIDevice.current.isBatteryMonitoringEnabled` automatically to read battery
state, then attempted to restore the host's prior value. This had two issues:
(1) the toggle is non-atomic — a host UI thread enabling monitoring between
our read-and-restore would have its setting silently overwritten; (2)
`UIDevice` mutable properties have a Apple-documented main-thread requirement,
violated by BGTask coroutines.

**After (v2.5.0):** the battery guard reads `isBatteryMonitoringEnabled` but
NEVER toggles it. If the host has not enabled monitoring at app launch, the
guard is skipped and the worker runs normally. `KmpWorkManagerConfig.minBatteryLevelPercent`
is therefore opt-in:

```swift
// In your app delegate (Swift):
UIDevice.current.isBatteryMonitoringEnabled = true
```

```kotlin
// In your KMP config (only takes effect if the Swift opt-in above is set):
KmpWorkManager.initialize(
    config = KmpWorkManagerConfig(minBatteryLevelPercent = 15)
)
```

**Impact**: apps that did NOT enable battery monitoring lose the battery guard
silently. The previous behaviour was already error-prone (auto-toggle race) so
the safer default is to skip rather than corrupt the host's setting.

### 10. iOS chain durations now use monotonic time

**Before (v2.4.x):** `ChainExecutor` measured elapsed time via wall-clock
(`NSDate.timeIntervalSince1970`). NTP sync mid-execution would corrupt
budget/timeout comparisons — including the BUG 10 / BUG 11 outer-cancel
disambiguation checks added in v2.5, leading to either re-introduced outer-
cancel misattribution or its inverse (legitimate task timeouts mis-attributed
to outer scopes).

**After (v2.5.0):** all internal `duration = endMark - startMark` arithmetic
uses `kotlin.time.TimeSource.Monotonic`. Wall-clock is still used for public
timestamps (telemetry event `startedAtMs`, `ExecutionMetrics.startTime`/`endTime`,
absolute BGTask deadlines from iOS) — those need to survive process boundaries
and represent real time-of-day.

**Impact**: telemetry consumers see no observable difference (the public
timestamps remain wall-clock; only the `durationMs` field is now monotonic-
derived). Internal correctness is improved: chain/task budgets and the
inner/outer timeout disambiguation are immune to NTP jitter.

### 11. Built-in workers now mark transient failures as retryable

**Before (v2.4.x):** `HttpUploadWorker`, `HttpSyncWorker`, `FileCompressionWorker`,
and `ParallelHttpUploadWorker` caught generic `Exception` and returned
`Failure(message)` — using the data class's default `shouldRetry = false`.

**After (v2.5.0):** these workers explicitly set `shouldRetry = true` on
network/IO catch-alls. Combined with the §8 chain-semantics change, this
prevents the contract regression where a single transient network blip
abandons the entire chain.

**Impact**: behaviour-equivalent for v2.4.x users (chain retry budget was
already applied by the executor regardless of `shouldRetry`). For v2.5.0
users who write their own workers wrapping network calls, the explicit
`shouldRetry = true` is now required for transient failures — see §8.

### 12. Legacy text-format queue migration streams the file

**Before (v2.4.x):** `AppendOnlyQueue.migrateFromTextToBinary` loaded the
entire legacy queue file via `NSString.stringWithContentsOfFile`, then
`content.split("\n")` materialised every line into a `List<String>` before
writing the new binary file. For users who had not opened the app in some
time, the legacy file could grow to 10–20 MB; peak RAM during migration was
~3× file size (NSString + UTF-16 buffers + List), exceeding the iOS BGTask
~30 MB budget on older devices. The OS sent `EXC_RESOURCE` and silently
killed the app every time it tried to migrate — users stuck permanently on
the pre-v2.5 version with no error indication.

**After (v2.5.0):** the migration streams the legacy file line-by-line via
`NSFileHandle` (using the same 4 KB chunked reader already used elsewhere in
the queue) and writes each unprocessed line directly into the new binary
file. Peak RAM is one line (~few KB) regardless of file size.

**Impact**: no user-visible behaviour change. Users who could not migrate
under v2.4.x → v2.5.0 due to silent EXC_RESOURCE kills can now upgrade
successfully.

---

## Additive changes (no migration needed)

These are all opt-in; existing code continues to work unchanged.

- `ChecksumAlgorithm` enum + `HttpDownloadConfig.expectedChecksum`
- `DuplicatePolicy` enum + `HttpDownloadConfig.onDuplicate`
- `WorkerResult.Retry(reason, delayMs, attemptCap)` (alongside existing `Failure(shouldRetry)`)
- `ChainProgress.stepRetryCounts: Map<Int, Int>` — defaults to `emptyMap()`,
  forward-compat with v2.4.3 JSON on disk (verified by `BackwardCompatibilityTest`)
- `ParallelHttpDownloadWorker`, `ParallelHttpUploadWorker`
- `IosBackgroundDownloadWorker` + `IosBackgroundUrlSessionManager`
- `KmpHeavyWorker.foregroundServiceType` (default `FGS_DATA_SYNC` matches
  pre-v2.5 hardcoded value)

---

## On-disk file format

No schema bumps. v2.5.0 reads every v2.4.x file format as-is:

- `ChainProgress` JSON — `stepRetryCounts` is additive with default
  `emptyMap()`. v2.4.3 files load cleanly via `ignoreUnknownKeys = true`.
- `AppendOnlyQueue` binary format — unchanged at `FORMAT_VERSION = 1`.
- `crashAttemptCount` / `schemaVersion` fields — unchanged.

Regression test: `BackwardCompatibilityTest.loads_v243_chainProgress_withoutStepRetryCounts_field`
proves v2.4.3-shaped JSON loads on v2.5.0 with no data loss.

If you ever need to downgrade from v2.5.0 back to v2.4.x, `stepRetryCounts`
and other new fields are silently dropped — but pending tasks survive.

---

## iOS host app — `Info.plist` and AppDelegate

### If you use `IosBackgroundDownloadWorker` (new in v2.5)

Add to AppDelegate:

```swift
func application(
    _ application: UIApplication,
    handleEventsForBackgroundURLSession identifier: String,
    completionHandler: @escaping () -> Void
) {
    IosBackgroundUrlSessionManager.shared.handleBackgroundEvents(
        sessionIdentifier: identifier,
        completionHandler: completionHandler
    )
}
```

Optionally (recommended) on cold-launch:

```swift
func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [...]?
) -> Bool {
    Task {
        await IosBackgroundUrlSessionManager.shared.sweepStaleStateOnLaunch()
    }
    return true
}
```

Full guide: [`docs/IOS_BACKGROUND_URL_SESSION.md`](IOS_BACKGROUND_URL_SESSION.md).

### If you don't use `IosBackgroundDownloadWorker`

No iOS host changes required.

---

## Android host app — manifest

### If you subclass `KmpHeavyWorker` for `mediaProcessing` workloads

Add to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />

<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync|mediaProcessing"
    tools:node="merge" />
```

Full guide: [`docs/ANDROID_FGS_GUIDE.md`](ANDROID_FGS_GUIDE.md).

### If you only use the default `KmpHeavyWorker` (no subclass)

No manifest changes required — defaults still match v2.4.x.

---

## Quick verification checklist

After upgrading:

- [ ] `./gradlew :app:assembleDebug` succeeds.
- [ ] Existing `BaseAlarmReceiver` subclasses still terminate cleanly under load.
- [ ] If you depended on `FileCompressionWorker` on iOS, either opt in to the
      legacy fallback or refactor the chain.
- [ ] If you depended on `HttpDownloadWorker` 5xx retry behaviour via
      `Failure(shouldRetry = true)`, switch to `WorkerResult.Retry` matching.
- [ ] If your CI parses `WorkerResult` patterns, add a `WorkerResult.Retry`
      branch (Kotlin exhaustiveness will catch missing branches at compile time).

---

## Got stuck?

- [`docs/TROUBLESHOOTING.md`](TROUBLESHOOTING.md) — common issues.
- [`docs/IOS_BGTASK_LIMITS.md`](IOS_BGTASK_LIMITS.md) — what iOS background
  execution actually guarantees (read before designing schedule-sensitive
  features).
- File an issue at https://github.com/Brewkits/kmpworkmanager/issues.
