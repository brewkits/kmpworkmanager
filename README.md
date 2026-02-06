# KMP WorkManager

<div align="center">

### The Only Background Task Manager You'll Ever Need for Kotlin Multiplatform

**Write once, run everywhere.** Stop maintaining separate background task logic for Android and iOS.

<img src="kmpworkmanager.png?v=2" alt="KMP WorkManager" width="100%" />

[![Maven Central](https://img.shields.io/maven-central/v/dev.brewkits/kmpworkmanager)](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-blue.svg)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-green.svg)](https://kotlinlang.org/docs/multiplatform.html)

</div>

---

## Why KMP WorkManager?

### 🎯 **The Problem**
Building cross-platform apps? You're probably:
- Writing background task logic **twice** (Android WorkManager + iOS BGTaskScheduler)
- Debugging **platform-specific issues** that waste days
- Struggling with iOS's **unpredictable** background execution
- Dealing with **data corruption** when tasks are killed mid-execution
- Missing **observability** - "Why didn't my task run?"

### ✨ **The Solution**
KMP WorkManager gives you **one unified API** for both platforms with:

- **Write Once, Run Everywhere**: Share 100% of your background task logic
- **Production-Ready Stability**: 100+ tests, zero data corruption, self-healing architecture
- **Smart iOS Handling**: Automatic time-slicing, state restoration, graceful shutdown
- **Built-in Diagnostics**: Know exactly why tasks succeed or fail
- **Battle-Tested Performance**: Handles 10,000+ queued tasks, survives low-memory conditions

### 🚀 **Get Started in 60 Seconds**

```kotlin
// 1. Add dependency
implementation("dev.brewkits:kmpworkmanager:2.2.2")

// 2. Define your worker (shared code!)
class SyncWorker : CommonWorker {
    override suspend fun doWork(input: String?): Boolean {
        // Your logic runs on BOTH platforms
        return true
    }
}

// 3. Schedule it
scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "SyncWorker"
)
```

**That's it.** No platform-specific code. No headaches.

---

## Installation

Add to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brewkits:kmpworkmanager:2.2.2")
        }
    }
}
```

### Platform Setup

**Android**: Initialize in `Application.onCreate()`:
```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KmpWorkManager.initialize(
            context = this,
            workerFactory = MyWorkerFactory()
        )
    }
}
```

**iOS**: Initialize in Swift:
```swift
// AppDelegate or App struct
KoinModuleKt.doInitKoinIos(workerFactory: MyWorkerFactory())
```

See [Platform Setup Guide](docs/platform-setup.md) for details.

## Key Features

### Unified API
Write background task logic once, run on both platforms:
- **Android**: WorkManager + AlarmManager integration
- **iOS**: BGTaskScheduler with intelligent time-slicing

### Task Types
- **Periodic**: Recurring tasks (15 min minimum)
- **One-Time**: Execute once with optional delay
- **Windowed**: Run within time window
- **Exact**: Precise timing (Android only)

### Task Chains
Execute tasks sequentially or in parallel:
```kotlin
// Sequential
scheduler.beginWith(TaskRequest("Download"))
    .then(TaskRequest("Process"))
    .then(TaskRequest("Upload"))
    .enqueue()

// Parallel
scheduler.beginWith(listOf(
    TaskRequest("FetchUsers"),
    TaskRequest("FetchPosts")
))
    .then(TaskRequest("Merge"))
    .enqueue()
```

### Constraints
Control when tasks run:
```kotlin
Constraints(
    requiresNetwork = true,
    requiresCharging = true,
    requiresUnmeteredNetwork = true,
    systemConstraints = setOf(
        SystemConstraint.REQUIRE_BATTERY_NOT_LOW
    )
)
```

### Progress Tracking
Real-time worker progress updates:
```kotlin
class DownloadWorker(
    private val progressListener: ProgressListener?
) : Worker {
    override suspend fun doWork(input: String?): Boolean {
        progressListener?.onProgressUpdate(
            WorkerProgress(progress = 50, message = "Downloading...")
        )
        return true
    }
}
```

## Real-World Use Cases

Perfect for apps that need reliable background execution:

- 📊 **Data Sync**: Periodic upload/download of user data
- 📸 **Media Processing**: Compress photos, generate thumbnails
- 🔔 **Analytics Events**: Batch send analytics when network available
- 💾 **Database Maintenance**: Cleanup old records, compact storage
- 🔄 **API Polling**: Check for updates at regular intervals
- 📦 **File Uploads**: Upload large files with retry logic

## What Makes It Different?

| Feature | KMP WorkManager | Native Solutions |
|---------|-----------------|------------------|
| **Code Sharing** | ✅ 100% shared logic | ❌ Duplicate code for each platform |
| **iOS State Restoration** | ✅ Automatic chain resumption | ❌ Manual implementation required |
| **Data Corruption Protection** | ✅ CRC32 validation + atomic writes | ⚠️ Manual error handling |
| **Diagnostics API** | ✅ Built-in health checks | ❌ Build your own |
| **Task Chains** | ✅ Sequential & parallel support | ⚠️ Complex custom logic |
| **Production Ready** | ✅ Battle-tested (100+ tests) | ⚠️ Your responsibility |

## Enterprise Features

- **Production Stability**: Zero data corruption, self-healing queue, graceful shutdown
- **Data Integrity**: CRC32 validation, atomic operations, transaction logs
- **High Performance**: Native CRC32, buffered I/O, optimized queue (O(1) startup)
- **Observability**: Diagnostics API, configurable logging, custom logger support
- **iOS Optimization**: Adaptive time-slicing, state restoration, cancellation safety
- **Android 14+ Support**: Compatible with all ROM variants, Koin isolation

## Platform Support

| Feature | Android | iOS |
|---------|---------|-----|
| Periodic Tasks | ✅ | ✅ (opportunistic) |
| One-Time Tasks | ✅ | ✅ |
| Exact Timing | ✅ | ❌ |
| Task Chains | ✅ | ✅ (with state restoration) |
| Network Constraints | ✅ | ✅ |
| Charging Constraints | ✅ | ❌ |

> **iOS Limitations**: Background tasks are opportunistic and may be delayed hours or never run. Force-quit kills all tasks. Not suitable for time-critical operations. See [iOS Best Practices](docs/ios-best-practices.md).

## Documentation

- [Quick Start Guide](docs/quickstart.md)
- [Platform Setup](docs/platform-setup.md)
- [API Reference](docs/api-reference.md)
- [Task Chains](docs/task-chains.md)
- [iOS Best Practices](docs/ios-best-practices.md) ⚠️ **Important**
- [Release Notes](docs/V2.2.2_RELEASE_NOTES.md)
- [Migration Guide](docs/MIGRATION_V2.2.2.md)
- [Architecture](docs/ARCHITECTURE.md)

## Production Track Record

**Latest: v2.2.2** (February 2026) - 16 bug fixes for ultimate stability:
- ✅ Zero thread safety issues
- ✅ No OOM on low-memory devices
- ✅ 100% backward compatible
- ✅ 40+ new integration tests

**Previous Milestones:**
- **v2.2.0** - Production-ready release with self-healing architecture
- **v2.0.0** - Maven Central publication (`dev.brewkits`)
- **v1.0.0** - Initial stable release

See [Release Notes](docs/V2.2.2_RELEASE_NOTES.md) | [Full Changelog](CHANGELOG.md) | [Roadmap](docs/ROADMAP.md)

## Ready to Simplify Your Background Tasks?

**Start building in minutes:**

1. Add dependency: `implementation("dev.brewkits:kmpworkmanager:2.2.2")`
2. Read [Quick Start Guide](docs/quickstart.md)
3. Check [Sample App](https://github.com/brewkits/kmp_worker) for examples

**Need help?** Open an [issue](https://github.com/brewkits/kmp_worker/issues) or email datacenter111@gmail.com

---

## Requirements

- Kotlin 2.1.21+
- Android: API 21+ (Android 5.0)
- iOS: 13.0+
- Gradle 8.0+

## License

```
Copyright 2026 Brewkits

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

See [LICENSE](LICENSE) for full text.

## Community & Support

- 📦 [Maven Central](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager)
- 🐛 [Report Issues](https://github.com/brewkits/kmp_worker/issues)
- 📝 [Changelog](CHANGELOG.md)
- 🗺️ [Roadmap](docs/ROADMAP.md)

**Found a bug?** We fix critical issues within 48 hours.
**Have a feature request?** Community feedback shapes our roadmap.
**Need enterprise support?** Contact datacenter111@gmail.com

---

<div align="center">

**Built with ❤️ by [Nguyễn Tuấn Việt](mailto:datacenter111@gmail.com) at Brewkits**

*Making Kotlin Multiplatform background tasks simple and reliable*

⭐ **Star this repo** if KMP WorkManager saves you time!

</div>
