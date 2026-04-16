# Migration Guide: v2.3.9 to v2.4.0

Version 2.4.0 introduces a simplified iOS background task integration path and important stability fixes for Android 12+.

## Key Changes

- **Simplified iOS Integration**: A new Kotlin-native `IosBackgroundTaskHandler` replaces the need for manual Swift boilerplate.
- **Android Exact Alarm Fallback**: Automatic registration of a default alarm receiver.
- **Android 12-16 Compatibility**: Fixed expedited work constraints and periodic task payload limits.

---

## iOS Migration (Highly Recommended)

In previous versions, you had to copy ~150 lines of Swift code to your `AppDelegate` to handle background tasks. In v2.4.0, this logic is moved into the library.

### 1. Update `AppDelegate.swift` or `iOSApp.swift`

**Before (v2.3.9):**
You had manual `handleAppRefreshTask`, `handleProcessingTask`, and `handleChainExecutorTask` methods in Swift.

**After (v2.4.0):**
Remove all the boilerplate methods and use the consolidated handler:

```swift
import ComposeApp // Library is exported via ComposeApp

// Inside your task registration block
BGTaskScheduler.shared.register(forTaskWithIdentifier: "your-task-id", using: nil) { task in
    IosBackgroundTaskHandler.shared.handleSingleTask(
        task: task,
        scheduler: koin.getScheduler(),
        executor: koin.getExecutor()
    )
}

// For the chain executor (REQUIRED if using Task Chains)
BGTaskScheduler.shared.register(forTaskWithIdentifier: "kmp_chain_executor_task", using: nil) { task in
    IosBackgroundTaskHandler.shared.handleChainExecutorTask(
        task: task,
        chainExecutor: koin.getChainExecutor()
    )
}
```

### 2. Framework Name Change
To avoid namespace collisions with apps named "KMPWorkManager", the internal library framework has been renamed to `KmpWorkerLibrary`. 

If you were manually importing the framework in Swift, change:
`import KMPWorkManager` → `import ComposeApp` (or your shared module name).

---

## Android Migration

### 1. Exact Alarms
If you use `TaskTrigger.Exact`, you no longer need to provide a custom `AlarmReceiver` subclass unless you have specific custom logic. The library now provides a `DefaultAlarmReceiver`.

### 2. Expedited Tasks
The library now automatically handles constraint compatibility for expedited tasks. If you schedule a task with `expedited = true` but include incompatible constraints (like `requiresCharging`), the library will gracefully fall back to a standard task instead of crashing.

---

## Documentation Updates

All official guides have been updated to reflect the v2.4.0 API. Please refer to:
- [Quick Start Guide](quickstart.md)
- [Platform Setup Guide](platform-setup.md)
