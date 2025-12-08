# Quick Start Guide

Get KMP TaskManager running in your project in just 5 minutes!

## Table of Contents

- [Installation](#installation)
- [Android Setup](#android-setup)
- [iOS Setup](#ios-setup)
- [Your First Task](#your-first-task)
- [Create a Worker](#create-a-worker)
- [Next Steps](#next-steps)

---

## Installation

Add KMP TaskManager to your `build.gradle.kts` (module level):

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.vietnguyentuan2019:kmptaskmanager:2.2.0")
        }
    }
}
```

Sync your project with Gradle files.

---

## Android Setup

### Step 1: Add Required Permissions

Add these permissions to your `AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Required for scheduling exact alarms (Android 12+) -->
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

    <!-- Required for notifications (Android 13+) -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Required for heavy tasks using foreground services -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <application>
        <!-- Your app content -->
    </application>
</manifest>
```

### Step 2: Initialize Koin in Application Class

Create or update your `Application` class:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@MyApp)
            modules(kmpTaskManagerModule())
        }
    }
}
```

Update your `AndroidManifest.xml` to reference the Application class:

```xml
<application
    android:name=".MyApp"
    ...>
</application>
```

### Step 3: Add WorkManager Dependency (Optional)

KMP TaskManager uses WorkManager internally, but you may want to add it explicitly:

```kotlin
androidMain.dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
```

---

## iOS Setup

### Step 1: Configure Info.plist

Add background task identifiers to your `Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>periodic-sync-task</string>
    <string>upload-task</string>
    <string>heavy-processing-task</string>
</array>

<key>UIBackgroundModes</key>
<array>
    <string>processing</string>
    <string>fetch</string>
    <string>remote-notification</string>
</array>
```

### Step 2: Initialize Koin in AppDelegate

Create or update your `iOSApp.swift`:

```swift
import SwiftUI
import composeApp

@main
struct iOSApp: App {

    init() {
        // Initialize Koin
        KoinIOSKt.doInitKoinIos()

        // Register background tasks
        registerBackgroundTasks()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }

    private func registerBackgroundTasks() {
        let koinIos = KoinIOS()

        // Register periodic sync task
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "periodic-sync-task",
            using: nil
        ) { task in
            koinIos.getScheduler().handleSingleTask(
                task: task as! BGAppRefreshTask,
                taskIdentifier: "periodic-sync-task"
            )
        }

        // Register heavy processing task
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "heavy-processing-task",
            using: nil
        ) { task in
            koinIos.getScheduler().handleSingleTask(
                task: task as! BGProcessingTask,
                taskIdentifier: "heavy-processing-task"
            )
        }
    }
}
```

### Step 3: Handle App Lifecycle

Add this extension to handle background task scheduling when app enters background:

```swift
extension iOSApp {
    func scenePhase(_ phase: ScenePhase) {
        if phase == .background {
            // iOS will execute scheduled tasks when app is in background
            print("App entered background - BGTasks can now execute")
        }
    }
}
```

---

## Your First Task

Now you're ready to schedule your first background task!

### 1. Inject the Scheduler

```kotlin
class MyViewModel(
    private val scheduler: BackgroundTaskScheduler
) {
    // Your code here
}
```

Or get it from Koin directly:

```kotlin
val scheduler: BackgroundTaskScheduler = get()
```

### 2. Schedule a Periodic Task

```kotlin
suspend fun scheduleDataSync() {
    val result = scheduler.enqueue(
        id = "data-sync",
        trigger = TaskTrigger.Periodic(
            intervalMs = 15 * 60 * 1000 // 15 minutes
        ),
        workerClassName = "SyncWorker",
        constraints = Constraints(
            requiresNetwork = true,
            requiresCharging = false
        )
    )

    when (result) {
        ScheduleResult.SUCCESS -> println("Task scheduled successfully!")
        ScheduleResult.REJECTED_OS_POLICY -> println("OS rejected the task")
        ScheduleResult.REJECTED_INVALID_PARAMS -> println("Invalid parameters")
        ScheduleResult.FAILED_UNKNOWN -> println("Unknown error occurred")
    }
}
```

### 3. Schedule a One-Time Task

```kotlin
suspend fun uploadFile() {
    scheduler.enqueue(
        id = "file-upload",
        trigger = TaskTrigger.OneTime(
            initialDelayMs = 0 // Execute immediately
        ),
        workerClassName = "UploadWorker",
        constraints = Constraints(
            requiresNetwork = true,
            networkType = NetworkType.UNMETERED, // WiFi only
            backoffPolicy = BackoffPolicy.EXPONENTIAL,
            backoffDelayMs = 10_000 // Retry after 10 seconds
        )
    )
}
```

---

## Create a Worker

Now implement the actual work that will be executed in the background.

### Android Worker

Add the worker logic to `KmpWorker.kt` (in `androidMain`):

```kotlin
class KmpWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val workerClassName = inputData.getString("workerClassName")

        return when (workerClassName) {
            "SyncWorker" -> executeSyncWorker()
            "UploadWorker" -> executeUploadWorker()
            else -> Result.failure()
        }
    }

    private suspend fun executeSyncWorker(): Result {
        return try {
            // Your sync logic here
            println("Syncing data from server...")
            delay(2000)

            // Emit event to notify UI
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "SyncWorker",
                    success = true,
                    message = "✅ Data synced successfully"
                )
            )

            Result.success()
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Sync failed", e)
            Result.retry()
        }
    }

    private suspend fun executeUploadWorker(): Result {
        return try {
            // Your upload logic here
            println("Uploading file...")
            delay(3000)

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "UploadWorker",
                    success = true,
                    message = "✅ File uploaded"
                )
            )

            Result.success()
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Upload failed", e)
            Result.retry()
        }
    }
}
```

### iOS Worker

Create worker classes in `iosMain/background/workers/`:

```kotlin
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        return try {
            // Your sync logic here (must complete within 25 seconds)
            println("Syncing data from server...")
            delay(2000)

            // Emit event to notify UI
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "SyncWorker",
                    success = true,
                    message = "✅ Data synced successfully"
                )
            )

            true // Return true for success
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Sync failed", e)
            false // Return false for failure
        }
    }
}
```

Register the worker in `IosWorkerFactory.kt`:

```kotlin
object IosWorkerFactory {
    fun createWorker(className: String): IosWorker? {
        return when (className) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            else -> null
        }
    }
}
```

---

## Next Steps

Congratulations! You've successfully integrated KMP TaskManager. Now you can:

1. **[Explore all triggers](constraints-triggers.md)** - Learn about 9 different trigger types
2. **[Build task chains](task-chains.md)** - Execute sequential and parallel workflows
3. **[Configure constraints](constraints-triggers.md#constraints)** - Fine-tune when tasks run
4. **[Platform-specific setup](platform-setup.md)** - Advanced Android & iOS configuration
5. **[API Reference](api-reference.md)** - Complete API documentation

---

## Common Issues

### Android: Tasks Not Running

1. **Check WorkManager initialization**: Ensure Koin is properly initialized
2. **Check permissions**: Verify all required permissions are in AndroidManifest.xml
3. **Check constraints**: Tasks won't run if constraints aren't met (e.g., no network)
4. **Check Doze mode**: Test with `adb shell dumpsys battery unplug` and `adb shell dumpsys deviceidle force-idle`

### iOS: Background Tasks Not Executing

1. **Check Info.plist**: Ensure task identifiers are registered
2. **Check AppDelegate**: Verify `registerBackgroundTasks()` is called
3. **App must be in background**: BGTasks only run when app is backgrounded
4. **Test with simulator**: Use `e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"periodic-sync-task"]` in LLDB
5. **Check worker registration**: Ensure worker is registered in `IosWorkerFactory`

### Tasks Running But No Events

Make sure you're collecting events from `TaskEventBus`:

```kotlin
@Composable
fun MyScreen() {
    LaunchedEffect(Unit) {
        TaskEventBus.events.collect { event ->
            println("Task event: ${event.taskName} - ${event.message}")
        }
    }
}
```

---

## Need Help?

- Read the [API Reference](api-reference.md)
- Check the [Platform Setup Guide](platform-setup.md)
- Browse [GitHub Issues](https://github.com/vietnguyentuan2019/KMPTaskManager/issues)
- Ask in [GitHub Discussions](https://github.com/vietnguyentuan2019/KMPTaskManager/discussions)

Happy scheduling! 🚀
