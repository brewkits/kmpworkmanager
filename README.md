# KMP WorkManager

<div align="center">

### Background Task Scheduling for Kotlin Multiplatform

Unified API for scheduling background tasks on Android and iOS with shared logic.

<img src="kmpworkmanager.png?v=2" alt="KMP WorkManager" width="100%" />

[![Maven Central](https://img.shields.io/maven-central/v/dev.brewkits/kmpworkmanager)](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-blue.svg)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-green.svg)](https://kotlinlang.org/docs/multiplatform.html)

</div>

---

## What is KMP WorkManager?

KMP WorkManager provides a unified API for scheduling and managing background tasks in Kotlin Multiplatform projects. It wraps Android's WorkManager and iOS's BGTaskScheduler with a shared interface.

### Key Features

- **Unified Scheduling API**: Single interface for both platforms
- **Multiple Task Types**: One-time, periodic, exact timing (Android), and chained tasks
- **WorkerResult API (v2.3.0+)**: Return structured data from workers
- **Security Hardened (v2.3.1+)**: SSRF protection, resource limits
- **Chain State Restoration**: Resume iOS task chains after app termination
- **Built-in Workers**: HTTP requests, file operations, sync tasks
- **Chain IDs (v2.3.0+)**: Prevent duplicate task execution

### Platform Implementation

| Feature | Android | iOS |
|---------|---------|-----|
| **One-Time Tasks** | ✅ WorkManager | ✅ BGTaskScheduler |
| **Periodic Tasks** | ✅ Native (≥15min) | ✅ Opportunistic |
| **Exact Timing** | ✅ AlarmManager | ❌ Not supported |
| **Task Chains** | ✅ WorkContinuation | ✅ With state restoration |
| **Background Constraints** | ✅ Network, battery, storage | ⚠️ Limited by iOS |

**Important iOS Limitations:**
- Background tasks are **opportunistic** - iOS decides when to run them
- Force-quit app = all background tasks cancelled
- Not suitable for time-critical operations
- Tasks may be delayed for hours based on system conditions

---

## Quick Start

### Installation

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brewkits:kmpworkmanager:2.3.1")
        }
    }
}
```

### Platform Setup

**Android** - Initialize in `Application.onCreate()`:
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

**iOS** - Initialize in your App:
```swift
import kmpworker

@main
struct MyApp: App {
    init() {
        KmpWorkManagerIos.shared.initialize(
            workerFactory: IosWorkerFactory()
        )
    }
    
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

### Define a Worker

**Note:** You need platform-specific worker implementations.

**Android**:
```kotlin
class SyncWorker : AndroidWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        // Android implementation
        return WorkerResult.Success(
            message = "Synced 150 items",
            data = mapOf("itemCount" to 150)
        )
    }
}
```

**iOS**:
```kotlin
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        // iOS implementation
        return WorkerResult.Success(
            message = "Synced 150 items",
            data = mapOf("itemCount" to 150)
        )
    }
}
```

### Schedule a Task

```kotlin
// Shared code
scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000), // 15 minutes
    workerClassName = "SyncWorker"
)
```

---

## WorkerResult API (v2.3.0+)

Workers can now return structured data:

```kotlin
sealed class WorkerResult {
    data class Success(
        val message: String? = null,
        val data: Map<String, Any?>? = null
    ) : WorkerResult()
    
    data class Failure(
        val message: String
    ) : WorkerResult()
}
```

**Example:**
```kotlin
class UploadWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        return try {
            val result = uploadFile()
            WorkerResult.Success(
                message = "Upload completed",
                data = mapOf(
                    "fileSize" to result.size,
                    "uploadTime" to result.duration
                )
            )
        } catch (e: Exception) {
            WorkerResult.Failure("Upload failed: ${e.message}")
        }
    }
}
```

---

## Task Chains

Chain multiple tasks with automatic sequencing:

```kotlin
scheduler.beginWith(
    TaskRequest(workerClassName = "DownloadWorker")
)
.then(
    TaskRequest(workerClassName = "ProcessWorker")
)
.then(
    TaskRequest(workerClassName = "UploadWorker")
)
.withId("download-process-upload", policy = ExistingPolicy.KEEP)
.enqueue()
```

**iOS Feature**: Chains automatically save state and resume from last successful step if app is terminated.

---

## Built-in Workers (v2.3.0+)

Pre-built workers for common tasks:

- **HttpRequestWorker** - Make HTTP requests with custom headers
- **HttpSyncWorker** - Sync data via HTTP GET/POST
- **HttpDownloadWorker** - Download files to local storage
- **HttpUploadWorker** - Upload files to remote server
- **FileCompressionWorker** - Compress files/directories

See [Built-in Workers Guide](docs/BUILTIN_WORKERS_GUIDE.md) for usage.

---

## Documentation

- [Quick Start Guide](docs/quickstart.md)
- [Platform Setup](docs/platform-setup.md)
- [API Reference](docs/api-reference.md)
- [Task Chains](docs/task-chains.md)
- [Built-in Workers](docs/BUILTIN_WORKERS_GUIDE.md)
- [iOS Best Practices](docs/ios-best-practices.md)
- [Migration Guide v2.3.0](docs/MIGRATION_V2.3.0.md)

---

## Requirements

- **Kotlin**: 2.1.21+
- **Android**: minSdk 24 (Android 7.0)
- **iOS**: iOS 13.0+
- **Dependency Injection**: Requires Koin for initialization

---

## Migration from v2.2.x

v2.3.0 is **100% backward compatible**. Workers returning `Boolean` still work:

```kotlin
// Old (still works)
override suspend fun doWork(input: String?): Boolean = true

// New (recommended)
override suspend fun doWork(input: String?): WorkerResult {
    return WorkerResult.Success()
}
```

See [Migration Guide](docs/MIGRATION_V2.3.0.md) for details.

---

## Important Considerations

### Dependencies

- **Koin**: Required for dependency injection (added automatically)
- If your project uses Hilt/Dagger, you'll have both DI frameworks

### Platform-Specific Code

Despite shared scheduling logic, you still need:
- Separate `AndroidWorker` and `IosWorker` implementations
- Platform-specific initialization code
- Platform-specific worker factories

### iOS Background Execution

iOS background tasks are **not guaranteed** to run:
- System decides when/if to execute based on battery, usage patterns
- Tasks may be delayed by hours
- Force-quit stops all background processing
- Not suitable for critical time-sensitive operations

---

## Examples

See `/composeApp` directory for complete demo app with:
- Single task examples
- Chain examples  
- Built-in worker demos
- Error handling examples

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for version history.

**Latest: v2.3.0** (2026-02-07)
- WorkerResult API for structured data
- Built-in workers with data return
- Chain IDs and ExistingPolicy
- Demo UX improvements

---

## Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Submit a Pull Request

---

## License

```
Copyright 2026 Nguyễn Tuấn Việt

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

## Author

**Nguyễn Tuấn Việt**
- Email: datacenter111@gmail.com
- GitHub: [@brewkits](https://github.com/brewkits)

---

## Support

- 📖 [Documentation](docs/)
- 🐛 [Issue Tracker](https://github.com/brewkits/kmpworkmanager/issues)
- 💬 [Discussions](https://github.com/brewkits/kmpworkmanager/discussions)

