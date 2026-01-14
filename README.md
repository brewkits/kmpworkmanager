# KMP Worker - Enterprise-grade Background Manager

**Production-ready** Kotlin Multiplatform library for scheduling and managing background tasks on Android and iOS with a unified API. Built for enterprise applications requiring reliability, stability, and comprehensive monitoring.

[![Maven Central](https://img.shields.io/maven-central/v/io.brewkits/kmpworkmanager)](https://central.sonatype.com/artifact/io.brewkits/kmpworkmanager)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-blue.svg)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-green.svg)](https://kotlinlang.org/docs/multiplatform.html)

## Overview

KMP Worker provides a single, consistent API for background task scheduling across Android and iOS platforms. It abstracts away platform-specific implementations (WorkManager on Android, BGTaskScheduler on iOS) and lets you write your background task logic once in shared Kotlin code.

**Enterprise Features**:
- Real-time progress tracking for long-running operations
- Chain state restoration for reliability on iOS
- Comprehensive test coverage for critical components
- File-based storage for improved iOS performance
- Production-grade error handling and logging

### The Problem

When building multiplatform apps, you typically need to maintain separate background task implementations:

```kotlin
// Android - WorkManager API
val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
    .setConstraints(...)
    .build()
WorkManager.getInstance(context).enqueue(workRequest)

// iOS - BGTaskScheduler API
let request = BGAppRefreshTaskRequest(identifier: "sync-task")
BGTaskScheduler.shared.submit(request)
```

This leads to duplicated logic, more maintenance, and platform-specific bugs.

### The Solution

With KMP WorkManager, you write your scheduling logic once:

```kotlin
scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "SyncWorker",
    constraints = Constraints(requiresNetwork = true)
)
```

The library handles platform-specific details automatically.

## Why Choose KMP Worker?

### For Enterprise Applications

**Production-Ready Reliability**
- Comprehensive test coverage (200+ tests) including iOS-specific integration tests
- Chain state restoration ensures no work is lost on iOS interruptions
- Retry logic with configurable limits prevents infinite failure loops
- File-based storage with atomic operations for data integrity

**Real-Time Monitoring**
- Built-in progress tracking for long-running operations (downloads, uploads, data processing)
- Event bus architecture for reactive UI updates
- Step-based progress for multi-phase operations
- Human-readable status messages for user feedback

**Platform Expertise**
- Deep understanding of iOS background limitations (documented in detail)
- Smart fallbacks for Android exact alarm permissions
- Batch processing optimization for iOS BGTask quotas
- Platform-specific best practices and migration guides

**Developer Experience**
- Single API for both platforms reduces maintenance
- Type-safe input serialization
- Koin integration for dependency injection
- Extensive documentation and examples

### Comparison with Alternatives

| Feature | KMP Worker | WorkManager (Android only) | Raw BGTaskScheduler (iOS only) |
|---------|-----------|---------------------------|-------------------------------|
| Multiplatform Support | ✅ Android + iOS | ❌ Android only | ❌ iOS only |
| Progress Tracking | ✅ Built-in | ⚠️ Manual setup | ❌ Not available |
| Chain State Restoration | ✅ Automatic | ✅ Yes | ❌ Manual implementation |
| Type-Safe Input | ✅ Yes | ⚠️ Limited | ❌ No |
| Test Coverage | ✅ Comprehensive | ✅ Yes | ❌ Manual testing |
| Enterprise Documentation | ✅ Extensive | ⚠️ Basic | ❌ Apple docs only |

## Installation

Add to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.brewkits:kmpworkmanager:1.0.0")
        }
    }
}
```

Or using version catalog:

```toml
[versions]
kmpworkmanager = "1.0.0"

[libraries]
kmpworkmanager = { module = "io.brewkits:kmpworkmanager", version.ref = "kmpworkmanager" }
```

## Quick Start

### 1. Define Your Workers

Create worker classes on each platform:

**Android** (`androidMain`):

```kotlin
class SyncWorker : AndroidWorker {
    override suspend fun doWork(input: String?): Boolean {
        // Your sync logic here
        return true
    }
}
```

**iOS** (`iosMain`):

```kotlin
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        // Same sync logic - shared code!
        return true
    }
}
```

### 2. Create Worker Factory

**Android** (`androidMain`):

```kotlin
class MyWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            else -> null
        }
    }
}
```

**iOS** (`iosMain`):

```kotlin
class MyWorkerFactory : IosWorkerFactory {
    override fun createWorker(workerClassName: String): IosWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            else -> null
        }
    }
}
```

### 3. Initialize Koin

**Android** (`Application.kt`):

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MyApp)
            modules(kmpWorkerModule(
                workerFactory = MyWorkerFactory()
            ))
        }
    }
}
```

**iOS** (`AppDelegate.swift`):

```swift
func application(_ application: UIApplication,
                 didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    KoinModuleKt.doInitKoinIos(workerFactory: MyWorkerFactory())
    return true
}
```

### 4. Schedule Tasks

```kotlin
class MyViewModel(private val scheduler: BackgroundTaskScheduler) {

    fun scheduleSync() {
        scheduler.enqueue(
            id = "data-sync",
            trigger = TaskTrigger.Periodic(intervalMs = 900_000), // 15 minutes
            workerClassName = "SyncWorker",
            constraints = Constraints(requiresNetwork = true)
        )
    }
}
```

## Features

### Multiple Trigger Types

**Periodic Tasks**
```kotlin
scheduler.enqueue(
    id = "periodic-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "SyncWorker"
)
```

**One-Time Tasks**
```kotlin
scheduler.enqueue(
    id = "upload-task",
    trigger = TaskTrigger.OneTime(initialDelayMs = 5000),
    workerClassName = "UploadWorker"
)
```

**Windowed Tasks** (Execute within a time window)
```kotlin
scheduler.enqueue(
    id = "maintenance",
    trigger = TaskTrigger.Windowed(
        earliest = System.currentTimeMillis() + 3600_000,  // 1 hour from now
        latest = System.currentTimeMillis() + 7200_000     // 2 hours from now
    ),
    workerClassName = "MaintenanceWorker"
)
```
> **Note**: On iOS, only `earliest` time is enforced via `earliestBeginDate`. The `latest` time is logged but not enforced by BGTaskScheduler.

**Exact Alarms** (Android only)
```kotlin
scheduler.enqueue(
    id = "reminder",
    trigger = TaskTrigger.Exact(atEpochMillis = System.currentTimeMillis() + 60_000),
    workerClassName = "ReminderWorker"
)
```

### Task Constraints

Control when tasks should run:

```kotlin
scheduler.enqueue(
    id = "heavy-task",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "ProcessingWorker",
    constraints = Constraints(
        requiresNetwork = true,
        requiresCharging = true,
        requiresUnmeteredNetwork = true,  // Wi-Fi only
        systemConstraints = setOf(
            SystemConstraint.REQUIRE_BATTERY_NOT_LOW,
            SystemConstraint.DEVICE_IDLE
        )
    )
)
```

### Task Chains

Execute tasks sequentially or in parallel:

```kotlin
// Sequential: Download -> Process -> Upload
scheduler.beginWith(TaskRequest("DownloadWorker"))
    .then(TaskRequest("ProcessWorker"))
    .then(TaskRequest("UploadWorker"))
    .enqueue()

// Parallel: Run multiple tasks, then finalize
scheduler.beginWith(listOf(
    TaskRequest("FetchUsers"),
    TaskRequest("FetchPosts"),
    TaskRequest("FetchComments")
))
    .then(TaskRequest("MergeDataWorker"))
    .enqueue()
```

### Type-Safe Input

Pass typed data to workers:

```kotlin
@Serializable
data class UploadRequest(val fileUrl: String, val fileName: String)

scheduler.enqueue(
    id = "upload",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "UploadWorker",
    input = UploadRequest("https://...", "data.zip")
)
```

### Real-Time Progress Tracking

Workers can report progress to provide real-time feedback to the UI, essential for enterprise applications with long-running operations:

**In Your Worker**:
```kotlin
class FileDownloadWorker(
    private val progressListener: ProgressListener?
) : Worker {
    override suspend fun doWork(input: String?): Boolean {
        val totalBytes = getTotalFileSize()
        var downloaded = 0L

        while (downloaded < totalBytes) {
            val chunk = downloadChunk()
            downloaded += chunk.size

            val progress = (downloaded * 100 / totalBytes).toInt()
            progressListener?.onProgressUpdate(
                WorkerProgress(
                    progress = progress,
                    message = "Downloaded $downloaded / $totalBytes bytes"
                )
            )
        }

        return true
    }
}
```

**In Your UI**:
```kotlin
@Composable
fun DownloadScreen() {
    val progressFlow = TaskProgressBus.events
        .filterIsInstance<TaskProgressEvent>()
        .filter { it.taskId == "download-task" }

    val progress by progressFlow.collectAsState(initial = null)

    LinearProgressIndicator(
        progress = (progress?.progress?.progress ?: 0) / 100f
    )
    Text(text = progress?.progress?.message ?: "Waiting...")
}
```

Progress features:
- Percentage-based progress (0-100%)
- Optional human-readable messages
- Step-based tracking (e.g., "Step 3/5")
- Real-time updates via SharedFlow
- Works across Android and iOS

## Platform-Specific Features

### Android

- WorkManager integration for deferrable tasks
- AlarmManager for exact timing requirements
- Foreground service support for long-running tasks
- ContentUri triggers for media monitoring
- Automatic fallback when exact alarms permission is denied

### iOS

- BGTaskScheduler integration
- **Chain state restoration**: Resume interrupted chains from last completed step
- Automatic re-scheduling of periodic tasks
- File-based storage for better performance and thread safety
- Thread-safe task execution with NSFileCoordinator
- Timeout protection with configurable limits
- Retry logic with max retry limits (prevents infinite loops)
- Batch processing for efficient BGTask usage

> [!WARNING]
> **Critical iOS Limitations**
>
> iOS background tasks are **fundamentally different** from Android:
>
> 1. **Opportunistic Execution**: The system decides when to run tasks based on device usage, battery, and other factors. Tasks may be delayed hours or never run.
>
> 2. **Strict Time Limits**:
>    - `BGAppRefreshTask`: ~30 seconds maximum
>    - `BGProcessingTask`: ~60 seconds (requires charging + WiFi)
>
> 3. **Force-Quit Termination**: All background tasks are **immediately killed** when user force-quits the app. This is by iOS design and cannot be worked around.
>
> 4. **Limited Constraints**: iOS does not support battery, charging, or storage constraints.
>
> **Do NOT use iOS background tasks for**:
> - Time-critical operations
> - Long-running processes (>30s)
> - Operations that must complete (use foreground mode)
>
> See [iOS Best Practices](docs/ios-best-practices.md) for detailed guidance.

## Platform Support Matrix

| Feature | Android | iOS |
|---------|---------|-----|
| Periodic Tasks | ✅ Supported (15 min minimum) | ✅ Supported (opportunistic) |
| One-Time Tasks | ✅ Supported | ✅ Supported |
| Windowed Tasks | ✅ Supported | ✅ Supported (`earliest` only) |
| Exact Timing | ✅ Supported (AlarmManager) | ❌ Not supported |
| Task Chains | ✅ Supported | ✅ Supported with state restoration |
| Progress Tracking | ✅ Supported | ✅ Supported |
| Network Constraints | ✅ Supported | ✅ Supported |
| Charging Constraints | ✅ Supported | ❌ Not supported |
| Battery Constraints | ✅ Supported | ❌ Not supported |
| ContentUri Triggers | ✅ Supported | ❌ Not supported |

## Documentation

- [Quick Start Guide](docs/quickstart.md)
- [Platform Setup](docs/platform-setup.md)
- [API Reference](docs/api-reference.md)
- [Task Chains](docs/task-chains.md)
- [iOS Best Practices](docs/ios-best-practices.md) ⚠️ **Read this if using iOS**
- [iOS Migration Guide](docs/ios-migration.md)
- [Architecture Overview](ARCHITECTURE.md)

## Version History

**v1.1.0** (Latest) - Stability & Enterprise Features
- **NEW**: Real-time worker progress tracking with `WorkerProgress` and `TaskProgressBus`
- **NEW**: iOS chain state restoration - resume from last completed step after interruptions
- **NEW**: Windowed task trigger support (execute within time window)
- **NEW**: Comprehensive iOS test suite (38+ tests for ChainProgress, ChainExecutor, IosFileStorage)
- Improved iOS retry logic with max retry limits (prevents infinite loops)
- Enhanced iOS batch processing for efficient BGTask usage
- Production-grade error handling and logging improvements
- iOS documentation: Best practices and migration guides

**v1.0.0** - Initial Stable Release
- Worker factory pattern for better extensibility
- Automatic iOS task ID validation from Info.plist
- Type-safe serialization extensions
- Unified API for Android and iOS
- File-based storage on iOS for better performance
- Smart exact alarm fallback on Android
- Heavy task support with foreground services
- Task chains with sequential and parallel execution

## Requirements

- Kotlin 2.2.0 or higher
- Android: API 21+ (Android 5.0)
- iOS: 13.0+
- Gradle 8.0+

## Contributing

Contributions are welcome. Please:

1. Open an issue to discuss proposed changes
2. Follow the existing code style
3. Add tests for new features
4. Update documentation as needed

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## License

```
Copyright 2025 Brewkits

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

## Links

- [Maven Central](https://central.sonatype.com/artifact/io.brewkits/kmpworkmanager)
- [GitHub Issues](https://github.com/brewkits/kmp_worker/issues)
- [Changelog](CHANGELOG.md)

---

**Organization**: [Brewkits](https://github.com/brewkits)
**Contact**: vietnguyentuan@gmail.com
