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
| `BGAppRefreshTask` | ~30 seconds | Always |
| `BGProcessingTask` | ~60 seconds | Requires charging + WiFi |

```kotlin
// ❌ BAD: Long-running task
class ProcessDataWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        // This will timeout after 30 seconds!
        processLargeDataset() // Takes 2 minutes
        return true
    }
}

// ✅ GOOD: Break into chunks
class ProcessDataWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        val batch = getNextBatch()
        processBatch(batch) // Takes 5 seconds
        if (hasMoreBatches()) {
            scheduleNextBatch()
        }
        return true
    }
}
```

### 3. Force-Quit Termination

**When a user force-quits your app, ALL background tasks are immediately killed.**

This is **by iOS design** and cannot be worked around.

```kotlin
// ❌ BAD: Critical data operation
class SaveUserDataWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        // If user force-quits during this, data may be corrupted!
        database.beginTransaction()
        database.updateUserProfile(data)
        database.commit() // May never reach here
        return true
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

iOS **does not support** these Android constraints:

```kotlin
// ❌ THESE WILL BE IGNORED ON iOS:
Constraints(
    requiresCharging = true,        // iOS ignores this
    requiresBatteryNotLow = true,   // iOS ignores this
    requiresStorageNotLow = true,   // iOS ignores this
    systemConstraints = setOf(
        SystemConstraint.REQUIRE_BATTERY_NOT_LOW, // iOS ignores
        SystemConstraint.DEVICE_IDLE               // iOS ignores
    )
)

// ✅ ONLY THESE WORK ON iOS:
Constraints(
    requiresNetwork = true,           // Supported
    requiresUnmeteredNetwork = true   // Supported (WiFi only)
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
    override suspend fun doWork(input: String?): Boolean {
        syncUsers()      // 10s
        syncPosts()      // 15s
        syncComments()   // 20s
        syncImages()     // 30s
        // Total: 75s - WILL TIMEOUT!
        return true
    }
}

// ✅ GOOD: Modular tasks
class SyncUsersWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        syncUsers() // 10s - Safe
        return true
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
    override suspend fun doWork(input: String?): Boolean {
        val progress = loadProgress() ?: Progress(0)

        try {
            withTimeout(25_000) { // Leave 5s buffer
                while (!progress.isComplete()) {
                    val chunk = progress.nextChunk()
                    processChunk(chunk)
                    saveProgress(progress)
                }
            }
            return true
        } catch (e: TimeoutCancellationException) {
            // Save progress and reschedule
            saveProgress(progress)
            rescheduleTask()
            return false
        }
    }
}
```

### 4. Prioritize Critical Operations

```kotlin
// ✅ GOOD: Critical first, optional later
class SmartSyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        // Critical: User-generated content (5s)
        syncUserPosts()

        // Important: Recent data (10s)
        syncLastDayData()

        // Optional: Only if time permits (15s)
        withTimeoutOrNull(10_000) {
            syncHistoricalData()
        }

        return true
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
    override suspend fun doWork(input: String?): Boolean {
        processPayment() // If force-quit here, payment may be lost!
        return true
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
    override suspend fun doWork(input: String?): Boolean {
        val result = syncData()

        // Event persists even if app is force-quit
        TaskEventManager.emit(
            TaskCompletionEvent(
                taskId = "data-sync",
                success = result.success,
                message = "Synced ${result.count} items"
            )
        )

        return result.success
    }
}

// On next app launch, sync events are replayed
EventSyncManager.syncEvents(eventStore)
```

## Constraint Limitations

### Supported Constraints

```kotlin
// ✅ These work on iOS:
Constraints(
    requiresNetwork = true,
    requiresUnmeteredNetwork = true
)
```

### Unsupported Constraints

```kotlin
// ❌ These are IGNORED on iOS:
Constraints(
    requiresCharging = true,         // Ignored
    requiresBatteryNotLow = true,    // Ignored
    requiresStorageNotLow = true,    // Ignored
    systemConstraints = setOf(...)   // Ignored
)
```

### Platform-Specific Scheduling

```kotlin
expect fun shouldScheduleHeavyTask(): Boolean

// Android
actual fun shouldScheduleHeavyTask(): Boolean {
    return true // Can rely on charging constraint
}

// iOS
actual fun shouldScheduleHeavyTask(): Boolean {
    // Manually check conditions iOS can't enforce
    val batteryLevel = UIDevice.currentDevice.batteryLevel
    val isCharging = UIDevice.currentDevice.batteryState == .charging
    return batteryLevel > 0.5 || isCharging
}
```

## Performance Optimization

### 1. Minimize Setup Time

```kotlin
// ❌ BAD: Heavy initialization
class SlowWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        val database = createDatabase() // 5s
        val api = initializeAPI()       // 3s
        doActualWork()                  // 8s
        // Total: 16s wasted on setup
        return true
    }
}

// ✅ GOOD: Reuse shared instances
class FastWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        val database = SharedDatabase.instance  // <1ms
        val api = SharedAPI.instance            // <1ms
        doActualWork()                          // 8s
        // Total: ~8s actual work
        return true
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
    override suspend fun doWork(input: String?): Boolean {
        val startTime = System.currentTimeMillis()

        try {
            doLongRunningWork()

            val duration = System.currentTimeMillis() - startTime
            Logger.i("TimeoutTest", "Completed in ${duration}ms")
            return true
        } catch (e: TimeoutCancellationException) {
            val duration = System.currentTimeMillis() - startTime
            Logger.w("TimeoutTest", "Timeout after ${duration}ms")
            return false
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
    override suspend fun doWork(input: String?): Boolean {
        processVideo() // Takes 5 minutes
        return true
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

### Pitfall #3: Assuming Android Constraints Work

```kotlin
// ❌ DOESN'T WORK ON iOS
scheduler.enqueue(
    id = "heavy-task",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "HeavyWorker",
    constraints = Constraints(
        requiresCharging = true // iOS ignores this!
    )
)

// ✅ MANUAL CHECK INSTEAD
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
    override suspend fun doWork(input: String?): Boolean {
        uploadFile() // Lost if force-quit
        deleteLocalCopy() // May execute without upload
        return true
    }
}

// ✅ SAFE WITH EVENT PERSISTENCE
class UploadWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        val success = uploadFile()
        if (success) {
            TaskEventManager.emit(
                TaskCompletionEvent("upload", true, "Uploaded")
            )
            deleteLocalCopy()
        }
        return success
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
- [ ] Only network constraints used
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
