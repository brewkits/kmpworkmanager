# KMP WorkManager - Enterprise-grade Background Manager

**Production-ready** Kotlin Multiplatform library for scheduling and managing background tasks on Android and iOS with a unified API. Built for enterprise applications requiring reliability, stability, and comprehensive monitoring.

<div align="center">
  <img src="kmpworkmanager.png?v=2" alt="KMP WorkManager - Enterprise-Grade Background Tasks for Kotlin Multiplatform" width="100%" />
</div>

<br/>

[![Maven Central](https://img.shields.io/maven-central/v/dev.brewkits/kmpworkmanager)](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-blue.svg)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-green.svg)](https://kotlinlang.org/docs/multiplatform.html)

## Overview

KMP WorkManager provides a single, consistent API for background task scheduling across Android and iOS platforms. It abstracts away platform-specific implementations (WorkManager on Android, BGTaskScheduler on iOS) and lets you write your background task logic once in shared Kotlin code.

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

## Why Choose KMP WorkManager?

### For Enterprise Applications

**Production-Ready Reliability**
- **Self-Healing Architecture**: Automatic recovery from file corruption and race conditions
- **Data Integrity Protection**: CRC32 validation prevents silent data corruption
- **High Performance**: O(1) queue operations handle high-throughput workloads efficiently
- **Comprehensive Testing**: 60+ tests including integration, stress, and concurrency scenarios
- **Chain State Restoration**: Resume from last completed step after iOS interruptions
- **Smart Retry Logic**: Configurable limits prevent infinite failure loops

**Real-Time Monitoring**
- Built-in progress tracking for long-running operations (downloads, uploads, data processing)
- Event bus architecture for reactive UI updates
- Step-based progress for multi-phase operations
- Human-readable status messages for user feedback

**Platform Expertise**
- **iOS Background Optimization**: Time-slicing strategy preserves system credit score
- **Android 14+ Support**: Compatible with all Android variants including Chinese ROMs
- **Smart Fallbacks**: Automatic degradation when permissions denied
- **Batch Processing**: Optimized for iOS BGTask quotas
- **Comprehensive Documentation**: Platform limitations, best practices, and migration guides

**Developer Experience**
- Single API for both platforms reduces maintenance
- Type-safe input serialization
- Koin integration for dependency injection
- Extensive documentation and examples

### Comparison with Alternatives

| Feature | KMP WorkManager | WorkManager (Android only) | Raw BGTaskScheduler (iOS only) |
|---------|-----------|---------------------------|-------------------------------|
| Multiplatform Support | ✅ Android + iOS | ❌ Android only | ❌ iOS only |
| Data Integrity (CRC32) | ✅ Built-in | ❌ Not available | ❌ Not available |
| Self-Healing | ✅ Automatic | ❌ Not available | ❌ Not available |
| Queue Performance | ✅ O(1) Operations | ⚠️ O(N) in some cases | ❌ No queue |
| Progress Tracking | ✅ Built-in | ⚠️ Manual setup | ❌ Not available |
| Chain State Restoration | ✅ Automatic | ✅ Yes | ❌ Manual implementation |
| Type-Safe Input | ✅ Yes | ⚠️ Limited | ❌ No |
| Test Coverage | ✅ 60+ Tests | ✅ Yes | ❌ Manual testing |
| Android 14+ Support | ✅ FAIL OPEN | ⚠️ Requires manual config | ❌ N/A |
| iOS Credit Score | ✅ Time-slicing | ❌ N/A | ⚠️ Manual management |
| Enterprise Documentation | ✅ Extensive | ⚠️ Basic | ❌ Apple docs only |

## Installation

Add to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brewkits:kmpworkmanager:2.2.1")
        }
    }
}
```

Or using version catalog:

```toml
[versions]
kmpworkmanager = "2.2.1"

[libraries]
kmpworkmanager = { module = "dev.brewkits:kmpworkmanager", version.ref = "kmpworkmanager" }
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

### Chain ExistingPolicy (v2.1.3+)

Control how duplicate chain IDs are handled:

```kotlin
// KEEP policy: Skip if chain ID already exists
scheduler.beginWith(TaskRequest("SyncWorker"))
    .then(TaskRequest("ProcessWorker"))
    .withId("daily-sync", ExistingPolicy.KEEP)
    .enqueue()

// REPLACE policy: Delete old chain, enqueue new one (default)
scheduler.beginWith(TaskRequest("UpdatedSyncWorker"))
    .then(TaskRequest("NewProcessWorker"))
    .withId("daily-sync", ExistingPolicy.REPLACE)
    .enqueue()
```

**KEEP Policy**: Prevents duplicate chains from being enqueued. Useful for periodic tasks where you only want one instance in the queue at a time.

**REPLACE Policy** (default): Replaces the existing chain with the new definition. The old chain is marked as deleted and skipped during execution.

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

### Binary Queue Format with CRC32 (v2.2.0+)

**Automatic Data Integrity Protection**

Starting with v2.2.0, iOS queue storage uses a binary format with CRC32 checksums for data integrity:

**Features**:
- Automatic migration from text JSONL to binary format on first launch
- CRC32 validation on every read (detects corrupted data)
- Safe migration with rollback on failure
- No manual intervention required

**Migration Details**:
- Queue file format: `[magic][version][length][data][crc32][\n]`
- Magic number: `KMPQ` (0x4B4D5051)
- Legacy files automatically renamed to `.legacy`
- Migration performance: <5s for 1000 items
- Supports Unicode, Emoji, large data

**Benefits**:
- Prevents silent data corruption
- Automatic corruption recovery
- No performance degradation
- Transparent to application code

> **Note**: Migration happens automatically on upgrade from v2.1.x to v2.2.0+. Your queue data is preserved.

### Android 14+ Foreground Service Configuration (v2.1.3+)

**Required Configuration for Heavy Tasks**

Android 14+ (API 34) requires explicit foreground service type declaration. Configure in your AndroidManifest.xml:

**Example - Data Sync (Default)**:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge" />
```

**Example - Location Tracking**:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="location|dataSync"
    tools:node="merge" />
```

**Specify Service Type in Code**:
```kotlin
import android.content.pm.ServiceInfo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val inputData = buildJsonObject {
    put(KmpHeavyWorker.FOREGROUND_SERVICE_TYPE_KEY,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
}.toString()

scheduler.enqueue(
    id = "location-tracking",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "LocationWorker",
    constraints = Constraints(isHeavyTask = true),
    inputJson = inputData
)
```

**Available Service Types**:
- `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (default)
- `FOREGROUND_SERVICE_TYPE_LOCATION`
- `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`
- `FOREGROUND_SERVICE_TYPE_CAMERA`
- `FOREGROUND_SERVICE_TYPE_MICROPHONE`
- `FOREGROUND_SERVICE_TYPE_HEALTH`

**Validation & Fallback**:
- Automatic validation on Android 14+ with FAIL OPEN strategy
- Falls back to DATA_SYNC if validation fails
- Compatible with Chinese ROMs (Xiaomi, Oppo, Vivo)
- Clear error messages with manifest requirements

### iOS Time-Slicing for Credit Score

**Intelligent BGTask Time Management**

KMP WorkManager uses conservative time-slicing to preserve iOS credit score:

**How It Works**:
- Uses 85% of available BGTask time
- 15% buffer reserved for cleanup and progress saving
- Early stop when remaining time insufficient
- Automatic continuation scheduling for large queues
- Callers can pass an absolute `deadlineEpochMs` (e.g., from the BGTask `expirationHandler`) to let the executor compute the effective timeout dynamically — avoids hard-coding and handles cold-start overhead accurately (v2.2.1+)

**Time Limits**:
- `BGAppRefreshTask`: 50s chain timeout (from 30s system limit)
- `BGProcessingTask`: 300s chain timeout (from 5-10min system limit)

**Benefits**:
- Prevents iOS system kills
- Preserves credit score for future task execution
- Automatic batch resumption
- Detailed execution metrics via TaskEventBus

**ExecutionMetrics Event**:
```kotlin
TaskEventBus.events
    .filterIsInstance<TaskCompletionEvent>()
    .filter { it.taskName.startsWith("BatchExecution") }
    .collect { event ->
        // Monitor time usage, chains processed, system kills
        println("Chains: ${event.chainsSucceeded}/${event.chainsAttempted}")
        println("Time usage: ${event.timeUsagePercentage}%")
    }
```

## Platform-Specific Features

### Android

- WorkManager integration for deferrable tasks
- AlarmManager for exact timing requirements
- Foreground service support for long-running tasks
- ContentUri triggers for media monitoring
- Automatic fallback when exact alarms permission is denied

### iOS

- BGTaskScheduler integration
- **Chain state restoration**: Resume interrupted chains from last completed step; parallel steps track per-task completion so only failed tasks re-execute on retry (v2.2.1+)
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
- [Demo Guide](DEMO_GUIDE.md) - Interactive demo app walkthrough
- [Platform Setup](docs/platform-setup.md)
- [API Reference](docs/api-reference.md)
- [Task Chains](docs/task-chains.md)
- [iOS Best Practices](docs/ios-best-practices.md) ⚠️ **Read this if using iOS**
- [iOS Migration Guide](docs/ios-migration.md)
- [Architecture Overview](ARCHITECTURE.md)

## Production Stability

**KMP WorkManager v2.2.1 is now production-stable** with comprehensive testing, self-healing architecture, and battle-tested reliability improvements:

**Proven Stability Features**:
- **60+ Comprehensive Tests**: Including integration, stress, concurrency, and real-world scenarios
- **Self-Healing Architecture**: Automatic recovery from file corruption and race conditions
- **Data Integrity Protection**: CRC32 validation prevents silent data corruption
- **Per-Task Retry Tracking**: Idempotent parallel chain execution - only failed tasks re-execute
- **Queue Corruption Recovery**: Truncation-based recovery preserves all valid records
- **Expired-Deadline Protection**: Prevents crashes when BGTask time budget exhausted
- **iOS Time-Slicing**: Conservative credit score preservation for reliable background execution

**Enterprise-Grade Reliability**:
- Binary queue format with automatic migration from legacy text format
- Buffered I/O for optimized performance during migration
- Schema-evolution safety with `ignoreUnknownKeys` on all deserialization
- Correct BGProcessingTask timeout (300s) for extended background work
- Thread-safe file operations with NSFileCoordinator on iOS
- Robust error handling across all platforms

KMP WorkManager is actively used in production applications and continuously improved based on real-world feedback. The v2.2.x series focuses on stability, reliability, and developer experience.

---

## Version History

**v2.2.1** (Latest) - Parallel Retry Idempotency & Corruption Recovery
- Per-task completion tracking in parallel chain steps — only failed tasks re-execute on retry
- Queue corruption recovery via truncation preserves all valid records
- Buffered legacy queue reads (4 KB chunks) reduce system calls during text-format migration
- Expired-deadline early return prevents crash; `deadlineEpochMs` for accurate time-slicing
- Correct 300 s chain timeout for BGProcessingTask
- All persisted-data deserialization uses `ignoreUnknownKeys` for schema-evolution safety
- 15 new tests (ChainProgressTest now 38 total, all passing)

**v2.2.0** - Production-Ready Release
- Self-healing architecture with automatic corruption recovery
- Data integrity protection with CRC32 validation
- High-performance O(1) queue operations
- Comprehensive test coverage (60+ tests)
- iOS time-slicing for credit score preservation
- Android 14+ compatibility with all ROM variants

**v2.0.0** - Package Namespace Migration

**BREAKING CHANGE**: Group ID changed from `io.brewkits` to `dev.brewkits`
- Maven artifact: `io.brewkits:kmpworkmanager` → `dev.brewkits:kmpworkmanager`
- Package namespace: `io.brewkits.kmpworkmanager.*` → `dev.brewkits.kmpworkmanager.*`
- Aligns with owned domain `brewkits.dev` for proper Maven Central ownership

**Migration Guide:**
1. Update dependency: `implementation("dev.brewkits:kmpworkmanager:2.0.0")`
2. Update imports: `import dev.brewkits.kmpworkmanager.*`
3. Clean and rebuild project

See [DEPRECATED_README.md](DEPRECATED_README.md) for detailed migration instructions.

**v1.1.0** - Stability & Enterprise Features
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

- Kotlin 2.1.21 or higher
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
Copyright 2026 Brewkits

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

- [Maven Central](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager)
- [GitHub Issues](https://github.com/brewkits/kmp_worker/issues)
- [Changelog](CHANGELOG.md)

---

## ⭐ Star Us on GitHub!

If KMP WorkManager saves you time, please give us a star!

It helps other developers discover this project.

[⬆️ Back to Top](#kmp-workmanager---enterprise-grade-background-manager)

---

Made with ❤️ by **Nguyễn Tuấn Việt** at **Brewkits**

**Support**: datacenter111@gmail.com • **Community**: [GitHub Issues](https://github.com/brewkits/kmp_worker/issues)
