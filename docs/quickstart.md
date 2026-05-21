# Quick Start

Let's get KMP WorkManager running in your project. Should take about 5 minutes.

## Table of Contents

- [Installation](#installation)
- [Android Setup](#android-setup)
- [iOS Setup](#ios-setup)
- [Your First Task](#your-first-task)
- [Create a Worker](#create-a-worker)
- [Next Steps](#next-steps)

---

## Installation

Add KMP WorkManager to your `build.gradle.kts` (module level):

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brewkits:kmpworkmanager:2.5.0")
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

        // Initialize KmpWorkManager with your worker factory
        // (Uses AndroidWorkerFactoryGenerated if you use kmpworker-ksp)
        KmpWorkManager.initialize(
            context = this,
            workerFactory = AndroidWorkerFactoryGenerated()
        )
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

KMP WorkManager uses WorkManager internally, but you may want to add it explicitly:

```kotlin
androidMain.dependencies {
    implementation("androidx.work:work-runtime-ktx:2.11.0")
}
```

---

## iOS Setup

### Step 1: Configure Info.plist

Since v2.4.1, KMP WorkManager supports **Dynamic Task IDs** on iOS — you no longer
need to declare each individual task ID. Only the two library dispatcher IDs are
required in `BGTaskSchedulerPermittedIdentifiers`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>kmp_master_dispatcher_task</string>
    <string>kmp_chain_executor_task</string>
</array>

<key>UIBackgroundModes</key>
<array>
    <string>processing</string>
    <string>fetch</string>
    <string>remote-notification</string>
</array>
```

Any task ID you pass to `scheduler.enqueue(...)` (e.g. `user-123-sync`) is routed
through the master dispatcher's internal queue and executed when iOS fires the
master dispatcher slot. See [iOS Dynamic Task Scheduling](ios-dynamic-task-scheduling.md)
for the full mechanism.

### Step 2: Initialize Koin in AppDelegate

Create or update your `iOSApp.swift`. You must register **both** dispatcher
identifiers with `BGTaskScheduler`. The library provides `handleMasterDispatcherTask`
and `handleChainExecutorTask` so you don't write any boilerplate.

> `KoinInitializerKt.doInitKoin(...)` and `KoinIOS()` below are **your own** Swift
> bridges to the shared Koin graph (the demo app's [`KoinIOS.kt`](https://github.com/brewkits/kmpworkmanager/blob/main/composeApp/src/iosMain/kotlin/dev/brewkits/kmpworkmanager/sample/di/KoinIOS.kt)
> is a working reference). They are **not** library APIs — wire them to match
> your project's DI setup.

```swift
import SwiftUI
import BackgroundTasks
import composeApp

@main
struct iOSApp: App {

    init() {
        // Initialize Koin — your iosModule must include
        // kmpWorkerModule(workerFactory = IosWorkerFactoryGenerated())
        KoinInitializerKt.doInitKoin(platformModule: IOSModuleKt.iosModule)

        // Register background tasks
        registerBackgroundTasks()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }

    private func registerBackgroundTasks() {
        let koin = KoinIOS()
        let scheduler = koin.getScheduler()
        let dispatcher = koin.getDynamicTaskDispatcher()
        let chainExecutor = koin.getChainExecutor()

        // 1. Master dispatcher — handles every dynamic task ID
        //    (everything not pre-registered as its own BGTask identifier).
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "kmp_master_dispatcher_task",
            using: nil
        ) { task in
            IosBackgroundTaskHandler.shared.handleMasterDispatcherTask(
                task: task,
                dispatcher: dispatcher,
                scheduler: scheduler
            )
        }

        // 2. Chain executor — handles batched task chains.
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "kmp_chain_executor_task",
            using: nil
        ) { task in
            IosBackgroundTaskHandler.shared.handleChainExecutorTask(
                task: task,
                chainExecutor: chainExecutor
            )
        }
    }
}
```

> **Both handlers are required.** `kmp_master_dispatcher_task` is what wakes
> up your dynamic tasks; without it registered, every task ID not declared
> explicitly in `Info.plist` will never fire. `BGTaskScheduler` also throws
> `NSInternalInconsistencyException` if an identifier appears in
> `BGTaskSchedulerPermittedIdentifiers` but has no handler registered.

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
        ScheduleResult.ACCEPTED -> println("Task scheduled successfully!")
        ScheduleResult.REJECTED_OS_POLICY -> println("OS rejected the task (e.g. battery saver, permissions)")
        ScheduleResult.DEADLINE_ALREADY_PASSED -> println("Scheduled time is in the past")
        ScheduleResult.THROTTLED -> println("Too many tasks scheduled")
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

Implement the actual work that will be executed in the background. KMP WorkManager encourages writing the core logic in your `commonMain` module and using platform-specific wrappers. 

We highly recommend using `kmpworker-ksp` to auto-generate the worker factories.

### 1. Shared Logic (commonMain)

```kotlin
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult

class SyncWorker : Worker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        return try {
            // Your sync logic here
            println("Syncing data from server...")
            
            // Check for OS cancellation
            if (env.isCancelled()) return WorkerResult.Failure("Cancelled")

            WorkerResult.Success("Data synced successfully")
        } catch (e: Exception) {
            WorkerResult.Failure("Sync failed: ${e.message}")
        }
    }
}
```

### 2. Android Wrapper (androidMain)

```kotlin
import dev.brewkits.kmpworkmanager.annotations.Worker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult

@Worker(name = "SyncWorker")
class SyncWorkerAndroid : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
        SyncWorker().doWork(input, env)
}
```

### 3. iOS Wrapper (iosMain)

```kotlin
import dev.brewkits.kmpworkmanager.annotations.Worker
import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult

@Worker(name = "SyncWorker", bgTaskId = "periodic-sync-task")
class SyncWorkerIos : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
        SyncWorker().doWork(input, env)
}
```

*Note: The `name` value (`"SyncWorker"`) must match the `workerClassName` you pass to `scheduler.enqueue(...)`. Setting it explicitly also protects against silent breakage if ProGuard/R8 renames the wrapper class.*

*By annotating these with `@Worker`, the KSP processor generates `AndroidWorkerFactoryGenerated` and `IosWorkerFactoryGenerated`, which you already passed to `KmpWorkManager.initialize()` on Android and to `kmpWorkerModule(workerFactory = …)` (inside `iosModule`, invoked via `KoinInitializerKt.doInitKoin(...)`) on iOS.*

---

## Next Steps

That's it! You now have KMP WorkManager set up. Here's what you can do next:

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

1. **Check Info.plist**: Ensure `kmp_master_dispatcher_task` and `kmp_chain_executor_task` are registered.
2. **Check AppDelegate**: Verify `registerBackgroundTasks()` is called.
3. **App must be in background**: BGTasks only run when app is backgrounded.
4. **Test with simulator**: Use `e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"kmp_master_dispatcher_task"]` in LLDB.
5. **Check worker registration**: Ensure worker is registered in `IosWorkerFactory`.

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
- Browse [GitHub Issues](https://github.com/brewkits/kmpworkmanager/issues)
- Ask in [GitHub Discussions](https://github.com/brewkits/kmpworkmanager/discussions)

Happy scheduling! 🚀
