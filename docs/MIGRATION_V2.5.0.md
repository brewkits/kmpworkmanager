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
