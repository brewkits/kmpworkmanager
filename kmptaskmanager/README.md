# KMP TaskManager

[![Maven Central](https://img.shields.io/maven-central/v/io.brewkits/kmptaskmanager?label=Maven%20Central)](https://central.sonatype.com/artifact/io.brewkits/kmptaskmanager)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.20-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Platform](https://img.shields.io/badge/platform-android%20|%20ios-lightgrey)](https://kotlinlang.org/docs/multiplatform.html)

A robust, production-ready Kotlin Multiplatform library for scheduling and managing background tasks on **Android** and **iOS**.

---

## ⚠️ Version 4.0.0 Breaking Changes

**v4.0.0** introduces a cleaner, more extensible worker registration system via factory pattern.

### Key Changes
- ✅ **Worker factory pattern** replaces hardcoded workers
- ✅ **iOS Info.plist auto-reading** for task ID validation
- ❌ **`WorkerTypes` object removed** (define your own)
- ❌ **Koin initialization** now requires `workerFactory` parameter

### Migration Time
~30 minutes - [See Migration Guide](../docs/MIGRATION_V4.md)

### Quick Migration Example

**Before (v3.x)**:
```kotlin
startKoin {
    modules(kmpTaskManagerModule())  // ❌ No longer works
}
```

**After (v4.0.0)**:
```kotlin
// 1. Create worker factory
class MyWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String) = when (workerClassName) {
        "SyncWorker" -> SyncWorker()
        else -> null
    }
}

// 2. Pass factory to Koin
startKoin {
    modules(kmpTaskManagerModule(
        workerFactory = MyWorkerFactory()  // ✅ Required
    ))
}
```

**Full migration guide**: [MIGRATION_V4.md](../docs/MIGRATION_V4.md)

---

## Features

✅ **Unified API** - Single interface for both platforms
✅ **Multiple Trigger Types** - OneTime, Periodic, Exact, ContentUri, Battery, Storage, DeviceIdle
✅ **Task Chains** - Sequential and parallel task execution
✅ **Constraints** - Network, charging, battery, storage requirements
✅ **ExistingPolicy** - KEEP or REPLACE duplicate tasks
✅ **Type-Safe Serialization** - Reified inline extensions for automatic JSON conversion
✅ **Professional Logging** - 4-level structured logging system
✅ **Timeout Protection** - Automatic timeout handling on iOS
✅ **Production Ready** - Comprehensive error handling and documentation

## Installation

### Gradle (Kotlin DSL)

```kotlin
commonMain.dependencies {
    implementation("io.brewkits:kmptaskmanager:4.0.0")
}
```

### Version Catalog

```toml
[versions]
kmptaskmanager = "4.0.0"

[libraries]
kmptaskmanager = { module = "io.brewkits:kmptaskmanager", version.ref = "kmptaskmanager" }
```

## Quick Start

### 1. Define Worker Identifiers

```kotlin
// commonMain - MyWorkers.kt
object MyWorkers {
    const val SYNC = "SyncWorker"
    const val UPLOAD = "UploadWorker"
}
```

### 2. Implement Workers

**Android:**

```kotlin
// androidMain - SyncWorker.kt
class SyncWorker : AndroidWorker {
    override suspend fun doWork(input: String?): Boolean {
        // Your sync logic
        delay(2000)
        TaskEventBus.emit(TaskCompletionEvent("Sync", true, "✅ Synced"))
        return true
    }
}
```

**iOS:**

```kotlin
// iosMain - SyncWorker.kt
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        // Your sync logic (must complete within 25s)
        delay(2000)
        TaskEventBus.emit(TaskCompletionEvent("Sync", true, "✅ Synced"))
        return true
    }
}
```

### 3. Create Worker Factory

**Android:**

```kotlin
// androidMain - MyWorkerFactory.kt
class MyWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            MyWorkers.SYNC -> SyncWorker()
            MyWorkers.UPLOAD -> UploadWorker()
            else -> null
        }
    }
}
```

**iOS:**

```kotlin
// iosMain - MyWorkerFactory.kt
class MyWorkerFactory : IosWorkerFactory {
    override fun createWorker(workerClassName: String): IosWorker? {
        return when (workerClassName) {
            MyWorkers.SYNC -> SyncWorker()
            MyWorkers.UPLOAD -> UploadWorker()
            else -> null
        }
    }
}
```

### 4. Initialize Koin with Factory

**Android:**

```kotlin
// Android - Application.kt
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MyApp)
            modules(kmpTaskManagerModule(
                workerFactory = MyWorkerFactory()  // ✅ Required in v4.0.0
            ))
        }
    }
}
```

**iOS:**

```kotlin
// iOS - KoinSetup.kt
fun initKoinIos() {
    startKoin {
        modules(kmpTaskManagerModule(
            workerFactory = MyWorkerFactory()  // ✅ Required in v4.0.0
        ))
    }
}
```

### 5. Schedule Tasks

```kotlin
class MyViewModel(
    private val scheduler: BackgroundTaskScheduler
) {
    suspend fun scheduleSync() {
        scheduler.enqueue(
            id = "data-sync",
            trigger = TaskTrigger.OneTime(initialDelayMs = 5000),
            workerClassName = MyWorkers.SYNC,  // ✅ Use your constant
            constraints = Constraints(requiresNetwork = true)
        )
    }
}
```

## Usage Examples

### One-Time Task

```kotlin
scheduler.enqueue(
    id = "upload-task",
    trigger = TaskTrigger.OneTime(initialDelayMs = 10_000),
    workerClassName = MyWorkers.UPLOAD,  // Use constant from MyWorkers
    constraints = Constraints(
        requiresNetwork = true,
        requiresCharging = false
    )
)
```

### Periodic Task

```kotlin
scheduler.enqueue(
    id = "periodic-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000), // 15 min
    workerClassName = MyWorkers.SYNC,  // Use constant from MyWorkers
    policy = ExistingPolicy.KEEP
)
```

### Task Chains

```kotlin
// Define your workers first
object MyWorkers {
    const val DOWNLOAD = "DownloadWorker"
    const val PROCESS = "ProcessWorker"
    const val UPLOAD = "UploadWorker"
}

// Sequential: Download → Process → Upload
scheduler.beginWith(TaskRequest(MyWorkers.DOWNLOAD))
    .then(TaskRequest(MyWorkers.PROCESS))
    .then(TaskRequest(MyWorkers.UPLOAD))
    .enqueue()

// Parallel: (Download ∥ Sync) → Process
scheduler.beginWith(
    listOf(
        TaskRequest(MyWorkers.DOWNLOAD),
        TaskRequest(MyWorkers.SYNC)
    )
)
    .then(TaskRequest(MyWorkers.PROCESS))
    .enqueue()
```

### Type-Safe Serialization

Use extension functions for automatic JSON serialization of input data:

```kotlin
// 1. Define your data model
@Serializable
data class UploadData(
    val fileUrl: String,
    val fileName: String,
    val size: Long
)

// 2. Pass object directly (no manual JSON conversion!)
scheduler.enqueue(
    id = "upload-file",
    trigger = TaskTrigger.OneTime(),
    workerClassName = MyWorkers.UPLOAD,
    input = UploadData(
        fileUrl = "https://example.com/file.zip",
        fileName = "data.zip",
        size = 1024000
    ),
    constraints = Constraints(requiresNetwork = true)
)

// 3. Worker receives JSON string
class UploadWorker : AndroidWorker {
    override suspend fun doWork(input: String?): Boolean {
        val data = input?.let { Json.decodeFromString<UploadData>(it) }
        // Use data.fileUrl, data.fileName, data.size
        return uploadFile(data)
    }
}
```

**Type-safe chains**:

```kotlin
@Serializable
data class DownloadRequest(val url: String, val priority: Int)

@Serializable
data class ProcessRequest(val inputPath: String, val format: String)

// Sequential with typed input
scheduler.beginWith(
    workerClassName = MyWorkers.DOWNLOAD,
    input = DownloadRequest("https://...", priority = 1)
)
    .then(TaskRequest(MyWorkers.PROCESS))
    .enqueue()

// Parallel with typed inputs
scheduler.beginWith(
    TaskSpec(MyWorkers.FETCH_USERS, input = FetchRequest("/users")),
    TaskSpec(MyWorkers.FETCH_POSTS, input = FetchRequest("/posts"))
)
    .then(TaskRequest(MyWorkers.MERGE_DATA))
    .enqueue()
```

**Benefits**:
- ✅ Compile-time type safety
- ✅ No manual JSON conversion
- ✅ Refactoring-friendly (rename fields, IDE updates all)
- ✅ Reduce boilerplate code

### Android-Only Triggers

```kotlin
// Define Android-specific workers
object AndroidWorkers {
    const val MEDIA = "MediaWorker"
    const val CLEANUP = "CleanupWorker"
}

// Content URI monitoring
scheduler.enqueue(
    id = "media-observer",
    trigger = TaskTrigger.ContentUri(
        uriString = "content://media/external/images/media",
        triggerForDescendants = true
    ),
    workerClassName = AndroidWorkers.MEDIA
)

// Device idle
scheduler.enqueue(
    id = "maintenance",
    trigger = TaskTrigger.DeviceIdle,
    workerClassName = AndroidWorkers.CLEANUP
)
```

### Logging

```kotlin
import io.kmp.taskmanager.utils.Logger
import io.kmp.taskmanager.utils.LogTags

Logger.i(LogTags.WORKER, "Task completed successfully")
Logger.e(LogTags.SCHEDULER, "Failed to schedule", exception)
```

## Platform Support Matrix

| Feature | Android | iOS |
|---------|---------|-----|
| OneTime Tasks | ✅ WorkManager | ✅ BGTaskScheduler |
| Periodic Tasks | ✅ 15min minimum | ✅ Re-schedules |
| Exact Alarms | ⚠️ Extend class | ⚠️ Extend class |
| Task Chains | ✅ Continuation API | ✅ Custom queue |
| Constraints (Network, Charging) | ✅ Full support | ✅ Basic support |
| ContentUri Triggers | ✅ Supported | ❌ Android only |
| Battery/Storage Triggers | ✅ Supported | ❌ Android only |
| Device Idle | ✅ Supported | ❌ Android only |

## ⚠️ Important iOS Limitations

### Opportunistic Scheduling

iOS background tasks are **opportunistic**, not guaranteed:

- ❌ **No guarantees**: iOS decides when (or if) to run tasks based on:
  - Device usage patterns
  - Battery level
  - Network availability
  - Thermal state
  - User engagement with your app

- ⏰ **Timing is unpredictable**: Even if you schedule a task for "15 minutes from now", iOS may:
  - Delay it for hours
  - Never run it if the user rarely opens your app
  - Bundle it with other tasks for efficiency

- 🔋 **System prioritizes battery**: Background execution is severely limited to preserve battery life

- 📱 **Best effort, not guaranteed**: Unlike Android's WorkManager which provides deterministic guarantees, iOS BGTaskScheduler is best-effort only

### Platform Reliability Comparison

| Aspect | Android (WorkManager) | iOS (BGTaskScheduler) |
|--------|----------------------|----------------------|
| **Scheduling** | Deterministic | Opportunistic |
| **Reliability** | High (guaranteed execution) | Low (system-dependent) |
| **Timing** | Predictable (within constraints) | Unpredictable |
| **Use Case** | Critical operations ✅ | Nice-to-have operations only ⚠️ |

### Best Practices for iOS

❌ **DO NOT** rely on iOS background tasks for:
- Time-critical operations
- User-facing features that must complete
- Financial transactions or important data sync
- Anything that affects app functionality

✅ **DO** use iOS background tasks for:
- Opportunistic cache updates
- Pre-fetching content
- Non-critical sync operations
- Nice-to-have optimizations

✅ **Alternative strategies for critical operations**:
- **Server-side scheduling**: Push notifications to trigger foreground work
- **User-initiated**: Prompt user to keep app open for important operations
- **Hybrid approach**: Use background tasks as optimization, but handle failures gracefully

### Example: Handling iOS Uncertainty

```kotlin
// ❌ BAD: Expecting iOS to reliably sync data
scheduler.enqueue(
    id = "critical-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "SyncWorker"
)
// User may lose data if iOS never runs this!

// ✅ GOOD: Opportunistic optimization + fallback
// 1. Schedule background sync (best effort)
scheduler.enqueue(
    id = "cache-refresh",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "CacheRefreshWorker"
)

// 2. Always sync on app launch (guaranteed)
class MyApp {
    override fun onCreate() {
        viewModelScope.launch {
            dataRepository.syncNow() // Foreground sync
        }
    }
}

// 3. Notify user if critical operation needed
if (hasPendingImportantData) {
    showNotification("Please open app to complete sync")
}
```

---

## iOS Specific

### Timeout Protection

- **Single tasks**: 25 seconds (BGAppRefreshTask)
- **Heavy tasks**: 55 seconds (BGProcessingTask)
- **Chain tasks**: 20 seconds per task, 50s total

### Info.plist Configuration

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>kmp_chain_executor_task</string>
    <string>your-task-id</string>
</array>
```

### Batch Processing

iOS processes up to 3 chains per BGTask invocation:

```swift
chainExecutor.executeChainsInBatch(maxChains: 3, totalTimeoutMs: 50_000)
```

## Android Specific

### WorkManager Configuration

```kotlin
// build.gradle.kts
android {
    defaultConfig {
        manifestPlaceholders["workManagerInitProvider"] = "androidx.startup"
    }
}
```

### Exact Alarms

Extend `NativeTaskScheduler` and override:

```kotlin
class MyScheduler(context: Context) : NativeTaskScheduler(context) {
    override fun scheduleExactAlarm(...): ScheduleResult {
        // Implement with AlarmManager + BroadcastReceiver
        return ScheduleResult.ACCEPTED
    }
}
```

## Requirements

- **Kotlin**: 2.2.20+
- **Android**: API 26+ (Android 8.0)
- **iOS**: 13.0+
- **Koin**: 4.1.1+

## Documentation

- [Full Documentation](https://github.com/vietnguyentuan2019/KMPTaskManager)
- [API Reference](https://github.com/vietnguyentuan2019/KMPTaskManager/wiki)
- [Migration Guide](https://github.com/vietnguyentuan2019/KMPTaskManager/blob/main/MIGRATION.md)

## License

```
Copyright 2025 Nguyễn Tuấn Việt

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) first.

## Support

- **Issues**: [GitHub Issues](https://github.com/vietnguyentuan2019/KMPTaskManager/issues)
- **Discussions**: [GitHub Discussions](https://github.com/vietnguyentuan2019/KMPTaskManager/discussions)

---

**Made with ❤️ using Kotlin Multiplatform**
