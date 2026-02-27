<div align="center">

<img src="kmpworkmanager.png?v=2" alt="KMP WorkManager" width="800px" />

# KMP WorkManager

**Background task scheduling for Kotlin Multiplatform**

Write once, run everywhere — Schedule background tasks on Android and iOS from shared code

[![Maven Central](https://img.shields.io/maven-central/v/dev.brewkits/kmpworkmanager?color=blue&label=maven%20central)](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.21-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-android%20%7C%20ios-lightgrey.svg)](https://kotlinlang.org/docs/multiplatform.html)

[Features](#features) •
[Installation](#installation) •
[Quick Start](#quick-start) •
[Documentation](#documentation) •
[Examples](#examples)

</div>

---

## The Problem

Building a multiplatform app that needs background task scheduling?

```kotlin
// ❌ Platform-specific nightmare
expect class BackgroundScheduler {
    fun schedule(task: Task)
}

// Android implementation uses WorkManager
// iOS implementation uses BGTaskScheduler
// Different APIs, different behaviors, double the code
```

## The Solution

```kotlin
// ✅ One API, both platforms
val scheduler = BackgroundTaskScheduler()

scheduler.enqueue(
    id = "sync-data",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "SyncWorker",
    constraints = Constraints(requiresNetwork = true)
)
```

No `expect`/`actual`. No platform checks. Just works.

---

## Features

### 🎯 **Unified API**
Single API for Android WorkManager and iOS BGTaskScheduler. Write scheduling logic once in common code.

### ⛓️ **Task Chains**
Sequential task execution with automatic state recovery. If interrupted, chains resume where they left off.

```kotlin
scheduler.beginWith(TaskRequest("Download"))
    .then(TaskRequest("Process"))
    .then(TaskRequest("Upload"))
    .enqueue()
```

### 🔄 **Multiple Task Types**
- **One-time**: Execute once, optionally delayed
- **Periodic**: Run every N minutes (min 15min)
- **Exact**: Precise timing on Android via AlarmManager
- **Chains**: Multi-step workflows with state persistence

### 📦 **Pre-built Workers**
Ready-to-use workers for common tasks:
- `HttpRequestWorker` — Make HTTP calls
- `HttpDownloadWorker` — Download files
- `HttpUploadWorker` — Upload files
- `HttpSyncWorker` — Sync data
- `FileCompressionWorker` — Compress files

### 🔒 **Security First**
Built-in SSRF protection, input validation, and resource limits (v2.3.1+).

### ⚡ **High Performance**
- 60-86% faster HTTP operations via singleton client (v2.3.4+)
- O(1) queue operations on iOS
- Efficient memory usage with streaming I/O

### 📊 **Rich Result Data**
Workers return structured results with custom data (v2.3.0+):

```kotlin
override suspend fun doWork(input: String?): WorkerResult {
    val result = uploadFile()
    return WorkerResult.Success(
        message = "Upload complete",
        data = mapOf("fileSize" to result.size, "duration" to result.duration)
    )
}
```

---

## Installation

Add to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brewkits:kmpworkmanager:2.3.4")
        }
    }
}
```

### Platform Setup

<details>
<summary><b>Android</b> — Initialize in Application class</summary>

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KmpWorkManager.initialize(
            context = this,
            config = KmpWorkManagerConfig(
                logLevel = Logger.Level.INFO
            )
        )
    }
}
```

</details>

<details>
<summary><b>iOS</b> — Register task handlers in App init</summary>

```swift
import KMPWorkManager

@main
struct MyApp: App {
    init() {
        KmpWorkManager.shared.initialize(
            config: KmpWorkManagerConfig(
                logLevel: .info
            )
        )
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

Add to `Info.plist`:
```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>kmp_chain_executor_task</string>
</array>
```

</details>

**→ Full setup guide:** [Platform Setup Documentation](docs/platform-setup.md)

---

## Quick Start

### 1. Create a Worker

```kotlin
// commonMain
class DataSyncWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        val api = ApiClient()
        val data = api.fetchLatestData()

        database.save(data)

        return WorkerResult.Success(
            message = "Synced ${data.size} items",
            data = mapOf("count" to data.size)
        )
    }
}
```

Platform-specific implementations:

```kotlin
// androidMain
class DataSyncWorkerAndroid : AndroidWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        return DataSyncWorker().doWork(input)
    }
}

// iosMain
class DataSyncWorkerIos : IosWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        return DataSyncWorker().doWork(input)
    }
}
```

### 2. Schedule the Task

```kotlin
// In your shared code
val scheduler = BackgroundTaskScheduler()

scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000), // 15 minutes
    workerClassName = "DataSyncWorker",
    constraints = Constraints(
        requiresNetwork = true,
        requiresCharging = false
    )
)
```

### 3. Done!

The task will run every 15 minutes on both Android and iOS, only when network is available.

---

## Platform Comparison

| Feature | Android | iOS |
|---------|---------|-----|
| **One-time tasks** | ✅ WorkManager | ✅ BGTaskScheduler |
| **Periodic tasks** | ✅ Min 15 minutes | ✅ Opportunistic |
| **Exact timing** | ✅ AlarmManager | ❌ Not available |
| **Task chains** | ✅ WorkContinuation | ✅ With state recovery |
| **Network constraint** | ✅ Enforced | ⚠️ Best effort |
| **Battery constraint** | ✅ Enforced | ⚠️ System decides |
| **Runs when app closed** | ✅ Yes | ⚠️ If not force-quit |

**iOS Limitations:**
- System decides when to run tasks (opportunistic scheduling)
- Force-quit cancels all pending tasks
- Execution may be delayed for hours
- Not suitable for time-critical operations

---

## Use Cases

<table>
<tr>
<td width="33%">

**📊 Data Synchronization**

Sync user data with your server periodically, only when connected to WiFi.

```kotlin
scheduler.enqueue(
    id = "sync",
    trigger = Periodic(15.minutes),
    constraints = Constraints(
        requiresNetwork = true,
        requiresCharging = false
    )
)
```

</td>
<td width="33%">

**📤 Background Uploads**

Upload photos/videos when device is charging and on WiFi.

```kotlin
scheduler.enqueue(
    id = "upload",
    workerClassName = "UploadWorker",
    constraints = Constraints(
        requiresNetwork = true,
        requiresCharging = true
    )
)
```

</td>
<td width="33%">

**⛓️ Multi-step Workflows**

Chain tasks together with automatic retry on failure.

```kotlin
scheduler.beginWith(
    TaskRequest("Download")
).then(
    TaskRequest("Process")
).then(
    TaskRequest("Upload")
).enqueue()
```

</td>
</tr>
</table>

---

## Documentation

📘 **Getting Started**
- [Quick Start Guide](docs/quickstart.md) — Get running in 5 minutes
- [Platform Setup](docs/platform-setup.md) — Android & iOS configuration
- [Migration Guide](docs/MIGRATION_V2.3.3_TO_V2.3.4.md) — Upgrading from v2.3.3

📖 **Core Concepts**
- [API Reference](docs/api-reference.md) — Complete API documentation
- [Task Chains](docs/task-chains.md) — Sequential workflows
- [Built-in Workers](docs/BUILTIN_WORKERS_GUIDE.md) — Pre-built workers

🎯 **Platform-Specific**
- [iOS Best Practices](docs/ios-best-practices.md) — iOS background task tips
- [Android Configuration](docs/platform-setup.md#android) — Android setup details

---

## Examples

Check the [`/composeApp`](composeApp/) directory for a complete demo app with:

- ✅ Single task scheduling
- ✅ Chain execution examples
- ✅ Built-in worker usage
- ✅ Error handling patterns
- ✅ Real device testing

---

## What's New in v2.3.4

🚀 **Performance**
- 60-86% faster HTTP operations (singleton HttpClient with connection pooling)
- 90% less progress data loss on iOS (reduced flush interval: 500ms → 100ms)

🐛 **Fixes**
- Fixed iOS queue compaction segmentation fault
- Fixed Logger test assertions
- Added proper shutdown mechanism for background operations

⚠️ **Breaking Changes**
- `TaskChain.enqueue()` is now suspending

**→ Full changelog:** [CHANGELOG.md](CHANGELOG.md)

**→ Migration guide:** [MIGRATION_V2.3.3_TO_V2.3.4.md](docs/MIGRATION_V2.3.3_TO_V2.3.4.md)

---

## Requirements

| Component | Version |
|-----------|---------|
| Kotlin | 2.1.21+ |
| Android | 7.0+ (API 24) |
| iOS | 13.0+ |
| Gradle | 8.0+ |

**Dependencies:**
- AndroidX WorkManager (Android)
- Koin (Dependency Injection)
- Ktor (HTTP operations)
- kotlinx.coroutines
- kotlinx.serialization

---

## Architecture

```
┌─────────────────────────────────────┐
│     Common API (Multiplatform)      │
│   BackgroundTaskScheduler Interface │
└─────────────┬───────────────────────┘
              │
      ┌───────┴────────┐
      │                │
┌─────▼─────┐    ┌────▼─────┐
│  Android  │    │   iOS    │
│ WorkManager│    │BGTask    │
│            │    │Scheduler │
└────────────┘    └──────────┘
```

All scheduling logic lives in common code. Platform implementations handle OS-specific details transparently.

---

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

**Before submitting:**
- Run tests: `./gradlew test`
- Check code style: `./gradlew ktlintCheck`
- Update documentation if needed

---

## License

```
Copyright 2024-2026 Nguyễn Tuấn Việt

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

---

## Support

💬 **Need help?**
- 📖 [Documentation](docs/)
- 🐛 [Report Issues](https://github.com/brewkits/kmpworkmanager/issues)
- 💡 [Discussions](https://github.com/brewkits/kmpworkmanager/discussions)
- 📧 Email: datacenter111@gmail.com

⭐ **Like this project?** Give it a star on GitHub!

---

<div align="center">

**Built with ❤️ by [Nguyễn Tuấn Việt](https://github.com/brewkits)**

[GitHub](https://github.com/brewkits/kmpworkmanager) •
[Maven Central](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager) •
[Documentation](docs/)

</div>
