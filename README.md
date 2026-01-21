# KMP WorkManager - Enterprise-grade Background Manager

**Production-ready** Kotlin Multiplatform library for scheduling and managing background tasks on Android and iOS with a unified API. Built for enterprise applications requiring reliability, stability, and comprehensive monitoring.

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
- Comprehensive test coverage (236 tests) including iOS-specific integration tests
- Chain state restoration ensures no work is lost on iOS interruptions
- Retry logic with configurable limits prevents infinite failure loops
- File-based storage with O(1) queue operations and atomic operations for data integrity

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

| Feature | KMP WorkManager | WorkManager (Android only) | Raw BGTaskScheduler (iOS only) |
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
            implementation("dev.brewkits:kmpworkmanager:2.1.2")
        }
    }
}
```

Or using version catalog:

```toml
[versions]
kmpworkmanager = "2.1.2"

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
- [Demo Guide](DEMO_GUIDE.md) - Interactive demo app walkthrough
- [Platform Setup](docs/platform-setup.md)
- [API Reference](docs/api-reference.md)
- [Task Chains](docs/task-chains.md)
- [iOS Best Practices](docs/ios-best-practices.md) ⚠️ **Read this if using iOS**
- [iOS Migration Guide](docs/ios-migration.md)
- [Architecture Overview](ARCHITECTURE.md)

## Roadmap

KMP WorkManager is actively developed with a focus on reliability, developer experience, and enterprise features. Here's our planned development roadmap:

### ✅ v2.1.2 - Production Enhancements (Released - January 2026)

**Android Improvements**
- Configurable Foreground Service Type for Android 14+ compliance
- Enhanced Maven Central metadata for improved dependency resolution

**iOS Stability**
- Critical stability fixes for background task handling
- Improved exact-reminder task registration in BGTaskScheduler
- Enhanced task ID handling for better iOS compatibility

**Documentation & Build**
- Cleaned up internal documentation
- Improved Maven Central publishing workflow
- Enhanced POM file structure

### ✅ v2.1.1 - Critical Fixes & iOS Transparency (Released - January 2026)

**Coroutine Lifecycle Management**
- Fixed `GlobalScope` usage in queue compaction (now uses injected `CoroutineScope`)
- Better lifecycle management and testability
- Proper resource cleanup

**Thread Safety Improvements**
- Fixed race condition in `ChainExecutor.isShuttingDown` access
- All reads/writes now consistently protected by mutex
- Eliminated potential crashes during shutdown

**iOS Exact Alarm Transparency** ⭐
- New `ExactAlarmIOSBehavior` enum for explicit iOS exact alarm handling
- Three options: `SHOW_NOTIFICATION` (default), `ATTEMPT_BACKGROUND_RUN`, `THROW_ERROR`
- Addresses platform parity issues - iOS cannot execute background code at exact times
- Fail-fast option for development safety

**Migration**: Backward compatible - existing code continues to work with default behavior. See [CHANGELOG.md](CHANGELOG.md) for details.

### ✅ v2.1.0 - Performance & Graceful Shutdown (Released - January 2026)

**Performance Improvements**
- iOS queue operations 13-40x faster with O(1) append-only queue
- Automatic compaction at 80% threshold
- Graceful shutdown for iOS BGTask expiration with 5-second grace period

**See**: [CHANGELOG.md](CHANGELOG.md) for complete details.

### v2.2.0 - Event Persistence & Smart Retries (Q1 2026)

**Event Persistence System**
- Persistent storage for TaskCompletionEvents (survives app kills and force-quit)
- Automatic event replay on app launch
- Zero event loss even when UI isn't actively listening
- SQLDelight on Android, file-based storage on iOS

**Smart Retry Policies**
- Error-aware retry strategies (network failures vs. business logic errors)
- Exponential backoff with jitter and circuit breaker patterns
- Configurable max retry limits per task type
- Retry predicates based on error classification

**Platform Capabilities API**
```kotlin
expect object PlatformCapabilities {
    val supportsExactTiming: Boolean
    val supportsChargingConstraint: Boolean
    val maxTaskDuration: Duration
    val maxChainLength: Int
}
```

### v2.3.0 - Typed Results & Enhanced Observability (Q2 2026)

**Typed Result Data Passing**
- Workers return structured results, not just Boolean
- Type-safe data flow between chained tasks
- Automatic serialization with kotlinx.serialization
```kotlin
sealed class WorkResult {
    data class Success(val data: JsonElement?) : WorkResult()
    data class Failure(val error: WorkError, val shouldRetry: Boolean) : WorkResult()
}
```

**Task Execution History & Analytics**
- Query past task executions and their results
- Task statistics: success rate, average duration, failure patterns
- Optional SQLDelight persistence with configurable retention
- Useful for debugging and monitoring in production

**Advanced Testing Support**
- TestTaskScheduler for unit testing without actual execution
- Mock worker factories
- Test utilities for simulating background task scenarios
- Documentation with testing best practices

### v2.4.0 - Developer Experience & Tooling (Q3 2026)

**Annotation-Based Worker Discovery**
- `@Worker` annotation for automatic registration
- KSP plugin for compile-time worker factory generation
- Reduces boilerplate and human error

**Gradle Plugin**
- Validate iOS Info.plist configuration at build time
- Detect missing BGTaskSchedulerPermittedIdentifiers
- Generate platform capability reports

**Enhanced Debugging**
- Built-in task monitoring UI for development builds
- Real-time visualization of scheduled, running, and completed tasks
- Export task history for analysis

**Batch Operations API**
```kotlin
scheduler.enqueueBatch(
    listOf(
        TaskRequest("Worker1"),
        TaskRequest("Worker2"),
        TaskRequest("Worker3")
    )
)
```

### v3.0.0 - Advanced Features & Platform Expansion (Q4 2026)

**Desktop Support (JVM)**
- Windows, macOS, Linux support
- Use native OS schedulers (Task Scheduler, launchd, systemd)
- Shared codebase with mobile platforms

**Web/JS Support (Experimental)**
- Service Worker integration
- Background Sync API support
- Progressive Web App (PWA) compatibility

**Cloud Integration**
- Optional Firebase Cloud Messaging integration for iOS background wakeup
- Remote task scheduling via push notifications
- Server-driven configuration

**iOS 17+ Features**
- Background Assets framework integration for large downloads
- Extended background time under optimal conditions
- Better Low Power Mode handling with adaptive intervals

### Future Considerations

**Features Under Research:**
- Background data sync with conflict resolution
- Distributed task orchestration across devices
- ML-based optimal scheduling prediction
- Integration with Kotlin/Wasm for web workers

### Contributing to the Roadmap

Have a feature request or idea? We welcome community input:
- Open a [GitHub Issue](https://github.com/brewkits/kmp_worker/issues) with the `enhancement` label
- Join discussions on existing feature proposals
- Contribute PRs for planned features

Priority is given to:
1. Features solving real developer pain points
2. Cross-platform capabilities (not single-platform)
3. Improvements to reliability and resilience
4. Better developer experience and testing

---

## Version History

**v2.1.2** (Latest) - Production Enhancements
- Configurable foreground service type for Android 14+
- Critical iOS stability fixes
- Enhanced Maven Central metadata
- Improved documentation and build process

**v2.1.1** - Critical Fixes & iOS Transparency
- Fixed coroutine lifecycle management
- Thread safety improvements
- iOS exact alarm transparency with `ExactAlarmIOSBehavior`

**v2.1.0** - Performance & Graceful Shutdown
- iOS queue operations 13-40x faster
- Graceful shutdown for iOS BGTask expiration
- Automatic compaction at 80% threshold

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
