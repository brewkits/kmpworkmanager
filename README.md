# KMP TaskManager 🚀

A robust, cross-platform framework for scheduling, managing, and executing background tasks consistently on Android and iOS, built entirely with Kotlin Multiplatform.

> **Note:** This project serves as a canonical example of how to build a sophisticated abstraction layer to solve the fundamental differences in background task handling between Android and iOS.

![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)
![Version](https://img.shields.io/badge/version-2.0.0-blue.svg)

KMP TaskManager is more than just a demo application. It's a foundational framework designed to provide a single, unified API that allows developers to define and manage complex background jobs without writing platform-specific logic. It solves the "Control vs. Opportunism" problem by leveraging the power of native APIs on each operating system.

---

## ✨ Key Features

### 🎯 Cross-Platform API
* **Unified API**: Provides a common interface in `commonMain` for scheduling periodic, one-off, and time-sensitive tasks
* **9 Trigger Types**: OneTime, Periodic, Exact, Windowed, ContentUri, BatteryLow, BatteryOkay, StorageLow, DeviceIdle
* **`expect/actual` Mechanism**: Leverages KMP's expect/actual pattern and Dependency Injection (Koin) to seamlessly provide native implementations
* **Shared Business Logic**: All logic regarding "when" and "what" to execute is written once in pure Kotlin
* **Task Chains**: Sequential and parallel task execution with fluent API
* **Event-Driven Architecture**: Real-time task completion events with SharedFlow

### 🤖 Android Platform Power
* **WorkManager Integration**: Utilizes Android Jetpack's WorkManager to ensure tasks have guaranteed execution, even if the app is closed or the device reboots
* **Rich Constraint Support**: Network status, device charging state, battery level, storage, device idle
* **ContentUri Triggers**: Monitor content provider changes (MediaStore, Contacts, etc.)
* **Long-Running Task Support**: Tasks that need more than 10 minutes via ForegroundService
* **Exact Scheduling**: AlarmManager integration for time-critical tasks like reminders
* **Advanced Triggers**: Storage low, battery conditions, device idle state

### 🍎 Full iOS Compliance
* **BackgroundTasks Framework Integration**: Uses BGTaskScheduler (iOS 13+) adhering to Apple's power management rules
* **`BGAppRefreshTask` Support**: Ideal for short, quick content refresh tasks
* **`BGProcessingTask` Support**: For larger maintenance and processing jobs with optimal battery management
* **Push-Triggered Execution**: Silent APNs to execute tasks triggered by server events
* **Task Chain Execution**: Custom queue system with parallel task support
* **Graceful Degradation**: Android-only triggers properly rejected with clear messages

### 🎨 User Interface
* **6 Organized Tabs**: Test & Demo, Tasks, Task Chains, Alarms & Push, Permissions, Debug
* **Test & Demo Tab**: Instant testing without waiting for background execution
* **20+ Interactive Buttons**: Comprehensive examples for all features
* **Real-time Feedback**: Toast notifications on schedule and completion
* **Debug Screen**: Live task monitoring with color-coded status
* **Error Handling**: Clear messages for rejected or throttled tasks

---

## 🏗️ Architecture Overview

The project is built on Kotlin Multiplatform's abstraction layer architecture, which allows for a clean separation of shared logic from platform-specific implementations.

### 1. Common Layer (`commonMain`)
This is the heart of the framework:
* Defines interfaces (`BackgroundTaskScheduler`, `DebugSource`)
* Contains all shared business logic, data models, and Koin dependency injection
* Event bus system (`TaskEventBus`) for worker-to-UI communication
* UI is shared using Compose Multiplatform

### 2. Platform Layer (`androidMain` & `iosMain`)
Provides actual implementations for expect declarations:
* **`androidMain`**: WorkManager and AlarmManager implementations
* **`iosMain`**: BGTaskScheduler and local notification implementations

### Interaction Flow (Example: Scheduling a Periodic Task)
1. **Request from Common Layer**: UI in `commonMain` calls `scheduler.enqueue()`
2. **Dependency Injection**: Koin provides appropriate platform implementation
3. **Platform Execution**:
   - On Android: Creates `PeriodicWorkRequest` and enqueues with WorkManager
   - On iOS: Creates `BGAppRefreshTaskRequest` and submits to BGTaskScheduler
4. **OS Execution**: Operating system decides best time to run based on constraints
5. **Event Emission**: Worker emits `TaskCompletionEvent` when done
6. **UI Update**: Toast notification shows result with Close button

---

## 🛠️ Tech Stack

* **Language**: Kotlin 2.2.0
* **Core Framework**: Kotlin Multiplatform (KMP), Compose Multiplatform
* **Architecture**: Clean Architecture, expect/actual, Dependency Injection, Event-Driven
* **Asynchronous Programming**: Coroutines & Flow
* **Background APIs**:
  - Android: WorkManager, AlarmManager
  - iOS: BackgroundTasks Framework, UserNotifications
* **Dependency Injection**: Koin
* **Build System**: Gradle with Kotlin DSL

---

## 📖 How to Use

### Quick Start Example

```kotlin
// In commonMain - Inject the scheduler
class MyViewModel(
    private val scheduler: BackgroundTaskScheduler
) {
    suspend fun scheduleDataSync() {
        // Schedule a one-time task
        val result = scheduler.enqueue(
            id = "data-sync",
            trigger = TaskTrigger.OneTime(initialDelayMs = 5000),
            workerClassName = "SyncWorker"
        )

        when (result) {
            ScheduleResult.ACCEPTED -> println("✅ Task scheduled")
            ScheduleResult.REJECTED_OS_POLICY -> println("❌ Rejected by OS")
            ScheduleResult.THROTTLED -> println("⏳ Throttled")
        }
    }
}
```

### Complete Usage Guide

#### 1️⃣ **One-Time Tasks**

Execute a task once after a delay:

```kotlin
scheduler.enqueue(
    id = "upload-task",
    trigger = TaskTrigger.OneTime(initialDelayMs = 10_000), // 10 seconds
    workerClassName = WorkerTypes.UPLOAD_WORKER,
    constraints = Constraints(
        requiresNetwork = true,
        requiresCharging = false
    )
)
```

**Android**: Uses WorkManager with `OneTimeWorkRequest`
**iOS**: Uses `BGAppRefreshTask` or `BGProcessingTask`

#### 2️⃣ **Periodic Tasks**

Execute a task repeatedly at intervals:

```kotlin
scheduler.enqueue(
    id = "periodic-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000), // 15 minutes
    workerClassName = WorkerTypes.SYNC_WORKER
)
```

**Android**: Uses `PeriodicWorkRequest` (minimum 15 minutes)
**iOS**: Re-schedules automatically after completion

#### 3️⃣ **Exact Alarms/Reminders**

Execute at a specific time:

```kotlin
val targetTime = Clock.System.now().plus(10.seconds).toEpochMilliseconds()

scheduler.enqueue(
    id = "reminder",
    trigger = TaskTrigger.Exact(atEpochMillis = targetTime),
    workerClassName = "Reminder"
)
```

**Android**: Uses `AlarmManager.setExactAndAllowWhileIdle()`
**iOS**: Uses local notifications via `UNUserNotificationCenter`

#### 4️⃣ **Task Chains**

Execute multiple tasks sequentially or in parallel:

```kotlin
// Sequential: Sync → Upload → Sync
scheduler.beginWith(TaskRequest(workerClassName = "SyncWorker"))
    .then(TaskRequest(workerClassName = "UploadWorker"))
    .then(TaskRequest(workerClassName = "SyncWorker"))
    .enqueue()

// Parallel: (Sync ∥ Upload) → Process
scheduler.beginWith(
    listOf(
        TaskRequest(workerClassName = "SyncWorker"),
        TaskRequest(workerClassName = "UploadWorker")
    )
)
    .then(TaskRequest(workerClassName = "ProcessWorker"))
    .enqueue()
```

**Android**: Uses WorkManager's continuation API
**iOS**: Custom queue system with coroutine-based parallel execution

#### 5️⃣ **Advanced Triggers (Android Only)**

**ContentUri Trigger** - Monitor content changes:
```kotlin
scheduler.enqueue(
    id = "media-observer",
    trigger = TaskTrigger.ContentUri(
        uriString = "content://media/external/images/media",
        triggerForDescendants = true
    ),
    workerClassName = "SyncWorker"
)
```

**Battery Constraints**:
```kotlin
// Only run when battery is good
scheduler.enqueue(
    id = "heavy-task",
    trigger = TaskTrigger.BatteryOkay,
    workerClassName = "ProcessWorker"
)
```

**Device Idle**:
```kotlin
// Only run when device is idle
scheduler.enqueue(
    id = "maintenance",
    trigger = TaskTrigger.DeviceIdle,
    workerClassName = "CleanupWorker"
)
```

**iOS**: These triggers return `ScheduleResult.REJECTED_OS_POLICY`

#### 6️⃣ **Task Management**

**Cancel specific task**:
```kotlin
scheduler.cancel("upload-task")
```

**Cancel all tasks**:
```kotlin
scheduler.cancelAll()
```

#### 7️⃣ **Constraints**

Apply conditions that must be met:

```kotlin
scheduler.enqueue(
    id = "sync-task",
    trigger = TaskTrigger.OneTime(initialDelayMs = 0),
    workerClassName = "SyncWorker",
    constraints = Constraints(
        requiresNetwork = true,           // Need network
        requiresUnmeteredNetwork = false, // Any network OK
        requiresCharging = false,         // Don't need charging
        isHeavyTask = false,             // Not a heavy task
        allowWhileIdle = true            // Can run in Doze mode
    )
)
```

#### 8️⃣ **Event System**

Listen to task completion events:

```kotlin
// In your Composable
LaunchedEffect(Unit) {
    TaskEventBus.events.collect { event ->
        println("${event.taskName}: ${event.message}")
        // Show toast, update UI, etc.
    }
}
```

Emit events from workers:

```kotlin
// In your worker
class MyWorker : IosWorker { // or Android CoroutineWorker
    override suspend fun doWork(input: String?): Boolean {
        try {
            // Do work...

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "My Task",
                    success = true,
                    message = "✅ Completed successfully"
                )
            )
            return true
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "My Task",
                    success = false,
                    message = "❌ Failed: ${e.message}"
                )
            )
            return false
        }
    }
}
```

---

## 🎯 Platform-Specific Implementation

### Android Implementation

#### 1. **Worker Implementation**

```kotlin
class KmpWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val workerClassName = inputData.getString("workerClassName")

        return try {
            when (workerClassName) {
                "SyncWorker" -> executeSyncWorker()
                "UploadWorker" -> executeUploadWorker()
                else -> Result.failure()
            }
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Task",
                    success = false,
                    message = "❌ Failed: ${e.message}"
                )
            )
            Result.failure()
        }
    }

    private suspend fun executeSyncWorker(): Result {
        // Your sync logic here
        delay(2000)

        TaskEventBus.emit(
            TaskCompletionEvent(
                taskName = "Sync",
                success = true,
                message = "🔄 Synced successfully"
            )
        )

        return Result.success()
    }
}
```

#### 2. **Heavy Task with Foreground Service**

```kotlin
class KmpHeavyWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Required for long-running tasks
        setForeground(createForegroundInfo())

        // Your heavy computation
        val result = performHeavyComputation()

        TaskEventBus.emit(
            TaskCompletionEvent(
                taskName = "Heavy Task",
                success = true,
                message = "⚡ Completed in ${result.duration}ms"
            )
        )

        return Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Heavy Task Running")
            .setSmallIcon(R.drawable.ic_notification)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
```

#### 3. **Exact Alarm Receiver**

```kotlin
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Reminder"
        val message = intent.getStringExtra("message") ?: "Event"

        // Show notification
        showNotification(context, title, message)

        // Emit event
        CoroutineScope(Dispatchers.Main).launch {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = title,
                    success = true,
                    message = "⏰ Alarm triggered"
                )
            )
        }
    }
}
```

### iOS Implementation

#### 1. **Worker Implementation**

```kotlin
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        println("KMP_BG_TASK_iOS: Starting SyncWorker...")

        try {
            // Your sync logic
            val steps = listOf("Fetching", "Processing", "Saving")
            for ((index, step) in steps.withIndex()) {
                println("KMP_BG_TASK_iOS: [$step] ${index + 1}/${steps.size}")
                delay(800)
            }

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Sync",
                    success = true,
                    message = "🔄 Data synced successfully"
                )
            )

            return true
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Sync",
                    success = false,
                    message = "❌ Sync failed: ${e.message}"
                )
            )
            return false
        }
    }
}
```

#### 2. **App Delegate Registration (Swift)**

```swift
import UIKit
import ComposeApp

class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {

        // Initialize Koin
        KoinIOSKt.doInitKoinIos()

        // Register background task handlers
        registerBackgroundTasks()

        return true
    }

    private func registerBackgroundTasks() {
        // Register app refresh task
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "your.app.refresh",
            using: nil
        ) { task in
            self.handleAppRefresh(task: task as! BGAppRefreshTask)
        }

        // Register processing task
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "your.app.processing",
            using: nil
        ) { task in
            self.handleProcessing(task: task as! BGProcessingTask)
        }
    }

    private func handleAppRefresh(task: BGAppRefreshTask) {
        let executor = SingleTaskExecutor()

        task.expirationHandler = {
            executor.cancel()
        }

        executor.execute(taskId: task.identifier) { success in
            task.setTaskCompleted(success: success)
        }
    }
}
```

#### 3. **Info.plist Configuration**

Add task identifiers to `Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>your.app.refresh</string>
    <string>your.app.processing</string>
    <string>kmp_chain_executor_task</string>
</array>
```

---

## 🐛 Debug Screen Usage

The Debug tab provides real-time monitoring of all background tasks:

### Features:
- **Task List**: See all scheduled, running, and completed tasks
- **Color-coded Status**:
  - 🟢 Green: SUCCEEDED
  - 🔴 Red: FAILED/CANCELLED
  - 🟡 Yellow: RUNNING
  - 🔵 Blue: ENQUEUED
- **Refresh Button**: Update task list manually
- **Task Details**: ID, worker class, type, status

### Android Debug:
Uses WorkManager API to query real-time task status:
```kotlin
val workInfos = workManager.getWorkInfosByTag("KMP_TASK").await()
```

### iOS Debug:
Queries multiple sources:
- UserDefaults for task metadata
- BGTaskScheduler for pending requests
- Chain queue for sequential tasks

---

## 🚀 Build Instructions

### Prerequisites
* **Android Studio**: Iguana | 2023.2.1 or newer
* **Xcode**: 15.0 or newer
* **JDK**: 17 or newer
* **Kotlin**: 2.2.0
* **Kotlin Multiplatform Mobile plugin**

### Setup Steps

1. **Clone the repository**:
```bash
git clone https://github.com/yourusername/KMPTaskManager.git
cd KMPTaskManager
```

2. **Configure `local.properties`**:
```properties
sdk.dir=/Users/your-user/Library/Android/sdk
```

3. **Build for Android**:
```bash
./gradlew assembleDebug
```

4. **Build for iOS**:
```bash
./gradlew compileKotlinIosSimulatorArm64
```

5. **Run on Android**:
- Open in Android Studio
- Select device/emulator
- Click Run

6. **Run on iOS**:
- Open `iosApp/iosApp.xcodeproj` in Xcode
- Select simulator/device
- Click Run

### Testing Background Tasks

**Android:**
```bash
# View WorkManager tasks
adb shell dumpsys jobscheduler | grep KmpWorker

# Force run a task
adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS
```

**iOS Simulator:**
```bash
# Force BGTaskScheduler execution
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"your.task.id"]
```

---

## 📊 Project Statistics

| Metric | Count |
|--------|-------|
| Total Files | 40+ |
| Lines of Code | 3,500+ |
| Trigger Types | 9 |
| Worker Implementations | 11 |
| UI Tabs | 5 |
| Interactive Buttons | 18 |
| Platform Implementations | 2 (Android + iOS) |

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

## 📄 License

Copyright © 2025 TechX. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.

---

## 📞 Support

For questions or issues, please open an issue on GitHub or contact the development team.

---

**Built with ❤️ using Kotlin Multiplatform**
