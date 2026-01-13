# Task Chains Guide

Master complex workflows with sequential and parallel task execution.

## Table of Contents

- [What Are Task Chains?](#what-are-task-chains)
- [Sequential Chains](#sequential-chains)
- [Parallel Execution](#parallel-execution)
- [Mixed Chains](#mixed-chains)
- [Real-World Examples](#real-world-examples)
- [Best Practices](#best-practices)
- [Platform Differences](#platform-differences)

---

## What Are Task Chains?

Task chains allow you to execute multiple background tasks in a specific order, with support for both sequential and parallel execution. This is perfect for complex workflows like:

- Download → Process → Upload pipelines
- Parallel data syncing from multiple sources
- Multi-step data transformations
- Batch operations with dependencies

### Benefits

- **Automatic dependency management** - Tasks run in the correct order
- **Error handling** - If one task fails, the chain stops
- **Parallel execution** - Run independent tasks simultaneously
- **Type-safe builder API** - Fluent, easy-to-read syntax
- **Cross-platform** - Works on both Android and iOS

---

## Sequential Chains

Execute tasks one after another, where each task waits for the previous one to complete.

### Basic Sequential Chain

```kotlin
scheduler
    .beginWith(TaskRequest(workerClassName = "DownloadWorker"))
    .then(TaskRequest(workerClassName = "ProcessWorker"))
    .then(TaskRequest(workerClassName = "UploadWorker"))
    .enqueue()
```

**Execution Order:**
```
DownloadWorker → ProcessWorker → UploadWorker
```

---

### Sequential Chain with Input Data

Pass data between tasks using the input parameter:

```kotlin
scheduler
    .beginWith(
        TaskRequest(
            workerClassName = "FetchUserWorker",
            input = "user_id_123"
        )
    )
    .then(
        TaskRequest(
            workerClassName = "FetchPostsWorker",
            input = "user_id_123"
        )
    )
    .then(
        TaskRequest(
            workerClassName = "CacheDataWorker"
        )
    )
    .enqueue()
```

---

### Sequential Chain with Constraints

Apply constraints to specific tasks in the chain:

```kotlin
scheduler
    .beginWith(
        TaskRequest(
            workerClassName = "DownloadWorker",
            constraints = Constraints(
                requiresNetwork = true,
                networkType = NetworkType.UNMETERED // WiFi only
            )
        )
    )
    .then(
        TaskRequest(
            workerClassName = "ProcessWorker",
            constraints = Constraints(
                requiresCharging = true,
                requiresBatteryNotLow = true
            )
        )
    )
    .then(
        TaskRequest(
            workerClassName = "UploadWorker",
            constraints = Constraints(
                requiresNetwork = true
            )
        )
    )
    .enqueue()
```

---

## Parallel Execution

Execute multiple independent tasks simultaneously, then continue with the next step.

### Basic Parallel Execution

```kotlin
scheduler
    .beginWith(listOf(
        TaskRequest(workerClassName = "SyncContactsWorker"),
        TaskRequest(workerClassName = "SyncCalendarWorker"),
        TaskRequest(workerClassName = "SyncPhotosWorker")
    ))
    .then(TaskRequest(workerClassName = "FinalizeWorker"))
    .enqueue()
```

**Execution Order:**
```
┌─ SyncContactsWorker ─┐
├─ SyncCalendarWorker ─┤ → FinalizeWorker
└─ SyncPhotosWorker ───┘
   (run in parallel)
```

**Note**: `FinalizeWorker` only starts after ALL parallel tasks complete successfully.

---

### Parallel Tasks with Different Constraints

Each task in a parallel group can have its own constraints:

```kotlin
scheduler
    .beginWith(listOf(
        TaskRequest(
            workerClassName = "DownloadImagesWorker",
            constraints = Constraints(
                requiresNetwork = true,
                networkType = NetworkType.UNMETERED
            )
        ),
        TaskRequest(
            workerClassName = "DownloadVideosWorker",
            constraints = Constraints(
                requiresNetwork = true,
                networkType = NetworkType.UNMETERED,
                requiresCharging = true // Videos need charging
            )
        ),
        TaskRequest(
            workerClassName = "DownloadDocumentsWorker",
            constraints = Constraints(
                requiresNetwork = true
            )
        )
    ))
    .then(TaskRequest(workerClassName = "IndexFilesWorker"))
    .enqueue()
```

---

## Mixed Chains

Combine sequential and parallel execution in complex workflows.

### Example 1: Parallel Start, Sequential End

```kotlin
scheduler
    .beginWith(listOf(
        TaskRequest(workerClassName = "FetchNewsWorker"),
        TaskRequest(workerClassName = "FetchWeatherWorker"),
        TaskRequest(workerClassName = "FetchStocksWorker")
    ))
    .then(TaskRequest(workerClassName = "MergeDataWorker"))
    .then(TaskRequest(workerClassName = "UpdateCacheWorker"))
    .then(TaskRequest(workerClassName = "NotifyUserWorker"))
    .enqueue()
```

**Execution Flow:**
```
┌─ FetchNewsWorker ────┐
├─ FetchWeatherWorker ─┤ → MergeDataWorker → UpdateCacheWorker → NotifyUserWorker
└─ FetchStocksWorker ──┘
```

---

### Example 2: Sequential, Then Parallel, Then Sequential

```kotlin
scheduler
    .beginWith(TaskRequest(workerClassName = "PrepareDataWorker"))
    .then(listOf(
        TaskRequest(workerClassName = "ProcessImagesWorker"),
        TaskRequest(workerClassName = "ProcessVideosWorker"),
        TaskRequest(workerClassName = "ProcessAudioWorker")
    ))
    .then(TaskRequest(workerClassName = "CompressWorker"))
    .then(TaskRequest(workerClassName = "UploadWorker"))
    .enqueue()
```

**Execution Flow:**
```
PrepareDataWorker
    ↓
┌─ ProcessImagesWorker ─┐
├─ ProcessVideosWorker ─┤ → CompressWorker → UploadWorker
└─ ProcessAudioWorker ──┘
```

---

### Example 3: Multiple Parallel Stages

```kotlin
scheduler
    .beginWith(listOf(
        TaskRequest(workerClassName = "DownloadImages1"),
        TaskRequest(workerClassName = "DownloadImages2")
    ))
    .then(listOf(
        TaskRequest(workerClassName = "ProcessImages1"),
        TaskRequest(workerClassName = "ProcessImages2")
    ))
    .then(TaskRequest(workerClassName = "MergeImagesWorker"))
    .then(listOf(
        TaskRequest(workerClassName = "UploadToServer1"),
        TaskRequest(workerClassName = "UploadToServer2"),
        TaskRequest(workerClassName = "UploadToBackup")
    ))
    .then(TaskRequest(workerClassName = "CleanupWorker"))
    .enqueue()
```

**Execution Flow:**
```
┌─ DownloadImages1 ─┐    ┌─ ProcessImages1 ─┐
└─ DownloadImages2 ─┘ → └─ ProcessImages2 ─┘ → MergeImagesWorker
                                                      ↓
                                           ┌─ UploadToServer1 ─┐
                                           ├─ UploadToServer2 ─┤ → CleanupWorker
                                           └─ UploadToBackup ──┘
```

---

## Real-World Examples

### 1. ML Model Update Pipeline

Download model → Train on device → Validate → Upload results

```kotlin
suspend fun updateMLModel() {
    scheduler
        .beginWith(
            TaskRequest(
                workerClassName = "DownloadMLModelWorker",
                constraints = Constraints(
                    requiresNetwork = true,
                    networkType = NetworkType.UNMETERED,
                    requiresCharging = true
                )
            )
        )
        .then(
            TaskRequest(
                workerClassName = "TrainMLModelWorker",
                constraints = Constraints(
                    isHeavyTask = true, // Long-running task
                    requiresCharging = true,
                    requiresBatteryNotLow = true
                )
            )
        )
        .then(
            TaskRequest(
                workerClassName = "ValidateModelWorker",
                constraints = Constraints(
                    requiresCharging = true
                )
            )
        )
        .then(
            TaskRequest(
                workerClassName = "UploadResultsWorker",
                constraints = Constraints(
                    requiresNetwork = true
                )
            )
        )
        .enqueue()
}
```

---

### 2. Multi-Source Data Sync

Sync from multiple APIs in parallel, then merge and cache:

```kotlin
suspend fun syncAllData() {
    scheduler
        .beginWith(listOf(
            TaskRequest(
                id = "sync-users",
                workerClassName = "SyncUsersWorker",
                constraints = Constraints(requiresNetwork = true)
            ),
            TaskRequest(
                id = "sync-posts",
                workerClassName = "SyncPostsWorker",
                constraints = Constraints(requiresNetwork = true)
            ),
            TaskRequest(
                id = "sync-comments",
                workerClassName = "SyncCommentsWorker",
                constraints = Constraints(requiresNetwork = true)
            ),
            TaskRequest(
                id = "sync-media",
                workerClassName = "SyncMediaWorker",
                constraints = Constraints(
                    requiresNetwork = true,
                    networkType = NetworkType.UNMETERED
                )
            )
        ))
        .then(
            TaskRequest(
                workerClassName = "MergeDataWorker"
            )
        )
        .then(
            TaskRequest(
                workerClassName = "UpdateDatabaseWorker"
            )
        )
        .then(
            TaskRequest(
                workerClassName = "RefreshUIWorker"
            )
        )
        .enqueue()
}
```

---

### 3. Video Processing Pipeline

Download → Extract frames → Process frames in parallel → Merge → Upload

```kotlin
suspend fun processVideo(videoId: String) {
    scheduler
        .beginWith(
            TaskRequest(
                workerClassName = "DownloadVideoWorker",
                input = videoId,
                constraints = Constraints(
                    requiresNetwork = true,
                    networkType = NetworkType.UNMETERED
                )
            )
        )
        .then(
            TaskRequest(
                workerClassName = "ExtractFramesWorker",
                input = videoId
            )
        )
        .then(listOf(
            TaskRequest(
                workerClassName = "ProcessFrames1Worker",
                input = videoId
            ),
            TaskRequest(
                workerClassName = "ProcessFrames2Worker",
                input = videoId
            ),
            TaskRequest(
                workerClassName = "ProcessFrames3Worker",
                input = videoId
            )
        ))
        .then(
            TaskRequest(
                workerClassName = "MergeFramesWorker",
                input = videoId
            )
        )
        .then(
            TaskRequest(
                workerClassName = "EncodeVideoWorker",
                input = videoId,
                constraints = Constraints(
                    isHeavyTask = true,
                    requiresCharging = true
                )
            )
        )
        .then(
            TaskRequest(
                workerClassName = "UploadVideoWorker",
                input = videoId,
                constraints = Constraints(
                    requiresNetwork = true,
                    networkType = NetworkType.UNMETERED
                )
            )
        )
        .enqueue()
}
```

---

### 4. Database Migration

Backup → Migrate schema → Migrate data in parallel → Verify → Cleanup

```kotlin
suspend fun migrateDatabase() {
    scheduler
        .beginWith(
            TaskRequest(
                workerClassName = "BackupDatabaseWorker",
                constraints = Constraints(
                    requiresStorageNotLow = true
                )
            )
        )
        .then(
            TaskRequest(
                workerClassName = "MigrateSchemaWorker"
            )
        )
        .then(listOf(
            TaskRequest(workerClassName = "MigrateUsersTableWorker"),
            TaskRequest(workerClassName = "MigratePostsTableWorker"),
            TaskRequest(workerClassName = "MigrateCommentsTableWorker")
        ))
        .then(
            TaskRequest(
                workerClassName = "VerifyMigrationWorker"
            )
        )
        .then(
            TaskRequest(
                workerClassName = "CleanupOldDataWorker"
            )
        )
        .enqueue()
}
```

---

## Best Practices

### 1. Keep Tasks Focused

Each worker should do ONE thing well:

**Good:**
```kotlin
scheduler
    .beginWith(TaskRequest(workerClassName = "DownloadWorker"))
    .then(TaskRequest(workerClassName = "ValidateWorker"))
    .then(TaskRequest(workerClassName = "ProcessWorker"))
    .enqueue()
```

**Bad:**
```kotlin
// Don't create a mega-worker that does everything
scheduler
    .beginWith(TaskRequest(workerClassName = "DownloadValidateProcessWorker"))
    .enqueue()
```

---

### 2. Handle Errors Gracefully

Workers should return failure when they can't complete:

```kotlin
class DownloadWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        return try {
            downloadFile(input)
            true // Success
        } catch (e: NetworkException) {
            Logger.e(LogTags.WORKER, "Download failed", e)
            false // Failure - chain will stop
        }
    }
}
```

On Android:
```kotlin
private suspend fun executeDownloadWorker(input: String?): Result {
    return try {
        downloadFile(input)
        Result.success()
    } catch (e: Exception) {
        Logger.e(LogTags.WORKER, "Download failed", e)
        Result.retry() // WorkManager will retry with backoff
    }
}
```

---

### 3. Use Constraints Wisely

Apply constraints only where needed:

```kotlin
scheduler
    .beginWith(
        TaskRequest(
            workerClassName = "DownloadWorker",
            constraints = Constraints(
                requiresNetwork = true // Only download needs network
            )
        )
    )
    .then(
        TaskRequest(
            workerClassName = "ProcessWorker"
            // No constraints - can run offline
        )
    )
    .then(
        TaskRequest(
            workerClassName = "UploadWorker",
            constraints = Constraints(
                requiresNetwork = true // Only upload needs network
            )
        )
    )
    .enqueue()
```

---

### 4. Optimize Parallel Execution

Group truly independent tasks in parallel:

**Good:**
```kotlin
// These tasks don't depend on each other
scheduler.beginWith(listOf(
    TaskRequest(workerClassName = "SyncContactsWorker"),
    TaskRequest(workerClassName = "SyncCalendarWorker"),
    TaskRequest(workerClassName = "SyncPhotosWorker")
))
```

**Bad:**
```kotlin
// ProcessWorker depends on DownloadWorker - don't parallelize!
scheduler.beginWith(listOf(
    TaskRequest(workerClassName = "DownloadWorker"),
    TaskRequest(workerClassName = "ProcessWorker") // Will fail!
))
```

---

### 5. Provide Meaningful IDs

Use descriptive task IDs for debugging:

```kotlin
scheduler
    .beginWith(
        TaskRequest(
            id = "ml-pipeline-download",
            workerClassName = "DownloadMLModelWorker"
        )
    )
    .then(
        TaskRequest(
            id = "ml-pipeline-train",
            workerClassName = "TrainMLModelWorker"
        )
    )
    .enqueue()
```

---

### 6. Emit Events for Monitoring

Track progress by emitting events from each worker:

```kotlin
class DownloadWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        return try {
            downloadFile(input)

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "DownloadWorker",
                    success = true,
                    message = "✅ Download complete (Step 1/3)"
                )
            )

            true
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "DownloadWorker",
                    success = false,
                    message = "❌ Download failed: ${e.message}"
                )
            )

            false
        }
    }
}
```

Then collect in UI:

```kotlin
@Composable
fun PipelineMonitor() {
    var progress by remember { mutableStateOf("Idle") }

    LaunchedEffect(Unit) {
        TaskEventBus.events.collect { event ->
            progress = event.message
        }
    }

    Text("Pipeline: $progress")
}
```

---

## Platform Differences

### Android

On Android, task chains use WorkManager's continuation API:

```kotlin
// Internally translates to:
WorkManager.getInstance(context)
    .beginWith(downloadWork)
    .then(processWork)
    .then(uploadWork)
    .enqueue()
```

**Features:**
- Full support for sequential and parallel chains
- Automatic retry with backoff
- Persists across device reboots
- Respects Doze mode restrictions

**Limitations:**
- Minimum 15-minute interval for periodic chains
- Tasks may be delayed by system battery optimizations

---

### iOS

On iOS, task chains use a custom queue system with coroutines:

```kotlin
// Internally uses ChainExecutor
val chain = TaskChain(...)
chainQueue.add(chain)
scheduleChainExecutorTask()
```

**Features:**
- Custom implementation with coroutines
- Supports sequential and parallel execution
- Batch execution for efficiency (up to 3 tasks at once)
- Automatic timeout handling

**Limitations:**
- BGAppRefreshTask: 25-second timeout per task
- BGProcessingTask: Several minutes (for `isHeavyTask = true`)
- Tasks only run when app is in background
- iOS decides when to execute (opportunistic)

**Best Practices for iOS:**

1. **Keep chains short** (max 3-5 tasks)
2. **Use heavy task mode for long chains**:
   ```kotlin
   scheduler
       .beginWith(TaskRequest(
           workerClassName = "Step1",
           constraints = Constraints(isHeavyTask = true)
       ))
       .then(TaskRequest(workerClassName = "Step2"))
       .enqueue()
   ```
3. **Break long chains into multiple periodic tasks**
4. **Test on physical devices** (simulator behavior differs)

---

## API Reference

### TaskChain

```kotlin
interface TaskChain {
    fun then(request: TaskRequest): TaskChain
    fun then(requests: List<TaskRequest>): TaskChain
    suspend fun enqueue(): ScheduleResult
}
```

### TaskRequest

```kotlin
data class TaskRequest(
    val id: String = UUID.randomUUID().toString(),
    val workerClassName: String,
    val input: String? = null,
    val constraints: Constraints = Constraints()
)
```

### BackgroundTaskScheduler

```kotlin
interface BackgroundTaskScheduler {
    fun beginWith(request: TaskRequest): TaskChain
    fun beginWith(requests: List<TaskRequest>): TaskChain
}
```

---

## Next Steps

- [API Reference](api-reference.md) - Complete API documentation
- [Constraints & Triggers](constraints-triggers.md) - All constraint options
- [Platform Setup](platform-setup.md) - Platform-specific configuration
- [Quick Start](quickstart.md) - Get started in 5 minutes

---

Need help? [Open an issue](https://github.com/brewkits/kmp_worker/issues) or ask in [Discussions](https://github.com/brewkits/kmp_worker/discussions).
