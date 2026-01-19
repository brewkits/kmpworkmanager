# Changelog

All notable changes to KMP WorkManager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.0.1] - 2026-01-19

### 🐛 Critical Bug Fixes

**iOS Concurrency & Stability**
- Fixed thread-safety issue in `ChainExecutor.activeChains` (replaced NSMutableSet with Mutex-protected Kotlin set)
- Fixed ANR risk in `NativeTaskScheduler.enqueueChain` (moved file I/O from MainScope to backgroundScope)
- Fixed stale metadata handling in `ExistingPolicy.KEEP` (now queries actual BGTaskScheduler pending tasks)
- Fixed NPE in `IosFileStorage.coordinated()` by implementing conditional coordination (production uses NSFileCoordinator for inter-process safety; tests skip coordination to avoid callback execution issues)

**Android Reliability**
- Fixed `AlarmReceiver` process kill risk by adding `goAsync()` support with `PendingResult` parameter
- Added configurable notification text for `KmpHeavyWorker` (supports localization via `NOTIFICATION_TITLE_KEY` and `NOTIFICATION_TEXT_KEY`)

**Sample App**
- Fixed memory leak in `DebugViewModel` (added proper CoroutineScope cleanup with `DisposableEffect`)

### ⚠️ Breaking Changes

**AlarmReceiver Signature Update**
```kotlin
// Before (v2.0.0)
abstract fun handleAlarm(
    context: Context,
    taskId: String,
    workerClassName: String,
    inputJson: String?
)

// After (v2.0.1+)
abstract fun handleAlarm(
    context: Context,
    taskId: String,
    workerClassName: String,
    inputJson: String?,
    pendingResult: PendingResult  // Must call finish() when done
)
```

**Migration**: Apps extending `AlarmReceiver` must update the method signature and call `pendingResult.finish()` when work completes.

### 📝 Documentation
- Added comprehensive inline documentation for all fixes
- All code changes marked with "v2.0.1+" comments for traceability

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
