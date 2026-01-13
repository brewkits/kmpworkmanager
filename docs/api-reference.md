# API Reference

Complete API documentation for KMP TaskManager.

## Table of Contents

- [BackgroundTaskScheduler](#backgroundtaskscheduler)
- [Task Triggers](#task-triggers)
- [Constraints](#constraints)
- [TaskChain](#taskchain)
- [Events](#events)
- [Enums](#enums)
- [Platform-Specific APIs](#platform-specific-apis)

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
    input: String? = null,
    constraints: Constraints = Constraints()
): ScheduleResult
```

**Parameters:**

- `id: String` - Unique identifier for the task. If a task with the same ID exists, behavior depends on `ExistingWorkPolicy` in constraints.
- `trigger: TaskTrigger` - When and how the task should be executed (OneTime, Periodic, Exact, etc.)
- `workerClassName: String` - Name of the worker class that will execute the task
- `input: String?` - Optional input data passed to the worker (must be serializable)
- `constraints: Constraints` - Execution constraints (network, battery, charging, etc.)

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
    val flexMs: Long? = null
) : TaskTrigger
```

**Parameters:**

- `intervalMs: Long` - Interval between executions in milliseconds (minimum: 15 minutes)
- `flexMs: Long?` - Flex time window for Android WorkManager (optional)

**Supported Platforms:** Android, iOS

**Important Notes:**

- Android: Minimum interval is 15 minutes (enforced by WorkManager)
- iOS: Task automatically re-schedules after completion
- iOS: Actual execution time determined by BGTaskScheduler (opportunistic)

**Example:**

```kotlin
TaskTrigger.Periodic(
    intervalMs = 30 * 60 * 1000, // 30 minutes
    flexMs = 5 * 60 * 1000       // 5 minutes flex
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
    val startEpochMillis: Long,
    val endEpochMillis: Long
) : TaskTrigger
```

**Parameters:**

- `startEpochMillis: Long` - Window start time in epoch milliseconds
- `endEpochMillis: Long` - Window end time in epoch milliseconds

**Supported Platforms:** Android only (iOS returns `REJECTED_OS_POLICY`)

**Example:**

```kotlin
val now = Clock.System.now().toEpochMilliseconds()
TaskTrigger.Windowed(
    startEpochMillis = now + 60_000,      // Start in 1 minute
    endEpochMillis = now + 5 * 60_000     // End in 5 minutes
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

### System State Triggers

Trigger tasks based on device state changes.

```kotlin
data object BatteryLow : TaskTrigger
data object BatteryOkay : TaskTrigger
data object StorageLow : TaskTrigger
data object DeviceIdle : TaskTrigger
```

**Supported Platforms:**

- `BatteryLow`, `BatteryOkay`: Android, iOS
- `StorageLow`, `DeviceIdle`: Android only

**Example:**

```kotlin
// Run heavy processing when battery is good
scheduler.enqueue(
    id = "ml-training",
    trigger = TaskTrigger.BatteryOkay,
    workerClassName = "MLTrainingWorker",
    constraints = Constraints(requiresCharging = true)
)
```

---

## Constraints

Constraints define the conditions under which a task can run.

```kotlin
data class Constraints(
    // Network
    val requiresNetwork: Boolean = false,
    val networkType: NetworkType = NetworkType.CONNECTED,
    val requiresUnmeteredNetwork: Boolean = false,

    // Battery
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = false,

    // Storage
    val requiresStorageNotLow: Boolean = false,

    // Device State
    val requiresDeviceIdle: Boolean = false,
    val allowWhileIdle: Boolean = false,

    // Task Properties
    val isHeavyTask: Boolean = false,
    val expedited: Boolean = false,

    // Retry Policy
    val backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL,
    val backoffDelayMs: Long = 10_000,

    // Existing Work Policy
    val existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,

    // iOS Quality of Service
    val qos: QualityOfService = QualityOfService.DEFAULT
)
```

### Network Constraints

```kotlin
requiresNetwork: Boolean = false
```

Whether the task requires network connectivity.

**Platforms:** Android, iOS

---

```kotlin
networkType: NetworkType = NetworkType.CONNECTED
```

Type of network required. Options:

- `NetworkType.NOT_REQUIRED` - No network needed
- `NetworkType.CONNECTED` - Any network connection
- `NetworkType.UNMETERED` - WiFi or unlimited data (Android only)
- `NetworkType.NOT_ROAMING` - Non-roaming network (Android only)
- `NetworkType.METERED` - Cellular data allowed (Android only)
- `NetworkType.TEMPORARILY_UNMETERED` - Temporarily free network (Android only)

**Platforms:** Android (full support), iOS (only CONNECTED/NOT_REQUIRED)

---

```kotlin
requiresUnmeteredNetwork: Boolean = false
```

Shortcut for requiring WiFi (same as `networkType = NetworkType.UNMETERED`).

**Platforms:** Android only

---

### Battery Constraints

```kotlin
requiresCharging: Boolean = false
```

Whether the device must be charging.

**Platforms:** Android, iOS

---

```kotlin
requiresBatteryNotLow: Boolean = false
```

Whether the battery level must be above the low threshold.

**Platforms:** Android, iOS

---

### Storage Constraints

```kotlin
requiresStorageNotLow: Boolean = false
```

Whether the device must have sufficient storage available.

**Platforms:** Android only

---

### Device State Constraints

```kotlin
requiresDeviceIdle: Boolean = false
```

Whether the device must be idle (screen off, not recently used).

**Platforms:** Android only

---

```kotlin
allowWhileIdle: Boolean = false
```

Whether the task can run while the device is in Doze mode.

**Platforms:** Android only

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

```kotlin
expedited: Boolean = false
```

Whether the task should be expedited (run as soon as possible).

**Platforms:** Android only (uses expedited WorkManager jobs)

---

### Retry Policy

```kotlin
backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL
```

Retry strategy when a task fails. Options:

- `BackoffPolicy.EXPONENTIAL` - Exponential backoff (10s, 20s, 40s, 80s, ...)
- `BackoffPolicy.LINEAR` - Linear backoff (10s, 20s, 30s, 40s, ...)

**Platforms:** Android, iOS

---

```kotlin
backoffDelayMs: Long = 10_000
```

Initial backoff delay in milliseconds (default: 10 seconds).

**Platforms:** Android, iOS

---

### Existing Work Policy

```kotlin
existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
```

What to do when a task with the same ID already exists. Options:

- `ExistingWorkPolicy.REPLACE` - Cancel existing and schedule new task
- `ExistingWorkPolicy.KEEP` - Keep existing task, ignore new request
- `ExistingWorkPolicy.APPEND` - Queue new task after existing one
- `ExistingWorkPolicy.APPEND_OR_REPLACE` - Append if existing is running, replace otherwise

**Platforms:** Android (full support), iOS (only REPLACE/KEEP)

---

### iOS Quality of Service

```kotlin
qos: QualityOfService = QualityOfService.DEFAULT
```

iOS priority hint for task execution. Options:

- `QualityOfService.HIGH` - User-initiated priority
- `QualityOfService.DEFAULT` - Default priority
- `QualityOfService.LOW` - Background priority

**Platforms:** iOS only

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

#### `enqueue()`

Execute the constructed task chain.

```kotlin
suspend fun enqueue(): ScheduleResult
```

**Returns:** `ScheduleResult` - Result of the chain scheduling operation

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
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)
```

**Parameters:**

- `taskName: String` - Name of the worker that completed
- `success: Boolean` - Whether the task succeeded
- `message: String` - Human-readable message
- `timestamp: Long` - Event timestamp in epoch milliseconds

---

### Usage

**Emitting events from workers:**

```kotlin
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        return try {
            syncDataFromServer()

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "SyncWorker",
                    success = true,
                    message = "✅ Data synced successfully"
                )
            )

            true
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "SyncWorker",
                    success = false,
                    message = "❌ Sync failed: ${e.message}"
                )
            )

            false
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
    SUCCESS,                    // Task scheduled successfully
    REJECTED_OS_POLICY,         // OS rejected the task (e.g., iOS background restrictions)
    REJECTED_INVALID_PARAMS,    // Invalid parameters provided
    FAILED_UNKNOWN              // Unknown error occurred
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

### ExistingWorkPolicy

Policy for handling existing tasks with the same ID.

```kotlin
enum class ExistingWorkPolicy {
    REPLACE,            // Cancel existing and schedule new task
    KEEP,              // Keep existing task, ignore new request
    APPEND,            // Queue new task after existing one
    APPEND_OR_REPLACE  // Append if running, replace otherwise
}
```

---

### NetworkType

Network requirement for tasks.

```kotlin
enum class NetworkType {
    NOT_REQUIRED,           // No network needed
    CONNECTED,              // Any network connection
    UNMETERED,              // WiFi or unlimited data
    NOT_ROAMING,            // Non-roaming network
    METERED,                // Cellular data allowed
    TEMPORARILY_UNMETERED   // Temporarily free network
}
```

---

### QualityOfService (iOS)

Priority hint for iOS tasks.

```kotlin
enum class QualityOfService {
    HIGH,       // User-initiated priority
    DEFAULT,    // Default priority
    LOW         // Background priority
}
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

#### IosWorker

Interface for iOS background workers.

```kotlin
interface IosWorker {
    suspend fun doWork(input: String?): Boolean
}
```

**Implementation:**

```kotlin
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        // Your implementation (must complete within 25 seconds)
        return true // Return true for success, false for failure
    }
}
```

---

#### IosWorkerFactory

Factory for creating worker instances.

```kotlin
object IosWorkerFactory {
    fun createWorker(className: String): IosWorker? {
        return when (className) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            else -> null
        }
    }
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
- [GitHub Issues](https://github.com/brewkits/kmp_worker/issues)
