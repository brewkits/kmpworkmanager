<div align="center">

<img src="assets/logo.svg" height="160" alt="KMP WorkManager" />

# KMP WorkManager

[![Maven Central](https://img.shields.io/maven-central/v/dev.brewkits/kmpworkmanager?color=7C3AED&label=Maven%20Central)](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7C3AED?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![CI](https://github.com/brewkits/kmpworkmanager/actions/workflows/build.yml/badge.svg)](https://github.com/brewkits/kmpworkmanager/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**Background task scheduling for Kotlin Multiplatform — including the parts iOS makes hard.**

</div>

---

## Installation

```kotlin
// build.gradle.kts
commonMain.dependencies {
    implementation("dev.brewkits:kmpworkmanager:3.4.1")          // core engine (no Ktor)
    // Optional — only if you use the built-in HTTP workers (Http*/ParallelHttp*).
    implementation("dev.brewkits:kmpworkmanager-http:3.4.1")     // Ktor 3 HTTP workers
}
```

> **Ktor 3 required only for `kmpworkmanager-http` (since v3.0.0).** The core artifact no
> longer depends on Ktor at all. The HTTP workers live in `kmpworkmanager-http`, which needs
> **Ktor 3.1.x**. Because Ktor 2 and Ktor 3 share the same Maven coordinates and are
> binary-incompatible, an app still on Ktor 2 cannot mix them — if your project is not yet on
> Ktor 3, **pin `dev.brewkits:kmpworkmanager:2.5.1`** until you migrate. See
> [`docs/MIGRATION_V3.0.0.md`](docs/MIGRATION_V3.0.0.md).

<details>
<summary><b>Android setup</b></summary>

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KmpWorkManager.initialize(
            context = this,
            workerFactory = AppWorkerFactory() // Must implement AndroidWorkerFactory
        )
    }
}
```

</details>

<details>
<summary><b>iOS setup</b></summary>

**1. AppDelegate**:

```swift
@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    override init() {
        super.init()
        // Expose these from Kotlin, e.g. in a Setup.kt:
        //   fun initKmpWorkManager() =
        //       KmpWorkManager.initialize(workerFactory = IosWorkerFactoryGenerated())
        //   fun kmpChainExecutor() = KmpWorkManager.getInstance().chainExecutor
        SetupKt.initKmpWorkManager()
    }

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "kmp_chain_executor_task",
            using: nil
        ) { task in
            IosBackgroundTaskHandler.shared.handleChainExecutorTask(
                task: task,
                chainExecutor: SetupKt.kmpChainExecutor()
            )
        }
        return true
    }
}
```

> **No DI framework required since v3.3.0.** If your app uses Koin or Hilt, bind
> `KmpWorkManager.getInstance()` from your own module — see
> [`docs/MIGRATION_V3.3.0.md`](docs/MIGRATION_V3.3.0.md).

**2. `Info.plist`**:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>kmp_chain_executor_task</string>
    <!-- Add other worker bgTaskIds here -->
</array>
```

Full setup: [docs/platform-setup.md](docs/platform-setup.md)

</details>

---

## Quick start

### Schedule a task

```kotlin
// One-time — runs as soon as constraints are met
scheduler.enqueue(
    id              = "nightly-sync",
    trigger         = TaskTrigger.OneTime(initialDelayMs = 0),
    workerClassName = "SyncWorker",
    constraints     = Constraints(requiresNetwork = true)
)

// Periodic — every 15 minutes
scheduler.enqueue(
    id              = "heartbeat",
    trigger         = TaskTrigger.Periodic(intervalMs = 15 * 60 * 1000L),
    workerClassName = "SyncWorker"
)
```

### Define a worker

You can implement background logic in your `commonMain` code, but KMP WorkManager expects platform-specific factory registration. We recommend using `kmpworker-ksp` to auto-generate this boilerplate.

```kotlin
// commonMain — shared logic
class SyncWorker : Worker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val items = api.fetchPendingItems()
        database.upsert(items)
        return WorkerResult.Success("Synced ${items.size} items")
    }
}
```

```kotlin
// androidMain
import dev.brewkits.kmpworkmanager.annotations.Worker

@Worker(name = "SyncWorker")
class SyncWorkerAndroid : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment) =
        SyncWorker().doWork(input, env)
}

// iosMain
import dev.brewkits.kmpworkmanager.annotations.Worker

@Worker(name = "SyncWorker", bgTaskId = "sync_task")
class SyncWorkerIos : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment) =
        SyncWorker().doWork(input, env)
}
```

The `name` argument **must match** the `workerClassName` you pass to `scheduler.enqueue(...)` (`"SyncWorker"` above). Set it explicitly so ProGuard/R8 rename of the wrapper class doesn't break factory lookup.

*Note: Use `AndroidWorkerFactoryGenerated()` and `IosWorkerFactoryGenerated()` in your DI/Initialization if you use KSP. Otherwise, manually implement `AndroidWorkerFactory` and `IosWorkerFactory`.*

### Chain tasks

```kotlin
// Multi-step workflows that survive process death.
// If step 47 of 100 was running when iOS killed the app —
// the next BGTask invocation resumes at step 47, not step 0.
scheduler.beginWith(TaskRequest("DownloadWorker", inputJson = """{"url":"$fileUrl"}"""))
    .then(TaskRequest("ValidateWorker"))
    .then(TaskRequest("TranscodeWorker"))
    .then(TaskRequest("UploadWorker", inputJson = """{"bucket":"processed"}"""))
    .withId("transcode-pipeline", policy = ExistingPolicy.KEEP)
    .enqueue()
```

### Pass data between chain steps

Set `mergeOutputFromPreviousStep = true` and a step receives the previous step's
`WorkerResult.Success.data` merged into its own `inputJson` — no external store needed.
On a key collision the previous step's value wins, matching WorkManager's default
`OverwritingInputMerger`. Both platforms behave identically.

```kotlin
scheduler.beginWith(TaskRequest("DownloadWorker", inputJson = """{"url":"$fileUrl"}"""))
    // DownloadWorker returns Success(data = {"filePath": "/tmp/x.zip"})
    .then(TaskRequest("ValidateWorker", mergeOutputFromPreviousStep = true))  // sees filePath
    .then(TaskRequest("UploadWorker", mergeOutputFromPreviousStep = true))    // sees filePath
    .enqueue()
```

### Cancel a group of tasks

Tags are business-context labels, independent of worker class — one call cancels a mixed
set of workers. Works for standalone tasks and chain steps alike.

```kotlin
scheduler.enqueue(
    id = "sync-profile",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "SyncWorker",
    tags = setOf("user-123"),
)
scheduler.beginWith(TaskRequest("UploadWorker", tags = setOf("user-123"))).enqueue()

// Cancels both, whatever worker they use:
scheduler.cancelByTag("user-123")

// Or cancel every task of one worker type:
scheduler.cancelByWorkerClass("SyncWorker")
```

> `cancelByTag` does not cover `TaskTrigger.Exact` on Android — exact alarms run through
> `AlarmManager`, which is not tag-indexed. Cancel those by id.

### Skip work that has gone stale

`deadlineMs` marks the point after which running the task is worse than not running it.
A task that has not started by then is skipped (recorded as `ExecutionStatus.SKIPPED`)
and never retried — retrying cannot un-miss a deadline. A skipped step does not abort the
rest of its chain.

```kotlin
scheduler.enqueue(
    id = "pre-flight-sync",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "SyncWorker",
    deadlineMs = departureTimeMs,  // pointless to sync after the flight leaves
)
```

This is what finally makes `TaskTrigger.Windowed(earliest, latest)` enforceable on iOS:
`latest` is now honoured as a deadline at execution time, where BGTaskScheduler itself
offers no ceiling.

---

## Why KMP WorkManager?

Most KMP libraries wrap the happy path — iOS BGTaskScheduler is not just "a different API."
It has a credit system that punishes apps overrunning their time budget, an opaque scheduling policy,
and no recovery mechanism for incomplete work. Getting it wrong means your tasks silently stop running.

| | Android | iOS |
|---|---------|-----|
| Scheduling | Deterministic via WorkManager | Opportunistic — OS decides when |
| Exact timing | ✅ AlarmManager | ⚠️ Best-effort |
| Chain recovery | ✅ WorkContinuation | ✅ Step-level persistence |
| Time budget enforcement | — | ✅ Adaptive (reserves 15–30% safety margin) |
| Queue integrity | ✅ | ✅ CRC32-verified binary format |
| Thread-safe expiry | ✅ | ✅ AtomicInt shutdown flag |

---

## Triggers

| Trigger | Android | iOS | Notes |
|---------|---------|-----|-------|
| `OneTime(delayMs)` | WorkManager | BGTaskScheduler | Minimum delay may be enforced by OS |
| `Periodic(intervalMs)` | WorkManager | BGTaskScheduler | Min 15 min on both platforms |
| `Exact(epochMs)` | AlarmManager | Best-effort | iOS: shows a notification by default — worker code does not run unless the user taps it or the app is reopened. Not a substitute for Android's real exact alarm; see `docs/iOS-EXACT-ALARM-GUIDE.md` |
| `Windowed(earliest, latest)` | WorkManager with delay | BGTaskScheduler | Preferred over Exact on iOS |
| `ContentUri(uri)` | WorkManager ContentUriTrigger | — | Android only |

---

## What's new in v3.4.0

**Jetpack WorkManager parity.** Four features native WorkManager users expect, now on both
platforms: **task tags + group cancellation** (`cancelByTag` / `cancelByWorkerClass`),
**per-task deadlines** (`deadlineMs` — skip rather than run stale work), the **chain
InputMerger** (`mergeOutputFromPreviousStep` — pass a step's output into the next step's
input), and **`ExistingPolicy.UPDATE`** (change a periodic task's constraints without
resetting its interval timer). See the sections above for usage.

Adding `cancelByTag`/`cancelByWorkerClass` to `BackgroundTaskScheduler` is source-compatible
— both ship with no-op default implementations so existing custom schedulers and test doubles
keep compiling. Classes that *override* `enqueue()` must add the new `tags` and `deadlineMs`
parameters.

**Second Android/iOS constraint-parity pass.** Six gaps closed, all previously just unwired
rather than platform-impossible: `requiresUnmeteredNetwork`/`requiresCharging` now enforced
for standalone iOS tasks (not just chain steps), `backoffPolicy`/`backoffDelayMs` now affect
real iOS retry timing, `SystemConstraint.REQUIRE_BATTERY_NOT_LOW`/`ALLOW_LOW_BATTERY`
implemented on iOS, and Android's `setExpedited()` now actually respects `TaskPriority` as
documented (a real behavior change for `NORMAL`/`LOW` tasks — see [CHANGELOG.md](CHANGELOG.md)).

**Two `WorkQuery`-style batch APIs**, matching `androidx.work.WorkQuery`'s own
AND-across-axes/OR-within-axis semantics: `observeTaskState(id): Flow<TaskState>` for a live
state stream (`Enqueued`/`Running`/`Succeeded`/`Failed`/`Cancelled`/`Unknown`), and
`queryTasks(tags, workerClassNames, states): List<QueriedTask>` for a batch read filtered by
any combination of those three. Both ship with no-op defaults for source compatibility.

**Extends two "Ultra" iOS features shipped in earlier releases**: `IosBackgroundUploadWorker`
(upload counterpart to the download-only `IosBackgroundDownloadWorker`), `sharedContainerIdentifier`
on both background-transfer configs (App Group transport sharing), and a new
`KmpWorkManager.initialize(appGroupIdentifier = ...)` for read-only cross-process task
visibility — see [`docs/IOS_APP_GROUP_STORAGE.md`](docs/IOS_APP_GROUP_STORAGE.md).

**Closes out Flutter parity Group 2**: `maxBytesPerSecond` bandwidth throttling on all four
HTTP download/upload configs (`kmpworkmanager-http`), a token-bucket rate limiter shared
across every concurrent chunk/file in the parallel variants.

**Documentation accuracy sweep**: `TaskTrigger.Exact` on iOS corrected to reflect its real
best-effort behavior (it does not guarantee code execution — see
[`docs/iOS-EXACT-ALARM-GUIDE.md`](docs/iOS-EXACT-ALARM-GUIDE.md)), and a pre-refactor
`Constraints`/`ExistingPolicy` shape that had drifted into several docs (`networkType`,
`requiresBatteryNotLow`, `expedited` as a `Constraints` field, a fictional `APPEND`/
`APPEND_OR_REPLACE` on `ExistingPolicy`) corrected throughout.

## What's new in v3.2.0

**Opt-In Permissions Architecture (Android).** To comply with strict Play Store guidelines and prevent unwarranted app rejections, KMP WorkManager no longer automatically merges `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` permissions into your app's manifest. If your app only uses standard background tasks, this update ensures you won't be blocked during app review.

> **Breaking Change for Heavy Worker Users:** If you use `KmpHeavyWorker`, you **must** now manually declare the required FGS permissions in your `AndroidManifest.xml`. See the [v3.2.0 Migration Guide](docs/MIGRATION_V3.2.0.md).

## Previous releases

See the [changelog](CHANGELOG.md) for the full history, and the per-version upgrade guides:
[v3.2.0](docs/MIGRATION_V3.2.0.md) · [v3.1.0](docs/MIGRATION_V3.1.0.md) · [v3.0.0](docs/MIGRATION_V3.0.0.md) · [v2.5.0](docs/MIGRATION_V2.5.0.md) · [v2.4.0](docs/MIGRATION_V2.4.0.md).

---

## Built-in workers

| Worker | Status | Notes |
|--------|--------|-------|
| `HttpRequestWorker` | Stable | One-shot HTTP with configurable method, headers, body. SSRF-validated. Optional `HmacSigningConfig` (HMAC-SHA256 request signing, GitHub-webhook-style prefix support) and `TokenRefreshConfig` (auto-refresh + one-shot retry on `401`, dot-notation token extraction from the refresh response). |
| `HttpDownloadWorker` | Stable (v2.5+) | Resumable download via HTTP `Range`. `<savePath>.partial` survives process kill; a process kill resumes from last byte. Supports SHA-256/SHA-1/SHA-512/MD5 checksum verification and `DuplicatePolicy` (overwrite / skip / rename). |
| `ParallelHttpDownloadWorker` | Stable | Splits a single file into N (1..16, default 4) HTTP `Range` chunks downloaded concurrently with per-chunk `.partN` resume. Automatic sequential fallback when the server does not advertise `Accept-Ranges: bytes`. Same checksum verification surface as `HttpDownloadWorker`. |
| `HttpUploadWorker` | ⚠️ Experimental | Streaming multipart upload. No resumable / chunked upload yet (see `ParallelHttpUploadWorker` for multi-file uploads). |
| `ParallelHttpUploadWorker` | Stable | One POST per file with per-host `maxConcurrent` limit (1..16, default 3) and per-file retry on 5xx / network errors (`maxRetries` 0..5). Per-file outcomes exposed via `WorkerResult.Success.data.fileResults`. |
| `IosBackgroundDownloadWorker` | iOS-only, experimental (v2.5+) | Hands the download to `URLSessionConfiguration.background` so the transfer survives **full app termination**. Host AppDelegate must wire `application(_:handleEventsForBackgroundURLSession:completionHandler:)` — see [docs/IOS_BACKGROUND_URL_SESSION.md](docs/IOS_BACKGROUND_URL_SESSION.md). |
| `IosBackgroundUploadWorker` | iOS-only, experimental (v3.4+) | Upload counterpart to `IosBackgroundDownloadWorker` — same background-daemon lifecycle and AppDelegate hook. Source must be a file on disk (`uploadTaskWithRequest(_:fromFile:)`; background sessions don't support in-memory bodies). |
| `HttpSyncWorker` | Stable | Fetch-and-persist data sync. |
| `FileCompressionWorker` | ✅ Stable | Produces a real PKZIP archive (DEFLATE) on both Android and iOS. Android uses `java.util.zip`; iOS uses native `platform.zlib` (system library, no external deps). Supports low/medium/high compression levels, exclude patterns, and optional delete-original. The `allowIosUncompressedFallback` flag is **deprecated** (ignored) since v3.2.0 — iOS now always produces a valid ZIP. |

> **Camera / media-app advisory.** For burst upload (50 photos at once), use
> `ParallelHttpUploadWorker` instead of one chain step per file. For RAW / video
> downloads over cellular, prefer `IosBackgroundDownloadWorker` on iOS so the
> transfer survives swipe-to-quit. `HttpUploadWorker` is the only stable worker
> without resumable/chunked semantics — pin those uploads to Wi-Fi
> (`Constraints(requiresUnmeteredNetwork = true)`) until v2.6.

---

## Security

**SSRF protection** — all built-in worker HTTP calls are validated before dispatch. Blocked:

```
169.254.169.254   AWS/GCP/Azure IMDS
fd00:ec2::254     AWS EC2 (IPv6)
100.100.100.200   Alibaba Cloud metadata
localhost, 0.0.0.0/8, [::1], 10.x, 172.16–31.x, 192.168.x
100.64.0.0/10     CGNAT (Tailscale, carrier-grade NAT)
fc00::/7, fe80::/10
```

RFC 3986 UserInfo bypass and multi-`@` authority attacks are both handled. DNS rebinding is a known limitation — use certificate pinning or an egress proxy for high-trust environments.

**Input size validation** — inputs exceeding WorkManager's 10 KB `Data` limit throw `IllegalArgumentException` at enqueue time.

---

## Testing

```
600+ tests across commonTest, iosTest, androidInstrumentedTest
```

- `QA_PersistenceResilienceTest` — 100-step chain killed at step 50, resumes at exactly step 50
- `V236ChainExecutorTest` — time budget, shutdown propagation, batch loop correctness
- `IosExecutionHistoryStoreTest` — save/get/clear, auto-pruning, all status variants
- `AppendOnlyQueueTest` — CRC32 corruption detection, truncation recovery, concurrent access
- `SecurityValidatorTest` — SSRF, IPv6 compressed loopback, multi-`@` UserInfo bypass

---

## Documentation

| | |
|---|---|
| [Quick Start](docs/quickstart.md) | Running in 5 minutes |
| [Platform Setup](docs/platform-setup.md) | Android & iOS configuration |
| [API Reference](docs/api-reference.md) | Full public API |
| [Task Chains](docs/task-chains.md) | Chain API and recovery semantics |
| [Built-in Workers](docs/BUILTIN_WORKERS_GUIDE.md) | Worker reference and input schema |
| [Constraints & Triggers](docs/constraints-triggers.md) | All scheduling options |
| [iOS Best Practices](docs/ios-best-practices.md) | BGTask gotchas and recommendations |
| [iOS BGTask Hard Limits](docs/IOS_BGTASK_LIMITS.md) | Opportunistic scheduling, time budget, headless DI |
| [iOS Exact Alarm Guide](docs/iOS-EXACT-ALARM-GUIDE.md) | What `TaskTrigger.Exact` actually does on iOS, and what not to use it for |
| [App Store Review Compliance](docs/APPLE_APP_STORE_REVIEW_GUIDELINES.md) | §2.5.4 — what gets rejected and how to ship safely |
| [Android FGS Type Guide](docs/ANDROID_FGS_GUIDE.md) | `mediaProcessing` / `camera` / `dataSync` setup |
| [iOS Background URLSession](docs/IOS_BACKGROUND_URL_SESSION.md) | Surviving app termination during long downloads/uploads |
| [iOS App Group Storage](docs/IOS_APP_GROUP_STORAGE.md) | Sharing task storage with a Widget/Share Extension |
| [iOS Live Activities](docs/IOS_LIVE_ACTIVITIES.md) | Dynamic Island & Lock Screen progress via `IosLiveActivityBridge` |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Common issues |
| [CHANGELOG](CHANGELOG.md) | Release history |

**Migration:** [v3.1.x → v3.2.0](docs/MIGRATION_V3.2.0.md) · [v2.5.x → v3.0.0](docs/MIGRATION_V3.0.0.md) · [v2.4.x → v2.5.0](docs/MIGRATION_V2.5.0.md)

---

## Requirements

| | |
|---|---|
| Kotlin | 2.1.0+ |
| Android | 8.0+ (API 26) |
| iOS | 13.0+ |
| Gradle | 8.0+ |

---

## Contributing

```bash
./gradlew :kmpworker:allTests   # all platforms must pass before opening a PR
```

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/).

---

## License

Apache 2.0. See [LICENSE](LICENSE).

---

<div align="center">

[GitHub](https://github.com/brewkits/kmpworkmanager) · [Maven Central](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager) · [Issues](https://github.com/brewkits/kmpworkmanager/issues)

</div>
