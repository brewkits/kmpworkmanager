# API Reference

Complete API documentation for KMP WorkManager.

## Table of Contents

- [v2.4.3 APIs](#v243-apis)
- [BackgroundTaskScheduler](#backgroundtaskscheduler)
- [WorkerResult (v2.3.0+)](#workerresult-v230)
- [Task Triggers](#task-triggers)
- [Constraints](#constraints)
- [TaskChain](#taskchain)
- [Events](#events)
- [Enums](#enums)
- [Platform-Specific APIs](#platform-specific-apis)

---

## v2.4.3 APIs

New types and scheduler methods added in v2.4.3.

### TaskPriority

Controls how urgently the scheduler tries to run a task when multiple tasks compete for the available background execution budget.

```kotlin
enum class TaskPriority(internal val weight: Int) {
    /** Deferred work that can wait for idle/charging conditions. */
    LOW(weight = 0),

    /** Default priority for most background tasks. */
    NORMAL(weight = 1),

    /**
     * Important work that should run before NORMAL tasks.
     * Android: mapped to expedited work (skips Doze).
     */
    HIGH(weight = 2),

    /**
     * Mission-critical work (payments, security tokens, compliance uploads).
     * Android: mapped to expedited work.
     * iOS: placed at head of execution queue, executed in the very first available BGTask window.
     * Use sparingly — overuse degrades the app's background execution budget.
     */
    CRITICAL(weight = 3)
}
```

**Android mapping:**
- `CRITICAL` / `HIGH` → `setExpedited()` (bypasses Doze)
- `NORMAL` → standard OneTime / Periodic work
- `LOW` → standard work, deferred when battery/network is constrained

**iOS mapping:** The in-memory queue is sorted by priority before each BGTask execution window. Higher-priority chains are dequeued first.

**Usage:**

```kotlin
scheduler.beginWith(
    TaskRequest(
        workerClassName = "PaymentSyncWorker",
        priority = TaskPriority.CRITICAL
    )
).enqueue()
```

---

### TelemetryHook

Hook interface for observing task lifecycle events. Implement this to route KMP WorkManager events to your telemetry backend (Sentry, Firebase Crashlytics, Datadog, etc.).

All methods have default no-op implementations — override only what you need. Callbacks are invoked from background coroutine dispatchers and must not suspend or block.

```kotlin
interface TelemetryHook {
    fun onTaskStarted(event: TelemetryHook.TaskStartedEvent) {}
    fun onTaskCompleted(event: TelemetryHook.TaskCompletedEvent) {}
    fun onTaskFailed(event: TelemetryHook.TaskFailedEvent) {}
    fun onChainCompleted(event: TelemetryHook.ChainCompletedEvent) {}
    fun onChainFailed(event: TelemetryHook.ChainFailedEvent) {}
    fun onChainSkipped(event: TelemetryHook.ChainSkippedEvent) {}
}
```

**Event types:**

| Event class | Key fields |
|---|---|
| `TaskStartedEvent` | `taskName`, `chainId?`, `stepIndex?`, `platform`, `startedAtMs` |
| `TaskCompletedEvent` | `taskName`, `chainId?`, `stepIndex?`, `platform`, `success`, `durationMs`, `errorMessage?` |
| `TaskFailedEvent` | `taskName`, `chainId?`, `stepIndex?`, `platform`, `error`, `durationMs`, `retryCount` |
| `ChainCompletedEvent` | `chainId`, `totalSteps`, `platform`, `durationMs` |
| `ChainFailedEvent` | `chainId`, `failedStep`, `platform`, `error`, `retryCount`, `willRetry` |
| `ChainSkippedEvent` | `chainId`, `platform`, `reason` |

**Registration:**

```kotlin
val config = KmpWorkManagerConfig(
    telemetryHook = object : TelemetryHook {
        override fun onTaskFailed(event: TelemetryHook.TaskFailedEvent) {
            Sentry.captureMessage("Task failed: ${event.taskName} — ${event.error}")
        }
        override fun onChainFailed(event: TelemetryHook.ChainFailedEvent) {
            FirebaseCrashlytics.getInstance().recordException(
                RuntimeException("Chain ${event.chainId} failed at step ${event.failedStep}")
            )
        }
    }
)
```

---

### ExecutionRecord

A single persisted record of a background task chain execution. Records are written when a chain finishes (success, failure, abandoned, skipped, or timeout).

```kotlin
@Serializable
data class ExecutionRecord(
    val id: String,
    val chainId: String,
    val status: ExecutionStatus,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationMs: Long,
    val totalSteps: Int,
    val completedSteps: Int,
    val failedStep: Int? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val platform: String,
    val workerClassNames: List<String> = emptyList()
)

enum class ExecutionStatus {
    SUCCESS, FAILURE, ABANDONED, SKIPPED, TIMEOUT
}
```

**ExecutionStatus values:**
- `SUCCESS` — all steps completed successfully.
- `FAILURE` — a step failed; chain is re-queued for retry.
- `ABANDONED` — max retries exhausted or non-idempotent tasks after corrupt-progress self-heal.
- `SKIPPED` — chain discarded before execution (REPLACE policy or deadline exceeded).
- `TIMEOUT` — chain timed out within its BGTask window; re-queued for continuation.

---

### ExecutionHistoryStore

Persistent store for `ExecutionRecord`s. Records are appended after each chain execution and automatically pruned when the store exceeds 500 entries.

```kotlin
interface ExecutionHistoryStore {
    suspend fun save(record: ExecutionRecord)
    suspend fun getRecords(limit: Int = 100): List<ExecutionRecord>
    suspend fun clear()

    companion object {
        const val MAX_RECORDS = 500
    }
}
```

Platform implementations: iOS uses a JSONL file in Application Support; Android uses a JSONL file in `filesDir`.

---

### WorkerDiagnostics

Interface for debugging scheduler state and system health. Useful for debug screens, production monitoring dashboards, and customer support diagnostics.

```kotlin
interface WorkerDiagnostics {
    suspend fun getSchedulerStatus(): SchedulerStatus
    suspend fun getSystemHealth(): SystemHealthReport
    suspend fun getTaskStatus(id: String): TaskStatusDetail?
}

data class SchedulerStatus(
    val isReady: Boolean,
    val totalPendingTasks: Int,
    val queueSize: Int,
    val platform: String,
    val timestamp: Long
)

data class SystemHealthReport(
    val timestamp: Long,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val networkAvailable: Boolean,
    val storageAvailable: Long,
    val isStorageLow: Boolean,
    val isLowPowerMode: Boolean,  // iOS only; always false on Android
    val deviceInDozeMode: Boolean // Android only; always false on iOS
)

data class TaskStatusDetail(
    val taskId: String,
    val workerClassName: String,
    val state: String, // "PENDING", "RUNNING", "COMPLETED", "FAILED"
    val retryCount: Int,
    val lastExecutionTime: Long?,
    val lastError: String?
)
```

**Usage:**

```kotlin
val diagnostics = WorkerDiagnostics.getInstance()
val health = diagnostics.getSystemHealth()

if (health.isLowPowerMode) {
    println("BGTasks may be throttled — device in low power mode")
}
if (health.isStorageLow) {
    println("Storage critical — tasks may fail")
}
```

---

### Execution History Methods (BackgroundTaskScheduler)

Two new methods on `BackgroundTaskScheduler` (v2.3.8+):

```kotlin
/** Returns the most recent task chain execution records, newest first. */
suspend fun getExecutionHistory(limit: Int = 100): List<ExecutionRecord>

/** Deletes all stored execution history records. */
suspend fun clearExecutionHistory()
```

**Typical usage:**

```kotlin
// On app foreground: collect and upload, then clear
val records = scheduler.getExecutionHistory(limit = 200)
analyticsService.uploadBatchAsync(records)
scheduler.clearExecutionHistory()
```

---

## BackgroundTaskScheduler

The main interface for scheduling and managing background tasks.

### Methods

#### `enqueue()`

Schedule a single background task.

```kotlin
suspend fun enqueue(
    id: String,
    trigger: TaskTrigger,
    workerClassName: String,
    constraints: Constraints = Constraints(),
    inputJson: String? = null,
    policy: ExistingPolicy = ExistingPolicy.REPLACE
): ScheduleResult
```

**Parameters:**

- `id: String` - Unique identifier for the task. If a task with the same ID exists, behavior depends on `policy`.
- `trigger: TaskTrigger` - When and how the task should be executed (OneTime, Periodic, Exact, etc.)
- `workerClassName: String` - Name of the worker class that will execute the task
- `constraints: Constraints` - Execution constraints (network, battery, charging, etc.)
- `inputJson: String?` - Optional JSON input data passed to the worker
- `policy: ExistingPolicy` - How to handle an existing task with the same ID (default: REPLACE)

**Returns:** `ScheduleResult` - Result of the scheduling operation

**Example:**

```kotlin
val result = scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 15_MINUTES),
    workerClassName = "SyncWorker",
    constraints = Constraints(requiresNetwork = true)
)
```

---

#### `beginWith()`

Start building a task chain with a single task or multiple parallel tasks.

```kotlin
fun beginWith(request: TaskRequest): TaskChain

fun beginWith(requests: List<TaskRequest>): TaskChain
```

**Parameters:**

- `request: TaskRequest` - Single task to start the chain
- `requests: List<TaskRequest>` - Multiple tasks to run in parallel at the start

**Returns:** `TaskChain` - Builder for constructing task chains

**Example:**

```kotlin
// Sequential chain
scheduler.beginWith(TaskRequest(workerClassName = "DownloadWorker"))
    .then(TaskRequest(workerClassName = "ProcessWorker"))
    .enqueue()

// Parallel start
scheduler.beginWith(listOf(
    TaskRequest(workerClassName = "SyncWorker"),
    TaskRequest(workerClassName = "CacheWorker")
))
    .then(TaskRequest(workerClassName = "FinalizeWorker"))
    .enqueue()
```

---

#### `cancel()`

Cancel a specific task by its ID.

```kotlin
suspend fun cancel(id: String)
```

**Parameters:**

- `id: String` - ID of the task to cancel

**Example:**

```kotlin
scheduler.cancel("data-sync")
```

---

#### `cancelAll()`

Cancel all scheduled tasks.

```kotlin
suspend fun cancelAll()
```

**Example:**

```kotlin
scheduler.cancelAll()
```

---

## WorkerResult (v2.3.0+)

**New in v2.3.0:** Workers can now return structured results instead of just boolean.

### WorkerResult Sealed Class

```kotlin
sealed class WorkerResult {
    data class Success(
        val message: String? = null,
        val data: Map<String, Any?>? = null
    ) : WorkerResult()

    data class Failure(
        val message: String
    ) : WorkerResult()
}
```

### Worker Interface

**Common Worker:**

```kotlin
interface CommonWorker {
    suspend fun doWork(input: String?): WorkerResult
}
```

**Backward Compatibility:**

Workers returning `Boolean` are automatically converted to `WorkerResult`:
- `true` → `WorkerResult.Success()`
- `false` → `WorkerResult.Failure("Task failed")`

### Examples

#### Basic Success/Failure

```kotlin
class SyncWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        return try {
            syncData()
            WorkerResult.Success(message = "Sync completed")
        } catch (e: Exception) {
            WorkerResult.Failure("Sync failed: ${e.message}")
        }
    }
}
```

#### Returning Data

```kotlin
class DownloadWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        val config = Json.decodeFromString<DownloadConfig>(input!!)
        val file = downloadFile(config.url, config.savePath)

        return WorkerResult.Success(
            message = "Downloaded ${file.length()} bytes in 5s",
            data = buildJsonObject {
                put("filePath", config.savePath)
                put("fileSize", file.length())
                put("url", config.url)
                put("duration", 5000L)
            }
        )
    }
}
```

#### Handling Results

```kotlin
// In your application code
when (val result = worker.doWork(input)) {
    is WorkerResult.Success -> {
        println("Success: ${result.message}")
        val fileSize = result.data?.get("fileSize")?.jsonPrimitive?.longOrNull
        println("File size: $fileSize bytes")
    }
    is WorkerResult.Failure -> {
        println("Failed: ${result.message}")
    }
}
```

#### Data Passing in Chains

```kotlin
// Worker 1: Download file and return metadata
class DownloadWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        val file = download(url)
        return WorkerResult.Success(
            data = buildJsonObject {
                put("filePath", file.path)
                put("size", file.size)
            }
        )
    }
}

// Worker 2: Process downloaded file
class ProcessWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        // In v2.3.0: Access previous worker data via event bus or custom implementation
        // In v2.4.3: Automatic data passing will be supported
        return WorkerResult.Success(message = "Processed file")
    }
}

// Chain them together
scheduler.beginWith(TaskRequest("DownloadWorker"))
    .then(TaskRequest("ProcessWorker"))
    .withId("download-process-chain", policy = ExistingPolicy.KEEP)
    .enqueue()
```

### Benefits

✅ **Structured Data Return**: Return any data from workers
✅ **Better Error Messages**: Detailed failure messages
✅ **Type Safety**: Explicit success/failure handling
✅ **Backward Compatible**: Boolean returns still work
✅ **Built-in Workers**: All 5 built-in workers return meaningful data

---

## Task Triggers

Task triggers define when and how tasks should be executed.

### TaskTrigger.OneTime

Execute a task once after an optional delay.

```kotlin
data class OneTime(
    val initialDelayMs: Long = 0
) : TaskTrigger
```

**Parameters:**

- `initialDelayMs: Long` - Delay before execution in milliseconds (default: 0)

**Supported Platforms:** Android, iOS

**Example:**

```kotlin
TaskTrigger.OneTime(initialDelayMs = 5_000) // Execute after 5 seconds
```

---

### TaskTrigger.Periodic

Execute a task repeatedly at fixed intervals.

```kotlin
data class Periodic(
    val intervalMs: Long,
    val flexMs: Long? = null,
    val initialDelayMs: Long = 0,
    val runImmediately: Boolean = true
) : TaskTrigger
```

**Parameters:**

- `intervalMs: Long` - Interval between executions in milliseconds (minimum: 15 minutes on Android)
- `flexMs: Long?` - Flex time window for Android WorkManager (optional). Clamped to `[5 min, intervalMs]` automatically.
- `initialDelayMs: Long` - Delay before the very first execution (default: 0)
- `runImmediately: Boolean` - Whether to run immediately on first schedule (default: `true`). When `false` and `initialDelayMs == 0`, the first run is deferred by one full `intervalMs`. Setting `runImmediately = false` with `initialDelayMs > 0` throws `IllegalArgumentException` — they are mutually exclusive.

**Supported Platforms:** Android, iOS

**Important Notes:**

- Android: Minimum interval is 15 minutes (enforced by WorkManager)
- iOS: Task automatically re-schedules after completion with drift correction
- iOS: Actual execution time determined by BGTaskScheduler (opportunistic)

**Example:**

```kotlin
// Every 30 minutes, run immediately on first schedule
TaskTrigger.Periodic(
    intervalMs = 30 * 60 * 1000,
    flexMs = 5 * 60 * 1000
)

// Every hour, but defer the very first run by 1 hour
TaskTrigger.Periodic(
    intervalMs = 60 * 60 * 1000,
    runImmediately = false
)

// Every 15 minutes, but wait 10 minutes before the first run
TaskTrigger.Periodic(
    intervalMs = 15 * 60 * 1000,
    initialDelayMs = 10 * 60 * 1000
)
```

---

### TaskTrigger.Exact

Execute a task at a precise time.

```kotlin
data class Exact(
    val atEpochMillis: Long
) : TaskTrigger
```

**Parameters:**

- `atEpochMillis: Long` - Exact timestamp in epoch milliseconds

**Supported Platforms:** Android, iOS

**Implementation:**

- Android: Uses `AlarmManager.setExactAndAllowWhileIdle()`
- iOS: Uses `UNUserNotificationCenter` local notifications

**Example:**

```kotlin
val targetTime = Clock.System.now()
    .plus(1.hours)
    .toEpochMilliseconds()

TaskTrigger.Exact(atEpochMillis = targetTime)
```

---

### TaskTrigger.Windowed

Execute a task within a time window.

```kotlin
data class Windowed(
    val earliest: Long,
    val latest: Long
) : TaskTrigger
```

**Parameters:**

- `earliest: Long` - Window start time in epoch milliseconds
- `latest: Long` - Window end time in epoch milliseconds. On iOS only `earliest` is enforced via `earliestBeginDate` — the OS decides when to run opportunistically within its background budget.

**Supported Platforms:** Android ✅ iOS ⚠️ (best-effort, `latest` not enforced)

**Example:**

```kotlin
val now = Clock.System.now().toEpochMilliseconds()
TaskTrigger.Windowed(
    earliest = now + 60_000,        // Start in 1 minute
    latest   = now + 5 * 60_000    // End in 5 minutes
)
```

---

### TaskTrigger.ContentUri

Trigger a task when content provider changes are detected.

```kotlin
data class ContentUri(
    val uriString: String,
    val triggerForDescendants: Boolean = true
) : TaskTrigger
```

**Parameters:**

- `uriString: String` - Content URI to observe (e.g., "content://media/external/images/media")
- `triggerForDescendants: Boolean` - Whether to trigger for descendant URIs (default: true)

**Supported Platforms:** Android only (iOS returns `REJECTED_OS_POLICY`)

**Example:**

```kotlin
TaskTrigger.ContentUri(
    uriString = "content://media/external/images/media",
    triggerForDescendants = true
)
```

---

### System State Triggers (Deprecated — compile error since v2.3.7)

`BatteryLow`, `BatteryOkay`, `StorageLow`, `DeviceIdle` were removed as `TaskTrigger` subtypes. They are now `DeprecationLevel.ERROR` — referencing them causes a compile error.

**Migration:** Use `Constraints(systemConstraints = setOf(...))` instead:

```kotlin
// Old (compile error)
trigger = TaskTrigger.BatteryLow

// New
constraints = Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY))
```

`SystemConstraint` values: `ALLOW_LOW_STORAGE`, `ALLOW_LOW_BATTERY`, `REQUIRE_BATTERY_NOT_LOW`, `DEVICE_IDLE`.

---

## Constraints

Constraints define the conditions under which a task can run.

```kotlin
data class Constraints(
    val requiresNetwork: Boolean = false,
    val requiresUnmeteredNetwork: Boolean = false,
    val requiresCharging: Boolean = false,
    val allowWhileIdle: Boolean = false,
    val qos: Qos = Qos.Background,
    val isHeavyTask: Boolean = false,
    val backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL,
    val backoffDelayMs: Long = 30_000,
    val maxRetries: Int = -1, // N → N+1 total runs; -1 = platform default. One-time & chains only.
    val systemConstraints: Set<SystemConstraint> = emptySet(),
    val exactAlarmIOSBehavior: ExactAlarmIOSBehavior = ExactAlarmIOSBehavior.SHOW_NOTIFICATION,
    val extras: Map<String, String> = emptyMap()
)
```

> **Claim vs. reality**: an earlier revision of this reference showed a `Constraints` shape
> with `networkType`, `requiresBatteryNotLow`, `requiresStorageNotLow`, `requiresDeviceIdle`,
> `expedited`, and `existingWorkPolicy` as fields. None of these exist — they predate a
> refactor that replaced most of them with the `SystemConstraint` enum below and moved
> `ExistingPolicy` to its own `enqueue()`/`enqueueChain()` parameter. `expedited` was replaced
> by `TaskPriority` (`CRITICAL`/`HIGH` map to Android's `setExpedited()`; see [TaskPriority](#taskpriority)).
> The block above is the actual, current shape (`Contracts.kt`).

### Network Constraints

```kotlin
requiresNetwork: Boolean = false
```

Whether the task requires network connectivity.

**Platforms:** Android, iOS

---

```kotlin
requiresUnmeteredNetwork: Boolean = false
```

Requires Wi-Fi (unmetered) rather than any network. There is no separate `NetworkType` enum —
this boolean is the only network-type axis in `Constraints`.

**Platforms:** ✅ Android, ✅ iOS — enforced for standalone tasks and chain steps alike
(iOS: `StandaloneConstraintGuard`/`ChainExecutor`, checked at dispatch time since
`BGTaskScheduler` has no native Wi-Fi-only flag).

---

### Battery Constraints

```kotlin
requiresCharging: Boolean = false
```

Whether the device must be charging.

**Platforms:** ✅ Android, ⚠️ iOS — honored unconditionally (OS-level flag) for a task with a
static Info.plist identifier, and for the dynamic queue when **every** currently-pending
dynamic task requires charging. For a queue mixing charging and non-charging tasks, enforced
only if the host app has separately opted in to `UIDevice.batteryMonitoringEnabled` — the
library never toggles that flag itself. See `docs/ROADMAP.md` for the tracked gap.

---

```kotlin
systemConstraints: Set<SystemConstraint> = emptySet()
```

Replaces the removed `requiresBatteryNotLow`/`requiresStorageNotLow`/`requiresDeviceIdle`
boolean fields with an explicit set:

```kotlin
enum class SystemConstraint {
    REQUIRE_BATTERY_NOT_LOW,  // ✅ Android, ✅ iOS (via Low Power Mode / NSProcessInfo)
    ALLOW_LOW_BATTERY,        // ✅ Android, ✅ iOS — overrides REQUIRE_BATTERY_NOT_LOW for that task
    ALLOW_LOW_STORAGE,        // ✅ Android, ❌ iOS — no storage-pressure API on iOS
    DEVICE_IDLE               // ✅ Android, ❌ iOS — no Doze-equivalent on iOS
}
```

`ALLOW_LOW_STORAGE`/`DEVICE_IDLE` are iOS-`❌` because there is genuinely no OS primitive to
implement them against, not because they're merely unwired — see `docs/constraints-triggers.md`
for the full platform matrix.

---

```kotlin
allowWhileIdle: Boolean = false
```

Whether the task can run while the device is in Doze mode.

**Platforms:** Android only — no Doze-equivalent on iOS to bypass.

---

### Task Property Constraints

```kotlin
isHeavyTask: Boolean = false
```

Whether this is a long-running task (>10 minutes).

- Android: Uses `KmpHeavyWorker` with foreground service
- iOS: Uses `BGProcessingTask` instead of `BGAppRefreshTask`

**Platforms:** Android, iOS

---

Task priority (`TaskRequest.priority: TaskPriority`, not a `Constraints` field — chain steps
only, standalone `enqueue()` has no priority parameter) determines Android expediting:

```kotlin
enum class TaskPriority { CRITICAL, HIGH, NORMAL, LOW }
```

`CRITICAL`/`HIGH` are expedited via `setExpedited()` (subject to delay/heavy/charging/unmetered
checks); `NORMAL`/`LOW` (the default) are not. iOS has no expedited-queue primitive; priority
only affects iOS dynamic-queue sort order. **Platforms:** Android (expediting), iOS (sort order
only).

---

### Retry Policy

```kotlin
backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL
```

Retry strategy when a task fails. Options:

- `BackoffPolicy.EXPONENTIAL` - Exponential backoff (10s, 20s, 40s, 80s, ...)
- `BackoffPolicy.LINEAR` - Linear backoff (10s, 20s, 30s, 40s, ...)

**Platforms:** ✅ Android, ✅ iOS — iOS honors this for standalone-task retry timing when
explicitly set (the 30s/EXPONENTIAL default preserves the pre-3.4.0 "retry on next
opportunistic wake" behavior for callers who never touch these fields).

---

```kotlin
backoffDelayMs: Long = 30_000
```

Initial backoff delay in milliseconds (default: 30 seconds — note this differs from the
`10_000` shown in some older examples in this doc).

**Platforms:** Android, iOS

---

```kotlin
maxRetries: Int = -1
```

Hard ceiling on retry attempts. `maxRetries = N` allows at most **N + 1** total runs
(1 initial + N retries). Caps both `Failure(shouldRetry = true)` and a `Retry` with no explicit
`attemptCap` (a per-result `attemptCap` takes precedence). **One-time and chained tasks only** —
periodic tasks ignore it. Default `-1` = platform default (Android uncapped; iOS 5 attempts for
single tasks, 3 whole-chain retries).

**Platforms:** Android, iOS

---

### Existing Policy

`ExistingPolicy` is **not** a `Constraints` field — it's a separate parameter on
`enqueue()`/`enqueueChain()`/`TaskChain.withId()`. See [`ExistingPolicy`](#existingpolicy)
further down for the full enum and platform support (`KEEP`/`REPLACE`/`UPDATE` — no `APPEND`).

---

### iOS Quality of Service

```kotlin
qos: Qos = Qos.Background
```

iOS priority hint for task execution:

```kotlin
enum class Qos { Utility, Background, UserInitiated, UserInteractive }
```

**Platforms:** iOS only — Android has no equivalent per-task QoS knob (`isHeavyTask`/priority
cover the closest analogues there).

---

## TaskChain

Builder for creating sequential and parallel task workflows.

### Methods

#### `then()`

Add the next step to the chain.

```kotlin
fun then(request: TaskRequest): TaskChain

fun then(requests: List<TaskRequest>): TaskChain
```

**Parameters:**

- `request: TaskRequest` - Single task to execute next
- `requests: List<TaskRequest>` - Multiple tasks to run in parallel

**Returns:** `TaskChain` - The chain builder for further chaining

---

#### `withId()` (v2.3.0+)

Set a unique ID for the chain and specify the ExistingPolicy.

```kotlin
fun withId(
    id: String,
    policy: ExistingPolicy = ExistingPolicy.REPLACE
): TaskChain
```

**Parameters:**

- `id: String` - Unique identifier for the chain
- `policy: ExistingPolicy` - How to handle if a chain with this ID already exists
  - `ExistingPolicy.KEEP` - Skip if chain already running
  - `ExistingPolicy.REPLACE` - Cancel old chain and start new one
  - `ExistingPolicy.UPDATE` (v3.4.0+) - Degrades to `REPLACE` for chains (no timer anchor to
    preserve, unlike a periodic task)

**Returns:** `TaskChain` - New chain instance with the specified ID and policy

**Example:**

```kotlin
// Prevent duplicate chain execution
scheduler.beginWith(TaskRequest("DownloadWorker"))
    .then(TaskRequest("ProcessWorker"))
    .withId("download-process-workflow", policy = ExistingPolicy.KEEP)
    .enqueue()

// Click button multiple times - only runs once
button.onClick {
    scheduler.beginWith(TaskRequest("SyncWorker"))
        .withId("sync-chain", policy = ExistingPolicy.KEEP)
        .enqueue()
}
```

---

#### `enqueue()`

Execute the constructed task chain.

```kotlin
fun enqueue()
```

**Note:** No return value in v2.3.0. The chain is enqueued asynchronously.

---

### TaskRequest

Data class representing a task in a chain.

```kotlin
data class TaskRequest(
    val id: String = UUID.randomUUID().toString(),
    val workerClassName: String,
    val input: String? = null,
    val constraints: Constraints = Constraints()
)
```

**Parameters:**

- `id: String` - Unique task identifier (auto-generated if not provided)
- `workerClassName: String` - Name of the worker class
- `input: String?` - Optional input data
- `constraints: Constraints` - Execution constraints

---

### Examples

```kotlin
// Sequential execution
scheduler
    .beginWith(TaskRequest(workerClassName = "DownloadWorker"))
    .then(TaskRequest(workerClassName = "ProcessWorker"))
    .then(TaskRequest(workerClassName = "UploadWorker"))
    .enqueue()

// Parallel execution
scheduler
    .beginWith(listOf(
        TaskRequest(workerClassName = "SyncWorker"),
        TaskRequest(workerClassName = "CacheWorker"),
        TaskRequest(workerClassName = "CleanupWorker")
    ))
    .then(TaskRequest(workerClassName = "FinalizeWorker"))
    .enqueue()

// Mixed sequential and parallel
scheduler
    .beginWith(TaskRequest(workerClassName = "DownloadWorker"))
    .then(listOf(
        TaskRequest(workerClassName = "ProcessImageWorker"),
        TaskRequest(workerClassName = "ProcessVideoWorker")
    ))
    .then(TaskRequest(workerClassName = "UploadWorker"))
    .enqueue()
```

---

## Events

Event system for worker-to-UI communication.

### TaskEventBus

Singleton object for emitting and collecting task completion events.

```kotlin
object TaskEventBus {
    val events: SharedFlow<TaskCompletionEvent>

    suspend fun emit(event: TaskCompletionEvent)
}
```

---

### TaskCompletionEvent

Event emitted when a task completes.

```kotlin
data class TaskCompletionEvent(
    val taskName: String,
    val success: Boolean,
    val message: String,
    val outputData: JsonObject? = null,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)
```

**Parameters:**

- `taskName: String` - Name of the worker that completed
- `success: Boolean` - Whether the task succeeded
- `message: String` - Human-readable message
- `outputData: JsonObject?` - Output data from worker (if successful)
- `timestamp: Long` - Event timestamp in epoch milliseconds

---

### Usage

**Emitting events from workers:**

```kotlin
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        return try {
            syncDataFromServer()

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "SyncWorker",
                    success = true,
                    message = "Data synced successfully",
                    outputData = buildJsonObject {
                        put("count", 100)
                    }
                )
            )

            WorkerResult.Success(message = "Data synced successfully")
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "SyncWorker",
                    success = false,
                    message = "Sync failed: ${e.message}"
                )
            )

            WorkerResult.Failure("Sync failed: ${e.message}")
        }
    }
}
```

**Collecting events in UI:**

```kotlin
@Composable
fun TaskMonitor() {
    LaunchedEffect(Unit) {
        TaskEventBus.events.collect { event ->
            when {
                event.success -> {
                    showSuccessToast(event.message)
                }
                else -> {
                    showErrorToast(event.message)
                }
            }
        }
    }
}
```

---

## Enums

### ScheduleResult

Result of a task scheduling operation.

```kotlin
enum class ScheduleResult {
    ACCEPTED,             // Task scheduled successfully
    REJECTED_OS_POLICY,   // OS rejected the task (e.g. Info.plist ID missing, Low Power Mode, BGTaskScheduler error)
    DEADLINE_ALREADY_PASSED, // Exact/Windowed trigger target time is in the past
    THROTTLED             // OS throttled the request (too many pending tasks)
}
```

---

### BackoffPolicy

Retry strategy for failed tasks.

```kotlin
enum class BackoffPolicy {
    EXPONENTIAL,  // Exponential backoff (10s, 20s, 40s, 80s, ...)
    LINEAR        // Linear backoff (10s, 20s, 30s, 40s, ...)
}
```

---

### ExistingPolicy

Policy for handling an existing task/chain with the same ID. Parameter to
`enqueue()`/`enqueueChain()`/`TaskChain.withId()` — **not** a `Constraints` field.

```kotlin
enum class ExistingPolicy {
    KEEP,     // Keep existing, ignore the new request
    REPLACE,  // Cancel existing, schedule new
    UPDATE    // (v3.4.0+) Update a periodic task's constraints/input without resetting its
              // interval timer. Degrades to REPLACE for one-time tasks and chains.
}
```

**Platforms:** ✅ Android, ✅ iOS — full parity, all three values.

`APPEND`/`APPEND_OR_REPLACE` do **not** exist on this enum. Appending steps to an
already-queued-or-running chain is a real, tracked, unimplemented gap — see
`docs/ROADMAP.md`'s `ExistingPolicy.APPEND` entry for why it's deliberately deferred rather
than a quick addition.

---

### NetworkType (does not exist)

There is no `NetworkType` enum in the current contract — an earlier draft of this doc showed
one. Network requirements are two independent booleans on `Constraints`:
`requiresNetwork`/`requiresUnmeteredNetwork`. See [Network Constraints](#network-constraints)
above.

---

### Qos (iOS)

Priority hint for iOS tasks — the real type is `Qos`, not `QualityOfService`, and it is
currently a no-op on both platforms (see [Constraints](#constraints) above for detail).

```kotlin
enum class Qos { Utility, Background, UserInitiated, UserInteractive }
```

---

## Platform-Specific APIs

### Android

#### KmpWorker

Base worker class for deferrable tasks.

```kotlin
class KmpWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val workerClassName = inputData.getString("workerClassName")

        return when (workerClassName) {
            "YourWorker" -> executeYourWorker()
            else -> Result.failure()
        }
    }

    private suspend fun executeYourWorker(): Result {
        // Your implementation
        return Result.success()
    }
}
```

---

#### KmpHeavyWorker

Foreground service worker for long-running tasks (>10 minutes).

```kotlin
class KmpHeavyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())

        // Your long-running work here

        return Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        // Create notification for foreground service
    }
}
```

---

### iOS

#### IosBackgroundTaskHandler

Kotlin-native API for handling the iOS background task lifecycle. Replaces the need for manual Swift boilerplate in `AppDelegate`.

```kotlin
object IosBackgroundTaskHandler {
    fun handleSingleTask(
        task: BGTask,
        scheduler: BackgroundTaskScheduler,
        executor: SingleTaskExecutor
    )

    fun handleChainExecutorTask(
        task: BGTask,
        chainExecutor: ChainExecutor
    )
}
```

**Swift Usage:**

```swift
BGTaskScheduler.shared.register(forTaskWithIdentifier: "my-task", using: nil) { task in
    IosBackgroundTaskHandler.shared.handleSingleTask(
        task: task,
        scheduler: SetupKt.kmpScheduler(),
        executor: SetupKt.kmpExecutor()
    )
}
```

---

#### IosWorker

Interface for iOS background workers.

```kotlin
interface IosWorker : dev.brewkits.kmpworkmanager.background.domain.Worker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult
}
```

**Implementation:**

```kotlin
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        // Your implementation (must complete within 25 seconds for light tasks)
        return WorkerResult.Success(message = "Sync complete")
    }
}
```

---

#### IosWorkerFactory

Factory for creating worker instances.

```kotlin
interface IosWorkerFactory : dev.brewkits.kmpworkmanager.background.domain.WorkerFactory {
    override fun createWorker(workerClassName: String): IosWorker?
}
```

---

## Constants

```kotlin
const val ONE_SECOND = 1_000L
const val ONE_MINUTE = 60_000L
const val FIFTEEN_MINUTES = 900_000L
const val ONE_HOUR = 3_600_000L
const val ONE_DAY = 86_400_000L
```

---

## Need More Help?

- [Quick Start Guide](quickstart.md) - Get started in 5 minutes
- [Platform Setup](platform-setup.md) - Detailed platform configuration
- [Task Chains](task-chains.md) - Advanced workflow patterns
- [Constraints & Triggers](constraints-triggers.md) - Detailed trigger documentation
- [GitHub Issues](https://github.com/brewkits/kmpworkmanager/issues)
