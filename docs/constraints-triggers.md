# Constraints & Triggers Reference

Complete guide to all task triggers and execution constraints in KMP TaskManager.

## Table of Contents

- [Task Triggers](#task-triggers)
- [Constraints](#constraints)
- [Platform Support Matrix](#platform-support-matrix)
- [Use Cases by Trigger](#use-cases-by-trigger)
- [Best Practices](#best-practices)

---

## Task Triggers

Triggers define **when** a task should execute. KMP TaskManager supports 9 different trigger types.

### OneTime

Execute a task once after an optional delay.

```kotlin
data class OneTime(
    val initialDelayMs: Long = 0
) : TaskTrigger
```

**Parameters:**
- `initialDelayMs`: Delay in milliseconds before execution (default: 0)

**Platform Support:** ✅ Android, ✅ iOS

**Example:**

```kotlin
// Execute immediately
TaskTrigger.OneTime()

// Execute after 5 seconds
TaskTrigger.OneTime(initialDelayMs = 5_000)

// Execute after 1 hour
TaskTrigger.OneTime(initialDelayMs = 60 * 60 * 1000)
```

**Use Cases:**
- Immediate background tasks
- Delayed operations (e.g., "delete photo in 24 hours")
- One-off data sync
- User-initiated uploads

**Implementation:**
- **Android**: `OneTimeWorkRequest` with initial delay
- **iOS**: `BGAppRefreshTaskRequest` scheduled with `earliestBeginDate`

---

### Periodic

Execute a task repeatedly at fixed intervals.

```kotlin
data class Periodic(
    val intervalMs: Long,
    val flexMs: Long? = null
) : TaskTrigger
```

**Parameters:**
- `intervalMs`: Interval between executions in milliseconds (minimum: 15 minutes)
- `flexMs`: Flex time window in milliseconds (Android only, optional)

**Platform Support:** ✅ Android, ✅ iOS

**Important:**
- Minimum interval: **15 minutes** (enforced by Android WorkManager)
- iOS: Task auto-reschedules after completion
- Android: WorkManager handles rescheduling automatically

**Example:**

```kotlin
// Every 15 minutes (minimum interval)
TaskTrigger.Periodic(intervalMs = 15 * 60 * 1000)

// Every 30 minutes
TaskTrigger.Periodic(intervalMs = 30 * 60 * 1000)

// Every 1 hour with 15-minute flex window (Android)
TaskTrigger.Periodic(
    intervalMs = 60 * 60 * 1000,
    flexMs = 15 * 60 * 1000
)

// Every 6 hours
TaskTrigger.Periodic(intervalMs = 6 * 60 * 60 * 1000)

// Every 24 hours (daily)
TaskTrigger.Periodic(intervalMs = 24 * 60 * 60 * 1000)
```

**Use Cases:**
- News feed sync
- Weather updates
- Stock price fetching
- Social media sync
- Background data refresh
- Health data collection

**Implementation:**
- **Android**: `PeriodicWorkRequest` with interval and flex time
- **iOS**: `BGAppRefreshTaskRequest` that reschedules itself after completion

**Flex Time (Android Only):**

Flex time allows the system to run the task within a flexible window:

```kotlin
TaskTrigger.Periodic(
    intervalMs = 60 * 60 * 1000, // 1 hour
    flexMs = 15 * 60 * 1000      // 15 minutes
)
```

This means the task runs between 45-60 minutes after the previous execution, allowing Android to batch tasks for better battery life.

---

### Exact

Execute a task at a precise time (alarm/reminder style).

```kotlin
data class Exact(
    val atEpochMillis: Long
) : TaskTrigger
```

**Parameters:**
- `atEpochMillis`: Exact timestamp in epoch milliseconds

**Platform Support:** ✅ Android, ✅ iOS

**Example:**

```kotlin
// In 1 hour
val oneHourLater = Clock.System.now()
    .plus(1.hours)
    .toEpochMilliseconds()

TaskTrigger.Exact(atEpochMillis = oneHourLater)

// At specific date/time
val specificTime = LocalDateTime(2025, 12, 25, 9, 0) // Christmas 9 AM
    .toInstant(TimeZone.currentSystemDefault())
    .toEpochMilliseconds()

TaskTrigger.Exact(atEpochMillis = specificTime)

// Tomorrow at 8 AM
val tomorrow8AM = Clock.System.now()
    .plus(1.days)
    .toLocalDateTime(TimeZone.currentSystemDefault())
    .let { LocalDateTime(it.year, it.monthNumber, it.dayOfMonth, 8, 0) }
    .toInstant(TimeZone.currentSystemDefault())
    .toEpochMilliseconds()

TaskTrigger.Exact(atEpochMillis = tomorrow8AM)
```

**Use Cases:**
- Medication reminders
- Meeting notifications
- Scheduled posts
- Wake-up alarms
- Appointment alerts
- Event reminders

**Implementation:**
- **Android**: `AlarmManager.setExactAndAllowWhileIdle()`
- **iOS**: `UNUserNotificationCenter` local notification

**Permissions Required:**
- **Android**: `SCHEDULE_EXACT_ALARM` permission (Android 12+)
- **iOS**: Notification permission

**Important Notes:**
- Tasks run even in Doze mode
- Very precise execution (within seconds)
- Best for user-facing time-sensitive operations

---

### Windowed

Execute a task within a time window (between start and end time).

```kotlin
data class Windowed(
    val startEpochMillis: Long,
    val endEpochMillis: Long
) : TaskTrigger
```

**Parameters:**
- `startEpochMillis`: Window start time in epoch milliseconds
- `endEpochMillis`: Window end time in epoch milliseconds

**Platform Support:** ✅ Android, ❌ iOS (returns `REJECTED_OS_POLICY`)

**Example:**

```kotlin
val now = Clock.System.now().toEpochMilliseconds()

// Execute between 1 minute and 5 minutes from now
TaskTrigger.Windowed(
    startEpochMillis = now + 60_000,
    endEpochMillis = now + 5 * 60_000
)

// Execute between 2 PM and 4 PM today
val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
val start = LocalDateTime(today.year, today.monthNumber, today.dayOfMonth, 14, 0)
    .toInstant(TimeZone.currentSystemDefault())
    .toEpochMilliseconds()
val end = LocalDateTime(today.year, today.monthNumber, today.dayOfMonth, 16, 0)
    .toInstant(TimeZone.currentSystemDefault())
    .toEpochMilliseconds()

TaskTrigger.Windowed(
    startEpochMillis = start,
    endEpochMillis = end
)
```

**Use Cases:**
- Off-peak processing (e.g., "run between 2 AM - 4 AM")
- Flexible scheduling
- Battery-friendly background tasks

**Implementation:**
- **Android**: `OneTimeWorkRequest` with delay and flex time window
- **iOS**: Not supported

---

### ContentUri

Trigger a task when content provider changes are detected (Android only).

```kotlin
data class ContentUri(
    val uriString: String,
    val triggerForDescendants: Boolean = true
) : TaskTrigger
```

**Parameters:**
- `uriString`: Content URI to observe
- `triggerForDescendants`: Watch descendant URIs (default: true)

**Platform Support:** ✅ Android, ❌ iOS (returns `REJECTED_OS_POLICY`)

**Example:**

```kotlin
// Watch for new photos
TaskTrigger.ContentUri(
    uriString = "content://media/external/images/media",
    triggerForDescendants = true
)

// Watch for new videos
TaskTrigger.ContentUri(
    uriString = "content://media/external/video/media"
)

// Watch for new downloads
TaskTrigger.ContentUri(
    uriString = "content://downloads/my_downloads"
)

// Watch specific URI only (no descendants)
TaskTrigger.ContentUri(
    uriString = "content://com.example.app/items/123",
    triggerForDescendants = false
)
```

**Use Cases:**
- Auto-backup new photos
- Process new downloads
- Sync MediaStore changes
- React to file system changes
- Watch for new documents

**Implementation:**
- **Android**: `OneTimeWorkRequest` with `ContentUriTrigger`
- **iOS**: Not supported (iOS doesn't have content providers)

**Important:**
- Requires `READ_EXTERNAL_STORAGE` permission for MediaStore URIs
- Task triggers when URI content changes
- Useful for reactive background processing

---

### BatteryLow

Trigger when device battery is low.

```kotlin
data object BatteryLow : TaskTrigger
```

**Platform Support:** ✅ Android, ✅ iOS

**Example:**

```kotlin
TaskTrigger.BatteryLow
```

**Use Cases:**
- Enable power-saving mode
- Reduce background sync
- Show low battery notification
- Trigger battery optimization
- Pause non-essential tasks

**Implementation:**
- **Android**: Uses `android.intent.action.BATTERY_LOW` broadcast
- **iOS**: Monitors battery state via `UIDevice.current.batteryState`

---

### BatteryOkay

Trigger when device battery is NOT low (good battery level).

```kotlin
data object BatteryOkay : TaskTrigger
```

**Platform Support:** ✅ Android, ✅ iOS

**Example:**

```kotlin
TaskTrigger.BatteryOkay
```

**Use Cases:**
- Resume background sync
- Start heavy processing
- ML model training
- Video transcoding
- Large downloads

**Implementation:**
- **Android**: Uses `android.intent.action.BATTERY_OKAY` broadcast
- **iOS**: Monitors battery state via `UIDevice.current.batteryState`

**Tip:** Combine with `requiresCharging` constraint for even safer battery usage:

```kotlin
scheduler.enqueue(
    id = "heavy-task",
    trigger = TaskTrigger.BatteryOkay,
    workerClassName = "HeavyWorker",
    constraints = Constraints(
        requiresCharging = true,
        requiresBatteryNotLow = true
    )
)
```

---

### StorageLow

Trigger when device storage is low.

```kotlin
data object StorageLow : TaskTrigger
```

**Platform Support:** ✅ Android, ❌ iOS (returns `REJECTED_OS_POLICY`)

**Example:**

```kotlin
TaskTrigger.StorageLow
```

**Use Cases:**
- Delete cache files
- Clean up temporary files
- Remove old logs
- Compress large files
- Show storage warning

**Implementation:**
- **Android**: Uses `android.intent.action.DEVICE_STORAGE_LOW` broadcast
- **iOS**: Not supported (no system-level storage low event)

---

### DeviceIdle

Trigger when device is idle (screen off, not recently used).

```kotlin
data object DeviceIdle : TaskTrigger
```

**Platform Support:** ✅ Android, ❌ iOS (returns `REJECTED_OS_POLICY`)

**Example:**

```kotlin
TaskTrigger.DeviceIdle
```

**Use Cases:**
- Database maintenance
- Index updates
- Cache cleanup
- Background optimization
- Non-urgent processing

**Implementation:**
- **Android**: Uses Doze mode idle state
- **iOS**: Not supported (BGTaskScheduler already runs when device is idle)

**Note:** Tasks with this trigger run when the device enters Doze mode idle state, typically when screen is off and device is stationary.

---

## Constraints

Constraints define **under what conditions** a task can execute.

### Network Constraints

#### requiresNetwork

```kotlin
requiresNetwork: Boolean = false
```

Whether the task requires any network connectivity.

**Example:**

```kotlin
Constraints(requiresNetwork = true)
```

**Platform Support:** ✅ Android, ✅ iOS

---

#### networkType

```kotlin
networkType: NetworkType = NetworkType.CONNECTED
```

Specific type of network required.

**Options:**
- `NetworkType.NOT_REQUIRED` - No network needed
- `NetworkType.CONNECTED` - Any network connection
- `NetworkType.UNMETERED` - WiFi or unlimited data (WiFi on iOS)
- `NetworkType.NOT_ROAMING` - Non-roaming network (Android only)
- `NetworkType.METERED` - Cellular allowed (Android only)
- `NetworkType.TEMPORARILY_UNMETERED` - Temporarily free (Android only)

**Example:**

```kotlin
// Require WiFi only
Constraints(
    requiresNetwork = true,
    networkType = NetworkType.UNMETERED
)

// Any network connection
Constraints(
    requiresNetwork = true,
    networkType = NetworkType.CONNECTED
)

// Non-roaming network (Android)
Constraints(
    requiresNetwork = true,
    networkType = NetworkType.NOT_ROAMING
)
```

**Platform Support:**
- Android: All types
- iOS: Only `CONNECTED` and `UNMETERED`

---

#### requiresUnmeteredNetwork

```kotlin
requiresUnmeteredNetwork: Boolean = false
```

Shortcut for requiring WiFi (same as `networkType = NetworkType.UNMETERED`).

**Example:**

```kotlin
Constraints(requiresUnmeteredNetwork = true)
```

**Platform Support:** ✅ Android, ✅ iOS

**Use Cases:**
- Large file downloads
- Video uploads
- Bulk data sync
- App updates

---

### Battery Constraints

#### requiresCharging

```kotlin
requiresCharging: Boolean = false
```

Whether the device must be charging.

**Example:**

```kotlin
Constraints(requiresCharging = true)
```

**Platform Support:** ✅ Android, ✅ iOS

**Use Cases:**
- Video transcoding
- ML model training
- Large backups
- Database migrations
- Heavy processing

---

#### requiresBatteryNotLow

```kotlin
requiresBatteryNotLow: Boolean = false
```

Whether battery must be above low threshold.

**Example:**

```kotlin
Constraints(requiresBatteryNotLow = true)
```

**Platform Support:** ✅ Android, ✅ iOS

**Use Cases:**
- Background sync
- Image processing
- Non-critical uploads

---

### Storage Constraints

#### requiresStorageNotLow

```kotlin
requiresStorageNotLow: Boolean = false
```

Whether device must have sufficient storage.

**Example:**

```kotlin
Constraints(requiresStorageNotLow = true)
```

**Platform Support:** ✅ Android, ❌ iOS

**Use Cases:**
- File downloads
- Cache operations
- Database writes
- Media processing

---

### Device State Constraints

#### requiresDeviceIdle

```kotlin
requiresDeviceIdle: Boolean = false
```

Whether device must be idle (screen off, not recently used).

**Example:**

```kotlin
Constraints(requiresDeviceIdle = true)
```

**Platform Support:** ✅ Android, ❌ iOS

**Use Cases:**
- Database maintenance
- Index updates
- Background optimization
- Non-urgent tasks

---

#### allowWhileIdle

```kotlin
allowWhileIdle: Boolean = false
```

Whether task can run in Doze mode (Android).

**Example:**

```kotlin
Constraints(allowWhileIdle = true)
```

**Platform Support:** ✅ Android, ❌ iOS

**Use Cases:**
- Critical sync tasks
- Important notifications
- Time-sensitive operations

**Note:** Use sparingly - tasks in Doze mode consume battery.

---

### Task Property Constraints

#### isHeavyTask

```kotlin
isHeavyTask: Boolean = false
```

Whether this is a long-running task (>10 minutes).

**Example:**

```kotlin
Constraints(isHeavyTask = true)
```

**Platform Support:** ✅ Android, ✅ iOS

**Implementation:**
- **Android**: Uses `KmpHeavyWorker` with foreground service
- **iOS**: Uses `BGProcessingTask` instead of `BGAppRefreshTask`

**Use Cases:**
- ML model training (hours)
- Video transcoding (minutes to hours)
- Large database migrations (minutes)
- Bulk file processing (>10 minutes)

**Time Limits:**
- Android: No hard limit (foreground service)
- iOS: Several minutes (iOS decides)

---

#### expedited

```kotlin
expedited: Boolean = false
```

Whether task should run ASAP with high priority (Android only).

**Example:**

```kotlin
Constraints(expedited = true)
```

**Platform Support:** ✅ Android, ❌ iOS

**Use Cases:**
- User-initiated sync
- Urgent uploads
- Critical updates
- Time-sensitive operations

**Requirements:**
- Android 12+
- Task must complete within 10 minutes
- Limited quota (system may reject if quota exceeded)

---

### Retry Policy

#### backoffPolicy

```kotlin
backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL
```

How to retry failed tasks.

**Options:**
- `BackoffPolicy.EXPONENTIAL` - 10s, 20s, 40s, 80s, ...
- `BackoffPolicy.LINEAR` - 10s, 20s, 30s, 40s, ...

**Example:**

```kotlin
Constraints(
    backoffPolicy = BackoffPolicy.EXPONENTIAL,
    backoffDelayMs = 10_000 // Start with 10 seconds
)
```

**Platform Support:** ✅ Android, ✅ iOS

---

#### backoffDelayMs

```kotlin
backoffDelayMs: Long = 10_000
```

Initial retry delay in milliseconds.

**Example:**

```kotlin
Constraints(
    backoffPolicy = BackoffPolicy.LINEAR,
    backoffDelayMs = 5_000 // Start with 5 seconds
)
```

**Platform Support:** ✅ Android, ✅ iOS

---

### Existing Work Policy

#### existingWorkPolicy

```kotlin
existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
```

What to do when a task with the same ID already exists.

**Options:**
- `ExistingWorkPolicy.REPLACE` - Cancel existing, schedule new
- `ExistingWorkPolicy.KEEP` - Keep existing, ignore new
- `ExistingWorkPolicy.APPEND` - Queue after existing
- `ExistingWorkPolicy.APPEND_OR_REPLACE` - Append if running, replace otherwise

**Example:**

```kotlin
// Replace existing task
Constraints(existingWorkPolicy = ExistingWorkPolicy.REPLACE)

// Keep existing, ignore new request
Constraints(existingWorkPolicy = ExistingWorkPolicy.KEEP)

// Queue tasks sequentially
Constraints(existingWorkPolicy = ExistingWorkPolicy.APPEND)
```

**Platform Support:**
- Android: All policies
- iOS: Only `REPLACE` and `KEEP`

---

### iOS Quality of Service

#### qos

```kotlin
qos: QualityOfService = QualityOfService.DEFAULT
```

iOS priority hint for task execution.

**Options:**
- `QualityOfService.HIGH` - User-initiated priority
- `QualityOfService.DEFAULT` - Default priority
- `QualityOfService.LOW` - Background priority

**Example:**

```kotlin
Constraints(qos = QualityOfService.HIGH)
```

**Platform Support:** ❌ Android, ✅ iOS

**Use Cases:**
- `HIGH`: User-initiated sync, critical updates
- `DEFAULT`: Regular background tasks
- `LOW`: Non-urgent maintenance

---

## Platform Support Matrix

### Triggers

| Trigger | Android | iOS | Notes |
|---------|---------|-----|-------|
| OneTime | ✅ | ✅ | Full support |
| Periodic | ✅ | ✅ | 15-minute minimum |
| Exact | ✅ | ✅ | Requires permissions |
| Windowed | ✅ | ❌ | Android only |
| ContentUri | ✅ | ❌ | Android only |
| BatteryLow | ✅ | ✅ | Full support |
| BatteryOkay | ✅ | ✅ | Full support |
| StorageLow | ✅ | ❌ | Android only |
| DeviceIdle | ✅ | ❌ | Android only |

### Constraints

| Constraint | Android | iOS | Notes |
|------------|---------|-----|-------|
| requiresNetwork | ✅ | ✅ | Full support |
| networkType | ✅ | Partial | iOS: CONNECTED, UNMETERED only |
| requiresUnmeteredNetwork | ✅ | ✅ | Full support |
| requiresCharging | ✅ | ✅ | Full support |
| requiresBatteryNotLow | ✅ | ✅ | Full support |
| requiresStorageNotLow | ✅ | ❌ | Android only |
| requiresDeviceIdle | ✅ | ❌ | Android only |
| allowWhileIdle | ✅ | ❌ | Android only |
| isHeavyTask | ✅ | ✅ | Full support |
| expedited | ✅ | ❌ | Android only |
| backoffPolicy | ✅ | ✅ | Full support |
| backoffDelayMs | ✅ | ✅ | Full support |
| existingWorkPolicy | ✅ | Partial | iOS: REPLACE, KEEP only |
| qos | ❌ | ✅ | iOS only |

---

## Use Cases by Trigger

### OneTime
- User-initiated uploads
- Delayed actions
- One-off sync
- Export operations

### Periodic
- News feed refresh
- Weather updates
- Health data sync
- Stock prices
- Social media sync

### Exact
- Medication reminders
- Meeting notifications
- Alarms
- Scheduled posts
- Calendar events

### Windowed
- Off-peak processing
- Flexible scheduling
- Battery-friendly tasks

### ContentUri
- Auto-backup photos
- Process downloads
- MediaStore sync
- File watchers

### BatteryLow
- Power-saving mode
- Reduce sync frequency
- Show warnings

### BatteryOkay
- Resume normal operations
- Start heavy tasks
- ML training

### StorageLow
- Cache cleanup
- Delete old files
- Show storage warnings

### DeviceIdle
- Database maintenance
- Index updates
- Background optimization

---

## Best Practices

### 1. Choose the Right Trigger

**For user-facing time-sensitive tasks:**
```kotlin
TaskTrigger.Exact(atEpochMillis = targetTime)
```

**For background refresh:**
```kotlin
TaskTrigger.Periodic(intervalMs = 30_MINUTES)
```

**For immediate tasks:**
```kotlin
TaskTrigger.OneTime(initialDelayMs = 0)
```

---

### 2. Combine Constraints Wisely

**Good - Battery-safe heavy task:**
```kotlin
scheduler.enqueue(
    id = "ml-training",
    trigger = TaskTrigger.BatteryOkay,
    workerClassName = "MLWorker",
    constraints = Constraints(
        isHeavyTask = true,
        requiresCharging = true,
        requiresBatteryNotLow = true
    )
)
```

**Good - WiFi-only large download:**
```kotlin
scheduler.enqueue(
    id = "video-download",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "DownloadWorker",
    constraints = Constraints(
        requiresNetwork = true,
        networkType = NetworkType.UNMETERED
    )
)
```

**Bad - Contradictory constraints:**
```kotlin
// Don't do this!
Constraints(
    requiresDeviceIdle = true,
    expedited = true // Expedited tasks can't wait for idle!
)
```

---

### 3. Respect Platform Limitations

**iOS BGAppRefreshTask - 25 seconds max:**
```kotlin
// Keep workers fast
class QuickSyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        withTimeout(20_000) {
            // Complete within 20 seconds
        }
        return true
    }
}
```

**For longer work, use heavy task mode:**
```kotlin
scheduler.enqueue(
    id = "long-task",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "LongWorker",
    constraints = Constraints(
        isHeavyTask = true // iOS: BGProcessingTask
    )
)
```

---

### 4. Test on Both Platforms

Different behavior:
- Android: Predictable, testable with ADB commands
- iOS: Opportunistic, best tested on physical devices

---

## Next Steps

- [API Reference](api-reference.md) - Complete API docs
- [Task Chains](task-chains.md) - Build complex workflows
- [Platform Setup](platform-setup.md) - Configuration guide
- [Quick Start](quickstart.md) - Get started in 5 minutes

---

Need help? [Open an issue](https://github.com/brewkits/kmp_worker/issues) or ask in [Discussions](https://github.com/brewkits/kmp_worker/discussions).
