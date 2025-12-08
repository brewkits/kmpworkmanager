# Platform Setup Guide

Comprehensive guide for configuring KMP TaskManager on Android and iOS.

## Table of Contents

- [Android Setup](#android-setup)
- [iOS Setup](#ios-setup)
- [Testing Background Tasks](#testing-background-tasks)
- [Troubleshooting](#troubleshooting)

---

## Android Setup

### 1. Dependencies

Add to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        androidMain.dependencies {
            // KMP TaskManager (required)
            implementation("io.github.vietnguyentuan2019:kmptaskmanager:2.2.0")

            // WorkManager (optional - already included transitively)
            implementation("androidx.work:work-runtime-ktx:2.9.0")

            // For Kotlin coroutines support
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
        }
    }
}
```

---

### 2. AndroidManifest.xml Configuration

#### Required Permissions

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Schedule exact alarms (Android 12+) -->
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

    <!-- Post notifications (Android 13+) -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Foreground service for heavy tasks -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <!-- Wake lock (for exact alarms) -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- Internet (if your tasks need network) -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:name=".KMPWorkManagerApp"
        ...>

        <!-- WorkManager Worker -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup" />
        </provider>

        <!-- Alarm Receiver for exact alarms -->
        <receiver
            android:name="com.example.kmpworkmanagerv2.background.data.AlarmReceiver"
            android:enabled="true"
            android:exported="false" />

    </application>
</manifest>
```

---

### 3. Application Class Setup

Create an Application class to initialize Koin:

```kotlin
class KMPWorkManagerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger(Level.DEBUG)
            androidContext(this@KMPWorkManagerApp)
            modules(kmpTaskManagerModule())
        }

        // Optional: Configure WorkManager
        configureWorkManager()
    }

    private fun configureWorkManager() {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setWorkerFactory(DefaultWorkerFactory())
            .build()

        WorkManager.initialize(this, config)
    }
}
```

Register in `AndroidManifest.xml`:

```xml
<application
    android:name=".KMPWorkManagerApp"
    ...>
</application>
```

---

### 4. Request Runtime Permissions (Android 13+)

For Android 13+, request notification permission at runtime:

```kotlin
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            println("Notification permission granted")
        } else {
            println("Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        // Request exact alarm permission for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).also {
                    startActivity(it)
                }
            }
        }
    }
}
```

---

### 5. ProGuard Rules (if using R8/ProGuard)

Add to `proguard-rules.pro`:

```proguard
# Keep WorkManager classes
-keep class androidx.work.** { *; }
-keep class com.example.kmpworkmanagerv2.background.** { *; }

# Keep Koin classes
-keep class org.koin.** { *; }

# Keep Kotlin coroutines
-keepclassmembernames class kotlinx.** { *; }
```

---

### 6. Worker Implementation

Add your worker logic to `KmpWorker.kt`:

```kotlin
class KmpWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val workerClassName = inputData.getString("workerClassName") ?: return Result.failure()
        val input = inputData.getString("input")

        Logger.i(LogTags.WORKER, "Executing worker: $workerClassName")

        return when (workerClassName) {
            "SyncWorker" -> executeSyncWorker(input)
            "UploadWorker" -> executeUploadWorker(input)
            // Add your workers here
            else -> {
                Logger.e(LogTags.WORKER, "Unknown worker: $workerClassName")
                Result.failure()
            }
        }
    }

    private suspend fun executeSyncWorker(input: String?): Result {
        return try {
            // Your sync logic here
            delay(2000)

            TaskEventBus.emit(
                TaskCompletionEvent("SyncWorker", true, "✅ Sync complete")
            )

            Result.success()
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Sync failed", e)
            Result.retry()
        }
    }

    private suspend fun executeUploadWorker(input: String?): Result {
        return try {
            // Your upload logic here
            delay(3000)

            TaskEventBus.emit(
                TaskCompletionEvent("UploadWorker", true, "✅ Upload complete")
            )

            Result.success()
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Upload failed", e)
            Result.retry()
        }
    }
}
```

---

### 7. Android-Specific Features

#### Heavy Tasks (Foreground Service)

For tasks longer than 10 minutes, set `isHeavyTask = true`:

```kotlin
scheduler.enqueue(
    id = "ml-training",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "MLTrainingWorker",
    constraints = Constraints(
        isHeavyTask = true,
        requiresCharging = true
    )
)
```

This uses `KmpHeavyWorker` which runs as a foreground service.

#### Expedited Work

For high-priority tasks that need to run ASAP:

```kotlin
scheduler.enqueue(
    id = "urgent-sync",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "SyncWorker",
    constraints = Constraints(
        expedited = true
    )
)
```

#### ContentUri Triggers

Monitor MediaStore changes:

```kotlin
scheduler.enqueue(
    id = "media-observer",
    trigger = TaskTrigger.ContentUri(
        uriString = "content://media/external/images/media",
        triggerForDescendants = true
    ),
    workerClassName = "MediaSyncWorker"
)
```

---

## iOS Setup

### 1. Info.plist Configuration

Add background task identifiers and capabilities:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- Background Task Identifiers -->
    <key>BGTaskSchedulerPermittedIdentifiers</key>
    <array>
        <string>periodic-sync-task</string>
        <string>upload-task</string>
        <string>heavy-processing-task</string>
        <string>kmp_chain_executor_task</string>
    </array>

    <!-- Background Modes -->
    <key>UIBackgroundModes</key>
    <array>
        <string>processing</string>
        <string>fetch</string>
        <string>remote-notification</string>
    </array>

    <!-- Disable Scene-based lifecycle (if using traditional AppDelegate) -->
    <key>UIApplicationSceneManifest</key>
    <dict>
        <key>UIApplicationSupportsMultipleScenes</key>
        <false/>
    </dict>
</dict>
</plist>
```

---

### 2. Xcode Project Settings

Open your Xcode project and verify:

1. **Signing & Capabilities**:
   - Add "Background Modes" capability
   - Enable "Background fetch" and "Background processing"

2. **Build Settings**:
   - Set `INFOPLIST_KEY_UIApplicationSceneManifest_Generation = NO` (if using AppDelegate)
   - Ensure deployment target is iOS 13.0 or higher

3. **General**:
   - Verify bundle identifier matches your configuration

---

### 3. AppDelegate Setup

Create or update `iOSApp.swift`:

```swift
import SwiftUI
import BackgroundTasks
import composeApp

@main
struct iOSApp: App {

    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

class AppDelegate: NSObject, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {

        // Initialize Koin
        KoinIOSKt.doInitKoinIos()

        // Register background tasks
        registerBackgroundTasks()

        // Request notification permissions
        requestNotificationPermissions()

        return true
    }

    private func registerBackgroundTasks() {
        let koinIos = KoinIOS()

        // Periodic sync task (BGAppRefreshTask)
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "periodic-sync-task",
            using: nil
        ) { task in
            self.handleAppRefreshTask(
                task: task as! BGAppRefreshTask,
                scheduler: koinIos.getScheduler()
            )
        }

        // Heavy processing task (BGProcessingTask)
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "heavy-processing-task",
            using: nil
        ) { task in
            self.handleProcessingTask(
                task: task as! BGProcessingTask,
                scheduler: koinIos.getScheduler()
            )
        }

        // Chain executor task
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "kmp_chain_executor_task",
            using: nil
        ) { task in
            self.handleChainExecutorTask(
                task: task as! BGProcessingTask,
                scheduler: koinIos.getScheduler()
            )
        }
    }

    private func handleAppRefreshTask(
        task: BGAppRefreshTask,
        scheduler: BackgroundTaskScheduler
    ) {
        scheduler.handleSingleTask(
            task: task,
            taskIdentifier: "periodic-sync-task"
        )
    }

    private func handleProcessingTask(
        task: BGProcessingTask,
        scheduler: BackgroundTaskScheduler
    ) {
        scheduler.handleSingleTask(
            task: task,
            taskIdentifier: "heavy-processing-task"
        )
    }

    private func handleChainExecutorTask(
        task: BGProcessingTask,
        scheduler: BackgroundTaskScheduler
    ) {
        scheduler.handleChainExecutorTask(task: task)
    }

    private func requestNotificationPermissions() {
        UNUserNotificationCenter.current().requestAuthorization(
            options: [.alert, .sound, .badge]
        ) { granted, error in
            if granted {
                print("Notification permission granted")
            } else if let error = error {
                print("Notification permission error: \(error)")
            }
        }
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        // Background tasks can now execute
        print("App entered background")
    }
}
```

---

### 4. Worker Implementation

Create worker classes in `iosMain/background/workers/`:

```kotlin
// SyncWorker.kt
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        return try {
            // IMPORTANT: Must complete within 25 seconds for BGAppRefreshTask
            // or within a few minutes for BGProcessingTask
            Logger.i(LogTags.WORKER, "iOS SyncWorker started")

            delay(2000) // Simulate work

            TaskEventBus.emit(
                TaskCompletionEvent("SyncWorker", true, "✅ iOS sync complete")
            )

            true
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "iOS sync failed", e)
            false
        }
    }
}
```

Register in `IosWorkerFactory.kt`:

```kotlin
object IosWorkerFactory {
    fun createWorker(className: String): IosWorker? {
        return when (className) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            "HeavyProcessingWorker" -> HeavyProcessingWorker()
            else -> {
                Logger.e(LogTags.FACTORY, "Unknown worker: $className")
                null
            }
        }
    }
}
```

---

### 5. iOS-Specific Features

#### BGAppRefreshTask vs BGProcessingTask

```kotlin
// Light task (BGAppRefreshTask - 25 seconds max)
scheduler.enqueue(
    id = "quick-sync",
    trigger = TaskTrigger.Periodic(15_MINUTES),
    workerClassName = "SyncWorker",
    constraints = Constraints(
        isHeavyTask = false // Uses BGAppRefreshTask
    )
)

// Heavy task (BGProcessingTask - several minutes)
scheduler.enqueue(
    id = "ml-training",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "MLTrainingWorker",
    constraints = Constraints(
        isHeavyTask = true // Uses BGProcessingTask
    )
)
```

#### Quality of Service (QoS)

Control task priority on iOS:

```kotlin
scheduler.enqueue(
    id = "high-priority-sync",
    trigger = TaskTrigger.Periodic(15_MINUTES),
    workerClassName = "SyncWorker",
    constraints = Constraints(
        qos = QualityOfService.HIGH // User-initiated priority
    )
)
```

#### Silent Push Notifications

For remote-triggered tasks, configure APNS:

1. Enable "Remote notifications" in Background Modes
2. Send silent push with `content-available: 1`
3. Handle in `didReceiveRemoteNotification`:

```swift
func application(
    _ application: UIApplication,
    didReceiveRemoteNotification userInfo: [AnyHashable : Any],
    fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
) {
    let koinIos = KoinIOS()
    let scheduler = koinIos.getScheduler()

    // Trigger background task
    Task {
        await scheduler.enqueue(
            id: "push-triggered-sync",
            trigger: TaskTriggerOneTime(initialDelayMs: 0),
            workerClassName: "SyncWorker",
            input: nil,
            constraints: Constraints()
        )
        completionHandler(.newData)
    }
}
```

---

## Testing Background Tasks

### Android Testing

#### 1. Force Run WorkManager Task

```bash
# View all scheduled tasks
adb shell dumpsys jobscheduler | grep KmpWorker

# Force run a task (requires WorkManager Test helpers)
adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS

# Check WorkManager database
adb shell sqlite3 /data/data/YOUR.PACKAGE.NAME/databases/androidx.work.workdatabase "SELECT * FROM WorkSpec"
```

#### 2. Test Doze Mode

```bash
# Unplug device
adb shell dumpsys battery unplug

# Enter Doze mode
adb shell dumpsys deviceidle force-idle

# Exit Doze mode
adb shell dumpsys deviceidle unforce

# Reset battery
adb shell dumpsys battery reset
```

#### 3. Test Exact Alarms

```bash
# Check if app can schedule exact alarms
adb shell dumpsys alarm | grep YOUR.PACKAGE.NAME

# View next alarm
adb shell dumpsys alarm | grep -A 20 "Next alarm clock"
```

---

### iOS Testing

#### 1. Simulator Testing with LLDB

In Xcode, run the app and pause at a breakpoint, then in LLDB console:

```lldb
# Force execute a BGAppRefreshTask
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"periodic-sync-task"]

# Force execute a BGProcessingTask
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"heavy-processing-task"]
```

#### 2. Scheme Arguments

Add launch arguments in Xcode scheme:

1. Product → Scheme → Edit Scheme
2. Run → Arguments → Arguments Passed On Launch
3. Add: `-BGTaskSchedulerSimulateEarlyTermination`

This simulates app termination during background task execution.

#### 3. Monitor Console Logs

```bash
# View iOS device logs
xcrun simctl spawn booted log stream --predicate 'subsystem == "com.apple.BGTaskScheduler"' --level debug

# Or use Console.app to filter by BGTaskScheduler
```

#### 4. Physical Device Testing

1. **Connect device to Xcode**
2. **Run app and send to background** (Home button)
3. **Wait or trigger via LLDB** (connect debugger to running app)
4. **Check logs in Xcode Console**

**Important**: BGTasks only run on physical devices when app is truly in background and system decides to execute them. Testing requires patience or LLDB simulation.

---

## Troubleshooting

### Android Issues

#### Tasks Not Running

**Problem**: Scheduled tasks never execute

**Solutions**:

1. Check WorkManager initialization:
   ```kotlin
   val workManager = WorkManager.getInstance(context)
   val workInfos = workManager.getWorkInfosForUniqueWork("task-id").get()
   println("Work state: ${workInfos.firstOrNull()?.state}")
   ```

2. Verify constraints are met:
   ```bash
   adb shell dumpsys battery unplug
   adb shell svc wifi enable
   ```

3. Check for Doze mode restrictions:
   ```bash
   adb shell dumpsys battery unplug
   adb shell dumpsys deviceidle whitelist +YOUR.PACKAGE.NAME
   ```

---

#### Exact Alarms Not Triggering

**Problem**: `TaskTrigger.Exact` doesn't fire

**Solutions**:

1. Check permission:
   ```kotlin
   val alarmManager = getSystemService(AlarmManager::class.java)
   val canSchedule = alarmManager.canScheduleExactAlarms()
   println("Can schedule exact alarms: $canSchedule")
   ```

2. Request permission manually:
   ```kotlin
   val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
   startActivity(intent)
   ```

3. Verify `AlarmReceiver` is registered in `AndroidManifest.xml`

---

#### Foreground Service Crashes

**Problem**: `KmpHeavyWorker` crashes with `ForegroundServiceStartNotAllowedException`

**Solutions**:

1. Add foreground service permission:
   ```xml
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
   ```

2. Request notification permission on Android 13+

3. Create proper notification channel:
   ```kotlin
   val channel = NotificationChannel(
       "heavy_task_channel",
       "Heavy Tasks",
       NotificationManager.IMPORTANCE_LOW
   )
   notificationManager.createNotificationChannel(channel)
   ```

---

### iOS Issues

#### Background Tasks Not Executing

**Problem**: BGTasks never run on device

**Solutions**:

1. **Verify Info.plist configuration**:
   - Check `BGTaskSchedulerPermittedIdentifiers` array
   - Ensure task IDs match exactly (case-sensitive)

2. **Check AppDelegate registration**:
   ```swift
   BGTaskScheduler.shared.register(
       forTaskWithIdentifier: "periodic-sync-task",
       using: nil
   ) { task in
       // Handler must be registered BEFORE task is scheduled
   }
   ```

3. **App must be in background**:
   - Press Home button to background app
   - Wait several minutes or hours (iOS decides when to run)
   - Use LLDB to force execution for testing

4. **Check system logs**:
   ```bash
   log stream --predicate 'subsystem == "com.apple.BGTaskScheduler"' --level debug
   ```

---

#### Tasks Timeout After 25 Seconds

**Problem**: BGAppRefreshTask terminates after 25 seconds

**Solutions**:

1. **Use BGProcessingTask for longer work**:
   ```kotlin
   constraints = Constraints(isHeavyTask = true)
   ```

2. **Optimize worker to complete faster**:
   ```kotlin
   class SyncWorker : IosWorker {
       override suspend fun doWork(input: String?): Boolean {
           withTimeout(20_000) { // Complete within 20 seconds
               // Fast sync logic
           }
           return true
       }
   }
   ```

3. **Split work into chains**:
   ```kotlin
   scheduler
       .beginWith(TaskRequest(workerClassName = "QuickSync1"))
       .then(TaskRequest(workerClassName = "QuickSync2"))
       .enqueue()
   ```

---

#### Worker Not Found

**Problem**: `IosWorkerFactory` returns null

**Solutions**:

1. **Register worker in factory**:
   ```kotlin
   object IosWorkerFactory {
       fun createWorker(className: String): IosWorker? {
           return when (className) {
               "SyncWorker" -> SyncWorker()
               else -> null
           }
       }
   }
   ```

2. **Check worker class name spelling** (case-sensitive)

3. **Verify worker implements `IosWorker` interface**

---

#### Periodic Tasks Not Re-scheduling

**Problem**: Task runs once but doesn't repeat

**Solution**: Ensure `handleSingleTask` re-schedules periodic tasks:

```kotlin
// This is handled internally by NativeTaskScheduler
// Verify metadata is stored correctly
val defaults = NSUserDefaults.standardUserDefaults
val metadata = defaults.stringForKey("kmp_periodic_meta_periodic-sync-task")
println("Periodic metadata: $metadata")
```

---

## Best Practices

### Android

1. **Use `expedited = true` for urgent tasks** (< 10 minutes)
2. **Use `isHeavyTask = true` for long tasks** (> 10 minutes)
3. **Always handle `Result.retry()` for transient failures**
4. **Request permissions before scheduling tasks**
5. **Test in Doze mode** to ensure tasks run when expected

---

### iOS

1. **Keep BGAppRefreshTask workers under 20 seconds**
2. **Use BGProcessingTask (`isHeavyTask = true`) for heavy work**
3. **Always re-schedule periodic tasks after completion**
4. **Register task handlers BEFORE scheduling tasks**
5. **Test on physical devices** (simulator behavior differs)
6. **Use LLDB commands for testing** (don't wait hours for iOS to trigger)
7. **Store task metadata in UserDefaults** for persistence

---

## Next Steps

- [Quick Start Guide](quickstart.md) - Get started quickly
- [API Reference](api-reference.md) - Complete API documentation
- [Task Chains](task-chains.md) - Build complex workflows
- [Constraints & Triggers](constraints-triggers.md) - All trigger types

---

Need help? [Open an issue](https://github.com/vietnguyentuan2019/KMPTaskManager/issues) or ask in [Discussions](https://github.com/vietnguyentuan2019/KMPTaskManager/discussions).
