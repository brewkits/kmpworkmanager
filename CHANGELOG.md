# Changelog

All notable changes to KMP TaskManager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1.0] - 2025-01-XX

### 🎉 Major Improvements

#### Professional Logging System
- **NEW**: Comprehensive logging framework with structured levels (DEBUG, INFO, WARN, ERROR)
- **ADDED**: Platform-specific implementations
  - Android: Uses Android Log system with proper tags
  - iOS: Uses NSLog for Xcode console integration
- **ADDED**: Emoji indicators for quick visual identification
- **ADDED**: `LogTags` constants for consistent logging across codebase
- **FILES**:
  - `commonMain/kotlin/utils/Logger.kt`
  - `androidMain/kotlin/utils/LoggerPlatform.android.kt`
  - `iosMain/kotlin/utils/LoggerPlatform.ios.kt`

---

### 🔴 Critical Fixes

#### iOS - Background Task Error Handling
- **FIXED**: Critical bug in `submitTaskRequest()` error handling
  - **BEFORE**: Used incorrect try-catch that never caught errors
  - **AFTER**: Proper `memScoped` with `NSErrorPointer` for Objective-C interop
- **IMPACT**: Tasks could fail silently without notification
- **FILES**: `iosMain/.../NativeTaskScheduler.kt:215-229`

#### iOS - Task Execution Timeout Protection
- **FIXED**: No timeout protection leading to iOS throttling
  - **ADDED**: 25s timeout for `SingleTaskExecutor` (5s margin for BGAppRefreshTask 30s limit)
  - **ADDED**: 50s timeout for `ChainExecutor` (10s margin for BGProcessingTask 60s limit)
  - **ADDED**: Per-task timeout of 20s in chain execution
- **IMPACT**: Prevents iOS from marking app as misbehaving
- **FILES**:
  - `iosMain/.../SingleTaskExecutor.kt`
  - `iosMain/.../ChainExecutor.kt`

#### Android - Notification Channel Creation
- **FIXED**: Alarm notifications not showing on Android 8.0+ (API 26+)
  - **CAUSE**: Missing notification channel creation
  - **SOLUTION**: Proper channel creation with existence check
- **IMPACT**: Exact alarms now display notifications correctly
- **FILES**: `androidMain/.../AlarmReceiver.kt:60-85`

#### Android - POST_NOTIFICATIONS Permission
- **FIXED**: Notifications failing on Android 13+ (API 33+)
  - **ADDED**: Runtime permission request for `POST_NOTIFICATIONS`
  - **ADDED**: `rememberNotificationPermissionState()` composable
  - **ADDED**: Lifecycle-aware permission checking
- **IMPACT**: Notifications now work on latest Android versions
- **FILES**: `androidMain/.../PlatformPermissions.kt:82-140`

---

### 🟡 High Priority Fixes

#### iOS - ExistingPolicy Implementation
- **FIXED**: `KEEP` and `REPLACE` policies were completely ignored
  - **ADDED**: Metadata existence checking for KEEP policy
  - **ADDED**: Automatic cancellation before re-scheduling for REPLACE policy
- **IMPACT**: Consistent behavior with Android implementation
- **FILES**: `iosMain/.../NativeTaskScheduler.kt:172-194`

#### iOS - Task ID Validation
- **FIXED**: Silent failures when using task IDs not in Info.plist
  - **ADDED**: `PERMITTED_TASK_IDS` constant matching Info.plist
  - **ADDED**: Validation before submitting to BGTaskScheduler
  - **ADDED**: Clear error messages with permitted IDs list
- **IMPACT**: Developers immediately know when using invalid task IDs
- **FILES**: `iosMain/.../NativeTaskScheduler.kt:48-99`

#### iOS - Memory Leak Prevention
- **FIXED**: CoroutineScope in executors never cancelled
  - **ADDED**: `cleanup()` methods with `SupervisorJob` cancellation
  - **ADDED**: Proper lifecycle management
- **IMPACT**: Prevents memory leaks if executors are recreated
- **FILES**:
  - `SingleTaskExecutor.kt:96-99`
  - `ChainExecutor.kt:250-253`

---

### 🟢 Medium Priority Improvements

#### iOS - Batch Chain Processing
- **ADDED**: `executeChainsInBatch()` method to process multiple chains per BGTask
  - **FEATURE**: Time-aware execution with remaining time checking
  - **FEATURE**: Configurable max chains (default: 3)
  - **FEATURE**: Returns count of successfully executed chains
- **BENEFIT**: Reduces iOS BGTask invocations, faster chain processing
- **FILES**:
  - `iosMain/.../ChainExecutor.kt:59-96`
  - `iosApp/iosApp/iOSApp.swift:279-329`

#### Enhanced Constraints System
- **ADDED**: Backoff policy customization for failed tasks
  - **NEW FIELDS**:
    - `backoffPolicy: BackoffPolicy` (LINEAR or EXPONENTIAL)
    - `backoffDelayMs: Long` (default: 30,000ms)
  - **PLATFORM**: Android WorkManager only
- **BENEFIT**: Fine-tuned retry behavior for failed tasks
- **FILES**: `commonMain/.../Contracts.kt:306-338`

#### Comprehensive API Documentation
- **ADDED**: Extensive KDoc for all public APIs
  - **COVERAGE**: TaskTrigger (9 types), Constraints, Qos, Policies
  - **DETAILS**: Platform support, use cases, examples, limitations
  - **CLARITY**: Clear warnings for Android-only vs iOS-only features
- **BENEFIT**: Better developer experience, fewer integration errors
- **FILES**: `commonMain/.../Contracts.kt` (470 lines of documentation)

---

### 📝 Documentation Improvements

#### Trigger Type Clarifications
- **CLARIFIED**: Battery/Storage triggers are CONSTRAINTS, not active triggers
  - **DOCUMENTED**: Use BroadcastReceiver for active monitoring
  - **ADDED**: Warning sections in KDoc
- **CLARIFIED**: Platform support matrix in every trigger type
- **ADDED**: Code examples for all trigger types

#### Platform Differences
- **DOCUMENTED**: Exact differences between Android and iOS implementations
- **ADDED**: Time limits for iOS BGTasks (30s vs 60s)
- **ADDED**: WorkManager interval minimums (15 minutes)
- **ADDED**: Permission requirements per platform

---

### 🔧 Technical Improvements

#### Error Handling
- **IMPROVED**: Consistent error handling across all components
- **ADDED**: Task completion event emission on errors
- **ADDED**: Proper exception catching with logging

#### Logging Consistency
- **REPLACED**: All `println()` calls with structured `Logger` calls
- **STANDARDIZED**: Log tags across Android and iOS
- **ADDED**: Error stack traces in logs

#### Code Organization
- **REFACTORED**: Extracted helper methods in NativeTaskScheduler
  - `validateTaskId()`, `handleExistingPolicy()`, `submitTaskRequest()`
- **IMPROVED**: Method naming and documentation
- **ADDED**: Companion objects for constants

---

### ⚠️ Breaking Changes

**NONE** - All changes are backward compatible.

New Constraints fields (`backoffPolicy`, `backoffDelayMs`) have default values, so existing code continues to work without modifications.

---

### 📦 Migration Guide

#### For Existing Projects

No migration required! All changes are additive and backward compatible.

**Optional Enhancements**:

1. **Add Notification Permission Request** (Android 13+):
```kotlin
@Composable
fun YourScreen() {
    val notificationPermission = rememberNotificationPermissionState()

    if (notificationPermission.shouldShowRequest) {
        Button(onClick = { notificationPermission.requestPermission() }) {
            Text("Enable Notifications")
        }
    }
}
```

2. **Customize Backoff Policy** (Android):
```kotlin
scheduler.enqueue(
    id = "upload-task",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "UploadWorker",
    constraints = Constraints(
        backoffPolicy = BackoffPolicy.LINEAR,
        backoffDelayMs = 60_000  // 1 minute constant retry delay
    )
)
```

3. **Use Batch Processing** (iOS - automatic):
The Swift code now automatically uses batch processing for chain execution. No code changes required.

---

### 🐛 Bug Fixes Summary

| Priority | Component | Issue | Fix |
|----------|-----------|-------|-----|
| 🔴 Critical | iOS Scheduler | Error handling broken | Proper NSError handling |
| 🔴 Critical | iOS Executor | No timeout protection | Added 25s/50s timeouts |
| 🔴 Critical | Android Alarm | Notifications not showing | Channel creation |
| 🔴 Critical | Android Permissions | POST_NOTIFICATIONS missing | Runtime permission |
| 🟡 High | iOS Scheduler | ExistingPolicy ignored | Implemented KEEP/REPLACE |
| 🟡 High | iOS Scheduler | Silent task ID failures | Validation with errors |
| 🟡 High | iOS Executor | Memory leak | Cleanup methods |
| 🟢 Medium | iOS Chain | Slow processing | Batch execution |

---

### 📊 Performance Improvements

- **iOS Chain Execution**: Up to 3x faster with batch processing
- **Memory Usage**: Reduced leaks with proper cleanup
- **Battery Impact**: Better timeout management prevents iOS throttling
- **Network Usage**: Improved with proper constraint handling

---

### 🎯 Testing Recommendations

After upgrading, test these scenarios:

1. **Exact Alarms** (Android 13+): Verify notification permission request
2. **Background Tasks** (iOS): Verify faster chain execution
3. **Failed Tasks** (Android): Verify backoff policy behavior
4. **Task Cancellation**: Verify KEEP/REPLACE policy works correctly
5. **Long Tasks** (Both): Verify timeout protection kicks in

---

### 🙏 Acknowledgments

This release includes significant fixes and improvements based on comprehensive code review and platform best practices analysis.

**Key Contributors**:
- Logger system design
- iOS error handling deep dive
- Android permission lifecycle management
- Comprehensive documentation review

---

### 📞 Support

For questions or issues:
- GitHub Issues: [github.com/yourrepo/issues](https://github.com/yourrepo/issues)
- Documentation: [README.md](README.md)

---

**Full Changelog**: v2.0.0...v2.1.0
