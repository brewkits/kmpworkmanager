# KMP Worker

A Kotlin Multiplatform library for scheduling and managing background tasks on Android and iOS with a unified API.

[![Maven Central](https://img.shields.io/maven-central/v/io.brewkits/kmpworker)](https://central.sonatype.com/artifact/io.brewkits/kmpworker)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

## Overview

KMP Worker provides a single, consistent API for background task scheduling across Android and iOS platforms. It abstracts away platform-specific implementations (WorkManager on Android, BGTaskScheduler on iOS) and lets you write your background task logic once in shared Kotlin code.

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

With KMP Worker, you write your scheduling logic once:

```kotlin
scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "SyncWorker",
    constraints = Constraints(requiresNetwork = true)
)
```

The library handles platform-specific details automatically.

## Installation

Add to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.brewkits:kmpworker:4.0.0")
        }
    }
}
```

Or using version catalog:

```toml
[versions]
kmpworker = "4.0.0"

[libraries]
kmpworker = { module = "io.brewkits:kmpworker", version.ref = "kmpworker" }
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

**Exact Alarms** (Android)
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

## Platform-Specific Features

### Android

- WorkManager integration for deferrable tasks
- AlarmManager for exact timing requirements
- Foreground service support for long-running tasks
- ContentUri triggers for media monitoring
- Automatic fallback when exact alarms permission is denied

### iOS

- BGTaskScheduler integration
- Automatic re-scheduling of periodic tasks
- File-based storage for better performance
- Thread-safe task execution
- Timeout protection

**Important iOS Limitation**: iOS background tasks are opportunistic. The system decides when to run them based on device usage, battery level, and other factors. Tasks may be delayed or never run if the app is rarely used. Do not rely on iOS background tasks for time-critical operations.

## Platform Support Matrix

| Feature | Android | iOS |
|---------|---------|-----|
| Periodic Tasks | Supported (15 min minimum) | Supported (opportunistic) |
| One-Time Tasks | Supported | Supported |
| Exact Timing | Supported (AlarmManager) | Not supported |
| Task Chains | Supported | Supported |
| Network Constraints | Supported | Supported |
| Charging Constraints | Supported | Not supported |
| Battery Constraints | Supported | Not supported |
| ContentUri Triggers | Supported | Not supported |

## Documentation

- [Quick Start Guide](docs/quickstart.md)
- [Migration Guide v3 to v4](docs/MIGRATION_V4.md)
- [Platform Setup](docs/platform-setup.md)
- [API Reference](docs/api-reference.md)
- [Task Chains](docs/task-chains.md)
- [Architecture Overview](ARCHITECTURE.md)

## Version History

**v4.0.0** (Latest)
- Worker factory pattern for better extensibility
- Automatic iOS task ID validation from Info.plist
- Type-safe serialization extensions
- Breaking changes from v3.x - see [migration guide](docs/MIGRATION_V4.md)

**v3.0.0**
- File-based storage on iOS (60% faster)
- Smart exact alarm fallback on Android
- Heavy task support with foreground services
- Improved API with SystemConstraint

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

## Links

- [Maven Central](https://central.sonatype.com/artifact/io.brewkits/kmpworker)
- [GitHub Issues](https://github.com/brewkits/kmp_worker/issues)
- [Changelog](CHANGELOG.md)

---

**Author**: Nguyễn Tuấn Việt
**Organization**: [Brewkits](https://github.com/brewkits)
**Contact**: vietnguyentuan@gmail.com
