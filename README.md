# KMP WorkManager

<div align="center">

### Background Tasks for Kotlin Multiplatform

Schedule background work on Android and iOS with the same code.

<img src="kmpworkmanager.png?v=2" alt="KMP WorkManager" width="100%" />

[![Maven Central](https://img.shields.io/maven-central/v/dev.brewkits/kmpworkmanager)](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-blue.svg)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-green.svg)](https://kotlinlang.org/docs/multiplatform.html)

</div>

---

## Why This Exists

Building a KMP app and need to sync data in the background? Download files while the app is closed? Run periodic tasks?

On Android you'd use WorkManager. On iOS you'd use BGTaskScheduler. Different APIs, different behavior, lots of platform-specific code.

This library gives you one API that works on both. Write your scheduling logic once in common code.

---

## What You Get

**One API for both platforms** - Schedule tasks from shared code, no expect/actual needed

**Multiple task types**
- One-time tasks (run once, with optional delay)
- Periodic tasks (every X minutes)
- Exact timing on Android (via AlarmManager)
- Task chains (run multiple tasks in sequence)

**Return data from workers** (v2.3.0+) - Workers can return structured results with custom data

**Security built-in** (v2.3.1+) - SSRF protection, input validation, resource limits

**Chain state recovery on iOS** - If your app gets killed, chains resume from where they left off

**Pre-built workers** - HTTP requests, downloads, uploads, file compression

**Chain IDs** - Prevent duplicate execution with automatic deduplication

---

## Platform Details

| Feature | Android | iOS |
|---------|---------|-----|
| One-time tasks | ✓ WorkManager | ✓ BGTaskScheduler |
| Periodic tasks | ✓ Native (min 15min) | ✓ Opportunistic |
| Exact timing | ✓ AlarmManager | ✗ Not supported |
| Task chains | ✓ WorkContinuation | ✓ With state recovery |
| Constraints | ✓ Network, battery, storage | Limited by iOS |

**About iOS background tasks:**
- iOS decides when to run them (opportunistic scheduling)
- If user force-quits your app, all tasks are cancelled
- Tasks can be delayed for hours based on system conditions
- Don't use this for time-critical work

---

## Quick Example

```kotlin
// In your shared code
val scheduler = BackgroundTaskScheduler()

// Schedule a periodic sync (runs every 15 minutes)
scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "SyncWorker",
    constraints = Constraints(requiresNetwork = true)
)

// Chain multiple tasks
scheduler.beginWith(
    TaskRequest(workerClassName = "DownloadWorker")
).then(
    TaskRequest(workerClassName = "ProcessWorker")
).then(
    TaskRequest(workerClassName = "UploadWorker")
).enqueue()
```

**Worker implementation:**

```kotlin
class SyncWorker : AndroidWorker {  // or IosWorker for iOS
    override suspend fun doWork(input: String?): WorkerResult {
        val data = fetchDataFromServer()
        saveToDatabase(data)

        return WorkerResult.Success(
            message = "Synced ${data.size} items",
            data = mapOf("count" to data.size)
        )
    }
}
```

---

## Installation

Add to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brewkits:kmpworkmanager:2.3.2")
        }
    }
}
```

**Setup required:**
- **Android:** Initialize in `Application.onCreate()`
- **iOS:** Register task handlers in your App init

See [Quick Start Guide](docs/quickstart.md) for complete setup instructions.

---

## Real-World Use Cases

**Periodic data sync**
Sync user data with your server every hour, only when connected to WiFi.

**Background uploads**
Upload photos/videos in the background, with automatic retry on failure.

**Multi-step workflows**
Download file → Process it → Upload results. If any step fails, retry with exponential backoff.

**Offline-first apps**
Queue up changes while offline, sync when network becomes available.

**Analytics batching**
Collect events locally, upload in batches to reduce network usage.

---

## WorkerResult API

New in v2.3.0 - workers can return structured data:

```kotlin
class UploadWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        return try {
            val result = uploadFile()
            WorkerResult.Success(
                message = "Upload complete",
                data = mapOf(
                    "fileSize" to result.size,
                    "duration" to result.duration
                )
            )
        } catch (e: Exception) {
            WorkerResult.Failure("Upload failed: ${e.message}")
        }
    }
}
```

Old Boolean return type still works (100% backward compatible).

---

## Built-in Workers

Pre-built workers for common tasks:

- `HttpRequestWorker` - Make HTTP requests
- `HttpSyncWorker` - Sync data via GET/POST
- `HttpDownloadWorker` - Download files
- `HttpUploadWorker` - Upload files
- `FileCompressionWorker` - Compress files/directories

See [Built-in Workers Guide](docs/BUILTIN_WORKERS_GUIDE.md) for details.

---

## Documentation

- [Quick Start Guide](docs/quickstart.md) - Get running in 5 minutes
- [Platform Setup](docs/platform-setup.md) - Android & iOS configuration
- [API Reference](docs/api-reference.md) - Complete API docs
- [Task Chains](docs/task-chains.md) - Sequential & parallel workflows
- [iOS Best Practices](docs/ios-best-practices.md) - iOS-specific tips
- [Migration Guides](docs/) - Upgrading from older versions

---

## Requirements

- Kotlin 2.1.21+
- Android 7.0+ (API 24)
- iOS 13.0+
- Koin for dependency injection (included automatically)

---

## Things to Know

**You still need platform-specific workers**

Despite shared scheduling code, you need separate `AndroidWorker` and `IosWorker` implementations. This is because the actual work often requires platform APIs (file system, network, etc).

**Koin is included**

This library uses Koin for DI. If your project uses Hilt or Dagger, you'll have both DI frameworks (Koin just for this library, yours for your app). They don't conflict.

**iOS background execution is unpredictable**

iOS decides when (and if) to run your tasks based on:
- Battery level
- User behavior patterns
- System load
- Whether user force-quit the app (if yes, tasks won't run)

Don't rely on iOS background tasks for critical operations.

---

## Examples

Check the `/composeApp` directory for a complete demo app with:
- Single task examples
- Chain examples
- Built-in worker demos
- Error handling patterns

---

## What's New

**v2.3.2** (February 16, 2026)
- Property-based testing with Kotest (1000+ generated test cases)
- Chinese ROM compatibility tests (MIUI, EMUI, ColorOS, FuntouchOS)
- Mutation testing framework
- Low-end device benchmarks (budget Android phones + older iPhones)
- 100% backward compatible

See [CHANGELOG.md](CHANGELOG.md) for full history.

---

## Contributing

Pull requests welcome! Please:
1. Fork the repo
2. Create a feature branch
3. Submit a PR

---

## License

Apache License 2.0 - see [LICENSE](LICENSE) file.

```
Copyright 2026 Nguyễn Tuấn Việt
```

---

## Need Help?

- 📖 [Documentation](docs/)
- 🐛 [Report Issues](https://github.com/brewkits/kmpworkmanager/issues)
- 💬 [Discussions](https://github.com/brewkits/kmpworkmanager/discussions)

---

**Made by Nguyễn Tuấn Việt**
📧 datacenter111@gmail.com
🐙 [@brewkits](https://github.com/brewkits)
