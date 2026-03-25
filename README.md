<div align="center">

<img src="kmpworkmanager.png?v=2" alt="KMP WorkManager" width="800px" />

# KMP WorkManager

**Background task scheduling for Kotlin Multiplatform**

Write once, run everywhere — unified API for Android WorkManager and iOS BGTaskScheduler

[![Maven Central](https://img.shields.io/maven-central/v/dev.brewkits/kmpworkmanager?color=blue&label=Maven%20Central)](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![CI](https://github.com/brewkits/kmpworkmanager/actions/workflows/build.yml/badge.svg)](https://github.com/brewkits/kmpworkmanager/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-lightgrey.svg)](https://kotlinlang.org/docs/multiplatform.html)

[Features](#features) · [Installation](#installation) · [Quick Start](#quick-start) · [Documentation](#documentation)

</div>

---

## The Problem

```kotlin
// Without KMP WorkManager — platform-specific boilerplate in every project
expect class BackgroundScheduler {
    fun schedule(task: Task)
}
// Android → WorkManager API
// iOS     → BGTaskScheduler API
// Two code paths. Two maintenance burdens. Two sets of edge cases.
```

## The Solution

```kotlin
// One API. Both platforms. Shared code.
val scheduler = BackgroundTaskScheduler()

scheduler.enqueue(
    id = "sync-data",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "SyncWorker",
    constraints = Constraints(requiresNetwork = true)
)
```

No `expect`/`actual`. No platform checks. No duplication.

---

## Features

### Unified API
Single scheduling interface over Android WorkManager and iOS BGTaskScheduler. Business logic stays in common code — platform differences are handled internally.

### Task Chains with State Recovery
Sequential workflows that survive process death. If the app is killed mid-chain, execution resumes from the last completed step on next launch.

```kotlin
scheduler.beginWith(TaskRequest("Download"))
    .then(TaskRequest("Validate"))
    .then(TaskRequest("Upload"))
    .enqueue()
```

### Multiple Task Types

| Type | Description |
|------|-------------|
| **One-time** | Execute once, with optional delay |
| **Periodic** | Repeat every N minutes (min 15 min) |
| **Exact** | Precise timing via Android AlarmManager |
| **Chain** | Multi-step workflows with persistence |

### Pre-built Workers

| Worker | Purpose |
|--------|---------|
| `HttpRequestWorker` | HTTP calls with retry |
| `HttpDownloadWorker` | File downloads |
| `HttpUploadWorker` | File uploads |
| `HttpSyncWorker` | Data synchronization |
| `FileCompressionWorker` | File compression |

### Security Built-in
SSRF protection, input validation, 10 KB payload limit for Android WorkManager — enabled by default, no configuration needed.

### Performance
- O(1) queue operations on iOS (binary format with persistent index)
- 60–86% faster HTTP via singleton Ktor client
- Adaptive time budgeting on iOS to protect BGTask scheduling credit

---

## Installation

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brewkits:kmpworkmanager:2.3.7")
        }
    }
}
```

### Platform Setup

<details>
<summary><b>Android</b></summary>

Initialize in your `Application` class:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KmpWorkManager.initialize(
            context = this,
            config = KmpWorkManagerConfig(logLevel = Logger.Level.INFO)
        )
    }
}
```

</details>

<details>
<summary><b>iOS</b></summary>

**Step 1 — Create a worker factory** in `iosMain`:

```kotlin
class MyWorkerFactory : IosWorkerFactory {
    override fun createWorker(workerClassName: String): IosWorker? = when (workerClassName) {
        "SyncWorker" -> SyncWorkerIos()
        else -> null
    }
}
```

**Step 2 — Initialize in AppDelegate**:

```swift
@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    override init() {
        super.init()
        KoinInitializerKt.doInitKoin(platformModule: IOSModuleKt.iosModule)
    }

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "kmp_chain_executor_task",
            using: nil
        ) { task in /* handle */ }
        return true
    }
}
```

**Step 3 — Add to `Info.plist`**:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>kmp_chain_executor_task</string>
</array>
```

</details>

Full setup: [docs/platform-setup.md](docs/platform-setup.md)

---

## Quick Start

### 1. Define a Worker

```kotlin
// commonMain
class SyncWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        val data = api.fetchLatest()
        database.save(data)
        return WorkerResult.Success(
            message = "Synced ${data.size} items",
            data = mapOf("count" to data.size)
        )
    }
}
```

```kotlin
// androidMain
class SyncWorkerAndroid : AndroidWorker {
    override suspend fun doWork(input: String?) = SyncWorker().doWork(input)
}

// iosMain
class SyncWorkerIos : IosWorker {
    override suspend fun doWork(input: String?) = SyncWorker().doWork(input)
}
```

### 2. Schedule It

```kotlin
// Runs every 15 minutes on both platforms when network is available
scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = "SyncWorker",
    constraints = Constraints(requiresNetwork = true)
)
```

---

## Platform Comparison

| Feature | Android | iOS |
|---------|---------|-----|
| One-time tasks | ✅ WorkManager | ✅ BGTaskScheduler |
| Periodic tasks | ✅ Min 15 minutes | ✅ Opportunistic |
| Exact timing | ✅ AlarmManager | — Not available |
| Task chains | ✅ WorkContinuation | ✅ With state recovery |
| Network constraint | ✅ Enforced | ⚠️ Best effort |
| Battery constraint | ✅ Enforced | ⚠️ System decides |
| Runs when closed | ✅ Yes | ⚠️ If not force-quit |

> **iOS scheduling is opportunistic.** The OS decides when to run tasks and may delay execution by hours. Do not use for time-critical operations. See [iOS Best Practices](docs/ios-best-practices.md).

---

## Documentation

| Guide | Description |
|-------|-------------|
| [Quick Start](docs/quickstart.md) | Up and running in 5 minutes |
| [Platform Setup](docs/platform-setup.md) | Android & iOS configuration |
| [API Reference](docs/api-reference.md) | Full API surface |
| [Task Chains](docs/task-chains.md) | Multi-step workflows |
| [Built-in Workers](docs/BUILTIN_WORKERS_GUIDE.md) | Pre-built worker reference |
| [iOS Best Practices](docs/ios-best-practices.md) | iOS background task tips |
| [Constraints & Triggers](docs/constraints-triggers.md) | Scheduling options |
| [Examples](docs/examples.md) | Code samples |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Common issues |
| [CHANGELOG](CHANGELOG.md) | Release history |

**Migration guides:** [v2.2.2](docs/MIGRATION_V2.2.2.md) · [v2.3.0](docs/MIGRATION_V2.3.0.md) · [v2.3.3→v2.3.4](docs/MIGRATION_V2.3.3_TO_V2.3.4.md) · [iOS migration](docs/ios-migration.md)

---

## What's New in v2.3.7

**Security fixes**
- SSRF: added Alibaba Cloud ECS metadata endpoint (`100.100.100.200`) to blocklist
- Android: 10 KB input size validation prevents WorkManager `Data` overflow

**iOS reliability**
- `FileCompressionWorker` now returns `Failure` instead of silent `Success` with uncompressed copy
- `IosWorkerDiagnostics`: disk space now reads from `NSFileManager` (was hardcoded 1 GB)
- `IosWorkerDiagnostics`: Low Power Mode now reads from `NSProcessInfo` (was hardcoded `false`)
- `IosFileCoordinator`: process name check tightened to `.endsWith("test.kexe")` only

**Android reliability**
- `AndroidWorkerDiagnostics.getTaskStatus` now finds tasks by chain ID, not only by UUID

**API hygiene**
- `TaskTrigger.StorageLow/BatteryLow/BatteryOkay/DeviceIdle` → `DeprecationLevel.ERROR`
- `TaskChain.enqueueBlocking()` → `DeprecationLevel.ERROR` (causes ANR on main thread)

Full details: [CHANGELOG.md](CHANGELOG.md) · [Release Notes](docs/release-notes/v2.3.7-RELEASE-NOTES.md)

---

## Requirements

| | Minimum |
|---|---------|
| Kotlin | 2.1.0+ |
| Android | 8.0+ (API 26) |
| iOS | 13.0+ |
| Gradle | 8.0+ |

---

## Architecture

```
┌──────────────────────────────────────────┐
│           commonMain                     │
│   BackgroundTaskScheduler (interface)    │
│   TaskChain · WorkerResult · Constraints │
└────────────────┬─────────────────────────┘
                 │
        ┌────────┴────────┐
        ▼                 ▼
┌──────────────┐   ┌──────────────────┐
│  androidMain │   │     iosMain      │
│  WorkManager │   │ BGTaskScheduler  │
│  AlarmManager│   │ ChainExecutor    │
│              │   │ IosFileStorage   │
└──────────────┘   └──────────────────┘
```

Platform implementations are internal. All scheduling logic lives in common code.

---

## Contributing

Contributions are welcome. Please open an issue before starting significant work to discuss the approach.

**Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):**

```
feat: add retry backoff for HTTP workers
fix: NPE in ChainExecutor when steps are empty
security: block Alibaba Cloud metadata endpoint
```

This keeps the CHANGELOG accurate and version bumps automatic.

**Before opening a PR:**
```bash
./gradlew :kmpworker:allTests   # must pass
```

---

## License

```
Copyright 2024–2026 Nguyễn Tuấn Việt

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

---

<div align="center">

[GitHub](https://github.com/brewkits/kmpworkmanager) ·
[Maven Central](https://central.sonatype.com/artifact/dev.brewkits/kmpworkmanager) ·
[Issues](https://github.com/brewkits/kmpworkmanager/issues) ·
[Discussions](https://github.com/brewkits/kmpworkmanager/discussions)

</div>
