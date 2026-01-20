# Critical Fixes Applied in v2.1.0

## Summary
Fixed 7 critical race conditions, memory leaks, and platform-specific bugs before release.

## Fixes Applied

### 1. Race Condition in Queue Compaction (iOS)
**File**: `AppendOnlyQueue.kt`
**Issue**: `isCompacting` flag not protected by mutex, could cause duplicate compactions
**Fix**: Added `compactionMutex` for thread-safe check-and-set operation
**Impact**: Prevents queue file corruption from concurrent compaction

### 2. Continuation Crash in Task Scheduling (iOS)
**File**: `NativeTaskScheduler.kt`
**Issue**: Continuation could resume after cancellation, causing crash
**Fix**: Use `suspendCancellableCoroutine` with `isActive` check
**Impact**: Prevents app crashes when tasks cancelled rapidly

### 3. Cache Corruption During Compaction (iOS)
**File**: `AppendOnlyQueue.kt`
**Issue**: Cache invalidation timing could cause stale reads
**Fix**: Invalidate cache after file replacement within mutex lock
**Impact**: Prevents EOF errors and stale data reads

### 4. NSFileHandle Resource Leak (iOS)
**File**: `AppendOnlyQueue.kt` (2 locations)
**Issue**: File handles not always closed on exception
**Fix**: Explicit null check before try-finally for guaranteed cleanup
**Impact**: Prevents "Too many open files" errors

### 5. Memory Leak in Event Emission (iOS)
**File**: `ChainExecutor.kt`
**Issue**: New CoroutineScope created for each event
**Fix**: Use existing managed coroutineScope
**Impact**: Prevents unbounded memory growth

### 6. NSFileCoordinator Incorrect Usage (iOS)
**File**: `IosFileStorage.kt`
**Issue**: `actualURL` from coordinator not passed to block
**Fix**: Changed signature to `block: (NSURL) -> T` and pass actualURL
**Impact**: Ensures proper file coordination and atomic operations

### 7. Android 14+ Crash - Missing Foreground Service Type (Android)
**File**: `KmpHeavyWorker.kt`
**Issue**: Missing `foregroundServiceType` on API 34+
**Fix**: Added `FOREGROUND_SERVICE_TYPE_DATA_SYNC` for Android 14+
**Impact**: Prevents SecurityException crash on Android 14+

## Verification

**Build**: ✅ SUCCESS
**iOS Tests**: ✅ 236/236 passing (100%)
**Android Build**: ✅ SUCCESS

## Files Modified
- `kmpworker/src/iosMain/kotlin/.../AppendOnlyQueue.kt`
- `kmpworker/src/iosMain/kotlin/.../NativeTaskScheduler.kt`
- `kmpworker/src/iosMain/kotlin/.../ChainExecutor.kt`
- `kmpworker/src/iosMain/kotlin/.../IosFileStorage.kt`
- `kmpworker/src/androidMain/kotlin/.../KmpHeavyWorker.kt`
- `.gitignore`
