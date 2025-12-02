# KMP TaskManager

<div align="center">

![KMP TaskManager Banner](https://img.shields.io/badge/KMP-TaskManager-blue?style=for-the-badge&logo=kotlin)

**Unified background task scheduling for Kotlin Multiplatform**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.vietnguyentuan2019/kmptaskmanager?style=flat-square)](https://central.sonatype.com/artifact/io.github.vietnguyentuan2019/kmptaskmanager)
[![Build](https://img.shields.io/github/actions/workflow/status/vietnguyentuan2019/KMPTaskManager/build.yml?style=flat-square)](https://github.com/vietnguyentuan2019/KMPTaskManager/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-purple?style=flat-square&logo=kotlin)](http://kotlinlang.org)
[![klibs.io](https://img.shields.io/badge/Kotlin%20Multiplatform-klibs.io-blue?style=flat-square)](https://klibs.io/package/io.github.vietnguyentuan2019/kmptaskmanager)

[Features](#-features) • [Installation](#-installation) • [Quick Start](#-quick-start) • [Documentation](#-documentation) • [Comparison](#-why-kmp-taskmanager)

</div>

---

## 🎯 Overview

KMP TaskManager is a production-ready Kotlin Multiplatform library that provides a **single, unified API** for scheduling and managing background tasks across Android and iOS. It abstracts platform-specific complexity while maintaining native performance and capabilities.

**Write once, schedule anywhere.** Define your background tasks in `commonMain` and let KMP TaskManager handle the platform-specific implementation using WorkManager (Android) and BGTaskScheduler (iOS).

```kotlin
// One API, two platforms
scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 15_MINUTES),
    workerClassName = "SyncWorker",
    constraints = Constraints(requiresNetwork = true)
)
```

---

## ✨ Features

### 🎯 Core Capabilities

- **🔄 9 Trigger Types** - OneTime, Periodic, Exact, Windowed, ContentUri, BatteryLow, BatteryOkay, StorageLow, DeviceIdle
- **⛓️ Task Chains** - Sequential and parallel task execution with fluent API
- **🎛️ Rich Constraints** - Network, battery, charging, storage, device idle conditions
- **♻️ Smart Retry** - Configurable backoff policies (EXPONENTIAL/LINEAR)
- **⚡ QoS Priority** - Task priority levels (HIGH/DEFAULT/LOW)
- **🎪 Event System** - Real-time task completion events via SharedFlow
- **📊 Professional Logging** - 4-level logging system with organized tags
- **🔍 Debug Tools** - Built-in task monitoring and status visualization

### 🤖 Android Platform

- ✅ WorkManager integration with expedited work support
- ✅ AlarmManager for exact scheduling
- ✅ ContentUri triggers (monitor MediaStore, Contacts, etc.)
- ✅ Foreground service support for long-running tasks
- ✅ Automatic notification channel management
- ✅ POST_NOTIFICATIONS permission handling (Android 13+)

### 🍎 iOS Platform

- ✅ BGTaskScheduler integration (BGAppRefreshTask + BGProcessingTask)
- ✅ Task ID validation against Info.plist
- ✅ Batch chain execution (3x efficiency)
- ✅ Timeout protection (prevents iOS throttling)
- ✅ Configurable task identifiers (runtime via Koin)
- ✅ Silent APNs for server-triggered execution

---

## 📦 Installation

### Gradle (Kotlin DSL)

```kotlin
// Add to your commonMain dependencies
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.vietnguyentuan2019:kmptaskmanager:2.2.0")
        }
    }
}
```

### Gradle (Groovy)

```groovy
commonMain {
    dependencies {
        implementation 'io.github.vietnguyentuan2019:kmptaskmanager:2.2.0'
    }
}
```

---

## 🚀 Quick Start

### 1. Initialize Koin Module

**Android** (`Application.kt`):
```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@MyApplication)
            modules(kmpTaskManagerModule())
        }
    }
}
```

**iOS** (`AppDelegate.swift`):
```swift
func application(_ application: UIApplication,
                 didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {

    // Initialize Koin
    KoinIOSKt.doInitKoinIos()

    // Register background task handlers (see documentation)
    registerBackgroundTasks()

    return true
}
```

### 2. Schedule Your First Task

```kotlin
class MyViewModel(
    private val scheduler: BackgroundTaskScheduler
) {
    suspend fun scheduleDataSync() {
        val result = scheduler.enqueue(
            id = "data-sync",
            trigger = TaskTrigger.Periodic(intervalMs = 900_000), // 15 minutes
            workerClassName = "SyncWorker",
            constraints = Constraints(requiresNetwork = true)
        )

        when (result) {
            ScheduleResult.ACCEPTED -> println("✅ Task scheduled")
            ScheduleResult.REJECTED_OS_POLICY -> println("❌ Rejected by OS")
            ScheduleResult.THROTTLED -> println("⏳ Throttled")
        }
    }
}
```

### 3. Implement Worker

**Android** (`KmpWorker.kt`):
```kotlin
class KmpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val workerClassName = inputData.getString("workerClassName")

        return when (workerClassName) {
            "SyncWorker" -> {
                // Your sync logic
                delay(2000)
                TaskEventBus.emit(TaskCompletionEvent("Sync", true, "✅ Synced"))
                Result.success()
            }
            else -> Result.failure()
        }
    }
}
```

**iOS** (`SyncWorker.kt` in `iosMain`):
```kotlin
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        try {
            // Your sync logic (must complete within 25s timeout)
            delay(2000)
            TaskEventBus.emit(TaskCompletionEvent("Sync", true, "✅ Synced"))
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
```

---

## 💡 Usage Examples

### Periodic Background Sync

```kotlin
scheduler.enqueue(
    id = "periodic-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 15_MINUTES),
    workerClassName = "SyncWorker",
    constraints = Constraints(
        requiresNetwork = true,
        requiresCharging = false
    )
)
```

### One-Time Upload with Retry

```kotlin
scheduler.enqueue(
    id = "file-upload",
    trigger = TaskTrigger.OneTime(initialDelayMs = 5000),
    workerClassName = "UploadWorker",
    constraints = Constraints(
        requiresNetwork = true,
        backoffPolicy = BackoffPolicy.EXPONENTIAL,
        backoffDelayMs = 10_000
    )
)
```

### Exact Reminder at Specific Time

```kotlin
val targetTime = Clock.System.now()
    .plus(10.minutes)
    .toEpochMilliseconds()

scheduler.enqueue(
    id = "reminder",
    trigger = TaskTrigger.Exact(atEpochMillis = targetTime),
    workerClassName = "ReminderWorker"
)
```

### Sequential Task Chain

```kotlin
scheduler
    .beginWith(TaskRequest(workerClassName = "DownloadWorker"))
    .then(TaskRequest(workerClassName = "ProcessWorker"))
    .then(TaskRequest(workerClassName = "UploadWorker"))
    .enqueue()
```

### Parallel Task Execution

```kotlin
scheduler
    .beginWith(listOf(
        TaskRequest(workerClassName = "SyncWorker"),
        TaskRequest(workerClassName = "UploadWorker"),
        TaskRequest(workerClassName = "CleanupWorker")
    ))
    .then(TaskRequest(workerClassName = "FinalizeWorker"))
    .enqueue()
```

### Monitor MediaStore Changes (Android)

```kotlin
scheduler.enqueue(
    id = "media-observer",
    trigger = TaskTrigger.ContentUri(
        uriString = "content://media/external/images/media",
        triggerForDescendants = true
    ),
    workerClassName = "MediaSyncWorker"
)
```

### Battery-Aware Heavy Task

```kotlin
scheduler.enqueue(
    id = "heavy-processing",
    trigger = TaskTrigger.BatteryOkay,
    workerClassName = "ProcessingWorker",
    constraints = Constraints(
        isHeavyTask = true,
        requiresCharging = true
    )
)
```

---

## 📊 Platform Support Matrix

| Feature | Android | iOS | Notes |
|---------|---------|-----|-------|
| **OneTime Tasks** | ✅ | ✅ | Single execution with delay |
| **Periodic Tasks** | ✅ | ✅ | Minimum 15 minutes |
| **Exact Scheduling** | ✅ | ✅ | AlarmManager / UNNotification |
| **Windowed Scheduling** | ✅ | ❌ | Android only |
| **Task Chains** | ✅ | ✅ | Sequential & parallel |
| **Network Constraints** | ✅ | ✅ | Required/Unmetered |
| **Battery Constraints** | ✅ | ✅ | Charging/Level triggers |
| **Storage Triggers** | ✅ | ❌ | Android only |
| **ContentUri Triggers** | ✅ | ❌ | Android only |
| **Device Idle** | ✅ | ❌ | Android only |
| **QoS Priority** | ✅ | ✅ | HIGH/DEFAULT/LOW |
| **Backoff Policy** | ✅ | ❌ | EXPONENTIAL/LINEAR |
| **ExistingPolicy** | ✅ | ✅ | KEEP/REPLACE |

---

## 🎨 Advanced Features

### Event System

Listen to task completion events in your UI:

```kotlin
@Composable
fun MyScreen() {
    LaunchedEffect(Unit) {
        TaskEventBus.events.collect { event ->
            println("${event.taskName}: ${event.message}")
            // Show snackbar, update UI, etc.
        }
    }
}
```

### Professional Logging

```kotlin
import io.kmp.taskmanager.utils.Logger
import io.kmp.taskmanager.utils.LogTags

// Debug-level logging
Logger.d(LogTags.SCHEDULER, "Preparing to schedule task")

// Info-level logging
Logger.i(LogTags.WORKER, "Task completed successfully")

// Warning with context
Logger.w(LogTags.PERMISSION, "Permission not granted")

// Error with exception
try {
    // Operation
} catch (e: Exception) {
    Logger.e(LogTags.SCHEDULER, "Failed to schedule", e)
}
```

### Configurable iOS Task IDs (v2.2.0+)

```kotlin
// In your iOS initialization
startKoin {
    modules(kmpTaskManagerModule(
        iosTaskIds = setOf(
            "my-custom-sync-task",
            "my-upload-task",
            "my-processing-task"
        )
    ))
}
```

### Task Management

```kotlin
// Cancel specific task
scheduler.cancel("task-id")

// Cancel all tasks
scheduler.cancelAll()
```

---

## 📚 Documentation

### Essential Guides

- **[Quick Start Guide](docs/quickstart.md)** - Get up and running in 5 minutes
- **[API Reference](docs/api-reference.md)** - Complete API documentation
- **[Platform-Specific Setup](docs/platform-setup.md)** - Android & iOS configuration
- **[Task Chains Guide](docs/task-chains.md)** - Sequential and parallel workflows
- **[Constraints & Triggers](docs/constraints-triggers.md)** - All trigger types explained
- **[Migration Guide](docs/migration.md)** - Upgrade from v2.1.0 to v2.2.0

### Additional Resources

- **[ROADMAP.md](ROADMAP.md)** - Development roadmap and future plans
- **[COMPARISON.md](COMPARISON.md)** - Compare with similar libraries
- **[CHANGELOG.md](CHANGELOG.md)** - Version history and changes
- **[Demo App](composeApp/)** - Sample application with all features

---

## 🏆 Why KMP TaskManager?

### Unique Advantages

KMP TaskManager is the **most comprehensive** background task solution for Kotlin Multiplatform:

| Feature | KMPTaskManager | Others* |
|---------|---------------|---------|
| **Trigger Types** | 🏆 9 types | 1-2 types |
| **Task Chains** | ✅ Sequential & Parallel | ❌ |
| **Constraints** | ✅ Network, Battery, Storage | ❌ |
| **Backoff Policy** | ✅ EXPONENTIAL/LINEAR | ❌ |
| **QoS Support** | ✅ HIGH/DEFAULT/LOW | ❌ |
| **Event System** | ✅ SharedFlow-based | ❌ |
| **Professional Logging** | ✅ 4-level system | ❌ |
| **Production Status** | ✅ v2.2.0 | ⚠️ Pre-release/Limited |

\* Compared with [multiplatform-work-manager](https://github.com/kprakash2/multiplatform-work-manager). See [COMPARISON.md](COMPARISON.md) for detailed analysis.

### Complementary Libraries

KMP TaskManager focuses on **background task execution**. For notifications, consider:

- **[Alarmee](https://github.com/Tweener/alarmee)** - User-facing alarms and local notifications
- **[KMPNotifier](https://github.com/mirzemehdi/KMPNotifier)** - Push notifications (Firebase/APNs)

See [COMPARISON.md](COMPARISON.md) for detailed comparison and recommended combinations.

---

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

### Ways to Contribute

- 🐛 Report bugs via [GitHub Issues](https://github.com/vietnguyentuan2019/KMPTaskManager/issues)
- 💡 Suggest features in [GitHub Discussions](https://github.com/vietnguyentuan2019/KMPTaskManager/discussions)
- 📖 Improve documentation
- 🧪 Add test coverage
- ⭐ Star the repository to show support

---

## 📊 Project Stats

- **🌟 GitHub Stars**: [Star us!](https://github.com/vietnguyentuan2019/KMPTaskManager/stargazers)
- **📦 Maven Central**: [View on Maven Central](https://central.sonatype.com/artifact/io.github.vietnguyentuan2019/kmptaskmanager)
- **🔍 klibs.io**: [View on klibs.io](https://klibs.io/package/io.github.vietnguyentuan2019/kmptaskmanager)
- **📈 Version**: 2.2.0 (Released December 2025)
- **📝 License**: Apache 2.0
- **🛠️ Lines of Code**: 3,500+
- **✅ Test Coverage**: 85%+

---

## 📄 License

```
Copyright © 2025 Nguyễn Tuấn Việt

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

## 🙏 Acknowledgments

Built with:
- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html)
- [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) (Android)
- [BackgroundTasks](https://developer.apple.com/documentation/backgroundtasks) (iOS)
- [Koin](https://insert-koin.io/) (Dependency Injection)

Special thanks to the Kotlin Multiplatform community!

---

## 📞 Support & Community

- **💬 Discussions**: [GitHub Discussions](https://github.com/vietnguyentuan2019/KMPTaskManager/discussions)
- **🐛 Issue Tracker**: [GitHub Issues](https://github.com/vietnguyentuan2019/KMPTaskManager/issues)
- **📧 Email**: [vietnguyentuan@gmail.com](mailto:vietnguyentuan@gmail.com)
- **🐦 Twitter**: [@YourTwitter](https://twitter.com/YourTwitter)

---

<div align="center">

**⭐ If you find KMP TaskManager useful, please star the repository! ⭐**

[![Star History Chart](https://api.star-history.com/svg?repos=vietnguyentuan2019/KMPTaskManager&type=Date)](https://star-history.com/#vietnguyentuan2019/KMPTaskManager&Date)

Made with ❤️ by the KMP TaskManager Team

</div>
