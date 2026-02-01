# Changelog

All notable changes to KMP WorkManager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.2.1] - 2026-02-01

### 🔴 Critical Fixes

**Parallel Chain Retry Idempotency (iOS)**
- Per-task completion tracking within parallel steps prevents redundant re-execution on retry
- New field `completedTasksInSteps: Map<Int, List<Int>>` in `ChainProgress` records which tasks within each parallel step succeeded
- On retry after partial failure only tasks that actually failed are re-executed; already-succeeded tasks are skipped
- Each task completion persisted atomically via a local `Mutex` inside `executeStep`, ensuring crash-safe progress
- Backward compatible: legacy persisted `ChainProgress` JSON without `completedTasksInSteps` deserializes to an empty map
- Files changed: `ChainProgress.kt`, `ChainExecutor.kt`
- Tests added: 15 new test cases in `ChainProgressTest.kt` (per-task tracking, serialization round-trip, legacy compat, parallel retry scenario) — total now 38 tests, all passing

**Deserialization Resilience for Persisted Data (iOS)**
- All JSON deserialization in `IosFileStorage` now uses `Json { ignoreUnknownKeys = true }`
- Prevents crash on schema evolution (new fields added in future releases) or app rollback (old app reading new data)
- Applies to chain definitions, chain progress, and task metadata
- Files changed: `IosFileStorage.kt`

### ⚡ Performance Optimizations

**Buffered Legacy Queue Reads (iOS)**
- Replaced byte-by-byte `readDataOfLength(1)` loop in `readSingleLine()` with 4 KB chunk reads
- Reduces system calls from N (one per byte) to N/4096 for legacy text-format queue files during migration
- Files changed: `AppendOnlyQueue.kt`

### 🐛 Bug Fixes

**Queue Corruption Recovery Preserves Valid Data (iOS)**
- Corruption handler now truncates the queue file at the first corrupt record instead of wiping the entire file
- All valid records before the corruption point are preserved; only the corrupt record and everything after it are discarded
- Falls back to full reset only when corruption is detected in the file header itself
- Files changed: `AppendOnlyQueue.kt`

**Expired-Deadline Crash Prevention (iOS)**
- `executeChainsInBatch()` now returns early when the computed conservative timeout is ≤ 0 ms (deadline already passed)
- Prevents `withTimeout(0)` which would throw `TimeoutCancellationException` immediately
- New `deadlineEpochMs` parameter allows callers (e.g., BGTask `expirationHandler`) to pass the absolute system deadline for accurate time-slicing
- Files changed: `ChainExecutor.kt`

**Correct Chain Timeout for BGProcessingTask (iOS)**
- `executeChain()` now uses the instance-level `chainTimeout` field (50 s for APP_REFRESH, 300 s for PROCESSING) instead of the hard-coded companion constant `CHAIN_TIMEOUT_MS` (always 50 s)
- Chains running inside a BGProcessingTask now correctly receive the full 5-minute budget
- Files changed: `ChainExecutor.kt`

### 📖 Documentation

- Clarified FAIL OPEN comment in `KmpHeavyWorker.validateForegroundServiceType()`: the catch-all is intentional for Chinese ROM compatibility; `setForeground()` in `doWork()` remains the authoritative enforcement
- Documented the concurrency safety assumption in `executeStep`: each async block only checks its own `taskIndex` in `completedTasksInSteps`, so the unguarded read of `currentProgress` is safe without holding `progressMutex`
- Files changed: `KmpHeavyWorker.kt`, `ChainExecutor.kt`

### 🎯 Test Coverage

- **ChainProgressTest**: 38 tests (23 existing + 15 new), all passing on iosSimulatorArm64
- New test categories:
  - `completedTasksInSteps` basics — 5 tests for `isTaskInStepCompleted`
  - `withCompletedTaskInStep` — 5 tests (add, append, sort, idempotent, multi-step independence)
  - `withCompletedStep` cleanup — 2 tests (clears per-task data for completed step, leaves other steps untouched)
  - Parallel retry scenario — 1 end-to-end test verifying only failed tasks re-execute
  - Serialization round-trip — 1 test verifying `completedTasksInSteps` survives encode/decode
  - Legacy JSON backward compat — 1 test verifying deserialization of old JSON without the new field

### 📝 Files Changed
- Modified: `ChainProgress.kt`, `ChainExecutor.kt`, `AppendOnlyQueue.kt`, `IosFileStorage.kt`, `KmpHeavyWorker.kt`
- Modified (tests): `ChainProgressTest.kt`

---

## [2.2.0] - 2026-01-29

### ⚡ Production-Ready Optimizations

**Optimized Maintenance Task Scheduling (iOS)**
- Enhanced IosFileStorage to run maintenance immediately if >24h since last run
- Previous behavior: Always delay 5s after app launch
- New behavior: Check timestamp and run immediately if overdue (>24 hours)
- Reduces risk of missed maintenance in short-lived app sessions
- Added `getHoursSinceLastMaintenance()` and `recordMaintenanceCompletion()`
- Maintenance timestamp persisted to `last_maintenance.txt`
- Files changed: `IosFileStorage.kt`

**Enhanced CRC32 Documentation & Performance Analysis**
- Added comprehensive performance documentation:
  - ~10-20ms for 10MB data (current max constraint)
  - Zero external dependencies (pure Kotlin implementation)
  - Documented future optimization path (platform.zlib.crc32 for iOS)
  - Trade-off analysis: Native vs Pure Kotlin
- Performance is acceptable for current use case (<10MB per record)
- Files changed: `CRC32.kt`

**VERBOSE Log Level for High-Frequency Operations**
- Added `Logger.v()` (VERBOSE) level to reduce log spam under heavy load
- Moved enqueue/dequeue logs from DEBUG to VERBOSE level
- Prevents console spam during high-throughput queue operations
- Maps to `Log.v()` on Android, `NSLog()` on iOS
- Backward compatible - existing DEBUG/INFO/WARN/ERROR logs unchanged
- Files changed: `Logger.kt`, `LoggerPlatform.android.kt`, `AppendOnlyQueue.kt`, `IosFileStorage.kt`

### ✅ Comprehensive Testing Suite

**Integration Tests for Critical Workflows**
- Added 13 integration tests covering end-to-end scenarios:
  - `testMigrationFromTextToBinary` - Verifies binary format migration with magic header validation
  - `testMigrationWithEmptyQueue` - Empty queue edge case handling
  - `testMigrationWithLargeQueue` - 1000 items migration performance (<5s)
  - `testForceQuitRecovery` - App restart persistence verification
  - `testExistingPolicyKeep` - KEEP policy prevents duplicates
  - `testExistingPolicyReplace` - REPLACE policy updates definitions
  - `testDiskFullHandling` - Clear error on insufficient disk space
  - `testQueueCorruptionRecovery` - Graceful corruption handling
  - `testBinaryFormatIntegrity` - CRC32 validation across data types
  - `testBinaryFormatCRCDetection` - Corrupted data detection
  - `testCompactionTriggeredAt80Percent` - Automatic space reclamation
- Files added: `IntegrationTests.kt`

**Stress Tests for Performance and Concurrency**
- Added 11 stress tests for scalability validation:
  - `testHighConcurrency` - 1000 concurrent enqueues with no data loss
  - `testConcurrentEnqueueDequeue` - Simultaneous operations safety
  - `testLargeQueuePerformance` - 10,000 items (<10s enqueue/dequeue)
  - `testLargeChainDefinitions` - Large chain data handling
  - `testChainExecutorTimeout` - Timeout simulation and recovery
  - `testMemoryUsage` - Memory leak detection across 10 cycles
  - `testFileHandleCleanup` - Resource leak detection
  - `testRapidEnqueueDequeue` - Alternating operations stress test
  - `testMaxQueueSize` - Size limit enforcement (1000 items)
- Files added: `StressTests.kt`

### 📖 Documentation Enhancements

**Comprehensive Testing Guide**
- Created TESTING.md with complete testing documentation:
  - Running tests (iOS, Android, Common)
  - Test categories (Unit, Integration, Stress)
  - Writing tests best practices
  - Example test patterns (Migration, Concurrency, Error Handling, Performance, State Persistence)
  - Debugging failed tests
  - CI/CD integration examples
  - Coverage goals (>80% unit, all critical workflows)
- Files added: `TESTING.md`

**Updated Documentation**
- Enhanced CHANGELOG.md with migration guides
- Updated README.md with v2.2.0+ features
- Android foreground service configuration examples
- Binary format migration notes

### 🎯 Test Coverage

**Total Test Count**: 60+ tests across all categories
- Unit tests: CRC32, Queue Corruption, ExistingPolicy
- Integration tests: 13 complete workflow scenarios
- Stress tests: 11 performance and concurrency scenarios
- All tests passing on iOS and Android

### 📝 Files Changed
- Added: `TESTING.md`, `IntegrationTests.kt`, `StressTests.kt`
- Updated: `CHANGELOG.md`, `README.md`

---

## [2.1.4] - 2026-01-29

### ⚡ Performance Optimizations

**Time-Slicing Strategy for iOS Credit Score**
- Implemented conservative timeout strategy for BGTask execution:
  - Uses 85% of available time, 15% buffer for cleanup
  - Early stop when remaining time insufficient
  - Automatic continuation scheduling for large queues
  - Prevents iOS credit score degradation from system kills
- Added `ExecutionMetrics` data class with detailed telemetry:
  - Task type, duration, chains processed
  - Time usage percentage
  - System kill detection
  - Success/failure counts
- Metrics emitted via TaskEventBus for monitoring
- Files changed: `ChainExecutor.kt`

**ChainExecutor Cleanup Enforcement**
- Implemented `Closeable` interface for resource management:
  - Compile-time safety with `.use {}` pattern
  - Automatic job cancellation on close
  - Mutex-protected close state
  - `checkNotClosed()` guards on all public methods
- Prevents memory leaks from unclosed executors
- Clear error messages for use-after-close
- Files changed: `ChainExecutor.kt`, `NativeTaskScheduler.kt`

### 🎯 Benefits
- Preserves iOS credit score by stopping early
- Automatic continuation for large batches
- Enforced cleanup prevents memory leaks
- Better observability with execution metrics

### 📝 Files Changed
- Modified: `ChainExecutor.kt`, `NativeTaskScheduler.kt`
- Added: Time-slicing logic, ExecutionMetrics, Closeable implementation

---

## [2.1.3] - 2026-01-29

### 🔒 Data Integrity & Format Migration

**Binary Queue Format with CRC32 Validation**
- Implemented length-prefixed binary format with CRC32 checksums:
  - Magic number: `KMPQ` (0x4B4D5051) for format detection
  - Format version: 1 (0x00000001)
  - Record structure: `[length][data][crc32][\n]`
  - CRC32 uses IEEE 802.3 polynomial (0xEDB88320)
- Added automatic migration from text JSONL to binary format:
  - Safe migration with rollback on failure
  - Preserves all queue items
  - Renames legacy file to `.legacy`
  - Migration performance: <5s for 1000 items
- Data integrity validation on every read:
  - CRC mismatch returns null (corruption detected)
  - Prevents silent data corruption
  - Works with Unicode, Emoji, large data
- Files added: `CRC32.kt`, `CRC32Test.kt` (15 test cases)
- Files changed: `AppendOnlyQueue.kt`

**Migration Safety**
- Legacy format detection via magic header check
- Rollback mechanism if migration fails
- Backward compatibility during transition
- No data loss during migration

### ⏱️ Dynamic Timeouts by Task Type

**BGTask Type-Specific Timeouts**
- Added `BGTaskType` enum for timeout configuration:
  - `APP_REFRESH`: 20s task timeout, 50s chain timeout
  - `PROCESSING`: 120s task timeout, 300s chain timeout
- Leverages full BGProcessingTask time (5-10 minutes)
- Prevents premature timeout on long-running chains
- Better resource utilization
- Files added: `BGTaskType.kt`
- Files changed: `ChainExecutor.kt`, `NativeTaskScheduler.kt`

### 💾 Disk Space Management

**Proactive Disk Space Checks**
- Added pre-flight disk space validation:
  - Checks before saving chain definitions
  - Checks before enqueueing items
  - Requires 100MB buffer + actual size
- Clear error messages instead of cryptic I/O failures:
  - `InsufficientDiskSpaceException` with detailed info
  - Shows required vs available space in MB
- Prevents corruption from partial writes
- Prevents system-wide issues from disk exhaustion
- Files changed: `IosFileStorage.kt`, `AppendOnlyQueue.kt`

### 🎯 Benefits
- CRC32 validation prevents silent corruption
- Migration preserves all existing data
- Dynamic timeouts maximize BGTask usage
- Disk checks prevent cryptic failures

### 📝 Files Changed
- Added: `CRC32.kt`, `CRC32Test.kt`, `BGTaskType.kt`
- Modified: `AppendOnlyQueue.kt`, `IosFileStorage.kt`, `ChainExecutor.kt`, `NativeTaskScheduler.kt`

### ⚠️ Migration Notes

**Automatic Binary Format Migration**
When upgrading from v2.1.x to v2.2.0+:
- First launch will automatically migrate queue.jsonl to binary format
- Migration is safe and preserves all data
- Legacy file renamed to queue.jsonl.legacy
- No manual intervention required
- If migration fails, rollback restores original file

**Performance Impact**
- Binary format is slightly larger than text (CRC32 overhead)
- Read/write performance similar to text format
- CRC validation adds <1ms per item
- Migration is one-time cost (<5s for 1000 items)

---

## [2.1.2] - 2026-01-29

### 🔴 Critical Fixes

**Queue Corruption Self-Healing (SEVERITY 1)**
- Implemented automatic recovery from corrupted queue files:
  - Added `isQueueCorrupt` flag with `corruptionMutex`
  - Updated `readSingleLine()` to catch corruption and set flag
  - Removed character validation - trusts JSON parser (prevents Unicode/Emoji false positives)
  - Added `resetQueueInternal()` for safe queue reset
  - Updated `dequeue()` with double-mutex pattern to prevent race conditions:
    - Mutex order: `corruptionMutex` → `queueMutex` (prevents deadlock)
    - Double-check pattern inside lock (prevents TOCTOU)
- Added `resetQueue()` public API for manual reset
- Added `CorruptQueueException` for clear error signaling
- Files changed: `AppendOnlyQueue.kt`
- Tests added: `QueueCorruptionTest.kt` (truncated files, Unicode, race conditions)

**iOS Chain ExistingPolicy Support**
- Implemented KEEP and REPLACE policies for chain enqueuing:
  - **KEEP**: Skips enqueue if chain ID already exists
  - **REPLACE**: Deletes old chain, marks as deleted, enqueues new definition
- Added deleted chain marker system:
  - Markers prevent duplicate execution during REPLACE
  - 7-day TTL to prevent disk space leaks
  - Automatic cleanup via maintenance tasks
- Added maintenance task scheduling:
  - Runs 5s after IosFileStorage init (prevents blocking app launch)
  - Cleans up stale deleted markers (>7 days old)
  - File enumeration only once per app launch
- Updated chain execution to check deleted markers:
  - Skips chains marked as deleted
  - Clears marker after skip
- Files changed: `IosFileStorage.kt`, `ChainExecutor.kt`, `NativeTaskScheduler.kt`, `BackgroundTaskScheduler.kt`, `TaskChain.kt`
- Files added: `ExistingPolicyTest.kt`

**Android Foreground Service Type Validation (Android 14+)**
- Added validation for FOREGROUND_SERVICE_TYPE with FAIL OPEN strategy:
  - Validates service type matches AndroidManifest.xml declaration
  - Validates required permissions granted
  - **FAIL OPEN**: Catches ALL exceptions for Chinese ROM compatibility
  - Fallback to DATA_SYNC if validation fails
  - Broad exception handling for manufacturer-modified Android
- Added `validateForegroundServiceType()` with:
  - `@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)`
  - Specific SecurityException re-throwing
  - Generic exception catching with logging
- Updated `createForegroundInfo()` to use validation
- Updated `doWork()` with detailed error messages
- Files changed: `KmpHeavyWorker.kt`
- Files added: `AndroidValidationTest.kt`

### 🐛 Bug Fixes

**Race Condition Prevention**
- Fixed potential race where `resetQueue()` could delete files while `enqueue()` writes
- Double-mutex pattern ensures atomic corruption check and reset
- Mutex ordering prevents deadlocks

**Unicode/Emoji Support**
- Removed character validation that caused false positives
- Now supports Vietnamese, Chinese, Emoji in queue items
- Trusts JSON parser for validation

**Chinese ROM Compatibility**
- FAIL OPEN strategy handles unexpected exceptions from Xiaomi, Oppo, Vivo ROMs
- Prevents crashes on manufacturer-modified Android
- Detailed logging for ROM-specific issues

### 📝 API Changes

**TaskChain.withId()**
```kotlin
// Set chain ID with policy
val chain = TaskChain.create(scheduler)
    .thenParallel(listOf(task1, task2))
    .withId("my-chain", ExistingPolicy.KEEP)
    .enqueue()
```

**BackgroundTaskScheduler.enqueueChain()**
```kotlin
// Old signature (still works)
fun enqueueChain(chain: TaskChain, id: String? = null)

// New signature (with policy)
fun enqueueChain(
    chain: TaskChain,
    id: String? = null,
    policy: ExistingPolicy = ExistingPolicy.REPLACE
)
```

### ⚠️ Migration Notes

**No Breaking Changes**
- All changes are backward compatible
- Default policy: `ExistingPolicy.REPLACE` (matches previous behavior)
- Existing code continues to work unchanged

**Recommended Actions**
1. Review chain enqueue calls and add explicit policies where needed
2. For Android 14+ apps: Verify AndroidManifest.xml foreground service types match usage
3. Add comprehensive manifest documentation for service types and permissions

**Android 14+ Manifest Requirements**
```xml
<!-- Example: Location-based foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="location|dataSync"
    tools:node="merge" />
```

### 🔬 Testing
- Added QueueCorruptionTest.kt (corruption recovery, Unicode, race conditions)
- Added ExistingPolicyTest.kt (KEEP/REPLACE policies, cleanup)
- Added AndroidValidationTest.kt (service type validation, Chinese ROM exceptions)

### 📝 Files Changed
- Modified: `AppendOnlyQueue.kt`, `IosFileStorage.kt`, `ChainExecutor.kt`, `NativeTaskScheduler.kt`, `KmpHeavyWorker.kt`, `BackgroundTaskScheduler.kt`, `TaskChain.kt`
- Added: `QueueCorruptionTest.kt`, `ExistingPolicyTest.kt`, `AndroidValidationTest.kt`

---

## [2.1.1] - 2026-01-21

### 🔴 Critical Android Fix (BLOCKER)

**Configurable Foreground Service Type for Android 14+ (API 34)**
- Fixed hardcoded FOREGROUND_SERVICE_TYPE_DATA_SYNC in KmpHeavyWorker
- **Issue**: Apps using location/media/camera would crash with SecurityException on Android 14+
- **Previous behavior**: Always used DATA_SYNC type, incompatible with location/media apps
- **New behavior**: Service type now configurable via inputData
- **Impact**: Library now works for ALL use cases (location tracking, media playback, camera, etc.)
- **Migration**: Backward compatible - defaults to DATA_SYNC if not specified

**Example - Location Tracking:**
```kotlin
val inputData = buildJsonObject {
    put(KmpHeavyWorker.FOREGROUND_SERVICE_TYPE_KEY,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
}.toString()

scheduler.enqueue(
    id = "location-tracking",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "LocationWorker",
    constraints = Constraints(isHeavyTask = true),
    inputJson = inputData
)
```

**AndroidManifest.xml (Location):**
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="location|dataSync"
    tools:node="merge" />
```

Available types:
- FOREGROUND_SERVICE_TYPE_DATA_SYNC (default)
- FOREGROUND_SERVICE_TYPE_LOCATION
- FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
- FOREGROUND_SERVICE_TYPE_CAMERA
- FOREGROUND_SERVICE_TYPE_MICROPHONE
- FOREGROUND_SERVICE_TYPE_HEALTH
- And more...

Files changed: `KmpHeavyWorker.kt`

---

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

## [2.1.0] - 2026-01-20

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

## [2.0.2] - 2026-01-20

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
