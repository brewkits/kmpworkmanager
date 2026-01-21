# iOS Exact Alarm Guide - Understanding Platform Limitations

**Version**: 2.1.1+
**Last Updated**: January 20, 2026

## Overview

This guide explains the fundamental difference between Android and iOS regarding exact alarm/timer functionality, and how to handle it properly in KMP WorkManager.

---

## The Fundamental Difference

### Android: Code Execution ✅
```kotlin
// Android: AlarmManager EXECUTES YOUR CODE at exact time
scheduler.enqueue(
    id = "morning-sync",
    trigger = TaskTrigger.Exact(atEpochMillis = morningTime),
    workerClassName = "SyncWorker"  // ← This CODE RUNS at exact time
)

// Behind the scenes (Android):
// AlarmManager → BroadcastReceiver → Worker.doWork() executes
```

### iOS: Notification Only ⚠️
```kotlin
// iOS: UNUserNotificationCenter SHOWS NOTIFICATION at exact time
scheduler.enqueue(
    id = "morning-sync",
    trigger = TaskTrigger.Exact(atEpochMillis = morningTime),
    workerClassName = "SyncWorker"  // ← This CODE DOES NOT RUN on iOS
)

// Behind the scenes (iOS):
// UNUserNotificationCenter → Notification displays → No code execution
```

**Key Insight**: iOS does NOT allow background code execution at exact times due to strict background execution policies.

---

## Why iOS Restricts Exact Execution

Apple's design philosophy prioritizes:
1. **Battery life**: Preventing apps from waking device frequently
2. **User privacy**: Limiting background tracking capabilities
3. **System stability**: Preventing resource exhaustion

**iOS Background Execution Rules**:
- BGAppRefreshTask: ~30 seconds, opportunistic (iOS decides when)
- BGProcessingTask: ~60 seconds, opportunistic (iOS decides when)
- No guaranteed exact timing for background code execution

**What iOS DOES allow at exact times**:
- ✅ Local notifications (UNUserNotificationCenter)
- ✅ Calendar/EventKit reminders
- ❌ Background code execution

---

## ExactAlarmIOSBehavior Options (v2.1.1+)

KMP WorkManager v2.1.1 introduces `ExactAlarmIOSBehavior` enum to make this limitation explicit and configurable.

### Option 1: SHOW_NOTIFICATION (Default - Recommended)

**Use When**:
- User-facing events (alarms, reminders, timers)
- Time-sensitive notifications
- Events that benefit from user interaction

**What Happens**:
- iOS displays local notification at exact time
- User can tap notification to open app
- No background code execution
- Works reliably in all power modes

**Example**:
```kotlin
scheduler.enqueue(
    id = "morning-alarm",
    trigger = TaskTrigger.Exact(morningTime),
    workerClassName = "AlarmWorker", // Ignored on iOS
    constraints = Constraints(
        exactAlarmIOSBehavior = ExactAlarmIOSBehavior.SHOW_NOTIFICATION
    )
)
```

**iOS Result**:
```
8:00 AM: [NOTIFICATION DISPLAYS]
Title: "AlarmWorker"
Body: "Scheduled event"
Sound: Default notification sound
```

**Pros**:
- ✅ Reliable timing (±seconds)
- ✅ Works in Low Power Mode
- ✅ Survives app termination
- ✅ Apple-approved behavior

**Cons**:
- ❌ No background code execution
- ❌ Requires notification permission

---

### Option 2: ATTEMPT_BACKGROUND_RUN (Best Effort)

**Use When**:
- Non-critical background sync
- Data refresh where approximate timing is acceptable
- Operations that benefit from timing hint but don't require exact execution

**What Happens**:
- Schedules BGAppRefreshTask with `earliestBeginDate`
- iOS MAY run task around specified time
- Timing accuracy: ±minutes to ±hours
- May not run at all if conditions not met

**Example**:
```kotlin
scheduler.enqueue(
    id = "nightly-sync",
    trigger = TaskTrigger.Exact(midnightTime),
    workerClassName = "SyncWorker",
    constraints = Constraints(
        exactAlarmIOSBehavior = ExactAlarmIOSBehavior.ATTEMPT_BACKGROUND_RUN
    )
)
```

**iOS Result**:
```
Target: 12:00 AM (midnight)
Actual: 12:43 AM (43 minutes late)
         OR 2:15 AM (2+ hours late)
         OR Never (if Low Power Mode enabled)
```

**Pros**:
- ✅ Code execution (if iOS allows)
- ✅ No user interaction required

**Cons**:
- ❌ NOT suitable for time-critical operations
- ❌ Timing is NOT guaranteed
- ❌ May not run in Low Power Mode
- ❌ May be delayed significantly
- ❌ iOS decides when/if to run

**When iOS WILL Run**:
- Device is plugged in OR has sufficient battery
- Device is connected to Wi-Fi (if `requiresNetwork = true`)
- App has not exceeded background budget
- Low Power Mode is disabled

---

### Option 3: THROW_ERROR (Fail Fast - Development)

**Use When**:
- Development/testing to catch incorrect assumptions
- Critical operations that MUST run at exact time (forces rethink)
- Ensuring iOS limitations are acknowledged before production

**What Happens**:
- Throws `UnsupportedOperationException` immediately when scheduling
- Prevents silent failures in production
- Forces platform-aware design decisions

**Example**:
```kotlin
scheduler.enqueue(
    id = "critical-transaction",
    trigger = TaskTrigger.Exact(criticalTime),
    workerClassName = "TransactionWorker",
    constraints = Constraints(
        exactAlarmIOSBehavior = ExactAlarmIOSBehavior.THROW_ERROR
    )
)
```

**iOS Result**:
```kotlin
Exception in thread "main" UnsupportedOperationException:
❌ iOS does not support exact alarms for background code execution.

TaskTrigger.Exact on iOS can only:
1. Show notification at exact time (SHOW_NOTIFICATION)
2. Attempt opportunistic background run (ATTEMPT_BACKGROUND_RUN - not guaranteed)

To fix this error, choose one of:

Option 1: Show notification (user-facing events)
Constraints(exactAlarmIOSBehavior = ExactAlarmIOSBehavior.SHOW_NOTIFICATION)

Option 2: Best-effort background run (non-critical sync)
Constraints(exactAlarmIOSBehavior = ExactAlarmIOSBehavior.ATTEMPT_BACKGROUND_RUN)

Option 3: Platform-specific implementation
if (Platform.isIOS) {
    // Use notification or rethink approach
} else {
    // Use TaskTrigger.Exact on Android
}
```

**Pros**:
- ✅ Immediate feedback during development
- ✅ Prevents deploying code with wrong expectations
- ✅ Forces explicit handling of iOS limitations

**Cons**:
- ❌ Crashes if not handled
- ❌ Requires code changes to fix

---

## Decision Tree: Which Option to Use?

```
┌─ Is this a user-facing event (alarm, reminder, timer)?
│  ├─ YES → Use SHOW_NOTIFICATION ✅
│  │         (User will see/hear notification at exact time)
│  │
│  └─ NO → Is exact timing CRITICAL for your business logic?
│     ├─ YES → Use THROW_ERROR 🚨
│     │        (Forces you to rethink iOS approach)
│     │
│     └─ NO → Can you tolerate ±minutes to ±hours delay?
│        ├─ YES → Use ATTEMPT_BACKGROUND_RUN ⏱️
│        │        (iOS will try, but no guarantee)
│        │
│        └─ NO → Use THROW_ERROR 🚨
│               (iOS cannot meet your requirements)
```

---

## Platform-Specific Implementation Example

For operations requiring exact timing on Android but accepting notifications on iOS:

```kotlin
fun scheduleAlarm(timestamp: Long) {
    if (Platform.isIOS) {
        // iOS: Show notification at exact time
        scheduler.enqueue(
            id = "alarm-$timestamp",
            trigger = TaskTrigger.Exact(timestamp),
            workerClassName = "AlarmWorker",
            constraints = Constraints(
                exactAlarmIOSBehavior = ExactAlarmIOSBehavior.SHOW_NOTIFICATION
            )
        )
    } else {
        // Android: Execute code at exact time
        scheduler.enqueue(
            id = "alarm-$timestamp",
            trigger = TaskTrigger.Exact(timestamp),
            workerClassName = "AlarmWorker"
            // Android always executes worker code
        )
    }
}
```

---

## Handling Notification Response on iOS

When using `SHOW_NOTIFICATION`, handle notification taps in your iOS app:

```swift
// iOSApp.swift
func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    didReceive response: UNNotificationResponse,
    withCompletionHandler completionHandler: @escaping () -> Void
) {
    let identifier = response.notification.request.identifier

    // Execute your logic when user taps notification
    if identifier == "morning-alarm" {
        // User tapped morning alarm notification
        // Now you can run your sync/processing
        let worker = SyncWorker()
        Task {
            await worker.doWork(input: nil)
        }
    }

    completionHandler()
}
```

---

## Migration from v2.1.0 to v2.1.1

**Good News**: Fully backward compatible!

**Default Behavior**:
```kotlin
// v2.1.0 and earlier
scheduler.enqueue(
    id = "alarm",
    trigger = TaskTrigger.Exact(time),
    workerClassName = "AlarmWorker"
)
// iOS: Showed notification (undocumented)

// v2.1.1+ (same behavior, now explicit)
scheduler.enqueue(
    id = "alarm",
    trigger = TaskTrigger.Exact(time),
    workerClassName = "AlarmWorker",
    constraints = Constraints(
        exactAlarmIOSBehavior = ExactAlarmIOSBehavior.SHOW_NOTIFICATION // Default
    )
)
// iOS: Shows notification (documented and explicit)
```

**Recommended Action**: Review your exact alarm usage and explicitly configure behavior:

```kotlin
// Audit your code
grep -r "TaskTrigger.Exact" .

// For each usage, ask:
// 1. Is this user-facing? → SHOW_NOTIFICATION
// 2. Is timing critical? → THROW_ERROR or rethink iOS approach
// 3. Is approximate timing OK? → ATTEMPT_BACKGROUND_RUN
```

---

## Common Pitfalls

### ❌ Pitfall 1: Expecting Code Execution with SHOW_NOTIFICATION
```kotlin
scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Exact(syncTime),
    workerClassName = "SyncWorker",
    constraints = Constraints(
        exactAlarmIOSBehavior = ExactAlarmIOSBehavior.SHOW_NOTIFICATION
    )
)
// ❌ SyncWorker.doWork() will NOT execute on iOS
// ✅ Notification will display, user can tap to trigger sync
```

### ❌ Pitfall 2: Assuming ATTEMPT_BACKGROUND_RUN Guarantees Timing
```kotlin
scheduler.enqueue(
    id = "critical-upload",
    trigger = TaskTrigger.Exact(deadline),
    workerClassName = "UploadWorker",
    constraints = Constraints(
        exactAlarmIOSBehavior = ExactAlarmIOSBehavior.ATTEMPT_BACKGROUND_RUN
    )
)
// ❌ iOS may run this hours later or not at all
// ✅ Use THROW_ERROR to acknowledge iOS cannot meet requirement
```

### ❌ Pitfall 3: Using ATTEMPT_BACKGROUND_RUN in Low Power Mode
```kotlin
// User enables Low Power Mode
scheduler.enqueue(
    id = "hourly-sync",
    trigger = TaskTrigger.Exact(nextHour),
    workerClassName = "SyncWorker",
    constraints = Constraints(
        exactAlarmIOSBehavior = ExactAlarmIOSBehavior.ATTEMPT_BACKGROUND_RUN
    )
)
// ❌ Task will NOT run until Low Power Mode is disabled
// ✅ SHOW_NOTIFICATION works even in Low Power Mode
```

---

## Best Practices

### 1. Choose the Right Option
- **User-facing events**: Always use `SHOW_NOTIFICATION`
- **Background sync**: Use `ATTEMPT_BACKGROUND_RUN` only if approximate timing is acceptable
- **Critical operations**: Use `THROW_ERROR` to force proper iOS handling

### 2. Test on Real Devices
```swift
// Simulate Low Power Mode
Settings → Battery → Low Power Mode → ON

// Check if your task still works as expected
```

### 3. Monitor Background Budget
```swift
// iOS Settings → Developer → Background App Refresh
// Check how much background time your app gets
```

### 4. Provide Fallback UI
```kotlin
// If background sync might not run, give user manual option
Button(onClick = { syncNow() }) {
    Text("Sync Now")
}
```

### 5. Document Expectations
```kotlin
/**
 * Syncs data with server.
 *
 * **Android**: Runs at exact scheduled time
 * **iOS**: Shows notification at scheduled time; sync runs when user taps
 */
fun scheduleSync(time: Long)
```

---

## Comparison Table

| Feature | SHOW_NOTIFICATION | ATTEMPT_BACKGROUND_RUN | THROW_ERROR |
|---------|-------------------|------------------------|-------------|
| Code execution | ❌ No | ✅ Yes (if iOS allows) | ❌ N/A (throws) |
| Timing accuracy | ✅ ±seconds | ❌ ±minutes to ±hours | ❌ N/A |
| Works in Low Power Mode | ✅ Yes | ❌ No | ❌ N/A |
| User interaction required | ⚠️ Yes (tap notification) | ✅ No | ❌ N/A |
| Production use | ✅ Recommended | ⚠️ Use with caution | ❌ Development only |
| Requires notification permission | ✅ Yes | ❌ No | ❌ N/A |

---

## Additional Resources

- **Apple Documentation**: [BGTaskScheduler](https://developer.apple.com/documentation/backgroundtasks/bgtaskscheduler)
- **WWDC Video**: [Background Execution](https://developer.apple.com/videos/play/wwdc2020/10063/)
- **KMP WorkManager**: [iOS Best Practices](iOS-BEST-PRACTICES.md)
- **GitHub Issues**: [Report iOS-specific issues](https://github.com/brewkits/kmpworkmanager/issues)

---

**Questions or Issues?**
Open an issue with the `[iOS]` label on GitHub.

---

Last Updated: January 20, 2026
Version: 2.1.1
