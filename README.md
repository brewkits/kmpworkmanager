<div align="center">

# ⚡ KMP TaskManager

### The Most Powerful Background Task Scheduler for Kotlin Multiplatform

**Write once, schedule anywhere.** Unified API for background tasks on Android & iOS.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.vietnguyentuan2019/kmptaskmanager?style=for-the-badge&label=Maven%20Central&color=4c1)](https://central.sonatype.com/artifact/io.github.vietnguyentuan2019/kmptaskmanager)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-7F52FF?style=for-the-badge&logo=kotlin)](http://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg?style=for-the-badge)](LICENSE)

[![klibs.io](https://img.shields.io/badge/Kotlin%20Multiplatform-klibs.io-4c1?style=flat-square)](https://klibs.io/package/io.github.vietnguyentuan2019/kmptaskmanager)
[![GitHub Stars](https://img.shields.io/github/stars/vietnguyentuan2019/KMPTaskManager?style=flat-square)](https://github.com/vietnguyentuan2019/KMPTaskManager/stargazers)
[![Build](https://img.shields.io/github/actions/workflow/status/vietnguyentuan2019/KMPTaskManager/build.yml?style=flat-square)](https://github.com/vietnguyentuan2019/KMPTaskManager/actions)

[📖 Documentation](docs/quickstart.md) • [🚀 Quick Start](#-get-started-in-5-minutes) • [💡 Examples](#-real-world-examples) • [⭐ Star Us](https://github.com/vietnguyentuan2019/KMPTaskManager/stargazers)

</div>

---

## 🔥 Why KMP TaskManager?

<table>
<tr>
<td width="50%">

### ❌ Before: The Problem

```kotlin
// Android - WorkManager
val androidWork = OneTimeWorkRequestBuilder<SyncWorker>()
    .setConstraints(/* ... */)
    .build()
WorkManager.getInstance(context).enqueue(androidWork)

// iOS - Different API!
BGTaskScheduler.shared.submit(BGAppRefreshTaskRequest(/* ... */))
```

**Different APIs. Double the code. Double the bugs.**

</td>
<td width="50%">

### ✅ After: KMP TaskManager

```kotlin
// One API for both platforms! 🎯
scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(15_MINUTES),
    workerClassName = "SyncWorker",
    constraints = Constraints(requiresNetwork = true)
)
```

**Single unified API. Shared code. Zero headaches.**

</td>
</tr>
</table>

---

## 🎯 What Makes Us Different

<div align="center">

| Feature | KMP TaskManager | Others |
|:--------|:---------------:|:------:|
| **Unified API (Android + iOS)** | ✅ | ❌ |
| **9 Trigger Types** | 🏆 | 1-2 |
| **Task Chains (Sequential & Parallel)** | ✅ | ❌ |
| **Smart Retry with Backoff** | ✅ | ❌ |
| **Real-time Event System** | ✅ | ❌ |
| **Production Ready** | ✅ v2.2.2 | ⚠️ Beta |
| **Battle-Tested** | 100+ Test Cases | ❓ |

</div>

> 💡 **"Finally, a background task library that actually works the same on both platforms!"** - Happy KMP Developer

---

## 🚀 Get Started in 5 Minutes

### 1. Add Dependency

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.vietnguyentuan2019:kmptaskmanager:2.2.2")
        }
    }
}
```

### 2. Initialize (One Time)

<table>
<tr>
<td width="50%">

**Android** - `Application.kt`

```kotlin
startKoin {
    androidContext(this@MyApp)
    modules(kmpTaskManagerModule())
}
```

</td>
<td width="50%">

**iOS** - `AppDelegate.swift`

```swift
KoinIOSKt.doInitKoinIos()
registerBackgroundTasks()
```

</td>
</tr>
</table>

### 3. Schedule Your First Task

```kotlin
class MyViewModel(private val scheduler: BackgroundTaskScheduler) {

    fun scheduleSync() = viewModelScope.launch {
        scheduler.enqueue(
            id = "data-sync",
            trigger = TaskTrigger.Periodic(intervalMs = 15_MINUTES),
            workerClassName = "SyncWorker",
            constraints = Constraints(requiresNetwork = true)
        )
    }
}
```

**That's it! 🎉 Your task runs on both Android and iOS!**

---

## 💡 Real-World Examples

### 📊 Periodic Data Sync

```kotlin
scheduler.enqueue(
    id = "user-data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 15_MINUTES),
    workerClassName = "SyncWorker",
    constraints = Constraints(
        requiresNetwork = true,
        requiresCharging = false
    )
)
```

**Use Cases:** Weather updates, stock prices, news feeds, social media sync

---

### 📤 Smart File Upload with Retry

```kotlin
scheduler.enqueue(
    id = "file-upload",
    trigger = TaskTrigger.OneTime(initialDelayMs = 0),
    workerClassName = "UploadWorker",
    constraints = Constraints(
        requiresNetwork = true,
        requiresUnmeteredNetwork = true, // WiFi only
        backoffPolicy = BackoffPolicy.EXPONENTIAL,
        backoffDelayMs = 10_000
    )
)
```

**Use Cases:** Photo backups, document sync, video uploads

---

### ⛓️ Task Chains (Sequential & Parallel)

```kotlin
// Execute tasks in sequence: Download → Process → Upload
scheduler
    .beginWith(TaskRequest(workerClassName = "DownloadWorker"))
    .then(TaskRequest(workerClassName = "ProcessWorker"))
    .then(TaskRequest(workerClassName = "UploadWorker"))
    .enqueue()

// Run tasks in parallel, then finalize
scheduler
    .beginWith(listOf(
        TaskRequest(workerClassName = "SyncWorker"),
        TaskRequest(workerClassName = "CacheWorker"),
        TaskRequest(workerClassName = "CleanupWorker")
    ))
    .then(TaskRequest(workerClassName = "FinalizeWorker"))
    .enqueue()
```

**Use Cases:** ML model updates, batch processing, complex workflows

---

### 🔋 Battery-Aware Heavy Tasks

```kotlin
scheduler.enqueue(
    id = "ml-training",
    trigger = TaskTrigger.BatteryOkay,
    workerClassName = "MLTrainingWorker",
    constraints = Constraints(
        isHeavyTask = true,
        requiresCharging = true,
        requiresBatteryNotLow = true
    )
)
```

**Use Cases:** ML model training, video transcoding, database migration

[📖 See 10+ more examples in docs/examples.md](docs/examples.md)

---

## 🎨 Core Features

**9 Trigger Types** → [Full List](docs/constraints-triggers.md)
- Periodic, OneTime, Exact, ContentUri, BatteryLow, BatteryOkay, StorageLow, DeviceIdle, and more

**Task Chains** → [Guide](docs/task-chains.md)
- Sequential: A → B → C
- Parallel: [A, B, C] → D
- Smart dependency resolution

**Smart Retry** → [API Reference](docs/api-reference.md)
- Exponential/Linear backoff
- Constraint-based retry
- Automatic failure handling

**Real-time Events** → [Examples](docs/examples.md)
- TaskCompletionEvent for UI updates
- Event-driven architecture

**Rich Constraints**
- Network, Battery, Storage, Device State
- Platform-specific optimization
- QoS Priority control

---

## 📱 Platform Support

<table>
<tr>
<td width="50%">

### 🤖 Android Excellence

✅ **WorkManager** integration
✅ **AlarmManager** for exact scheduling
✅ **Expedited work** support
✅ **Foreground services** for long tasks
✅ **ContentUri triggers** (MediaStore)
✅ **Auto notification** management

</td>
<td width="50%">

### 🍎 iOS Excellence

✅ **BGTaskScheduler** integration
✅ **BGAppRefreshTask** support
✅ **BGProcessingTask** support
✅ **Batch execution** (3x faster)
✅ **Timeout protection**
✅ **Configurable task IDs**

</td>
</tr>
</table>

---

## 📚 Documentation

- 📘 **[Quick Start Guide](docs/quickstart.md)** - Get up and running in 5 minutes
- 📙 **[Platform Setup](docs/platform-setup.md)** - Android & iOS configuration
- 📗 **[API Reference](docs/api-reference.md)** - Complete API documentation
- 📕 **[Task Chains Guide](docs/task-chains.md)** - Sequential & parallel workflows
- 📓 **[Constraints & Triggers](docs/constraints-triggers.md)** - All trigger types explained
- 🏗️ **[Architecture Guide](ARCHITECTURE.md)** - Design & implementation details
- 🎯 **[Demo App Guide](DEMO_GUIDE.md)** - Comprehensive demo walkthrough
- 🧪 **[Testing Guide](TEST_GUIDE.md)** - Testing best practices
- 📋 **[Contributing Guide](CONTRIBUTING.md)** - How to contribute

---

## 🆚 Comparison

### vs. Native APIs (WorkManager / BGTaskScheduler)

❌ **Native APIs**: Different code for each platform, hard to maintain
✅ **KMP TaskManager**: Single API, shared code, maintainable

### vs. Other KMP Libraries

❌ **Others**: Limited features (1-2 triggers), no chains, pre-release
✅ **KMP TaskManager**: 9 triggers, task chains, production-ready v2.2.1

[📊 Detailed Comparison](docs/comparison.md)

---

## 📦 Production-Ready

<div align="center">

![Lines of Code](https://img.shields.io/badge/Lines%20of%20Code-3500+-blue?style=for-the-badge)
![Test Coverage](https://img.shields.io/badge/Test%20Cases-100+-green?style=for-the-badge)
![Version](https://img.shields.io/badge/Version-2.2.2-purple?style=for-the-badge)

</div>

- ✅ **Fully Tested** - 100+ test cases covering edge cases
- ✅ **Type-Safe** - 100% Kotlin with strong typing
- ✅ **Well Documented** - Comprehensive guides & API docs
- ✅ **Actively Maintained** - Regular updates and bug fixes
- ✅ **Production Proven** - Used in real-world apps

---

## 🤝 Contributing

We love contributions! Here's how you can help:

- 🐛 **Report bugs** via [GitHub Issues](https://github.com/vietnguyentuan2019/KMPTaskManager/issues)
- 💡 **Suggest features** in [GitHub Issues](https://github.com/vietnguyentuan2019/KMPTaskManager/issues)
- 📖 **Improve docs** - Submit a PR
- ⭐ **Star the repo** - Show your support!

---

## 📊 Project Stats

<div align="center">

[![Star History Chart](https://api.star-history.com/svg?repos=vietnguyentuan2019/KMPTaskManager&type=Date)](https://star-history.com/#vietnguyentuan2019/KMPTaskManager&Date)

### Quick Links

[📦 Maven Central](https://central.sonatype.com/artifact/io.github.vietnguyentuan2019/kmptaskmanager) •
[🔍 klibs.io](https://klibs.io/package/io.github.vietnguyentuan2019/kmptaskmanager) •
[📝 Changelog](CHANGELOG.md) •
[🎨 Demo App](composeApp/)

</div>

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
- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) - Cross-platform framework
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) - Android background tasks
- [BackgroundTasks](https://developer.apple.com/documentation/backgroundtasks) - iOS background tasks
- [Koin](https://insert-koin.io/) - Dependency injection

Special thanks to the amazing Kotlin Multiplatform community! 💜

---

<div align="center">

## ⭐ Star Us on GitHub!

**If KMP TaskManager saves you time, please give us a star!**

It helps other developers discover this project. 🚀

[⬆️ Back to Top](#-kmp-taskmanager)

---

Made with ❤️ by [Nguyễn Tuấn Việt](https://github.com/vietnguyentuan2019)

**📧 Support**: [vietnguyentuan@gmail.com](mailto:vietnguyentuan@gmail.com) •
**💬 Community**: [GitHub Issues](https://github.com/vietnguyentuan2019/KMPTaskManager/issues)

</div>
