# iOS Best Practices

This guide covers critical iOS-specific considerations and best practices when using KMP WorkManager.

## Table of Contents

- [Critical iOS Limitations](#critical-ios-limitations)
- [Understanding iOS Background Tasks](#understanding-ios-background-tasks)
- [Task Design Guidelines](#task-design-guidelines)
- [Force-Quit Behavior](#force-quit-behavior)
- [Constraint Limitations](#constraint-limitations)
- [Performance Optimization](#performance-optimization)
- [Testing iOS Background Tasks](#testing-ios-background-tasks)
- [Common Pitfalls](#common-pitfalls)

## Critical iOS Limitations

### 0. Dynamic Task ID Support

Previously, iOS `BGTaskScheduler` required all task identifiers to be statically declared in your `Info.plist` file (`BGTaskSchedulerPermittedIdentifiers`).
**However, starting in v2.4.1, KMP WorkManager natively supports dynamic task IDs (e.g., `upload-photo-123`).**

You only need to declare the library's master dispatcher task (`kmp_master_dispatcher_task`) and chain executor (`kmp_chain_executor_task`) in your `Info.plist`, **and register a handler for each in your `AppDelegate`** (`handleMasterDispatcherTask` and `handleChainExecutorTask` respectively). The library then routes every dynamic task through the master dispatcher's internal queue and executes them when iOS fires the master dispatcher slot.
For historical context on how this works under the hood, read the [iOS Dynamic Task Scheduling Guide](ios-dynamic-task-scheduling.md).

### 1. Opportunistic Execution

iOS background tasks are **fundamentally different** from Android's WorkManager:

```kotlin
// ❌ BAD: Expecting predictable execution
scheduler.enqueue(
    id = "urgent-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000), // Every 15 minutes
    workerClassName = "DataSyncWorker"
)
// Reality: iOS may run this in 1 hour, 4 hours, or never
```

**Key Points:**
- The system decides when to run tasks based on:
  - Device usage patterns
  - Battery level
  - Thermal state
  - App usage history
  - Network availability
- Tasks may be delayed by hours or never execute
- No guarantees on execution timing

### 2. Strict Time Limits

| Task Type | Time Limit | When Available |
|-----------|-----------|----------------|
| `BGAppRefreshTask` | ~30 seconds, hard ceiling, no extension API | Always |
| `BGProcessingTask` | Several minutes (typically 1–10 min), iOS adjusts dynamically based on battery + thermal state | Available without charging/Wi-Fi; those are opt-in constraints you request, not requirements iOS imposes |

See [`docs/IOS_BGTASK_LIMITS.md`](IOS_BGTASK_LIMITS.md) for the full breakdown, including why the
library's own per-task executor timeout is 25s regardless of which BGTask type is used.

```kotlin
// ❌ BAD: Long-running task
class ProcessDataWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        // This will timeout after 30 seconds!
        processLargeDataset() // Takes 2 minutes
        return WorkerResult.Success()
    }
}

// ✅ GOOD: Break into chunks
class ProcessDataWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val batch = getNextBatch()
        processBatch(batch) // Takes 5 seconds
        if (hasMoreBatches()) {
            scheduleNextBatch()
        }
        return WorkerResult.Success()
    }
}
```

### 3. Force-Quit Termination

**When a user force-quits your app, ALL background tasks are immediately killed.**

This is **by iOS design** and cannot be worked around.

```kotlin
// ❌ BAD: Critical data operation
class SaveUserDataWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        // If user force-quits during this, data may be corrupted!
        database.beginTransaction()
        database.updateUserProfile(data)
        database.commit() // May never reach here
        return WorkerResult.Success()
    }
}

// ✅ GOOD: Use foreground operation for critical tasks
fun saveUserData() {
    // Show progress UI to prevent force-quit
    showSavingDialog()
    database.saveInTransaction(data)
    hideSavingDialog()
}
```

### 4. Limited Constraints

`requiresBatteryNotLow` and `requiresStorageNotLow` are **not real `Constraints` fields** —
they don't compile. That shape was superseded by `SystemConstraint` and `Contracts.kt`'s
current fields; see [`docs/constraints-triggers.md`](constraints-triggers.md) for the
authoritative list. Of the fields that DO exist:

```kotlin
// ✅ Fully supported on iOS, no caveats:
Constraints(
    requiresNetwork = true,
    requiresUnmeteredNetwork = true,               // Wi-Fi only
    systemConstraints = setOf(
        SystemConstraint.REQUIRE_BATTERY_NOT_LOW,  // via Low Power Mode
        SystemConstraint.ALLOW_LOW_BATTERY
    ),
    backoffPolicy = BackoffPolicy.EXPONENTIAL,     // affects real retry timing
    backoffDelayMs = 30_000L
)

// ⚠️ Supported, but only if the host app has already opted in to
// UIDevice.batteryMonitoringEnabled somewhere in its own code — the library never
// toggles that flag itself (doing so would race the host's own UI thread). Hosts
// that never touch batteryMonitoringEnabled see this silently unenforced:
Constraints(requiresCharging = true)

// ❌ No iOS primitive exists — structurally unsupported, not just unwired:
Constraints(
    systemConstraints = setOf(SystemConstraint.DEVICE_IDLE)       // no Doze-equivalent
    // SystemConstraint.ALLOW_LOW_STORAGE — no storage-pressure API either
)
```

## Understanding iOS Background Tasks

### BGAppRefreshTask vs BGProcessingTask

KMP WorkManager automatically chooses the right task type:

| Your Trigger | iOS Task Type | Time Limit | Requirements |
|--------------|---------------|-----------|--------------|
| `Periodic` | `BGAppRefreshTask` | ~30s | None |
| `OneTime` | `BGAppRefreshTask` | ~30s | None |
| `OneTime` with long duration | `BGProcessingTask` | ~60s | Charging + WiFi |

### Task Scheduling Behavior

```kotlin
// Android: Runs every 15 minutes ±5 minutes
// iOS: System decides when to run (could be hours)
scheduler.enqueue(
    id = "sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "SyncWorker"
)
```

**iOS Scheduling Factors:**
1. **App usage**: Frequently used apps get more background time
2. **Time patterns**: System learns when user typically uses app
3. **Battery**: Low battery reduces background activity
4. **Network**: Tasks requiring network wait for connectivity
5. **Force-quit**: Resets background execution privileges

## Task Design Guidelines

### 1. Design for Short Execution

```kotlin
// ❌ BAD: Monolithic task
class SyncAllDataWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        syncUsers()      // 10s
        syncPosts()      // 15s
        syncComments()   // 20s
        syncImages()     // 30s
        // Total: 75s - WILL TIMEOUT!
        return WorkerResult.Success()
    }
}

// ✅ GOOD: Modular tasks
class SyncUsersWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        syncUsers() // 10s - Safe
        return WorkerResult.Success()
    }
}

// Schedule separately
scheduler.enqueue(id = "sync-users", workerClassName = "SyncUsersWorker")
scheduler.enqueue(id = "sync-posts", workerClassName = "SyncPostsWorker")
scheduler.enqueue(id = "sync-comments", workerClassName = "SyncCommentsWorker")
```

### 2. Use Task Chains Wisely

```kotlin
// ⚠️ WARNING: Long chains may not complete
scheduler.beginWith(TaskRequest("Step1"))  // 15s
    .then(TaskRequest("Step2"))            // 15s
    .then(TaskRequest("Step3"))            // 15s
    .enqueue()
// Total: 45s - HIGH RISK on BGAppRefreshTask

// ✅ BETTER: Keep chains short (2-3 steps max)
scheduler.beginWith(TaskRequest("Download"))  // 10s
    .then(TaskRequest("Process"))             // 10s
    .enqueue()
// Total: 20s - SAFE
```

### 3. Handle Interruptions Gracefully

```kotlin
class ResilientWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val progress = loadProgress() ?: Progress(0)

        try {
            withTimeout(25_000) { // Leave 5s buffer
                while (!progress.isComplete()) {
                    val chunk = progress.nextChunk()
                    processChunk(chunk)
                    saveProgress(progress)
                }
            }
            return WorkerResult.Success()
        } catch (e: TimeoutCancellationException) {
            // Save progress and reschedule
            saveProgress(progress)
            rescheduleTask()
            return WorkerResult.Failure("Timed out — rescheduled for continuation")
        }
    }
}
```

### 4. Prioritize Critical Operations

```kotlin
// ✅ GOOD: Critical first, optional later
class SmartSyncWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        // Critical: User-generated content (5s)
        syncUserPosts()

        // Important: Recent data (10s)
        syncLastDayData()

        // Optional: Only if time permits (15s)
        withTimeoutOrNull(10_000) {
            syncHistoricalData()
        }

        return WorkerResult.Success()
    }
}
```

## Force-Quit Behavior

### What Happens on Force-Quit

1. **All background tasks are killed immediately**
2. **All future tasks are canceled**
3. **App loses background execution privileges temporarily**
4. **EventStore persistence survives** (if properly implemented)

### Handling Force-Quit

```kotlin
// ❌ BAD: No force-quit protection
class PaymentWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        processPayment() // If force-quit here, payment may be lost!
        return WorkerResult.Success()
    }
}

// ✅ GOOD: Use foreground mode for critical operations
fun initiatePayment() {
    // Show UI - prevents force-quit
    showPaymentProcessingDialog()

    // Process payment synchronously
    processPayment()

    // Then schedule background cleanup
    scheduler.enqueue(
        id = "cleanup-payment",
        trigger = TaskTrigger.OneTime(),
        workerClassName = "PaymentCleanupWorker"
    )
}
```

### Using Event Persistence

```kotlin
// ✅ GOOD: Emit events that survive force-quit
class DataSyncWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val result = syncData()

        // Event persists even if app is force-quit
        TaskEventManager.emit(
            TaskCompletionEvent(
                taskId = "data-sync",
                success = result.success,
                message = "Synced ${result.count} items"
            )
        )

        return if (result.success) WorkerResult.Success(message = "Synced ${result.count} items")
               else WorkerResult.Failure("Sync failed")
    }
}

// On next app launch, sync events are replayed
EventSyncManager.syncEvents(eventStore)
```

## Constraint Limitations

See [§4 above](#4-limited-constraints) for the current, field-accurate breakdown of what's
supported, opt-in-conditional, or structurally unsupported on iOS — duplicated here in an
earlier revision of this doc using a `Constraints` shape that no longer exists.

If your app never opts in to `UIDevice.batteryMonitoringEnabled` and still needs a real
charging check before scheduling a heavy task, do it manually at the call site instead of
relying on `Constraints.requiresCharging`:

```kotlin
expect fun shouldScheduleHeavyTask(): Boolean

// Android — WorkManager enforces this natively; the manual check is redundant but harmless.
actual fun shouldScheduleHeavyTask(): Boolean = true

// iOS — only needed if the host hasn't already turned on batteryMonitoringEnabled
// (if it has, prefer Constraints(requiresCharging = true) instead of this).
actual fun shouldScheduleHeavyTask(): Boolean {
    val device = UIDevice.currentDevice
    device.batteryMonitoringEnabled = true
    val isCharging = device.batteryState != UIDeviceBatteryState.UIDeviceBatteryStateUnplugged
    return device.batteryLevel > 0.5 || isCharging
}
```

## Performance Optimization

### 1. Minimize Setup Time

```kotlin
// ❌ BAD: Heavy initialization
class SlowWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val database = createDatabase() // 5s
        val api = initializeAPI()       // 3s
        doActualWork()                  // 8s
        // Total: 16s wasted on setup
        return WorkerResult.Success()
    }
}

// ✅ GOOD: Reuse shared instances
class FastWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val database = SharedDatabase.instance  // <1ms
        val api = SharedAPI.instance            // <1ms
        doActualWork()                          // 8s
        // Total: ~8s actual work
        return WorkerResult.Success()
    }
}
```

### 2. Use Efficient Storage

```kotlin
// ❌ BAD: Slow I/O
suspend fun saveData(data: List<Item>) {
    data.forEach { item ->
        database.insert(item) // Many small writes
    }
}

// ✅ GOOD: Batch operations
suspend fun saveData(data: List<Item>) {
    database.insertBatch(data) // Single write
}
```

### 3. Optimize Network Calls

```kotlin
// ❌ BAD: Sequential requests
suspend fun syncData() {
    val users = api.getUsers()      // 3s
    val posts = api.getPosts()      // 3s
    val comments = api.getComments() // 3s
    // Total: 9s
}

// ✅ GOOD: Parallel requests
suspend fun syncData() {
    coroutineScope {
        val usersDeferred = async { api.getUsers() }
        val postsDeferred = async { api.getPosts() }
        val commentsDeferred = async { api.getComments() }

        val users = usersDeferred.await()
        val posts = postsDeferred.await()
        val comments = commentsDeferred.await()
    }
    // Total: ~3s
}
```

## Testing iOS Background Tasks

### 1. Simulator Testing

```bash
# Trigger BGAppRefreshTask
xcrun simctl spawn booted \
  log stream --predicate 'subsystem == "com.apple.BackgroundTasks"'

# Manually trigger your task
xcrun simctl spawn booted \
  launchctl stop com.apple.BGTaskSchedulerAgent

# Schedule immediate execution
e -l objc -- \
  (void)[[BGTaskScheduler sharedScheduler] \
    _simulateLaunchForTaskWithIdentifier:@"your.task.id"]
```

### 2. Timeout Testing

```kotlin
class TimeoutTestWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val startTime = System.currentTimeMillis()

        try {
            doLongRunningWork()

            val duration = System.currentTimeMillis() - startTime
            Logger.i("TimeoutTest", "Completed in ${duration}ms")
            return WorkerResult.Success(message = "Completed in ${duration}ms")
        } catch (e: TimeoutCancellationException) {
            val duration = System.currentTimeMillis() - startTime
            Logger.w("TimeoutTest", "Timeout after ${duration}ms")
            return WorkerResult.Failure("Timeout after ${duration}ms")
        }
    }
}
```

### 3. Force-Quit Testing

1. Schedule a background task
2. Background the app
3. **Force-quit** the app (swipe up in app switcher)
4. Check if task executed (it shouldn't)
5. Reopen app and check EventStore for persistence

## Common Pitfalls

### Pitfall #1: Expecting Predictable Timing

```kotlin
// ❌ WRONG EXPECTATION
// "My periodic task will run every 15 minutes"

// ✅ CORRECT EXPECTATION
// "My periodic task will run when iOS decides,
//  which could be 15 minutes or 4 hours from now"
```

### Pitfall #2: Long-Running Tasks

```kotlin
// ❌ WILL FAIL
class VideoProcessingWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        processVideo() // Takes 5 minutes
        return WorkerResult.Success()
    }
}

// ✅ CORRECT APPROACH
// Process video in foreground with progress UI
fun processVideo() {
    showProgressDialog()
    processVideoSynchronously()
    hideProgressDialog()
}
```

### Pitfall #3: Assuming `requiresCharging` Enforces Itself Without Setup

```kotlin
// ⚠️ SILENTLY UNENFORCED if the host app never opts in to
// UIDevice.batteryMonitoringEnabled anywhere in its own code — the library never
// toggles that flag itself, so this constraint does nothing on a host that doesn't.
scheduler.enqueue(
    id = "heavy-task",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "HeavyWorker",
    constraints = Constraints(requiresCharging = true)
)

// ✅ EITHER opt in once at app startup so the constraint above actually works:
UIDevice.currentDevice.batteryMonitoringEnabled = true

// ✅ OR do the manual check instead, if you'd rather not touch that global flag:
if (isCharging() || getBatteryLevel() > 0.8) {
    scheduler.enqueue(
        id = "heavy-task",
        trigger = TaskTrigger.OneTime(),
        workerClassName = "HeavyWorker"
    )
}
```

### Pitfall #4: Not Handling Force-Quit

```kotlin
// ❌ LOSES DATA ON FORCE-QUIT
class UploadWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        uploadFile() // Lost if force-quit
        deleteLocalCopy() // May execute without upload
        return WorkerResult.Success()
    }
}

// ✅ SAFE WITH EVENT PERSISTENCE
class UploadWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val success = uploadFile()
        if (success) {
            TaskEventManager.emit(
                TaskCompletionEvent("upload", true, "Uploaded")
            )
            deleteLocalCopy()
            return WorkerResult.Success(message = "Uploaded")
        }
        return WorkerResult.Failure("Upload failed")
    }
}
```

### Pitfall #5: Over-Engineering

```kotlin
// ❌ TOO COMPLEX FOR iOS
scheduler.beginWith(TaskRequest("Download"))     // 10s
    .then(TaskRequest("Validate"))              // 5s
    .then(TaskRequest("Transform"))             // 8s
    .then(TaskRequest("Process"))               // 10s
    .then(TaskRequest("Upload"))                // 12s
    .enqueue()
// Total: 45s - HIGH RISK OF TIMEOUT

// ✅ SIMPLIFIED
scheduler.beginWith(TaskRequest("DownloadAndValidate"))  // 12s
    .then(TaskRequest("ProcessAndUpload"))                // 18s
    .enqueue()
// Total: 30s - WITHIN LIMIT
```

## Summary

### iOS Background Task Checklist

- [ ] Tasks complete within 25 seconds
- [ ] Critical operations use foreground mode
- [ ] Event persistence used for important state
- [ ] `requiresCharging` only relied upon if the host opts in to `UIDevice.batteryMonitoringEnabled`
- [ ] `DEVICE_IDLE` / `ALLOW_LOW_STORAGE` not used (no iOS equivalent — will silently never apply)
- [ ] Handles timeout gracefully
- [ ] Handles force-quit gracefully
- [ ] Minimal setup/initialization time
- [ ] Batch operations used where possible
- [ ] Network calls parallelized
- [ ] Tested timeout scenarios
- [ ] Tested force-quit scenarios
- [ ] Documentation mentions iOS limitations

### When to Use Background Tasks on iOS

**✅ GOOD Use Cases:**
- Refreshing content when app is in background
- Syncing small amounts of data
- Checking for updates
- Lightweight processing (< 20s)

**❌ BAD Use Cases:**
- Time-critical operations
- Operations that must complete
- Long-running processes (> 30s)
- Heavy processing
- Large file uploads/downloads

### When to Use Foreground Operations Instead

If your operation has any of these requirements, use foreground mode:
- Must complete reliably
- User-initiated action
- Longer than 25 seconds
- Critical for app functionality
- Sensitive data operations

## Additional Resources

- [Apple BGTaskScheduler Documentation](https://developer.apple.com/documentation/backgroundtasks)
- [iOS Migration Guide](ios-migration.md)
- [Platform Setup Guide](platform-setup.md)
- [API Reference](api-reference.md)
