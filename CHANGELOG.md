# Changelog

All notable changes to KMP WorkManager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.1.0] - 2026-01-16

### 🎉 Major Enhancement: DI-Agnostic Architecture

KMP WorkManager is now **dependency injection framework agnostic**! Koin is optional.

### Added

#### Core Library (Zero DI Dependencies)
- **WorkerManagerConfig**: Global service locator for DI-agnostic factory registration
- **WorkerManagerInitializer**: Unified initialization API (expect/actual pattern)
  - Manual initialization without any DI framework
  - Platform-specific setup (Android Context, iOS task IDs)
- **AndroidWorkerFactoryProvider** / **IosWorkerFactoryProvider**: Type-safe factory accessors
- **IosTaskHandlerRegistry**: Lazy-initialized task executors for iOS

#### Extension Modules
- **kmpworkmanager-koin** (v2.1.0): Optional Koin integration extension
  - 100% backward compatible with v2.0.0
  - Same API, just add the dependency
- **kmpworkmanager-hilt** (v2.1.0): Optional Hilt/Dagger integration (Android only)
  - Native Hilt support for Android apps

### Changed

- **Core library**: Removed Koin dependencies (koin-core, koin-android)
- **KmpWorker** / **KmpHeavyWorker**: Use `AndroidWorkerFactoryProvider` instead of Koin injection
- **Version**: 2.0.0 → 2.1.0 (minor version bump - non-breaking)

### Deprecated

- **KoinModule files** in core library: Moved to `kmpworkmanager-koin` extension
  - Old code still works with extension dependency
  - Will be removed in v3.0.0

### Migration

**For existing Koin users** (100% backward compatible):
```kotlin
// Just add one dependency - code stays the same
implementation("dev.brewkits:kmpworkmanager:2.1.0")
implementation("dev.brewkits:kmpworkmanager-koin:2.1.0")  // ADD THIS
```

**For new projects** (manual initialization):
```kotlin
// Android
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkerManagerInitializer.initialize(
            workerFactory = MyWorkerFactory(),
            context = this
        )
    }
}

// iOS
fun initializeWorkManager() {
    WorkerManagerInitializer.initialize(
        workerFactory = MyWorkerFactory(),
        iosTaskIds = setOf("my-task")
    )
}
```

See [docs/migration-v2.1.0.md](docs/migration-v2.1.0.md) for complete migration guide.

### Benefits

- 🎯 **Zero dependencies**: Core library has no DI framework requirements
- 🔌 **Flexible integration**: Choose your DI solution (Koin, Hilt, manual, or others)
- 📦 **Smaller binary size**: Only include DI framework if you need it
- 🧪 **Easier testing**: Simple manual initialization for tests
- ♻️ **Backward compatible**: Existing Koin code works with extension module

---

## [2.0.0] - 2026-01-15

### BREAKING CHANGES

**Group ID Migration: `io.brewkits` → `dev.brewkits`**

This version introduces a breaking change to align with domain ownership for Maven Central.

**What Changed:**
- Maven artifact: `io.brewkits:kmpworkmanager` → `dev.brewkits:kmpworkmanager`
- Package namespace: `io.brewkits.kmpworkmanager.*` → `dev.brewkits.kmpworkmanager.*`
- All source files (117 files) updated with new package structure

**Migration Required:**
```kotlin
// Old (v1.x)
implementation("io.brewkits:kmpworkmanager:1.1.0")
import io.brewkits.kmpworkmanager.*

// New (v2.0+)
implementation("dev.brewkits:kmpworkmanager:2.0.0")
import dev.brewkits.kmpworkmanager.*
```

**Why?**
- Aligns with owned domain `brewkits.dev`
- Proper Maven Central ownership verification
- Long-term namespace stability

See [DEPRECATED_README.md](DEPRECATED_README.md) for detailed migration guide.

## [1.1.0] - 2026-01-14

### Added
- Real-time worker progress tracking with `WorkerProgress` and `TaskProgressBus`
- iOS chain state restoration - resume from last completed step after interruptions
- Windowed task trigger support (execute within time window)
- Comprehensive iOS test suite (38+ tests for ChainProgress, ChainExecutor, IosFileStorage)

### Improved
- iOS retry logic with max retry limits (prevents infinite loops)
- Enhanced iOS batch processing for efficient BGTask usage
- Production-grade error handling and logging improvements

### Documentation
- iOS best practices guide
- iOS migration guide
- Updated API examples with v1.1.0 features

## [1.0.0] - 2026-01-13

### Added
- Worker factory pattern for better extensibility
- Automatic iOS task ID validation from Info.plist
- Type-safe serialization extensions with reified inline functions
- File-based storage on iOS for better performance
- Smart exact alarm fallback on Android
- Heavy task support with foreground services
- Unified API for Android and iOS
- Comprehensive test suite with 41+ test cases
- Support for task chains with parallel and sequential execution
- Multiple trigger types (OneTime, Periodic, Exact, NetworkChange)
- Rich constraint system (network, battery, charging, storage)
- Background task scheduling using WorkManager (Android) and BGTaskScheduler (iOS)

### Changed
- Rebranded from `kmpworker` to `kmpworkmanager`
- Migrated package from `io.kmp.worker` to `dev.brewkits.kmpworkmanager`
- Project organization moved to brewkits/kmpworkmanager

### Documentation
- Complete API reference documentation
- Platform setup guides for Android and iOS
- Quick start guide
- Task chains documentation
- Architecture overview
- Examples and use cases
