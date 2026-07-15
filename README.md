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
    implementation("dev.brewkits:kmpworkmanager:3.1.0")          // core engine (no Ktor)
    // Optional — only if you use the built-in HTTP workers (Http*/ParallelHttp*).
    implementation("dev.brewkits:kmpworkmanager-http:3.1.0")     // Ktor 3 HTTP workers
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
        // IOSModuleKt.iosModule calls kmpWorkerModule(workerFactory = IosWorkerFactoryGenerated())
        KoinInitializerKt.doInitKoin(platformModule: IOSModuleKt.iosModule)
    }

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let koin = KoinIOS()
        
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "kmp_chain_executor_task",
            using: nil
        ) { task in
            IosBackgroundTaskHandler.shared.handleChainExecutorTask(
                task: task,
                chainExecutor: koin.getChainExecutor()
            )
        }
        return true
    }
}
```

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
| `Exact(epochMs)` | AlarmManager | Best-effort | iOS cannot guarantee exact timing |
| `Windowed(earliest, latest)` | WorkManager with delay | BGTaskScheduler | Preferred over Exact on iOS |
| `ContentUri(uri)` | WorkManager ContentUriTrigger | — | Android only |

---

## What's new in v3.1.0

**`Constraints.maxRetries` — a real, cross-platform retry ceiling.** `maxRetries = N` caps a
failing task at **N + 1** total runs (1 initial + N retries), then marks it a permanent failure.
It bounds both `WorkerResult.Failure(shouldRetry = true)` and a `WorkerResult.Retry` without an
explicit `attemptCap` (a per-result `attemptCap` still wins).

- **Android:** WorkManager has no native max-retry API, so `shouldRetry = true` previously
  retried until the OS quota ran out. `maxRetries` is now enforced inside the worker.
- **iOS:** honored by both the single-task dispatcher and the chain executor. As part of this,
  a chain that fails with retries remaining is now correctly **re-enqueued** — previously such a
  chain was dropped from the queue and never retried.
- **Applies to one-time and chained tasks only** — periodic tasks ignore it. Default `-1` keeps
  each platform's prior behavior (Android uncapped; iOS 5 attempts for single tasks, 3
  whole-chain retries).
- Additive API — no breaking changes. See the [constraints guide](docs/constraints-triggers.md#maxretries).

## Previous releases

See the [changelog](CHANGELOG.md) for the full history, and the per-version upgrade guides:
[v3.1.0](docs/MIGRATION_V3.1.0.md) · [v3.0.0](docs/MIGRATION_V3.0.0.md) · [v2.5.0](docs/MIGRATION_V2.5.0.md) · [v2.4.0](docs/MIGRATION_V2.4.0.md).

---

## Built-in workers

| Worker | Status | Notes |
|--------|--------|-------|
| `HttpRequestWorker` | Stable | One-shot HTTP with configurable method, headers, body. SSRF-validated. |
| `HttpDownloadWorker` | Stable (v2.5+) | Resumable download via HTTP `Range`. `<savePath>.partial` survives process kill; a process kill resumes from last byte. Supports SHA-256/SHA-1/SHA-512/MD5 checksum verification and `DuplicatePolicy` (overwrite / skip / rename). |
| `ParallelHttpDownloadWorker` | New in v2.5 | Splits a single file into N (1..16, default 4) HTTP `Range` chunks downloaded concurrently with per-chunk `.partN` resume. Automatic sequential fallback when the server does not advertise `Accept-Ranges: bytes`. Same checksum verification surface as `HttpDownloadWorker`. |
| `HttpUploadWorker` | ⚠️ Experimental | Streaming multipart upload. No resumable / chunked upload yet (see `ParallelHttpUploadWorker` for multi-file uploads). |
| `ParallelHttpUploadWorker` | New in v2.5 | One POST per file with per-host `maxConcurrent` limit (1..16, default 3) and per-file retry on 5xx / network errors (`maxRetries` 0..5). Per-file outcomes exposed via `WorkerResult.Success.data.fileResults`. |
| `IosBackgroundDownloadWorker` | iOS-only, experimental (v2.5+) | Hands the download to `URLSessionConfiguration.background` so the transfer survives **full app termination**. Host AppDelegate must wire `application(_:handleEventsForBackgroundURLSession:completionHandler:)` — see [docs/IOS_BACKGROUND_URL_SESSION.md](docs/IOS_BACKGROUND_URL_SESSION.md). |
| `HttpSyncWorker` | Stable | Fetch-and-persist data sync. |
| `FileCompressionWorker` | ✅ Android · 🚧 iOS | **iOS has no ZIP codec in Kotlin/Native.** The default behavior on iOS is to **fail fast** with an explicit error. Set `FileCompressionConfig.allowIosUncompressedFallback = true` to accept an uncompressed copy at the output path (useful for demo chains; the output is **not** a real ZIP). For real iOS compression, integrate [ZIPFoundation](https://github.com/weichsel/ZIPFoundation) via cinterop. |

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
| [App Store Review Compliance](docs/APPLE_APP_STORE_REVIEW_GUIDELINES.md) | §2.5.4 — what gets rejected and how to ship safely |
| [Android FGS Type Guide](docs/ANDROID_FGS_GUIDE.md) | `mediaProcessing` / `camera` / `dataSync` setup |
| [iOS Background URLSession](docs/IOS_BACKGROUND_URL_SESSION.md) | Surviving app termination during long downloads |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Common issues |
| [CHANGELOG](CHANGELOG.md) | Release history |

**Migration:** [v2.2.2 → v2.3.0](docs/MIGRATION_V2.3.0.md) · [v2.3.3 → v2.3.4](docs/MIGRATION_V2.3.3_TO_V2.3.4.md) · [v2.4.x → v2.5.0](docs/MIGRATION_V2.5.0.md) · [v2.5.x → v3.0.0](docs/MIGRATION_V3.0.0.md)

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
