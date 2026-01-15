# ⚠️ DEPRECATED: KMPTaskManager

> **📦 MOVED TO NEW HOME:** This library has been superseded by **[KMP WorkManager](https://github.com/brewkits/kmpworkmanager)**.
>
> Please migrate to the new library for better iOS reliability, thread-safety, and enterprise features.

## 👉 [GO TO NEW LIBRARY: io.brewkits.kmpworkmanager](https://github.com/brewkits/kmpworkmanager)

[![Maven Central](https://img.shields.io/maven-central/v/io.brewkits/kmpworkmanager)](https://central.sonatype.com/artifact/io.brewkits/kmpworkmanager)

---

## Why Migrate?

The original `vietnguyentuan2019/KMPTaskManager` library has evolved into a production-ready, enterprise-grade solution under the **Brewkits** organization. Here's what you gain by migrating:

### 🎯 Zero Event Loss
- **Problem (Old)**: Events emitted when UI isn't listening are lost forever
- **Solution (New)**: Persistent EventStore with automatic replay on app launch
- **Impact**: Critical completion events survive app kills and force-quits

### 🔄 Resilient Task Chains
- **Problem (Old)**: iOS chains restart from beginning after timeout
- **Solution (New)**: ChainProgress with state restoration - resume from last completed step
- **Impact**: Long chains (>30s) complete successfully despite iOS interruptions

### 💾 Better iOS Storage
- **Problem (Old)**: UserDefaults race conditions and data loss
- **Solution (New)**: File-based storage with NSFileCoordinator for atomic operations
- **Impact**: Thread-safe, reliable metadata storage

### 🎨 Type-Safe API
- **Problem (Old)**: Manual JSON parsing with `Map<String, Any>`
- **Solution (New)**: `kotlinx.serialization` with reified inline functions
- **Impact**: Compile-time safety, less boilerplate, fewer runtime errors

### 📊 Real-Time Progress Tracking
- **Problem (Old)**: No visibility into long-running tasks
- **Solution (New)**: `WorkerProgress` with `TaskProgressBus` for live UI updates
- **Impact**: Professional UX for downloads, uploads, batch processing

### 🧪 Production-Grade Testing
- **Problem (Old)**: Minimal test coverage
- **Solution (New)**: 200+ tests including iOS integration tests
- **Impact**: Confidence in reliability across platforms

### 📚 Enterprise Documentation
- **Problem (Old)**: Basic README only
- **Solution (New)**: Comprehensive guides (iOS best practices, migration, API reference)
- **Impact**: Faster onboarding, fewer support questions

---

## Migration Guide

### Step 1: Update Dependencies

**Old (vietnguyentuan2019/KMPTaskManager):**
```kotlin
dependencies {
    implementation("io.kmp.worker:kmpworker:0.x.x")
}
```

**New (brewkits/kmpworkmanager):**
```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.brewkits:kmpworkmanager:1.1.0")
        }
    }
}
```

### Step 2: Update Package Imports

**Old:**
```kotlin
import io.kmp.worker.BackgroundTaskScheduler
import io.kmp.worker.domain.TaskTrigger
import io.kmp.worker.domain.Constraints
```

**New:**
```kotlin
import io.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import io.brewkits.kmpworkmanager.background.domain.TaskTrigger
import io.brewkits.kmpworkmanager.background.domain.Constraints
```

### Step 3: Update Worker Factory Pattern

The new library uses a cleaner factory pattern:

**Old:**
```kotlin
class MyWorkerFactory : WorkerFactory {
    override fun createWorker(className: String): Worker? {
        // ...
    }
}
```

**New (Android):**
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

**New (iOS):**
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

### Step 4: Update Koin Initialization

**Old:**
```kotlin
startKoin {
    modules(workerModule(MyWorkerFactory()))
}
```

**New (Android):**
```kotlin
startKoin {
    androidContext(this@MyApp)
    modules(kmpWorkerModule(
        workerFactory = MyWorkerFactory()
    ))
}
```

**New (iOS):**
```kotlin
KoinModuleKt.doInitKoinIos(workerFactory: MyWorkerFactory())
```

### Step 5: Leverage New Features

**Enable Progress Tracking:**
```kotlin
class DownloadWorker(
    private val progressListener: ProgressListener?
) : AndroidWorker {
    override suspend fun doWork(input: String?): Boolean {
        progressListener?.onProgressUpdate(
            WorkerProgress(
                progress = 50,
                message = "Downloaded 5MB / 10MB"
            )
        )
        return true
    }
}
```

**Listen to Progress in UI:**
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
}
```

---

## Breaking Changes

### API Changes

| Old API | New API | Notes |
|---------|---------|-------|
| `io.kmp.worker.*` | `io.brewkits.kmpworkmanager.*` | Package renamed |
| `WorkerFactory` | `AndroidWorkerFactory` / `IosWorkerFactory` | Platform-specific factories |
| `workerModule()` | `kmpWorkerModule()` | Koin module renamed |
| `Worker.doWork(): Boolean` | Same | No change |

### Removed Features
- None - all features retained with improvements

### Deprecated Features
- `TaskTrigger.StorageLow` → Use `Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE))`
- `TaskTrigger.BatteryLow` → Use `Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY))`
- `TaskTrigger.DeviceIdle` → Use `Constraints(systemConstraints = setOf(SystemConstraint.DEVICE_IDLE))`

---

## Feature Comparison

| Feature | Old Library | New Library (v1.1.0) |
|---------|-------------|----------------------|
| Android Support | ✅ | ✅ |
| iOS Support | ✅ | ✅ |
| Periodic Tasks | ✅ | ✅ |
| One-Time Tasks | ✅ | ✅ |
| Exact Alarms | ✅ | ✅ |
| Task Chains | ✅ | ✅ + State Restoration |
| Constraints | ✅ | ✅ + More options |
| Event Bus | ✅ | ✅ + Persistence |
| Progress Tracking | ❌ | ✅ Built-in |
| Type-Safe Input | ⚠️ Manual | ✅ Automatic |
| iOS File Storage | ❌ UserDefaults | ✅ Atomic Files |
| Chain Restoration | ❌ | ✅ Resume from checkpoint |
| Test Coverage | ⚠️ Basic | ✅ 200+ tests |
| Documentation | ⚠️ README only | ✅ Comprehensive |
| Production Ready | ⚠️ | ✅ |

---

## Support & Community

- **New Library GitHub**: [brewkits/kmpworkmanager](https://github.com/brewkits/kmpworkmanager)
- **Issues**: [GitHub Issues](https://github.com/brewkits/kmpworkmanager/issues)
- **Maven Central**: [io.brewkits:kmpworkmanager](https://central.sonatype.com/artifact/io.brewkits/kmpworkmanager)
- **Contact**: datacenter111@gmail.com

---

## Timeline

- **2025**: Original `vietnguyentuan2019/KMPTaskManager` development
- **2026-01-13**: v1.0.0 - Rebranded as **KMP WorkManager** under Brewkits
- **2026-01-14**: v1.1.0 - Added progress tracking, chain restoration, event persistence
- **2026-Q1**: This repository archived, all development moved to new repo

---

## FAQs

**Q: Will the old library receive updates?**
A: No. All development has moved to [brewkits/kmpworkmanager](https://github.com/brewkits/kmpworkmanager). This repository is archived.

**Q: Can I still use the old library?**
A: Yes, but it won't receive bug fixes or new features. We strongly recommend migrating.

**Q: How long will migration take?**
A: Most projects can migrate in 1-2 hours. It's mostly package rename and minor API updates.

**Q: What if I encounter issues during migration?**
A: Open an issue in the [new repository](https://github.com/brewkits/kmpworkmanager/issues) with the `migration` label.

**Q: Is the license the same?**
A: Yes, both are Apache 2.0 licensed.

---

## Legacy Documentation

<details>
<summary>Click to expand old README (for reference only)</summary>

_Original README content preserved below for historical reference..._

</details>

---

**Thank you for using KMPTaskManager!** 🙏

We appreciate your support. Please give the new library a star on GitHub if it helps your project!

**[⭐ Star the new repo: brewkits/kmpworkmanager](https://github.com/brewkits/kmpworkmanager)**

---

**Made with ❤️ by Nguyễn Tuấn Việt at Brewkits**
