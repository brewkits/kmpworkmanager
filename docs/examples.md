# 💡 KMP TaskManager Examples

Comprehensive examples for common use cases with v3.0.0 API.

[📘 Back to README](../README.md) • [📦 Migration Guide](migration-v3.md)

---

## Table of Contents

1. [Periodic Tasks](#periodic-tasks)
2. [One-Time Tasks](#one-time-tasks)
3. [Exact Alarms (Android)](#exact-alarms-android)
4. [Heavy Tasks (Long-Running)](#heavy-tasks-long-running)
5. [Battery-Aware Tasks](#battery-aware-tasks)
6. [Network-Constrained Tasks](#network-constrained-tasks)
7. [Task Chains](#task-chains)
8. [Content Uri Triggers (Android)](#content-uri-triggers-android)
9. [Task Cancellation](#task-cancellation)
10. [Real-time Events](#real-time-events)

---

## Periodic Tasks

### Weather Sync Every 15 Minutes

```kotlin
scheduler.enqueue(
    id = "weather-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 15.minutes.inWholeMilliseconds),
    workerClassName = "WeatherSyncWorker",
    constraints = Constraints(requiresNetwork = true)
)
```

### Stock Price Updates

```kotlin
scheduler.enqueue(
    id = "stock-updates",
    trigger = TaskTrigger.Periodic(intervalMs = 1.hours.inWholeMilliseconds),
    workerClassName = "StockPriceWorker",
    constraints = Constraints(
        requiresNetwork = true,
        requiresUnmeteredNetwork = false // Allow cellular
    )
)
```

### Background Database Cleanup

```kotlin
scheduler.enqueue(
    id = "db-cleanup",
    trigger = TaskTrigger.Periodic(intervalMs = 24.hours.inWholeMilliseconds),
    workerClassName = "DatabaseCleanupWorker",
    constraints = Constraints(
        systemConstraints = setOf(
            SystemConstraint.DEVICE_IDLE, // Run when device idle
            SystemConstraint.REQUIRE_BATTERY_NOT_LOW
        )
    )
)
```

---

## One-Time Tasks

### Delayed Notification

```kotlin
scheduler.enqueue(
    id = "welcome-notification",
    trigger = TaskTrigger.OneTime(initialDelayMs = 5.minutes.inWholeMilliseconds),
    workerClassName = "WelcomeNotificationWorker"
)
```

### Photo Upload with Retry

```kotlin
scheduler.enqueue(
    id = "photo-upload-${photoId}",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "PhotoUploadWorker",
    constraints = Constraints(
        requiresNetwork = true,
        requiresUnmeteredNetwork = true, // WiFi only
        backoffPolicy = BackoffPolicy.EXPONENTIAL,
        backoffDelayMs = 10_000 // 10s → 20s → 40s → 80s
    ),
    inputJson = """{"photoId": "$photoId"}"""
)
```

### App Initialization After Update

```kotlin
scheduler.enqueue(
    id = "post-update-init",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "PostUpdateInitWorker",
    policy = ExistingPolicy.REPLACE // Replace old tasks
)
```

---

## Exact Alarms (Android)

**v3.0+**: Exact alarms now have automatic fallback to WorkManager when permission is denied.

### 1. Create Your AlarmReceiver

```kotlin
// In your app module (not library)
class MyAlarmReceiver : AlarmReceiver() {
    override fun handleAlarm(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?
    ) {
        // Execute work using your DI framework
        val workerFactory = KoinContext.get().get<WorkerFactory>()
        val worker = workerFactory.createWorker(workerClassName)

        CoroutineScope(Dispatchers.IO).launch {
            worker?.doWork(inputJson)
        }
    }
}
```

### 2. Register in AndroidManifest.xml

```xml
<manifest>
    <!-- Permissions -->
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application>
        <!-- Alarm Receiver -->
        <receiver
            android:name=".MyAlarmReceiver"
            android:enabled="true"
            android:exported="false" />
    </application>
</manifest>
```

### 3. Extend NativeTaskScheduler

```kotlin
class MyTaskScheduler(context: Context) : NativeTaskScheduler(context) {
    override fun getAlarmReceiverClass(): Class<out AlarmReceiver> {
        return MyAlarmReceiver::class.java
    }
}

// In Koin module
single<BackgroundTaskScheduler> { MyTaskScheduler(androidContext()) }
```

### 4. Schedule Exact Alarms

```kotlin
// Reminder in 1 hour
scheduler.enqueue(
    id = "reminder-${reminderId}",
    trigger = TaskTrigger.Exact(
        atEpochMillis = System.currentTimeMillis() + 1.hours.inWholeMilliseconds
    ),
    workerClassName = "ReminderWorker",
    inputJson = """{"reminderId": "$reminderId"}"""
)

// Daily alarm at 8:00 AM
val calendar = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 8)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    if (timeInMillis <= System.currentTimeMillis()) {
        add(Calendar.DAY_OF_MONTH, 1) // Next day if already passed
    }
}

scheduler.enqueue(
    id = "daily-alarm",
    trigger = TaskTrigger.Exact(atEpochMillis = calendar.timeInMillis),
    workerClassName = "DailyAlarmWorker"
)
```

**Auto-Fallback**: If permission is denied, automatically falls back to WorkManager OneTime task with appropriate delay.

---

## Heavy Tasks (Long-Running)

**v3.0+**: Use `isHeavyTask = true` for tasks >10 minutes or CPU-intensive work.

### Video Processing

```kotlin
scheduler.enqueue(
    id = "video-process-${videoId}",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "VideoProcessingWorker",
    constraints = Constraints(
        isHeavyTask = true, // Uses KmpHeavyWorker (foreground service)
        requiresCharging = true,
        systemConstraints = setOf(
            SystemConstraint.REQUIRE_BATTERY_NOT_LOW,
            SystemConstraint.DEVICE_IDLE
        )
    ),
    inputJson = """{"videoId": "$videoId"}"""
)
```

### ML Model Training

```kotlin
scheduler.enqueue(
    id = "ml-training",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "MLTrainingWorker",
    constraints = Constraints(
        isHeavyTask = true,
        requiresCharging = true,
        requiresUnmeteredNetwork = true, // Download dataset
        systemConstraints = setOf(SystemConstraint.DEVICE_IDLE)
    )
)
```

### Large File Encryption

```kotlin
scheduler.enqueue(
    id = "file-encryption-${fileId}",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "FileEncryptionWorker",
    constraints = Constraints(
        isHeavyTask = true,
        systemConstraints = setOf(
            SystemConstraint.REQUIRE_BATTERY_NOT_LOW,
            SystemConstraint.ALLOW_LOW_STORAGE // Allow even if storage low
        )
    ),
    inputJson = """{"fileId": "$fileId"}"""
)
```

**Requirements**:
- Add `FOREGROUND_SERVICE` permission in AndroidManifest.xml
- Shows persistent notification while running (Android requirement)

---

## Battery-Aware Tasks

**v3.0+**: Use `SystemConstraint` for fine-grained battery control.

### Run Only When Battery Healthy

```kotlin
scheduler.enqueue(
    id = "cache-rebuild",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "CacheRebuildWorker",
    constraints = Constraints(
        systemConstraints = setOf(
            SystemConstraint.REQUIRE_BATTERY_NOT_LOW // Battery > 15%
        )
    )
)
```

### Allow Even on Low Battery (Critical Tasks)

```kotlin
scheduler.enqueue(
    id = "emergency-sync",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "EmergencySyncWorker",
    constraints = Constraints(
        requiresNetwork = true,
        systemConstraints = setOf(
            SystemConstraint.ALLOW_LOW_BATTERY // Run even if battery < 15%
        )
    )
)
```

### Charging Required

```kotlin
scheduler.enqueue(
    id = "full-backup",
    trigger = TaskTrigger.Periodic(intervalMs = 7.days.inWholeMilliseconds),
    workerClassName = "FullBackupWorker",
    constraints = Constraints(
        requiresCharging = true, // Must be plugged in
        requiresUnmeteredNetwork = true,
        systemConstraints = setOf(SystemConstraint.DEVICE_IDLE)
    )
)
```

---

## Network-Constrained Tasks

### WiFi Only Upload

```kotlin
scheduler.enqueue(
    id = "video-upload-${videoId}",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "VideoUploadWorker",
    constraints = Constraints(
        requiresNetwork = true,
        requiresUnmeteredNetwork = true // WiFi only, no cellular
    ),
    inputJson = """{"videoId": "$videoId"}"""
)
```

### Any Network Connection

```kotlin
scheduler.enqueue(
    id = "analytics-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 6.hours.inWholeMilliseconds),
    workerClassName = "AnalyticsSyncWorker",
    constraints = Constraints(
        requiresNetwork = true,
        requiresUnmeteredNetwork = false // Allow cellular
    )
)
```

### Offline-First Processing

```kotlin
scheduler.enqueue(
    id = "image-compression",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "ImageCompressionWorker",
    constraints = Constraints(
        requiresNetwork = false // No network needed
    )
)
```

---

## Task Chains

**v3.0+**: Execute complex workflows with sequential and parallel tasks.

### Sequential: Download → Process → Upload

```kotlin
scheduler
    .beginWith(TaskRequest(
        workerClassName = "DownloadDataWorker",
        inputJson = """{"url": "https://api.example.com/data"}"""
    ))
    .then(TaskRequest(
        workerClassName = "ProcessDataWorker"
    ))
    .then(TaskRequest(
        workerClassName = "UploadResultWorker"
    ))
    .enqueue()
```

### Parallel: Multiple Syncs → Finalize

```kotlin
scheduler
    .beginWith(listOf(
        TaskRequest("SyncContactsWorker"),
        TaskRequest("SyncCalendarWorker"),
        TaskRequest("SyncNotesWorker")
    ))
    .then(TaskRequest("FinalizeWorker")) // Runs after ALL parallel tasks complete
    .enqueue()
```

### Complex Workflow

```kotlin
scheduler
    // Step 1: Parallel downloads
    .beginWith(listOf(
        TaskRequest("DownloadImagesWorker"),
        TaskRequest("DownloadVideosWorker"),
        TaskRequest("DownloadDocsWorker")
    ))
    // Step 2: Sequential processing
    .then(TaskRequest("ValidateDownloadsWorker"))
    .then(TaskRequest("CompressMediaWorker"))
    // Step 3: Parallel uploads
    .then(listOf(
        TaskRequest("UploadToCloudWorker"),
        TaskRequest("UploadToBackupWorker")
    ))
    // Step 4: Finalize
    .then(TaskRequest("CleanupWorker"))
    .enqueue()
```

---

## Content Uri Triggers (Android)

React to media changes in MediaStore.

### Watch for New Photos

```kotlin
scheduler.enqueue(
    id = "photo-backup-watcher",
    trigger = TaskTrigger.ContentUri(
        uriString = "content://media/external/images/media",
        triggerForDescendants = true // Watch subdirectories
    ),
    workerClassName = "PhotoBackupWorker"
)
```

### Watch Specific Album

```kotlin
scheduler.enqueue(
    id = "album-watcher",
    trigger = TaskTrigger.ContentUri(
        uriString = "content://media/external/images/media/123",
        triggerForDescendants = false
    ),
    workerClassName = "AlbumSyncWorker"
)
```

---

## Task Cancellation

### Cancel Specific Task

```kotlin
scheduler.cancel("task-id")
```

### Cancel All Tasks

```kotlin
scheduler.cancelAll()
```

### Replace Existing Task

```kotlin
scheduler.enqueue(
    id = "sync-task",
    trigger = TaskTrigger.Periodic(intervalMs = 30.minutes.inWholeMilliseconds),
    workerClassName = "SyncWorker",
    policy = ExistingPolicy.REPLACE // Replace old task with same ID
)
```

### Keep Existing Task

```kotlin
scheduler.enqueue(
    id = "important-task",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "ImportantWorker",
    policy = ExistingPolicy.KEEP // Keep old task if exists
)
```

---

## Real-time Events

Listen to task completion events in your UI.

### Setup Event Listener

```kotlin
class MyViewModel : ViewModel() {

    init {
        viewModelScope.launch {
            TaskEventBus.events.collect { event ->
                when (event) {
                    is TaskCompletionEvent -> {
                        if (event.success) {
                            println("✅ ${event.taskName} completed")
                        } else {
                            println("❌ ${event.taskName} failed: ${event.message}")
                        }
                    }
                }
            }
        }
    }
}
```

### UI Updates

```kotlin
@Composable
fun SyncStatusScreen(viewModel: SyncViewModel) {
    val syncStatus by viewModel.syncStatus.collectAsState()

    LaunchedEffect(Unit) {
        TaskEventBus.events.collect { event ->
            if (event is TaskCompletionEvent && event.taskName == "SyncWorker") {
                viewModel.updateSyncStatus(event.success)
            }
        }
    }

    when (syncStatus) {
        SyncStatus.Success -> Text("✅ Sync completed")
        SyncStatus.Failed -> Text("❌ Sync failed")
        SyncStatus.InProgress -> CircularProgressIndicator()
    }
}
```

---

## Best Practices

### 1. Use Unique Task IDs

```kotlin
// ❌ Bad: Same ID for different items
scheduler.enqueue(id = "upload", ...)

// ✅ Good: Unique ID per item
scheduler.enqueue(id = "upload-photo-${photoId}", ...)
```

### 2. Choose Right Trigger Type

```kotlin
// ❌ Bad: Using Periodic for one-time work
scheduler.enqueue(trigger = TaskTrigger.Periodic(15_MINUTES), ...)

// ✅ Good: Use OneTime for one-time work
scheduler.enqueue(trigger = TaskTrigger.OneTime(), ...)
```

### 3. Set Appropriate Constraints

```kotlin
// ❌ Bad: Too strict (may never run)
Constraints(
    requiresCharging = true,
    requiresUnmeteredNetwork = true,
    systemConstraints = setOf(SystemConstraint.DEVICE_IDLE)
)

// ✅ Good: Balanced constraints
Constraints(
    requiresNetwork = true,
    backoffPolicy = BackoffPolicy.EXPONENTIAL
)
```

### 4. Handle Failures Gracefully

```kotlin
class MyWorker : KmpWorker {
    override suspend fun doWork(inputJson: String?): Boolean {
        return try {
            // Your work here
            true
        } catch (e: NetworkException) {
            Logger.w("Network error, will retry")
            false // Retry with backoff
        } catch (e: Exception) {
            Logger.e("Fatal error", e)
            true // Don't retry fatal errors
        }
    }
}
```

---

## Platform-Specific Tips

### Android

- Use `isHeavyTask = true` for tasks >10 minutes
- Add `SCHEDULE_EXACT_ALARM` permission for exact alarms
- Override `getAlarmReceiverClass()` for custom alarm handling
- Use `ContentUri` triggers for media monitoring

### iOS

- Minimum periodic interval: 15 minutes
- Tasks may be batched by iOS (up to 3 per BGTask)
- Configure task IDs in `Info.plist`
- Handle timeout protection (20s per task, 50s per chain)

---

[📘 Back to README](../README.md) • [📦 Migration Guide](migration-v3.md) • [📗 API Reference](api-reference.md)
