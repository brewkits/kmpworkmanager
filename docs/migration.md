# Migration Guide

Step-by-step guide for upgrading to the latest version of KMP TaskManager.

## Table of Contents

- [Version 2.2.0](#version-220)
- [Version 2.1.0](#version-210)
- [Migrating from Other Libraries](#migrating-from-other-libraries)
- [Breaking Changes](#breaking-changes)

---

## Version 2.2.0

**Release Date:** December 2024

### What's New

✨ **Features:**
- Downgraded Kotlin to 2.1.0 for better compatibility
- Added Maven Central checksums support for security
- Improved repository maintenance with `.mailmap`
- Enhanced README with better marketing and user engagement

### Migration Steps

No breaking changes. Simply update your dependency:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.brewkits:kmptaskmanager:2.2.0")
        }
    }
}
```

### Compatibility

- **Kotlin:** 2.1.0
- **Compose Multiplatform:** 1.7.1
- **Android:** API 24+
- **iOS:** iOS 13.0+

---

## Version 2.1.0

**Release Date:** November 2024

### What's New

✨ **Features:**
- Task chains with sequential and parallel execution
- Event system (`TaskEventBus`) for worker-to-UI communication
- iOS batch execution for better performance
- Enhanced retry policies
- Quality of Service (QoS) support for iOS

### Breaking Changes

#### 1. Koin Initialization (iOS)

**Before (v2.0.x):**
```swift
// Old way
initKoin()
```

**After (v2.1.0+):**
```swift
// New way
KoinIOSKt.doInitKoinIos()
```

**Migration:**

Update your `iOSApp.swift`:

```swift
@main
struct iOSApp: App {
    init() {
        // Change this line
        KoinIOSKt.doInitKoinIos() // Was: initKoin()
        registerBackgroundTasks()
    }
}
```

---

#### 2. Worker Return Type (iOS)

**Before (v2.0.x):**
```kotlin
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?) {
        // No return value
        syncData()
    }
}
```

**After (v2.1.0+):**
```kotlin
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        return try {
            syncData()
            true // Return true for success
        } catch (e: Exception) {
            false // Return false for failure
        }
    }
}
```

**Migration:**

Update all iOS workers to return `Boolean`:

```kotlin
// Step 1: Add return type
override suspend fun doWork(input: String?): Boolean {

// Step 2: Return true on success
    syncData()
    return true
}

// Step 3: Handle errors with false
override suspend fun doWork(input: String?): Boolean {
    return try {
        syncData()
        true
    } catch (e: Exception) {
        Logger.e(LogTags.WORKER, "Failed", e)
        false
    }
}
```

---

#### 3. Event Bus (New Feature)

**New in v2.1.0:**

Workers can now emit events for UI updates:

```kotlin
// In your worker
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        return try {
            syncData()

            // NEW: Emit event
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "SyncWorker",
                    success = true,
                    message = "✅ Sync complete"
                )
            )

            true
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "SyncWorker",
                    success = false,
                    message = "❌ Sync failed"
                )
            )

            false
        }
    }
}
```

Collect events in UI:

```kotlin
@Composable
fun MyScreen() {
    LaunchedEffect(Unit) {
        TaskEventBus.events.collect { event ->
            println("Task: ${event.taskName} - ${event.message}")
        }
    }
}
```

---

#### 4. Task Chains (New Feature)

**New in v2.1.0:**

Sequential execution:

```kotlin
scheduler
    .beginWith(TaskRequest(workerClassName = "DownloadWorker"))
    .then(TaskRequest(workerClassName = "ProcessWorker"))
    .then(TaskRequest(workerClassName = "UploadWorker"))
    .enqueue()
```

Parallel execution:

```kotlin
scheduler
    .beginWith(listOf(
        TaskRequest(workerClassName = "SyncWorker1"),
        TaskRequest(workerClassName = "SyncWorker2"),
        TaskRequest(workerClassName = "SyncWorker3")
    ))
    .then(TaskRequest(workerClassName = "FinalizeWorker"))
    .enqueue()
```

---

### Recommended Updates

While not required, we recommend adding event emissions to your workers for better observability:

```kotlin
// Before
class MyWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        doSomeWork()
        return true
    }
}

// After (recommended)
class MyWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        return try {
            doSomeWork()

            TaskEventBus.emit(
                TaskCompletionEvent("MyWorker", true, "✅ Complete")
            )

            true
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent("MyWorker", false, "❌ Failed")
            )

            false
        }
    }
}
```

---

## Migrating from Other Libraries

### From Native WorkManager (Android)

**Before (Native WorkManager):**

```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .setRequiresCharging(true)
    .build()

val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
    .setConstraints(constraints)
    .build()

WorkManager.getInstance(context).enqueue(workRequest)
```

**After (KMP TaskManager):**

```kotlin
// Common code (works on Android AND iOS!)
scheduler.enqueue(
    id = "sync",
    trigger = TaskTrigger.Periodic(intervalMs = 15 * 60 * 1000),
    workerClassName = "SyncWorker",
    constraints = Constraints(
        requiresNetwork = true,
        requiresCharging = true
    )
)
```

**Benefits:**
- ✅ Single API for Android and iOS
- ✅ Share business logic across platforms
- ✅ Reduced code duplication
- ✅ Type-safe Kotlin API

---

### From Native BGTaskScheduler (iOS)

**Before (Native BGTaskScheduler):**

```swift
let request = BGAppRefreshTaskRequest(identifier: "sync-task")
request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)

do {
    try BGTaskScheduler.shared.submit(request)
} catch {
    print("Could not schedule task: \(error)")
}

// Register handler
BGTaskScheduler.shared.register(
    forTaskWithIdentifier: "sync-task",
    using: nil
) { task in
    self.handleSync(task: task as! BGAppRefreshTask)
}
```

**After (KMP TaskManager):**

```kotlin
// Common code (works on Android AND iOS!)
scheduler.enqueue(
    id = "sync",
    trigger = TaskTrigger.Periodic(intervalMs = 15 * 60 * 1000),
    workerClassName = "SyncWorker",
    constraints = Constraints(requiresNetwork = true)
)
```

**Benefits:**
- ✅ Write once, run on both platforms
- ✅ Kotlin coroutines instead of completion handlers
- ✅ Automatic re-scheduling for periodic tasks
- ✅ Built-in retry logic

---

### From Alarmee

**Alarmee** is focused on exact alarms and notifications. KMP TaskManager provides background execution.

**Before (Alarmee):**

```kotlin
val alarmee = Alarmee.build {
    // Configuration
}

alarmee.schedule(
    alarm = Alarm(
        id = "reminder",
        timeInMillis = targetTime
    )
)
```

**After (KMP TaskManager):**

```kotlin
scheduler.enqueue(
    id = "reminder",
    trigger = TaskTrigger.Exact(atEpochMillis = targetTime),
    workerClassName = "ReminderWorker"
)
```

**Use Both:**

You can use KMP TaskManager with Alarmee:
- **KMP TaskManager**: Background task execution
- **Alarmee**: User-facing notifications

```kotlin
// Background work with KMP TaskManager
scheduler.enqueue(
    id = "background-sync",
    trigger = TaskTrigger.Periodic(15_MINUTES),
    workerClassName = "SyncWorker"
)

// User notification with Alarmee
alarmee.schedule(
    alarm = Alarm(
        id = "reminder",
        timeInMillis = targetTime,
        title = "Meeting in 10 minutes"
    )
)
```

---

### From KMPNotifier

**KMPNotifier** is for push notifications. KMP TaskManager handles background execution.

**Before (KMPNotifier only):**

```kotlin
// Only push notifications
notifier.notify("sync-complete", "Data synced")
```

**After (KMP TaskManager + KMPNotifier):**

```kotlin
// Background work with KMP TaskManager
scheduler.enqueue(
    id = "sync",
    trigger = TaskTrigger.Periodic(15_MINUTES),
    workerClassName = "SyncWorker"
)

// In SyncWorker, show notification with KMPNotifier
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        syncData()

        // Show notification
        notifier.notify("sync-complete", "Data synced successfully")

        return true
    }
}
```

**Recommended Approach:**

Use both libraries together:
- **KMP TaskManager**: Schedule and execute background tasks
- **KMPNotifier**: Show notifications to users

---

## Breaking Changes

### Version 2.1.0

1. **iOS Worker Return Type**
   - Changed from `suspend fun doWork(input: String?)` to `suspend fun doWork(input: String?): Boolean`
   - **Fix:** Return `true` on success, `false` on failure

2. **iOS Koin Initialization**
   - Changed from `initKoin()` to `KoinIOSKt.doInitKoinIos()`
   - **Fix:** Update `iOSApp.swift` initialization

### Version 2.0.0

1. **Package Rename**
   - Changed from `com.example` to `io.brewkits`
   - **Fix:** Update imports

2. **Koin Modules**
   - Changed from manual DI to Koin-based DI
   - **Fix:** Initialize Koin in Application class (Android) and AppDelegate (iOS)

---

## Common Migration Issues

### Issue 1: iOS Workers Not Executing

**Symptom:** Tasks scheduled but never run on iOS

**Causes:**
1. Task identifiers not in `Info.plist`
2. Background task handlers not registered
3. App not in background

**Solution:**

1. Add task IDs to `Info.plist`:
   ```xml
   <key>BGTaskSchedulerPermittedIdentifiers</key>
   <array>
       <string>periodic-sync-task</string>
   </array>
   ```

2. Register handlers in `iOSApp.swift`:
   ```swift
   BGTaskScheduler.shared.register(
       forTaskWithIdentifier: "periodic-sync-task",
       using: nil
   ) { task in
       koinIos.getScheduler().handleSingleTask(
           task: task as! BGAppRefreshTask,
           taskIdentifier: "periodic-sync-task"
       )
   }
   ```

3. Test with app in background (Home button)

---

### Issue 2: Koin Dependency Injection Errors

**Symptom:** `NullPointerException` or "No definition found" errors

**Cause:** Koin not initialized

**Solution:**

**Android:**
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

**iOS:**
```swift
init() {
    KoinIOSKt.doInitKoinIos()
}
```

---

### Issue 3: Task Events Not Received

**Symptom:** Workers complete but UI doesn't update

**Cause:** Not collecting from `TaskEventBus`

**Solution:**

```kotlin
@Composable
fun MyScreen() {
    LaunchedEffect(Unit) {
        TaskEventBus.events.collect { event ->
            // Handle event
            println("Event: ${event.message}")
        }
    }
}
```

---

### Issue 4: Android WorkManager Not Initialized

**Symptom:** `WorkManager is not initialized properly` error

**Cause:** WorkManager initialized before Koin

**Solution:**

Initialize Koin first in `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize Koin FIRST
        startKoin {
            androidContext(this@MyApp)
            modules(kmpTaskManagerModule())
        }

        // Then configure WorkManager (if needed)
        // WorkManager will auto-initialize through Koin
    }
}
```

---

## Upgrade Checklist

Use this checklist when upgrading:

### For All Versions

- [ ] Update dependency in `build.gradle.kts`
- [ ] Sync Gradle files
- [ ] Clean and rebuild project
- [ ] Run tests

### For v2.1.0+ (from v2.0.x)

- [ ] Update iOS Koin initialization to `KoinIOSKt.doInitKoinIos()`
- [ ] Update iOS workers to return `Boolean`
- [ ] Add error handling with `try-catch` in workers
- [ ] (Optional) Add `TaskEventBus` emissions
- [ ] (Optional) Migrate to task chains for complex workflows

### iOS-Specific

- [ ] Verify `Info.plist` has task identifiers
- [ ] Verify `iOSApp.swift` registers background tasks
- [ ] Test on physical device (simulator has limitations)

### Android-Specific

- [ ] Verify `AndroidManifest.xml` has required permissions
- [ ] Verify Application class initializes Koin
- [ ] Test with `adb` commands
- [ ] Test in Doze mode

---

## Getting Help

- 📖 [Quick Start Guide](quickstart.md)
- 📗 [API Reference](api-reference.md)
- 📙 [Platform Setup](platform-setup.md)
- 🐛 [GitHub Issues](https://github.com/brewkits/kmp_worker/issues)
- 💬 [GitHub Discussions](https://github.com/brewkits/kmp_worker/discussions)

---

## Changelog

For a complete list of changes, see [CHANGELOG.md](../CHANGELOG.md).

---

**Need more help?** Open an issue or ask in Discussions!
