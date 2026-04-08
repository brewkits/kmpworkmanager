<div align="center">

<img src="kmpworkmanager.png?v=2" alt="KMP WorkManager" width="720px" />

# KMP WorkManager

[![Maven Central](https://img.shields.io/maven-central/v/dev.brewkits/kmpworkmanager?color=4A90D9&label=Maven%20Central)](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![CI](https://github.com/brewkits/kmpworkmanager/actions/workflows/build.yml/badge.svg)](https://github.com/brewkits/kmpworkmanager/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**Background task scheduling for Kotlin Multiplatform — including the parts iOS makes hard.**

</div>

---

Most KMP libraries wrap the happy path. This one was written after hitting the edge cases.

iOS BGTaskScheduler is not just "a different API" — it has a credit system that punishes apps that overrun their time budget, an opaque scheduling policy, and no recovery mechanism for incomplete work. Getting it wrong means your tasks silently stop running.

This library handles the scheduling. Your workers handle the work.

```kotlin
// Schedule once. Runs on Android (WorkManager) and iOS (BGTaskScheduler).
scheduler.enqueue(
    id = "nightly-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 15 * 60 * 1000L),
    workerClassName = "SyncWorker",
    constraints = Constraints(requiresNetwork = true)
)
```

```kotlin
// Multi-step workflows that survive process death.
// If step 47 of 100 was running when iOS killed the app —
// the next BGTask invocation resumes at step 47, not step 0.
scheduler.beginWith(TaskRequest("FetchUser"))
    .then(TaskRequest("ProcessData"))
    .then(TaskRequest("SyncToServer"))
    .then(TaskRequest("UpdateLocalCache"))
    .enqueue()
```

---

## What's new in v2.3.8

### Execution history export
Every chain and task execution is now persisted locally after completion.
Collect on foreground, upload, then clear:

```kotlin
// In Activity.onResume() or LifecycleObserver
lifecycleScope.launch {
    val records = scheduler.getExecutionHistory(limit = 200)
    if (records.isNotEmpty()) {
        analyticsService.uploadBatch(records)   // your backend
        scheduler.clearExecutionHistory()        // free disk space
    }
}
```

Each `ExecutionRecord` carries: `chainId`, `status` (SUCCESS / FAILURE / ABANDONED / SKIPPED / TIMEOUT), `durationMs`, `completedSteps`, `failedStep`, `errorMessage`, `retryCount`, `platform`, and `workerClassNames`.
Up to 500 records are kept on disk; older ones are pruned automatically.

### Telemetry hook
Route task lifecycle events to Sentry, Crashlytics, Datadog, or any backend:

```kotlin
KmpWorkManagerConfig(
    telemetryHook = object : TelemetryHook {
        override fun onTaskFailed(event: TelemetryHook.TaskFailedEvent) {
            Sentry.captureMessage("Task failed: ${event.taskName} — ${event.error}")
        }
        override fun onChainFailed(event: TelemetryHook.ChainFailedEvent) {
            analytics.track("chain_failed", mapOf(
                "chainId"   to event.chainId,
                "failedStep" to event.failedStep,
                "willRetry"  to event.willRetry
            ))
        }
    }
)
```

Six events: `onTaskStarted`, `onTaskCompleted`, `onTaskFailed`, `onChainCompleted`, `onChainFailed`, `onChainSkipped`. All have default no-op implementations — override only what you need.

### Task priority
Control execution order for queued chains:

```kotlin
scheduler.beginWith(
    TaskRequest(
        workerClassName = "PaymentSyncWorker",
        priority = TaskPriority.CRITICAL   // runs before NORMAL/LOW chains
    )
).enqueue()
```

`LOW`, `NORMAL`, `HIGH`, `CRITICAL`. On Android, `HIGH`/`CRITICAL` map to expedited work (bypasses Doze). On iOS, the queue is sorted by priority before each BGTask window.

### Battery guard
Tasks are deferred when battery is critically low and not charging:

```kotlin
KmpWorkManagerConfig(
    minBatteryLevelPercent = 10  // defer when battery < 10% and not charging
                                 // 0 = disabled
)
```

Default: `5%`. Works on both Android (`BatteryManager`) and iOS (`UIDevice`).

### KSP: auto-generated factories with BGTask ID validation

```kotlin
// iosMain — annotate once, no manual factory boilerplate
@Worker("SyncWorker", bgTaskId = "com.example.sync-task")
class SyncWorker : IosWorker { ... }

// Generated IosWorkerFactoryGenerated.kt automatically:
//  - implements BgTaskIdProvider
//  - declares requiredBgTaskIds = setOf("com.example.sync-task")
//  - kmpWorkerModule() validates IDs against Info.plist at startup
```

```kotlin
// Pass to kmpWorkerModule — validation is automatic
kmpWorkerModule(workerFactory = IosWorkerFactoryGenerated())
```

If any `bgTaskId` is missing from `Info.plist → BGTaskSchedulerPermittedIdentifiers`, the app fails fast at startup with a descriptive error rather than silently misbehaving at task time.

Add to `build.gradle.kts`:
```kotlin
plugins { id("com.google.devtools.ksp") }

dependencies {
    ksp("dev.brewkits:kmpworker-ksp:2.3.8")
    commonMain.implementation("dev.brewkits:kmpworker-annotations:2.3.8")
}
```

---

## What this solves

### Chain recovery after process kill

Completed step indices are persisted to disk after every step. On resume, the executor reads the progress file and skips already-completed steps. A 100-step chain interrupted at step 47 continues from step 47 — exactly once, no duplicates.

Chains retry up to 3 times on failure. After 3 failures, the chain is abandoned and its state is cleaned up.

### iOS time budget management

BGTaskScheduler gives your app a time window. If you consistently overrun it, iOS reduces how often your tasks are scheduled — silently. The chain executor uses adaptive time budgeting: it measures how long cleanup takes historically and reserves 15–30% of the budget as a safety margin, adjusting per run. Tasks that would exceed the remaining window are deferred to the next BGTask invocation rather than running over.

### Queue integrity

The task queue uses a binary format with per-record CRC32 checksums. When a corrupted record is detected (incomplete write, flash wear, abrupt power loss), the queue truncates at the corruption point and preserves all valid records before it. Nothing is silently lost, and nothing causes a crash on read.

### Thread safety during expiry

iOS calls the BGTask expiration handler on a separate thread, which races with the executor's own shutdown logic. The library uses `AtomicInt` for the shutdown flag (not `Mutex`) to avoid blocking on I/O during the OS-mandated shutdown window.

---

## Installation

```kotlin
// build.gradle.kts
commonMain.dependencies {
    implementation("dev.brewkits:kmpworkmanager:2.3.8")
}
```

<details>
<summary><b>Android setup</b></summary>

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KmpWorkManager.initialize(this)
    }
}
```

</details>

<details>
<summary><b>iOS setup</b></summary>

**1. Worker factory** (`iosMain`):

```kotlin
class AppWorkerFactory : IosWorkerFactory {
    override fun createWorker(workerClassName: String): IosWorker? = when (workerClassName) {
        "SyncWorker"     -> SyncWorkerIos()
        "UploadWorker"   -> UploadWorkerIos()
        else             -> null
    }
}
```

**2. AppDelegate**:

```swift
@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    override init() {
        super.init()
        KoinInitializerKt.doInitKoin(platformModule: IOSModuleKt.iosModule)
    }

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "kmp_chain_executor_task",
            using: nil
        ) { task in
            IosBackgroundTaskHandlerKt.handleChainExecutorTask(task as! BGProcessingTask)
        }
        return true
    }
}
```

**3. `Info.plist`**:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>kmp_chain_executor_task</string>
</array>
```

Full setup: [docs/platform-setup.md](docs/platform-setup.md)

</details>

---

## Quick start

### Define a worker

```kotlin
// commonMain — shared logic
class SyncWorker : Worker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val items = api.fetchPendingItems()
        database.upsert(items)
        return WorkerResult.Success(
            message = "Synced ${items.size} items",
            data    = mapOf("count" to items.size)
        )
    }
}
```

```kotlin
// androidMain
class SyncWorkerAndroid : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment) = SyncWorker().doWork(input, env)
}

// iosMain
class SyncWorkerIos : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment) = SyncWorker().doWork(input, env)
}
```

### Schedule it

```kotlin
val scheduler = BackgroundTaskScheduler()

// Periodic — every 15 minutes when network is available
scheduler.enqueue(
    id                = "sync",
    trigger           = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName   = "SyncWorker",
    constraints       = Constraints(requiresNetwork = true),
    inputJson         = """{"userId": "u_123"}"""
)

// One-time with delay
scheduler.enqueue(
    id              = "post-login-sync",
    trigger         = TaskTrigger.OneTime(initialDelayMs = 5_000),
    workerClassName = "SyncWorker"
)

// Exact time (Android: AlarmManager; iOS: best-effort)
scheduler.enqueue(
    id              = "morning-report",
    trigger         = TaskTrigger.Exact(atEpochMillis = tomorrowAt8am),
    workerClassName = "ReportWorker"
)
```

---

## Triggers

| Trigger | Android | iOS | Notes |
|---------|---------|-----|-------|
| `OneTime(delayMs)` | WorkManager | BGTaskScheduler | Minimum delay may be enforced by OS |
| `Periodic(intervalMs)` | WorkManager | BGTaskScheduler | Min 15 min on both platforms |
| `Exact(epochMs)` | AlarmManager (`setExactAndAllowWhileIdle`) | Best-effort | iOS cannot guarantee exact timing |
| `Windowed(earliest, latest)` | WorkManager with delay | BGTaskScheduler | Preferred over Exact on iOS |
| `ContentUri(uri)` | WorkManager ContentUriTrigger | — | Android only (`@AndroidOnly`) |

---

## Platform behavior

| | Android | iOS |
|---|---------|-----|
| Scheduling | Deterministic via WorkManager | Opportunistic — OS decides when |
| Exact timing | ✅ AlarmManager | ⚠️ Best-effort, may be hours late |
| Network constraint | ✅ Enforced | ⚠️ Advisory only |
| Runs after force-quit | ✅ | ❌ Force-quit clears all pending tasks |
| Runs in background | ✅ | ⚠️ Only when OS grants BGTask window |
| Chain recovery | ✅ WorkContinuation | ✅ Step-level persistence |
| Time budget | No limit | ~30s (APP_REFRESH) / ~5min (PROCESSING) |

**iOS note:** If your app's background tasks are not running, check that you are not calling `BGTaskScheduler.shared.submit()` from the main thread, and that your `Info.plist` identifiers exactly match what you register. iOS provides no diagnostic feedback for misconfiguration.

---

## Task chains

Chains execute steps sequentially. Each step completes before the next begins.

```kotlin
scheduler.beginWith(
    TaskRequest(workerClassName = "DownloadWorker", inputJson = """{"url": "$fileUrl"}""")
).then(
    TaskRequest(workerClassName = "ValidateWorker")
).then(
    TaskRequest(workerClassName = "TranscodeWorker")
).then(
    TaskRequest(workerClassName = "UploadWorker", inputJson = """{"bucket": "processed"}""")
).enqueue()
```

**State model:**

```
Chain definition (steps[]) ──────────────────────────────── stored on disk
Chain progress (completedSteps[]) ───── updated after every step ─ stored on disk

After process kill:
  nextStep = first index NOT in completedSteps
  → execution resumes there, no re-runs, no skips
```

Steps are idempotent by design. If the same step index is completed twice (unlikely but possible on crash-during-write), the second completion is a no-op.

---

## Built-in workers

Ready to use with `scheduler.enqueue(workerClassName = "HttpRequestWorker", ...)`.

| Worker | Purpose |
|--------|---------|
| `HttpRequestWorker` | HTTP request with configurable method, headers, body |
| `HttpDownloadWorker` | File download to local storage |
| `HttpUploadWorker` | Multipart file upload |
| `HttpSyncWorker` | Fetch-and-persist data sync |
| `FileCompressionWorker` | File compression (requires ZIPFoundation on iOS) |

Input/output passed as JSON via `inputJson` / `WorkerResult.data`.

---

## Security

**SSRF protection** — outbound HTTP requests made by built-in workers are validated against a blocklist of internal/cloud-metadata endpoints before dispatch:

```
169.254.169.254   AWS/GCP/Azure IMDS
fd00:ec2::254     AWS EC2 (IPv6)
100.100.100.200   Alibaba Cloud ECS metadata
localhost, 0.0.0.0, [::1], 10.x, 172.16–31.x, 192.168.x
```

Compressed IPv6 loopback forms (`0::1`, `::0:0:0:1`, `0:0:0:0:0:0:0:1`, etc.) are all blocked via `::` expansion before matching.

RFC 3986 UserInfo bypass (`https://evil.com@127.0.0.1/`) is blocked by stripping the userinfo component before hostname resolution. Multiple `@` characters in the authority (e.g. `https://user:pass@127.0.0.1@legit.com`) are detected and all potential host segments are checked.

**Known limitation (DNS rebinding):** `SecurityValidator` checks the hostname at call time. It cannot defend against DNS rebinding attacks where the domain resolves to a public IP at validation but rebinds to a private IP at connection. For high-trust environments, add certificate pinning, a custom DNS resolver, or an egress proxy with its own blocklist.

**Input size validation** — Android WorkManager's `Data` object has a 10 KB hard limit. Inputs exceeding 10 KB throw `IllegalArgumentException` at enqueue time, before WorkManager sees them.

Custom workers making outbound requests should use `SecurityValidator` if needed.

---

## Testing

```
600+ tests across commonTest, iosTest, androidInstrumentedTest
```

Notable test coverage:
- `QA_PersistenceResilienceTest` — 100-step chain force-killed at step 50, verified to resume at exactly step 50 with no duplicate executions
- `V236ChainExecutorTest` — ChainExecutor regression tests for time budget, shutdown propagation, and batch loop correctness
- `V238BugFixesTest` — regression tests for v2.3.8: ExecutionRecord serialization, TaskPriority ordering, BgTaskIdProvider, TelemetryHook, IPv6 compressed loopback, multi-@ SSRF bypass, case-sensitivity path traversal
- `IosExecutionHistoryStoreTest` — iOS history store: save/get/clear, auto-pruning at 500 records, all `ExecutionStatus` variants, field preservation
- `AdaptiveTimeBudgetTest` — BGTask time budget under various cleanup scenarios + Low Power Mode floor reduction
- `AppendOnlyQueueTest` — CRC32 corruption detection, truncation recovery, concurrent read/write
- `SecurityValidatorTest` — SSRF, IPv6 compressed loopback, multi-@ UserInfo, case-sensitivity bypass

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
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Common issues |
| [CHANGELOG](CHANGELOG.md) | Release history |

**Migration:** [v2.2.2](docs/MIGRATION_V2.2.2.md) · [v2.3.0](docs/MIGRATION_V2.3.0.md) · [v2.3.3 → v2.3.4](docs/MIGRATION_V2.3.3_TO_V2.3.4.md)

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

Before opening a PR, run:

```bash
./gradlew :kmpworker:allTests   # all platforms must pass
```

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/). This keeps the CHANGELOG accurate and version bumps automated:

```
feat: add exponential backoff to HttpRequestWorker
fix: chain executor deadlock when BGTask expires during flush
security: validate redirects in HttpDownloadWorker
```

---

## License

Apache 2.0. See [LICENSE](LICENSE).

---

<div align="center">

[GitHub](https://github.com/brewkits/kmpworkmanager) · [Maven Central](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager) · [Issues](https://github.com/brewkits/kmpworkmanager/issues)

</div>
