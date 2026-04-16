# 🏗️ KMP WorkManager Architecture

This document provides a comprehensive overview of the architecture, design decisions, and implementation details of KMP WorkManager.

## 📐 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         User Application                         │
│  ┌──────────────────┐                  ┌──────────────────────┐ │
│  │  Compose UI / UIKit                 │   Business Logic    │ │
│  └──────────┬───────┘                  └──────────┬───────────┘ │
│             │                                     │             │
│             └─────────────────┬───────────────────┘             │
└───────────────────────────────┼─────────────────────────────────┘
                                │
                ┌───────────────▼───────────────┐
                │ KMP WorkManager Library  │
                │  (Kotlin Multiplatform)       │
                └───────────────┬───────────────┘
                                │
        ┌───────────────────────┴───────────────────────┐
        │                                               │
┌───────▼────────┐                            ┌────────▼────────┐
│  Android Layer │                            │   iOS Layer     │
│  WorkManager   │                            │ BGTaskScheduler │
│  AlarmManager  │                            │ UNNotification  │
└────────────────┘                            └─────────────────┘
```

## 🎯 Core Design Principles

### 1. **Platform Abstraction**
- Common interface defined in `commonMain`
- Platform-specific implementations in `androidMain` and `iosMain`
- Users interact only with common API, platform details are hidden

### 2. **Type Safety**
- Sealed interfaces for triggers and results
- Enum classes for constraints and policies
- Compile-time guarantees for valid configurations

### 3. **Flexibility**
- Support for one-time, periodic, exact, and conditional triggers
- Granular constraints (network, battery, charging, etc.)
- Task chains for complex workflows

### 4. **Reliability**
- Automatic retry with configurable backoff policies
- Constraint-aware execution (won't run if conditions not met)
- Persistent scheduling (survives app restart and device reboot)

### 5. **Observable**
- EventBus for task completion events
- Debug tools for monitoring scheduled tasks
- Structured logging with platform-specific implementations

## 📦 Module Structure

```
kmpworkmanager/
├── commonMain/
│   ├── background/
│   │   ├── domain/           # Public API interfaces
│   │   │   ├── BackgroundTaskScheduler.kt
│   │   │   ├── Contracts.kt  # TaskTrigger, Constraints, etc.
│   │   │   ├── TaskChain.kt
│   │   │   ├── TaskCompletionEvent.kt
│   │   │   └── TaskTriggerHelper.kt
│   │   └── data/             # Shared implementation
│   │       ├── NativeTaskScheduler.kt (expect)
│   │       └── TaskIds.kt
│   └── utils/
│       └── Logger.kt         # Cross-platform logging
├── androidMain/
│   ├── background/
│   │   └── data/
│   │       ├── NativeTaskScheduler.kt (actual)
│   │       └── KmpWorker.kt
│   ├── utils/
│   │   └── LoggerPlatform.android.kt (actual)
│   └── KoinModule.android.kt
├── iosMain/
│   ├── background/
│   │   └── data/
│   │       ├── NativeTaskScheduler.kt (actual)
│   │       ├── IosWorker.kt
│   │       ├── ChainExecutor.kt
│   │       └── SingleTaskExecutor.kt
│   ├── utils/
│   │   └── LoggerPlatform.ios.kt (actual)
│   └── KoinModule.ios.kt
└── commonTest/
    └── dev.brewkits/kmpworkmanager/
        ├── ContractsTest.kt
        ├── TaskChainTest.kt
        ├── UtilsTest.kt
        ├── TaskEventTest.kt
        ├── TaskTriggerHelperTest.kt
        ├── SerializationTest.kt
        └── EdgeCasesTest.kt
```

## 🔄 Data Flow

### Scheduling a Task

```
User Code
    │
    ├─► scheduler.enqueue(id, trigger, workerClassName, constraints)
    │
    ▼
BackgroundTaskScheduler (interface)
    │
    ├─► Android: NativeTaskScheduler.kt (androidMain)
    │   │
    │   ├─► Builds WorkManager constraints
    │   ├─► Creates OneTimeWorkRequest or PeriodicWorkRequest
    │   ├─► Enqueues to WorkManager
    │   └─► Returns ScheduleResult
    │
    └─► iOS: NativeTaskScheduler.kt (iosMain)
        │
        ├─► Validates task ID against Info.plist
        ├─► Creates BGAppRefreshTaskRequest or BGProcessingTaskRequest
        ├─► Submits to BGTaskScheduler
        ├─► Stores metadata in File Storage (IosFileStorage)
        └─► Returns ScheduleResult
```

### Task Execution

#### Android Flow

```
WorkManager
    │
    ├─► Checks constraints (network, battery, etc.)
    │
    ├─► If constraints met:
    │   │
    │   └─► Executes KmpWorker.doWork()
    │       │
    │       ├─► Reads workerClassName from inputData
    │       ├─► Executes corresponding worker logic
    │       ├─► Emits TaskCompletionEvent to EventBus
    │       └─► Returns Result (success/failure/retry)
    │
    └─► If constraints not met: Waits until met
```

#### iOS Flow

```
BGTaskScheduler
    │
    ├─► iOS decides when to launch task (based on battery, usage, etc.)
    │
    └─► Launches BGTask:
        │
        ├─► SingleTaskExecutor.execute() OR ChainExecutor.execute()
        │   │
        │   ├─► Retrieves metadata from IosFileStorage
        │   ├─► Creates worker via IosWorkerFactory
        │   ├─► Executes worker.doWork() with timeout protection
        │   ├─► Emits TaskCompletionEvent to EventBus
        │   └─► Calls task.setTaskCompleted(success: Bool)
        │
        └─► If periodic: Reschedules itself
```

## 🧩 Component Details

### 1. TaskTrigger (Sealed Interface)

Defines **when** a task should run:

```kotlin
sealed interface TaskTrigger {
    data class OneTime(val initialDelayMs: Long = 0)
    data class Periodic(val intervalMs: Long, val flexMs: Long? = null)
    data class Exact(val atEpochMillis: Long)
    data class Windowed(val earliest: Long, val latest: Long)
    data class ContentUri(val uriString: String, val triggerForDescendants: Boolean = false)
    data object StorageLow
    data object BatteryLow
    data object BatteryOkay
    data object DeviceIdle
}
```

**Platform Support Matrix:**

| Trigger | Android | iOS | Notes |
|---------|---------|-----|-------|
| OneTime | ✅ | ✅ | WorkManager / BGAppRefreshTask |
| Periodic | ✅ | ✅ | Min 15min (Android) / iOS decides |
| Exact | ✅ | ✅ | AlarmManager / UNNotification |
| Windowed | ❌ | ❌ | Not implemented |
| ContentUri | ✅ | ❌ | Android only |
| Battery* | ✅ | ❌ | Android only |
| StorageLow | ✅ | ❌ | Android only |
| DeviceIdle | ✅ | ❌ | Android only |

### 2. Constraints (Data Class)

Defines **conditions** for task execution:

```kotlin
data class Constraints(
    val requiresNetwork: Boolean = false,
    val requiresUnmeteredNetwork: Boolean = false,
    val requiresCharging: Boolean = false,
    val allowWhileIdle: Boolean = false,
    val qos: Qos = Qos.Background,
    val isHeavyTask: Boolean = false,
    val backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL,
    val backoffDelayMs: Long = 30_000
)
```

**Platform Mapping:**

| Constraint | Android Implementation | iOS Implementation |
|------------|------------------------|-------------------|
| requiresNetwork | `NetworkType.CONNECTED` | `requiresNetworkConnectivity` (BGProcessingTask only) |
| requiresUnmeteredNetwork | `NetworkType.UNMETERED` | Falls back to requiresNetwork |
| requiresCharging | `setRequiresCharging(true)` | `requiresExternalPower` (BGProcessingTask only) |
| allowWhileIdle | `setExactAndAllowWhileIdle()` | N/A |
| qos | Ignored (WorkManager handles) | `DispatchQoS` for task priority |
| isHeavyTask | `ForegroundService` | `BGProcessingTask` (60s vs 30s) |
| backoffPolicy | `BackoffPolicy.EXPONENTIAL/LINEAR` | Manual retry required |
| backoffDelayMs | Initial retry delay | Manual retry required |

### 3. TaskChain

Enables complex workflows with sequential and parallel execution:

```kotlin
scheduler.beginWith(TaskRequest("Step1"))
    .then(
        listOf(
            TaskRequest("Step2A"),
            TaskRequest("Step2B")  // Parallel
        )
    )
    .then(TaskRequest("Step3"))
    .enqueue()
```

**Android Implementation:**
- Uses WorkManager's `WorkContinuation` API
- Native parallel execution support
- Automatic dependency management

**iOS Implementation:**
- Delegation to native Kotlin `IosBackgroundTaskHandler`
- ChainExecutor processes chains step-by-step
- Parallel tasks use `coroutineScope { async { } }.awaitAll()` — each task's result is collected to determine per-task success
- Per-task completion tracked in `ChainProgress.completedTasksInSteps`; on retry only failed tasks re-execute, already-succeeded tasks are skipped (v2.2.1+)
- Limited by 30s/60s execution windows

### 4. Logger System

Platform-agnostic logging with 4 levels:

```kotlin
Logger.d(LogTags.SCHEDULER, "Task scheduled")  // Debug
Logger.i(LogTags.WORKER, "Task executing")     // Info
Logger.w(LogTags.CHAIN, "Chain delayed")       // Warning
Logger.e(LogTags.ERROR, "Task failed")         // Error
```

**Android:** Uses `android.util.Log`
**iOS:** Uses `platform.Foundation.NSLog`

### 5. EventBus Pattern

Decoupled communication between workers and UI:

```kotlin
// Worker emits event
TaskEventBus.emit(
    TaskCompletionEvent("Upload", success = true, "Uploaded 100MB")
)

// UI listens
LaunchedEffect(Unit) {
    TaskEventBus.events.collect { event ->
        showToast(event.message)
    }
}
```

**Implementation:**
- `MutableSharedFlow<TaskCompletionEvent>` in commonMain
- `replay = 0` (no caching)
- `extraBufferCapacity = 64` (buffering for slow collectors)

## 🔐 Platform-Specific Details

### Android: WorkManager Integration

**Why WorkManager?**
- Deferrable tasks that survive app/device restart
- Constraint-aware execution
- Battery-friendly (uses JobScheduler, AlarmManager, BroadcastReceiver internally)
- Guaranteed execution (even in Doze mode with proper constraints)

**Worker Implementation:**

```kotlin
class KmpWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val workerClassName = inputData.getString("workerClassName")
        // Execute logic based on workerClassName
        // Emit events to EventBus
        return Result.success() // or failure() or retry()
    }
}
```

**Key Features:**
- Expedited work for light tasks (`OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`)
- Foreground service for heavy tasks (`setForeground()`)
- Automatic retry with exponential backoff
- Constraint combinations (network AND charging AND battery, etc.)

### iOS: BGTaskScheduler Integration

**Why BGTaskScheduler?**
- Modern iOS background execution API (iOS 13+)
- Replaces deprecated background modes
- System-optimized execution (considers battery, usage patterns)
- Two task types: `BGAppRefreshTask` (30s) and `BGProcessingTask` (60s)

**Task Registration (Info.plist):**

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>one-time-upload</string>
    <string>periodic-sync-task</string>
    <string>heavy-task-1</string>
</array>
```

**Worker Factory Pattern:**

```kotlin
class MyWorkerFactory : IosWorkerFactory {
    override fun createWorker(workerClassName: String): IosWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            else -> null
        }
    }
}
```

**Key Challenges:**
- **Non-deterministic execution**: iOS decides when to run tasks
- **Time limits**: 30s for refresh, 60s for processing
- **Force-quit kills tasks**: User swiping app = no background execution
- **Low Power Mode**: Severely delays task execution
- **No native retry**: Must manually reschedule on failure

**Solutions:**
- Timeout protection with `withTimeout()` — callers can pass `deadlineEpochMs` for dynamic time-slicing against the actual BGTask deadline (v2.2.1+)
- Metadata storage in IosFileStorage for task tracking
- Chain batching (execute up to 3 chains per invocation)
- ExistingPolicy support with KEEP/REPLACE
- Queue corruption recovery via truncation at the first bad record — preserves all valid data before the corruption point (v2.2.1+)
- All persisted JSON uses `ignoreUnknownKeys = true` for forward/backward schema compatibility (v2.2.1+)

## 🧪 Testing Strategy

### Unit Tests (commonTest)

Tests common business logic without platform dependencies:

- **ContractsTest**: TaskTrigger, Constraints, enums
- **TaskChainTest**: Chain building, validation
- **UtilsTest**: Utility classes, constants
- **TaskEventTest**: EventBus, events
- **SerializationTest**: JSON serialization
- **EdgeCasesTest**: Boundary conditions

**Coverage:** ~101 test cases covering common code

### Integration Tests (Manual)

Platform-specific tests require actual devices/emulators:

**Android:**
```bash
# Schedule task
adb shell am start -n com.example/.MainActivity
# Wait for execution
adb logcat | grep "KMP_Worker"
```

**iOS:**
```bash
# Simulate task launch (Xcode LLDB)
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] \
  _simulateLaunchForTaskWithIdentifier:@"one-time-upload"]
```

### Demo App

Comprehensive test scenarios in 6 tabs:
1. Quick foreground tests (EventBus, simulations)
2. Background task scheduling
3. Task chains (sequential/parallel)
4. Exact alarms and push notifications
5. Permission management
6. Debug inspector

## 🎯 Design Decisions & Trade-offs

### 1. Expect/Actual vs Interfaces

**Decision:** Use `expect class NativeTaskScheduler`

**Rationale:**
- Direct platform API access
- No additional abstraction layer
- Better Koin integration (can `expect` object)
- Clear platform-specific implementations

**Trade-off:**
- Can't mock in commonTest (need platform-specific tests)
- Slightly more complex than pure interfaces

### 2. TaskChain API Design

**Decision:** Fluent builder pattern

```kotlin
scheduler.beginWith(task1)
    .then(task2)
    .then(listOf(task3, task4))  // parallel
    .enqueue()
```

**Rationale:**
- Intuitive API (reads like English)
- Type-safe (compile-time validation)
- Flexible (mix sequential and parallel)

**Trade-off:**
- iOS implementation more complex (custom serialization)
- Can't dynamically modify chain after creation

### 3. EventBus vs Callbacks

**Decision:** SharedFlow-based EventBus

**Rationale:**
- Reactive, Kotlin-idiomatic
- Decouples workers from UI
- Multiple collectors supported
- No callback hell

**Trade-off:**
- Events emitted without collector are lost (replay = 0)
- Requires coroutine scope in UI

### 4. Constraint Validation

**Decision:** Accept all constraints, reject at schedule time

**Rationale:**
- Provides flexibility
- Returns clear `ScheduleResult.REJECTED_OS_POLICY`
- User can handle unsupported constraints gracefully

**Trade-off:**
- Runtime errors instead of compile-time
- Users must test on both platforms

### 5. iOS Chain Execution

**Decision:** Custom executor instead of native dependency graph

**Rationale:**
- BGTaskScheduler doesn't support task dependencies
- Full control over execution order
- Can batch multiple chains in single invocation

**Trade-off:**
- More complex implementation
- Limited by 30s/60s total execution time
- Serialization overhead

## 📊 Performance Characteristics

### Memory Footprint

- **Library size**: ~150KB (Android AAR), ~200KB (iOS Framework)
- **Runtime overhead**: < 5MB additional memory
- **Metadata storage**: ~1KB per task (IosFileStorage on iOS)

### Execution Latency

| Operation | Android | iOS |
|-----------|---------|-----|
| Schedule task | < 10ms | < 50ms |
| Task start (constraints met) | Immediate | OS-dependent (0s - hours) |
| Event emission | < 1ms | < 1ms |
| Chain serialization | N/A | < 5ms |

### Battery Impact

- **Android**: < 0.5% per day (typical usage with 10 periodic tasks)
- **iOS**: < 0.3% per day (iOS manages execution)

## 🔮 Future Architecture Considerations

### Planned Improvements

1. **Result Data Passing**: Enable workers to return structured data (not just Boolean)
   - Workers can return serializable results to be consumed by next task in chain
   - Useful for passing data between sequential tasks

2. **Progress Updates**: Real-time progress reporting for long-running tasks
   - Support for intermediate progress callbacks (0-100%)
   - Useful for UI progress indicators during heavy processing

3. **Advanced Worker Discovery**: Enhanced worker registration mechanisms
   - Automatic worker discovery via reflection or annotation processing
   - Reduce boilerplate in worker factory implementations
   - Note: Current worker factory pattern provides good extensibility

4. **Task History & Analytics**: Optional task execution history tracking
   - Query past task executions and their results
   - Useful for debugging and monitoring
   - Lightweight in-memory option or optional SQLite persistence

5. **Retry Strategies**: More sophisticated retry policies
   - Exponential backoff
   - Custom retry predicates based on failure type
   - Maximum retry attempts with callbacks

### Scalability Limits

- **Max concurrent tasks**: WorkManager limit (~200 on Android)
- **Max chain length**: ~50 tasks (serialization overhead on iOS)
- **Input data size**: < 10KB (WorkManager limit)
- **Total scheduled tasks**: ~1000 (recommended for performance)

## 📚 Further Reading

- [Android WorkManager Guide](https://developer.android.com/topic/libraries/architecture/workmanager)
- [iOS BGTaskScheduler](https://developer.apple.com/documentation/backgroundtasks/bgtaskscheduler)
- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html)
- [Kotlinx Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)

---

**Last Updated:** February 2026
**Version:** 2.3.1
