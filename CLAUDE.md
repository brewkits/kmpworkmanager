# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KMP TaskManager is a Kotlin Multiplatform (KMP) framework demonstrating a unified API for scheduling and executing background tasks on both Android and iOS. It's an educational reference implementation showcasing how to abstract platform-specific background task mechanisms (WorkManager on Android, BGTaskScheduler on iOS) behind a common interface.

## Build and Run Commands

### Building the Project
```bash
# Sync Gradle dependencies
./gradlew clean build

# Build Android APK
./gradlew assembleDebug

# Run Android tests
./gradlew testDebug
```

### Running the Application
- **Android**: Open in Android Studio, select `composeApp` configuration, and run on emulator/device
- **iOS**: Open `iosApp/iosApp.xcodeproj` in Xcode, select simulator/device, and run

### Requirements
- JDK 17
- Android Studio Iguana (2023.2.1+) with KMP plugin
- Xcode 15.0+ (for iOS development)
- Gradle 8.x

## Architecture

### Expect/Actual Pattern
The project uses KMP's `expect`/`actual` mechanism to provide platform-specific implementations:
- `expect class NativeTaskScheduler` defined in `commonMain/background/domain/BackgroundTaskScheduler.kt`
- `actual class NativeTaskScheduler` implemented in:
  - `androidMain/background/data/NativeTaskScheduler.kt` (uses WorkManager & AlarmManager)
  - `iosMain/background/data/NativeTaskScheduler.kt` (uses BGTaskScheduler & UNUserNotificationCenter)

### Dependency Injection
The project uses Koin for DI across all platforms:
- Common modules defined in `commonMain/di/`
- Platform-specific modules in `androidMain/di/` and `iosMain/di/`
- iOS initialization occurs in Swift (`iOSApp.swift`) before KMP code runs
- Android initialization in `MainActivity.kt` or custom Application class

### Core Components

#### BackgroundTaskScheduler Interface
The central abstraction (`commonMain/background/domain/BackgroundTaskScheduler.kt`) provides:
- `enqueue()`: Schedule tasks with various triggers (Periodic, OneTime, Exact, Windowed)
- `cancel()` / `cancelAll()`: Cancel scheduled tasks
- `beginWith()`: Start a task chain (sequential/parallel execution)
- `enqueueChain()`: Execute a constructed task chain

#### Task Triggers (`commonMain/background/domain/Contracts.kt`)
- `TaskTrigger.Periodic`: Repeating tasks with interval
- `TaskTrigger.OneTime`: Single execution with optional delay
- `TaskTrigger.Exact`: Time-critical tasks (uses AlarmManager on Android, notifications on iOS)
- `TaskTrigger.Windowed`: Android-only windowed execution

#### Constraints
Defined in `Constraints` data class:
- `requiresNetwork` / `requiresUnmeteredNetwork`
- `requiresCharging`
- `isHeavyTask`: Uses ForegroundService (Android) or BGProcessingTask (iOS)
- `qos`: Quality of Service hint for iOS priority

### Platform-Specific Details

#### Android Implementation
- `KmpWorker`: A `CoroutineWorker` that bridges WorkManager to shared logic
- `KmpHeavyWorker`: For long-running tasks requiring foreground service
- `AlarmReceiver`: BroadcastReceiver for exact-time alarms
- Worker class names passed as strings via `inputData` to `KmpWorker`

#### iOS Implementation
- `IosWorker` interface: All iOS background workers implement this
- Workers stored in `background/workers/` (e.g., `SyncWorker`, `UploadWorker`, `HeavyProcessingWorker`)
- `IosWorkerFactory`: Maps worker class names to concrete implementations
- `SingleTaskExecutor`: Executes individual tasks
- `ChainExecutor`: Processes task chains using queue in `NSUserDefaults`
- Task metadata stored in `NSUserDefaults` with prefixes:
  - `kmp_task_meta_`: One-time task metadata
  - `kmp_periodic_meta_`: Periodic task metadata
  - `kmp_chain_definition_`: Serialized chain definitions
  - `kmp_chain_queue`: Queue of chain IDs to process

#### iOS Background Task Registration
In `iOSApp.swift`, all task identifiers must be:
1. Registered in `registerBackgroundTasks()` with handlers
2. Declared in `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`

Task types handled:
- `kmp_chain_executor_task`: Generic executor for all chains
- Individual task IDs for periodic/one-time tasks

### Task Chains
Task chains allow sequential and parallel execution:
```kotlin
scheduler.beginWith(task1)
    .then(task2)                    // Sequential
    .then(listOf(task3, task4))     // Parallel
    .enqueue()
```

**Android**: Uses WorkManager's `beginWith()` and `then()` API
**iOS**: Serializes chain to JSON, stores in UserDefaults, executes step-by-step via `ChainExecutor`

### Push Notifications
Both platforms support remote push notifications:
- **Android**: `PushReceiver.kt` handles incoming FCM messages
- **iOS**: `AppDelegate` handles APNs registration and delivery
- Shared handler in `commonMain/push/PushNotificationHandler.kt`
- iOS can schedule background tasks in response to silent push notifications

## Common Development Patterns

### Adding a New Background Worker

**Android:**
1. Create worker logic in `commonMain` or `androidMain`
2. Reference it by string name in `enqueue()` call
3. `KmpWorker` dynamically routes to appropriate logic

**iOS:**
1. Create class implementing `IosWorker` in `iosMain/background/workers/`
2. Register in `IosWorkerFactory` mapping
3. Add task identifier to `Info.plist` and `iOSApp.swift` registration
4. Use worker class name when calling `enqueue()`

### Debugging Background Tasks

**Android:**
- Use Logcat and filter by `KMP_BG_TASK`
- Inspect WorkManager state: `adb shell dumpsys jobscheduler`
- Test with `WorkManager.getInstance(context).getWorkInfoByIdLiveData()`

**iOS:**
- Check Console.app for logs with `KMP_BG_TASK_iOS` prefix
- Simulate background tasks in Xcode: `e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"your-task-id"]`
- Verify task registration in `BGTaskScheduler`

### Key File Locations
- Shared contracts: `composeApp/src/commonMain/kotlin/com/example/kmpworkmanagerv2/background/domain/`
- Android implementation: `composeApp/src/androidMain/kotlin/com/example/kmpworkmanagerv2/background/data/`
- iOS implementation: `composeApp/src/iosMain/kotlin/com/example/kmpworkmanagerv2/background/data/`
- iOS native entry: `iosApp/iosApp/iOSApp.swift`
- UI: `composeApp/src/commonMain/kotlin/com/example/kmpworkmanagerv2/App.kt`

## Important Notes

- iOS background tasks have OS-imposed limits (30 seconds for BGAppRefreshTask, several minutes for BGProcessingTask)
- Exact alarms on Android 12+ require `SCHEDULE_EXACT_ALARM` permission
- iOS periodic tasks are "best effort" - OS decides actual execution time
- Task chains on iOS execute one chain at a time via a shared executor task
- All iOS background task identifiers must be pre-registered in `Info.plist`
- The `-Xexpect-actual-classes` compiler flag is required (set in `composeApp/build.gradle.kts`)
