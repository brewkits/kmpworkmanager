# Changelog

All notable changes to KMP WorkManager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.1.2] - 2026-01-21

### 🩹 Critical iOS Stability Fixes

**Eliminated Main Thread Blocking Risk (CRITICAL)**
- Converted `IosFileStorage.getQueueSize()` from blocking to suspend function
- **Issue**: Used `runBlocking` which could block iOS Main Thread if called from UI code
- **Risk**: iOS Watchdog Kill (0x8badf00d) when background thread holds mutex during file I/O
- **Fix**: Now properly `suspend fun` - forces async usage from Swift via `await`
- **Impact**: Prevents ANR/Watchdog kills, eliminates UI jank
- Files changed:
  - `IosFileStorage.kt:150` - Removed `runBlocking`, now `suspend fun`
  - `ChainExecutor.kt:118` - Updated to `suspend fun getChainQueueSize()`
  - `iOSApp.swift:307, 325` - Swift callers now use `await`

**Self-Healing for Corrupt JSON Files (CRITICAL)**
- Added automatic cleanup of corrupt files to prevent crash loops
- **Issue**: Partial writes during crashes could create corrupt JSON files
- **Previous behavior**: Return `null` on parse error, file remains corrupt
- **New behavior**: Delete corrupt file + log error, preventing infinite crash loops
- **Impact**: App recovers from corrupt data instead of crashing repeatedly
- Files changed:
  - `IosFileStorage.kt:195-204` - `loadChainDefinition()` self-healing
  - `IosFileStorage.kt:260-267` - `loadChainProgress()` self-healing
  - `IosFileStorage.kt:321-329` - `loadTaskMetadata()` self-healing

**Fixed Timeout Documentation Mismatch (MEDIUM)**
- Corrected timeout values in documentation to match implementation
- **Issue**: Comments said 25s/55s but implementation used 20s/50s
- **Fix**: Updated `IosWorker.kt` documentation to reflect actual values
- Files changed: `IosWorker.kt:26-33`

### 📝 Documentation Updates
- Added comprehensive comments explaining self-healing mechanism
- Updated Swift async/await usage examples for `getChainQueueSize()`
- Clarified iOS BGTask timeout behavior vs hardcoded timeouts

## [2.1.1] - 2026-01-20

### 🔧 Critical Fixes & Improvements

**Coroutine Lifecycle Management (HIGH PRIORITY)**
- Fixed `GlobalScope` usage in `AppendOnlyQueue` compaction
- **Issue**: GlobalScope not tied to lifecycle, difficult to test and cancel
- **Fix**: Injected `CoroutineScope` parameter with default `SupervisorJob + Dispatchers.Default`
- **Impact**: Better lifecycle management, testability, and resource cleanup
- Changed in: `AppendOnlyQueue.kt:46-48` (constructor), `AppendOnlyQueue.kt:388` (compaction)

**Race Condition Fix in ChainExecutor (MEDIUM PRIORITY)**
- Fixed inconsistent mutex protection for `isShuttingDown` flag
- **Issue**: Read at line 153 was not protected by `shutdownMutex`, potential race condition
- **Fix**: All reads/writes now consistently use `shutdownMutex.withLock`
- **Impact**: Thread-safe shutdown state access, eliminates potential crashes
- Changed in: `ChainExecutor.kt:153-156`

**iOS Exact Alarm Transparency (HIGH PRIORITY)**
- Added `ExactAlarmIOSBehavior` enum for explicit iOS exact alarm handling
- **Problem**: iOS cannot execute background code at exact times (unlike Android)
- **Previous behavior**: Silently showed notification without documentation
- **New behavior**: Three explicit options with fail-fast capability
  - `SHOW_NOTIFICATION` (default): Display notification at exact time
  - `ATTEMPT_BACKGROUND_RUN`: Best-effort background task (timing NOT guaranteed)
  - `THROW_ERROR`: Fail fast for development/testing

### ✨ New Features

**ExactAlarmIOSBehavior Configuration**
```kotlin
// Option 1: Notification-based (recommended for user-facing events)
scheduler.enqueue(
    id = "morning-alarm",
    trigger = TaskTrigger.Exact(morningTime),
    workerClassName = "AlarmWorker",
    constraints = Constraints(
        exactAlarmIOSBehavior = ExactAlarmIOSBehavior.SHOW_NOTIFICATION // Default
    )
)

// Option 2: Fail fast (development safety)
scheduler.enqueue(
    id = "critical-task",
    trigger = TaskTrigger.Exact(criticalTime),
    workerClassName = "CriticalWorker",
    constraints = Constraints(
        exactAlarmIOSBehavior = ExactAlarmIOSBehavior.THROW_ERROR
    )
)
// Throws: "iOS does not support exact alarms for code execution"

// Option 3: Best effort (non-critical sync)
scheduler.enqueue(
    id = "nightly-sync",
    trigger = TaskTrigger.Exact(midnightTime),
    workerClassName = "SyncWorker",
    constraints = Constraints(
        exactAlarmIOSBehavior = ExactAlarmIOSBehavior.ATTEMPT_BACKGROUND_RUN
    )
)
// iOS will TRY to run around midnight, but timing is NOT guaranteed
```

### 🔄 API Changes

**New Enum: ExactAlarmIOSBehavior** (iOS only)
- Added to `Constraints` data class
- Default: `SHOW_NOTIFICATION` (backward compatible)
- No breaking changes for existing code

**Updated Methods**:
- `NativeTaskScheduler.scheduleExactAlarm()` - Now handles all three behaviors
- `NativeTaskScheduler.scheduleExactNotification()` - Made private
- Added `NativeTaskScheduler.scheduleExactBackgroundTask()` - For best-effort runs

### 📖 Documentation Improvements

**Enhanced Documentation for iOS Limitations**
- Updated `TaskTrigger.Exact` documentation with clear iOS behavior description
- Added comprehensive examples for each `ExactAlarmIOSBehavior` option
- Documented platform differences (Android always executes code, iOS has restrictions)
- Added migration guide for apps using exact alarms

**Code Comments**
- All changes marked with "v2.1.1+" for traceability
- Added rationale comments for design decisions
- Improved inline documentation for thread safety patterns

### 🎯 Migration Guide

**No Breaking Changes**
This is a backward-compatible release. Existing code will continue to work with default behavior.

**Recommended Action for iOS Exact Alarms**:
```kotlin
// Review your exact alarm usage and explicitly configure behavior:

// If showing notification is acceptable (most cases)
constraints = Constraints(
    exactAlarmIOSBehavior = ExactAlarmIOSBehavior.SHOW_NOTIFICATION
)

// If you need to catch iOS limitation during development
constraints = Constraints(
    exactAlarmIOSBehavior = ExactAlarmIOSBehavior.THROW_ERROR
)
```

### 🔬 Testing

**Test Impact**:
- All existing tests pass without modification (backward compatible)
- Default behavior (`SHOW_NOTIFICATION`) matches previous implementation
- No test updates required unless explicitly testing new behaviors

### 🙏 Acknowledgments

This release addresses critical technical debt and improves transparency about platform limitations. Special thanks to the community for highlighting these issues through code reviews.

## [2.1.0] - 2026-01-20

### 🚀 Major Performance Improvements

**iOS Queue Operations - 13-40x Faster**
- Implemented O(1) append-only queue with head pointer tracking
- **Enqueue performance**: 40x faster (1000ms → 24ms for 100 chains)
- **Dequeue performance**: 13x faster (5000ms → 382ms for 100 chains)
- **Mixed operations**: 17x faster (30s+ → 1.7s for 700 operations)
- Line position cache for efficient random access
- Automatic compaction at 80% threshold to manage disk space

**iOS Graceful Shutdown for BGTask Expiration**
- Added graceful shutdown support for iOS BGProcessingTask time limits
- 5-second grace period for progress save before termination
- Automatic chain re-queuing on cancellation
- Thread-safe shutdown state management with Mutex
- Batch execution with shutdown flag checks
- Integration with iOS BGTask `expirationHandler`

### 🐛 Bug Fixes

**iOS Storage Migration**
- Fixed ClassCastException in `StorageMigration.kt` when migrating periodic tasks
- Issue: `NSUserDefaults.dictionaryRepresentation().keys` returns NSSet, not List
- Fix: Removed incorrect type casts at lines 79 and 173
- Impact: Unblocked migration for users with existing periodic tasks

**Demo App Integration**
- Added `requestShutdown()` and `resetShutdownState()` to demo app ChainExecutor
- Fixed Swift error handling in `iOSApp.swift` BGTask expiration handler
- Added proper `do-catch` block with `try await` for Kotlin suspend functions

### ✅ Test Coverage

- Added 22 comprehensive tests for AppendOnlyQueue
- Added 5 graceful shutdown scenario tests
- Added 5 performance benchmark tests
- **Total test count**: 236 tests (all passing)

### 📖 Documentation

- Added v2.1.0 implementation progress report
- Added comprehensive code review document
- Added test coverage analysis with production readiness assessment
- Documented all bug fixes with root cause analysis

### 🔧 Technical Details

**New Classes**:
- `AppendOnlyQueue.kt` - O(1) queue implementation with compaction
- `GracefulShutdownTest.kt` - Shutdown scenario testing
- `QueuePerformanceBenchmark.kt` - Performance verification

**Modified Classes**:
- `IosFileStorage.kt` - Integrated AppendOnlyQueue
- `ChainExecutor.kt` - Added graceful shutdown support
- `StorageMigration.kt` - Fixed NSSet casting bug

**Performance Metrics**:
- Queue operations now O(1) instead of O(N)
- Compaction reduces file size by up to 90%
- Background compaction doesn't block main operations

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
