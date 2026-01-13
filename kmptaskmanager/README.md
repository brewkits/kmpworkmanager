# KMP TaskManager

[![Maven Central](https://img.shields.io/maven-central/v/io.github.vietnguyentuan2019/kmptaskmanager?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.vietnguyentuan2019/kmptaskmanager)
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
✅ **Professional Logging** - 4-level structured logging system
✅ **Timeout Protection** - Automatic timeout handling on iOS
✅ **Production Ready** - Comprehensive error handling and documentation

## Installation

### Gradle (Kotlin DSL)

```kotlin
commonMain.dependencies {
    implementation("io.github.vietnguyentuan2019:kmptaskmanager:4.0.0")
}
```

### Version Catalog

```toml
[versions]
kmptaskmanager = "4.0.0"

[libraries]
kmptaskmanager = { module = "io.github.vietnguyentuan2019:kmptaskmanager", version.ref = "kmptaskmanager" }
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
