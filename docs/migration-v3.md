# 📦 Migration Guide: v2.x → v3.0.0

Complete guide for upgrading from KMP TaskManager v2.x to v3.0.0.

[📘 Back to README](../README.md) • [💡 Examples](examples.md)

---

## Table of Contents

1. [Overview](#overview)
2. [Breaking Changes](#breaking-changes)
3. [New Features](#new-features)
4. [Step-by-Step Migration](#step-by-step-migration)
5. [API Changes](#api-changes)
6. [Platform-Specific Changes](#platform-specific-changes)
7. [Troubleshooting](#troubleshooting)

---

## Overview

**v3.0.0** is a major release with significant performance improvements and API refinements.

### What's New

- 🚀 **60% faster iOS** - File-based storage replaces NSUserDefaults
- 🎯 **Better API** - `SystemConstraint` enum for clearer intent
- ⏰ **Smart exact alarms** - Auto-fallback on permission denial (Android)
- 🔋 **Heavy task support** - New `KmpHeavyWorker` for long-running tasks
- 🔔 **AlarmReceiver base** - Easy exact alarm implementation
- 🛡️ **Thread-safe iOS** - File locking + duplicate detection

### Migration Time

- **Simple apps**: 10-15 minutes
- **Complex apps**: 30-45 minutes
- **Breaking changes**: Minimal (deprecated, not removed)

---

## Breaking Changes

### 1. Deprecated Triggers (Backward Compatible)

These triggers are **deprecated but still work** in v3.0.0. They will be removed in v4.0.0.

#### Before (v2.x)

```kotlin
// ❌ Deprecated in v3.0.0
scheduler.enqueue(
    id = "battery-task",
    trigger = TaskTrigger.BatteryLow,
    workerClassName = "BatteryWorker"
)

scheduler.enqueue(
    id = "storage-task",
    trigger = TaskTrigger.StorageLow,
    workerClassName = "StorageWorker"
)

scheduler.enqueue(
    id = "idle-task",
    trigger = TaskTrigger.DeviceIdle,
    workerClassName = "IdleWorker"
)

scheduler.enqueue(
    id = "charging-task",
    trigger = TaskTrigger.BatteryOkay,
    workerClassName = "ChargingWorker"
)
```

#### After (v3.0.0)

```kotlin
// ✅ Use SystemConstraints instead
scheduler.enqueue(
    id = "battery-task",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "BatteryWorker",
    constraints = Constraints(
        systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY)
    )
)

scheduler.enqueue(
    id = "storage-task",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "StorageWorker",
    constraints = Constraints(
        systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE)
    )
)

scheduler.enqueue(
    id = "idle-task",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "IdleWorker",
    constraints = Constraints(
        systemConstraints = setOf(SystemConstraint.DEVICE_IDLE)
    )
)

scheduler.enqueue(
    id = "charging-task",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "ChargingWorker",
    constraints = Constraints(
        requiresCharging = true,
        systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW)
    )
)
```

**Note**: Old code still works! v3.0.0 automatically converts deprecated triggers to constraints.

---

## New Features

### 1. SystemConstraint Enum

Clearer separation between triggers (when to run) and constraints (conditions to run).

```kotlin
enum class SystemConstraint {
    ALLOW_LOW_STORAGE,        // Allow when storage < threshold
    ALLOW_LOW_BATTERY,        // Allow when battery < 15%
    REQUIRE_BATTERY_NOT_LOW,  // Require battery > 15%
    DEVICE_IDLE               // Require device idle (doze mode)
}
```

### 2. Heavy Task Support (Android)

```kotlin
// v3.0+: Long-running tasks (>10 min)
scheduler.enqueue(
    id = "video-encode",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "VideoEncoderWorker",
    constraints = Constraints(
        isHeavyTask = true // Uses foreground service with notification
    )
)
```

**Requirements**:
```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 3. AlarmReceiver Base Class (Android)

Easy exact alarm implementation with auto-fallback.

#### Step 1: Create Receiver

```kotlin
class MyAlarmReceiver : AlarmReceiver() {
    override fun handleAlarm(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?
    ) {
        val factory = KoinContext.get().get<WorkerFactory>()
        val worker = factory.createWorker(workerClassName)

        CoroutineScope(Dispatchers.IO).launch {
            worker?.doWork(inputJson)
        }
    }
}
```

#### Step 2: Register

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

<receiver
    android:name=".MyAlarmReceiver"
    android:enabled="true"
    android:exported="false" />
```

#### Step 3: Extend Scheduler

```kotlin
class MyScheduler(context: Context) : NativeTaskScheduler(context) {
    override fun getAlarmReceiverClass() = MyAlarmReceiver::class.java
}

// Koin module
single<BackgroundTaskScheduler> { MyScheduler(androidContext()) }
```

#### Step 4: Use Exact Alarms

```kotlin
scheduler.enqueue(
    id = "reminder",
    trigger = TaskTrigger.Exact(atEpochMillis = targetTime),
    workerClassName = "ReminderWorker"
)
```

**Auto-fallback**: If permission denied, automatically uses WorkManager.

### 4. iOS Performance Improvements

- **File-based storage**: 60% faster than NSUserDefaults
- **Thread-safe**: File locking prevents race conditions
- **Duplicate detection**: Prevents same chain from running twice
- **Auto-migration**: Seamlessly migrates from v2.x on first launch

**No code changes needed!** Migration happens automatically.

---

## Step-by-Step Migration

### Step 1: Update Dependency

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.brewkits:kmptaskmanager:3.0.0") // Changed
        }
    }
}
```

### Step 2: Update Deprecated Triggers

Run a search for deprecated triggers and replace them:

```bash
# Search for deprecated triggers
grep -r "TaskTrigger.BatteryLow" .
grep -r "TaskTrigger.BatteryOkay" .
grep -r "TaskTrigger.StorageLow" .
grep -r "TaskTrigger.DeviceIdle" .
```

Replace with `SystemConstraint` as shown in [Breaking Changes](#breaking-changes).

### Step 3: Add Heavy Task Support (Optional)

If you have long-running tasks:

```kotlin
// Before (v2.x)
scheduler.enqueue(
    id = "video-process",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "VideoProcessWorker"
)

// After (v3.0+)
scheduler.enqueue(
    id = "video-process",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "VideoProcessWorker",
    constraints = Constraints(
        isHeavyTask = true // Add this
    )
)
```

Add permissions:

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Step 4: Setup Exact Alarms (Optional)

If you use exact alarms, implement [AlarmReceiver](#3-alarmreceiver-base-class-android).

### Step 5: Test Migration

```bash
# Build and test
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

### Step 6: Verify iOS Migration

iOS migration is automatic. On first launch:

```
[TaskManager] Storage migration: SUCCESS - Migrated X chains, Y tasks
```

Check logs to confirm migration succeeded.

---

## API Changes

### Constraints

#### Added Fields

```kotlin
data class Constraints(
    // ... existing fields ...

    // v3.0+: New fields
    val systemConstraints: Set<SystemConstraint> = emptySet()
)
```

### SystemConstraint Enum (New)

```kotlin
enum class SystemConstraint {
    ALLOW_LOW_STORAGE,
    ALLOW_LOW_BATTERY,
    REQUIRE_BATTERY_NOT_LOW,
    DEVICE_IDLE
}
```

### NativeTaskScheduler (Android)

#### New Methods

```kotlin
open class NativeTaskScheduler(context: Context) {
    // v3.0+: Override to provide AlarmReceiver
    protected open fun getAlarmReceiverClass(): Class<out AlarmReceiver>? = null

    // v3.0+: Exact alarm scheduling with auto-fallback
    protected open fun scheduleExactAlarm(...): ScheduleResult
}
```

### AlarmReceiver (Android, New)

```kotlin
abstract class AlarmReceiver : BroadcastReceiver() {
    abstract fun handleAlarm(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?
    )

    companion object {
        const val EXTRA_TASK_ID = "io.kmp.taskmanager.TASK_ID"
        const val EXTRA_WORKER_CLASS = "io.kmp.taskmanager.WORKER_CLASS"
        const val EXTRA_INPUT_JSON = "io.kmp.taskmanager.INPUT_JSON"

        fun createNotificationChannel(context: Context)
    }
}
```

### KmpHeavyWorker (Android, New)

```kotlin
class KmpHeavyWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result
}
```

Used automatically when `isHeavyTask = true`.

---

## Platform-Specific Changes

### Android

#### 1. Exact Alarm Permission (Android 12+)

```xml
<!-- Required for exact alarms on Android 12+ -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

#### 2. Foreground Service Permission

```xml
<!-- Required for isHeavyTask = true -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

#### 3. Auto-Fallback Behavior

v3.0+ automatically falls back to WorkManager when:
- Exact alarm permission denied
- AlarmReceiver not configured

Check logs for warnings:

```
[ALARM] ⚠️ SCHEDULE_EXACT_ALARM permission denied
[ALARM] Falling back to WorkManager OneTime task
```

### iOS

#### 1. Automatic Storage Migration

v3.0+ migrates from NSUserDefaults to file-based storage on first launch.

```
[SCHEDULER] Storage migration: SUCCESS - Migrated 5 chains, 12 tasks
```

#### 2. Performance Improvements

- Queue operations: 60% faster
- Chain loading: 50% faster
- Memory usage: 40% lower

#### 3. Thread Safety

File operations now use `NSFileCoordinator` for thread-safe access.

#### 4. Duplicate Detection

Prevents same chain from running twice in batch mode.

---

## Troubleshooting

### Android

#### Exact Alarms Not Working

**Problem**: Exact alarms not firing

**Solution**:

1. Check permission:
```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

2. Implement `getAlarmReceiverClass()`:
```kotlin
class MyScheduler(context: Context) : NativeTaskScheduler(context) {
    override fun getAlarmReceiverClass() = MyAlarmReceiver::class.java
}
```

3. Register receiver:
```xml
<receiver android:name=".MyAlarmReceiver" android:exported="false" />
```

#### Heavy Tasks Crashing

**Problem**: `SecurityException: Permission Denial`

**Solution**: Add foreground service permission:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

#### Deprecation Warnings

**Problem**: Seeing deprecation warnings

**Solution**: These are intentional! Code still works. Update when convenient:

```kotlin
// Old (deprecated but works)
trigger = TaskTrigger.BatteryLow

// New (recommended)
trigger = TaskTrigger.OneTime()
constraints = Constraints(
    systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY)
)
```

### iOS

#### Migration Failed

**Problem**: `Storage migration: FAILED`

**Solution**: Check logs for details. Migration failures are non-fatal - tasks will be rescheduled.

#### Tasks Not Running

**Problem**: Tasks scheduled but not executing

**Solution**:

1. Check iOS background mode is enabled
2. Verify task IDs in `Info.plist`
3. Check device is not in Low Power Mode
4. Verify network/battery constraints are met

---

## Rollback Guide

If you need to rollback to v2.x:

### Step 1: Revert Dependency

```kotlin
implementation("io.brewkits:kmptaskmanager:2.2.2")
```

### Step 2: Remove v3.0-Specific Code

- Remove `systemConstraints` usage
- Remove `AlarmReceiver` implementation
- Remove `isHeavyTask` usage

### Step 3: iOS Storage Rollback (Optional)

v3.0 stores migration status. Rollback is safe - NSUserDefaults data is preserved.

---

## Migration Checklist

- [ ] Update dependency to v3.0.0
- [ ] Search and replace deprecated triggers
- [ ] Add `FOREGROUND_SERVICE` permission (if using heavy tasks)
- [ ] Add `SCHEDULE_EXACT_ALARM` permission (if using exact alarms)
- [ ] Implement `AlarmReceiver` (if using exact alarms)
- [ ] Extend `NativeTaskScheduler` (if using exact alarms)
- [ ] Test on Android 12+ (exact alarm permission)
- [ ] Test on iOS (verify migration)
- [ ] Run full test suite
- [ ] Update app documentation

---

## Need Help?

- 📖 [Examples](examples.md) - Code examples
- 📗 [API Reference](api-reference.md) - Complete API docs
- 💬 [GitHub Issues](https://github.com/brewkits/kmp_worker/issues) - Ask questions
- 📧 [Email Support](mailto:vietnguyentuan@gmail.com) - Direct help

---

[📘 Back to README](../README.md) • [💡 Examples](examples.md) • [📗 API Reference](api-reference.md)
