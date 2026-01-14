# iOS Migration Guide

This guide helps developers migrate from Android-first thinking to iOS-compatible background task design.

## Table of Contents

- [Key Mindset Shifts](#key-mindset-shifts)
- [Android vs iOS Comparison](#android-vs-ios-comparison)
- [Migration Patterns](#migration-patterns)
- [Common Migration Scenarios](#common-migration-scenarios)
- [Step-by-Step Migration](#step-by-step-migration)

## Key Mindset Shifts

### 1. From Guaranteed to Opportunistic

**Android Mindset:**
> "My periodic task will run every 15 minutes"

**iOS Reality:**
> "My task will run when the system decides it's appropriate"

### 2. From Long-Running to Quick Tasks

**Android Mindset:**
> "I can process data for 10 minutes in the background"

**iOS Reality:**
> "I have 30 seconds maximum, better make it count"

### 3. From Rich Constraints to Basic Constraints

**Android Mindset:**
> "I'll wait until the device is charging and has WiFi"

**iOS Reality:**
> "I can only check for network connectivity, must verify other conditions manually"

### 4. From Persistent to Fragile

**Android Mindset:**
> "My task will complete even if the user closes the app"

**iOS Reality:**
> "If the user force-quits, my task dies immediately"

## Android vs iOS Comparison

### Execution Model

| Aspect | Android | iOS |
|--------|---------|-----|
| **Timing** | Predictable (within flex window) | Unpredictable (system decides) |
| **Duration** | Minutes (with foreground service) | 30-60 seconds max |
| **Force-Quit** | Tasks continue running | Tasks killed immediately |
| **Frequency** | Reliable periodic execution | Best-effort periodic execution |

### Constraints Support

| Constraint | Android | iOS |
|-----------|---------|-----|
| Network Required | ✅ Supported | ✅ Supported |
| WiFi Only | ✅ Supported | ✅ Supported |
| Charging | ✅ Supported | ❌ Not available |
| Battery Not Low | ✅ Supported | ❌ Not available |
| Storage Not Low | ✅ Supported | ❌ Not available |
| Device Idle | ✅ Supported | ❌ Not available |

### Trigger Types

| Trigger | Android | iOS |
|---------|---------|-----|
| Periodic | ✅ Guaranteed (±flex) | ⚠️ Best-effort |
| OneTime | ✅ Guaranteed | ⚠️ Best-effort |
| OneTime with delay | ✅ Honored | ⚠️ Approximate |
| Exact | ✅ AlarmManager | ❌ Not available |
| ContentUri | ✅ Supported | ❌ Not available |

## Migration Patterns

### Pattern 1: Long Task → Chunked Task

**Android (Before):**
```kotlin
class ProcessDataWorker : AndroidWorker {
    override suspend fun doWork(input: String?): Boolean {
        val allData = loadAllData() // 1000 items
        processData(allData) // 2 minutes
        return true
    }
}
```

**iOS (After):**
```kotlin
class ProcessDataWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        val batch = loadNextBatch(size = 50) // 50 items
        processBatch(batch) // 10 seconds

        if (hasMoreBatches()) {
            scheduleNextBatch()
        }
        return true
    }
}
```

### Pattern 2: Constraint-Based → Manual Checking

**Android (Before):**
```kotlin
scheduler.enqueue(
    id = "heavy-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 3600_000),
    workerClassName = "HeavySyncWorker",
    constraints = Constraints(
        requiresCharging = true,
        requiresNetwork = true,
        requiresBatteryNotLow = true
    )
)
```

**iOS (After):**
```kotlin
class HeavySyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        // Manually check conditions iOS can't enforce
        if (!isDeviceReady()) {
            Logger.i("HeavySync", "Device not ready, skipping")
            return true // Don't retry
        }

        performSync()
        return true
    }

    private fun isDeviceReady(): Boolean {
        val batteryLevel = UIDevice.currentDevice.batteryLevel
        val isCharging = UIDevice.currentDevice.batteryState == .charging
        return (batteryLevel > 0.5 || isCharging)
    }
}

// Schedule without constraints (iOS ignores them anyway)
scheduler.enqueue(
    id = "heavy-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 3600_000),
    workerClassName = "HeavySyncWorker",
    constraints = Constraints(requiresNetwork = true) // Only this works
)
```

### Pattern 3: Sequential Chain → Parallel Scheduling

**Android (Before):**
```kotlin
// Android: Reliable chain execution
scheduler.beginWith(TaskRequest("DownloadWorker"))   // 30s
    .then(TaskRequest("ProcessWorker"))              // 45s
    .then(TaskRequest("UploadWorker"))               // 30s
    .enqueue()
// Total: 105s - OK with foreground service
```

**iOS (After):**
```kotlin
// iOS: Simplified and parallelized
scheduler.enqueue(
    id = "download",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "QuickDownloadWorker" // 15s
)

scheduler.enqueue(
    id = "process",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "QuickProcessWorker" // 15s
)

scheduler.enqueue(
    id = "upload",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "QuickUploadWorker" // 15s
)
// Each task: <20s - Safe for iOS
```

### Pattern 4: Critical Task → Foreground Operation

**Android (Before):**
```kotlin
// Background task is fine for critical operations
class PaymentWorker : AndroidWorker {
    override suspend fun doWork(input: String?): Boolean {
        processPayment()
        return true
    }
}
```

**iOS (After):**
```kotlin
// Use foreground operation for critical tasks
fun initiatePayment(amount: Double) {
    showProgressDialog("Processing payment...")

    CoroutineScope(Dispatchers.Main).launch {
        try {
            val result = withContext(Dispatchers.IO) {
                processPayment(amount)
            }

            if (result.success) {
                // Schedule cleanup in background
                scheduler.enqueue(
                    id = "payment-cleanup",
                    trigger = TaskTrigger.OneTime(),
                    workerClassName = "PaymentCleanupWorker"
                )
            }

            hideProgressDialog()
            showResult(result)
        } catch (e: Exception) {
            hideProgressDialog()
            showError(e)
        }
    }
}
```

### Pattern 5: Fire-and-Forget → Event-Driven

**Android (Before):**
```kotlin
// Just schedule and trust it will complete
scheduler.enqueue(
    id = "sync",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "SyncWorker"
)
```

**iOS (After):**
```kotlin
// Schedule with event persistence
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        val result = performSync()

        // Persist event - survives force-quit
        TaskEventManager.emit(
            TaskCompletionEvent(
                taskId = "sync",
                success = result.success,
                message = result.summary
            )
        )

        return result.success
    }
}

// In app launch
fun onAppLaunch() {
    // Replay missed events
    CoroutineScope(Dispatchers.Main).launch {
        val eventStore = get<EventStore>()
        EventSyncManager.syncEvents(eventStore)
    }
}
```

## Common Migration Scenarios

### Scenario 1: Periodic Data Sync

**Android Implementation:**
```kotlin
class DataSyncWorker : AndroidWorker {
    override suspend fun doWork(input: String?): Boolean {
        // Download all data
        val users = api.getUsers()          // 2s
        val posts = api.getPosts()          // 3s
        val comments = api.getComments()    // 2s
        val media = api.getMedia()          // 5s

        // Save to database
        database.saveUsers(users)           // 1s
        database.savePosts(posts)           // 1s
        database.saveComments(comments)     // 1s
        database.saveMedia(media)           // 2s

        // Total: 17s - Fine on Android
        return true
    }
}

scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "DataSyncWorker",
    constraints = Constraints(
        requiresNetwork = true,
        requiresBatteryNotLow = true
    )
)
```

**iOS Migration:**
```kotlin
class DataSyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        withTimeout(25_000) { // Leave 5s buffer
            // Parallel network calls
            coroutineScope {
                val usersDeferred = async { api.getUsers() }
                val postsDeferred = async { api.getPosts() }
                val commentsDeferred = async { api.getComments() }
                // Skip media - too slow

                val users = usersDeferred.await()
                val posts = postsDeferred.await()
                val comments = commentsDeferred.await()

                // Batch database operations
                database.saveBatch(users, posts, comments)
            }
        }
        // Total: ~5s - Safe for iOS

        return true
    }
}

scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "DataSyncWorker",
    constraints = Constraints(requiresNetwork = true)
    // Battery constraint removed - iOS doesn't support it
)

// Schedule separate task for media
scheduler.enqueue(
    id = "media-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 3600_000),
    workerClassName = "MediaSyncWorker",
    constraints = Constraints(requiresUnmeteredNetwork = true)
)
```

### Scenario 2: File Upload with Retry

**Android Implementation:**
```kotlin
class UploadWorker : AndroidWorker {
    override suspend fun doWork(input: String?): Boolean {
        val files = getPendingFiles()

        for (file in files) {
            try {
                uploadFile(file) // 10s each
                markAsUploaded(file)
            } catch (e: Exception) {
                // Android WorkManager handles retry
                return false
            }
        }

        return true
    }
}

scheduler.enqueue(
    id = "upload",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "UploadWorker",
    constraints = Constraints(requiresUnmeteredNetwork = true)
)
```

**iOS Migration:**
```kotlin
class UploadWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        val files = getPendingFiles()

        // Upload only ONE file per execution
        val file = files.firstOrNull() ?: return true

        return try {
            withTimeout(25_000) {
                uploadFile(file)
                markAsUploaded(file)

                // Emit event for persistence
                TaskEventManager.emit(
                    TaskCompletionEvent(
                        taskId = "upload-${file.id}",
                        success = true,
                        message = "Uploaded ${file.name}"
                    )
                )

                // Schedule next file if any
                if (files.size > 1) {
                    scheduler.enqueue(
                        id = "upload-next",
                        trigger = TaskTrigger.OneTime(initialDelayMs = 60_000),
                        workerClassName = "UploadWorker",
                        constraints = Constraints(requiresUnmeteredNetwork = true)
                    )
                }
            }
            true
        } catch (e: Exception) {
            Logger.e("Upload", "Failed to upload ${file.name}", e)
            false // iOS scheduler will retry
        }
    }
}
```

### Scenario 3: Database Cleanup

**Android Implementation:**
```kotlin
class CleanupWorker : AndroidWorker {
    override suspend fun doWork(input: String?): Boolean {
        // Delete old records
        database.deleteOldUsers()       // 30s
        database.deleteOldPosts()       // 45s
        database.deleteOldComments()    // 30s
        database.vacuum()               // 60s

        // Total: 165s - Fine with foreground service
        return true
    }
}

scheduler.enqueue(
    id = "cleanup",
    trigger = TaskTrigger.Periodic(intervalMs = 86400_000), // Daily
    workerClassName = "CleanupWorker",
    constraints = Constraints(
        requiresCharging = true,
        requiresDeviceIdle = true
    )
)
```

**iOS Migration:**
```kotlin
class QuickCleanupWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        withTimeout(25_000) {
            // Only delete most critical old data
            database.deleteOldestRecords(limit = 1000) // 5s

            // Skip vacuum - too slow
            // User can trigger manual cleanup in app settings
        }

        return true
    }
}

scheduler.enqueue(
    id = "cleanup",
    trigger = TaskTrigger.Periodic(intervalMs = 86400_000),
    workerClassName = "QuickCleanupWorker"
    // No constraints - iOS can't enforce charging/idle
)

// Offer manual cleanup in app UI
fun performFullCleanup() {
    showProgressDialog("Cleaning up...")

    CoroutineScope(Dispatchers.Main).launch {
        withContext(Dispatchers.IO) {
            database.deleteOldUsers()
            database.deleteOldPosts()
            database.deleteOldComments()
            database.vacuum()
        }

        hideProgressDialog()
        showToast("Cleanup complete")
    }
}
```

## Step-by-Step Migration

### Step 1: Audit Current Tasks

List all background tasks and their characteristics:

```kotlin
// Example audit
Task: DataSync
- Duration: 45s
- Frequency: Every 15 min
- Constraints: Network, Battery Not Low
- Critical: No
- iOS Compatible: ❌ Too long

Task: PushNotificationToken
- Duration: 2s
- Frequency: On app launch
- Constraints: Network
- Critical: Yes
- iOS Compatible: ✅ Short and simple

Task: FileUpload
- Duration: 120s
- Frequency: One-time
- Constraints: WiFi, Charging
- Critical: Yes
- iOS Compatible: ❌ Too long, charging constraint
```

### Step 2: Categorize Tasks

**Category A: iOS Compatible (Minor changes)**
- Short duration (< 20s)
- No unsupported constraints
- Not time-critical

**Category B: Needs Refactoring**
- Long duration (20-60s)
- Uses unsupported constraints
- Can be chunked

**Category C: Foreground Only**
- Very long duration (> 60s)
- Critical operations
- User-initiated

### Step 3: Refactor Category B Tasks

Apply migration patterns:

1. **Break into chunks**
2. **Parallelize operations**
3. **Remove unsupported constraints**
4. **Add manual checks**
5. **Add event persistence**
6. **Add timeout handling**

### Step 4: Move Category C to Foreground

```kotlin
// Before: Background task
class HeavyProcessWorker : Worker

// After: Foreground operation
fun processHeavyData() {
    showProgressUI()
    processDataSynchronously()
    hideProgressUI()
}
```

### Step 5: Add Event Persistence

For important tasks, add event persistence:

```kotlin
class ImportantWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        val result = doImportantWork()

        TaskEventManager.emit(
            TaskCompletionEvent(
                taskId = "important-work",
                success = result.success,
                message = result.message
            )
        )

        return result.success
    }
}
```

### Step 6: Update Documentation

Document iOS-specific behavior:

```kotlin
/**
 * Syncs data from server.
 *
 * **iOS Note**: Due to 30s time limit, only syncs essential data.
 * Media files are synced separately via [MediaSyncWorker].
 *
 * **Force-Quit**: Uses [TaskEventManager] to persist completion state.
 */
class DataSyncWorker : IosWorker
```

### Step 7: Test iOS-Specific Scenarios

1. **Timeout testing**: Ensure tasks complete within 25s
2. **Force-quit testing**: Verify event persistence
3. **Constraint testing**: Verify manual checks work
4. **Background launch**: Test opportunistic execution

## Migration Checklist

### Pre-Migration
- [ ] Audit all background tasks
- [ ] Measure task durations
- [ ] Identify unsupported constraints
- [ ] Categorize tasks (A, B, C)

### During Migration
- [ ] Refactor long-running tasks
- [ ] Remove unsupported constraints
- [ ] Add manual condition checks
- [ ] Add event persistence
- [ ] Add timeout handling
- [ ] Move critical tasks to foreground

### Post-Migration
- [ ] Test on iOS simulator
- [ ] Test force-quit scenarios
- [ ] Test timeout scenarios
- [ ] Update documentation
- [ ] Educate team on iOS limitations

### Validation
- [ ] All tasks complete within 25s
- [ ] No unsupported constraints used
- [ ] Event persistence for important tasks
- [ ] Timeout handling implemented
- [ ] Critical operations in foreground
- [ ] Documentation updated

## Additional Resources

- [iOS Best Practices](ios-best-practices.md)
- [Platform Setup Guide](platform-setup.md)
- [API Reference](api-reference.md)
- [Apple BGTaskScheduler Documentation](https://developer.apple.com/documentation/backgroundtasks)
