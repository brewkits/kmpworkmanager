# Constraints & Triggers Reference

Complete guide to all task triggers and execution constraints in KMP WorkManager.

## Table of Contents

- [Task Triggers](#task-triggers)
- [Constraints](#constraints)
- [Platform Support Matrix](#platform-support-matrix)
- [Use Cases by Trigger](#use-cases-by-trigger)
- [Best Practices](#best-practices)

---

## Task Triggers

Triggers define **when** a task should execute. KMP WorkManager supports 9 different trigger types.

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
    val flexMs: Long? = null,
    val initialDelayMs: Long = 0,
    val runImmediately: Boolean = true
) : TaskTrigger
```

**Parameters:**
- `intervalMs`: Interval between executions in milliseconds (minimum: 15 minutes on Android)
- `flexMs`: Flex time window in milliseconds (Android only, optional). Auto-clamped to `[5 min, intervalMs]`.
- `initialDelayMs`: Delay before the very first execution in milliseconds (default: 0)
- `runImmediately`: Whether to run on the first schedule (default: `true`). When `false` and `initialDelayMs == 0`, the first run is deferred by one full `intervalMs`. Setting `runImmediately = false` **and** `initialDelayMs > 0` is ambiguous and throws `IllegalArgumentException`.

**Platform Support:** ✅ Android, ✅ iOS

**Important:**
- Minimum interval: **15 minutes** (enforced by Android WorkManager)
- iOS: Task auto-reschedules after completion with drift correction anchored to the original schedule time
- Android: WorkManager handles rescheduling automatically

**Example:**

```kotlin
// Every 15 minutes, run immediately (default)
TaskTrigger.Periodic(intervalMs = 15 * 60 * 1000)

// Every 1 hour, but skip the immediate first run
TaskTrigger.Periodic(
    intervalMs = 60 * 60 * 1000,
    runImmediately = false
)

// Every 15 minutes, but wait 1 hour before the very first run
TaskTrigger.Periodic(
    intervalMs = 15 * 60 * 1000,
    initialDelayMs = 60 * 60 * 1000
)

// Every 1 hour with 15-minute flex window (Android)
TaskTrigger.Periodic(
    intervalMs = 60 * 60 * 1000,
    flexMs = 15 * 60 * 1000
)

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

**Platform Support:** ✅ Android (real exact alarm) · ⚠️ iOS (best-effort, see below — do not treat as equivalent)

> **Claim vs. Reality (verified in code)**
>
> *Claim a reader might assume from "✅ iOS":* an exact alarm on iOS wakes the app and runs
> worker code at the requested time, like Android's `AlarmManager.setExactAndAllowWhileIdle`.
>
> *Reality:* iOS has **no primitive** for "wake up at exactly time T and run this code" —
> confirmed in the library's own KDoc at `NativeTaskScheduler.kt:1057-1109`. What actually
> happens by default (`ExactAlarmIOSBehavior.SHOW_NOTIFICATION`) is a local notification is
> shown at the requested time; **your worker code does not run unless the user taps it**.
> `ATTEMPT_BACKGROUND_RUN` additionally tries an opportunistic `BGTaskScheduler` request, with
> no timing guarantee. The only reliable way missed work gets caught up is a
> `applicationDidBecomeActive` hook the host app must wire in — see
> `docs/iOS-EXACT-ALARM-GUIDE.md` and `docs/IOS_BGTASK_LIMITS.md` §5 for the full breakdown.

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

**Use Cases — Android only:**
- Medication reminders
- Meeting notifications
- Scheduled posts
- Wake-up alarms
- Appointment alerts
- Event reminders

**Use Cases — iOS (non-critical, opportunistic only):**
- "Sync drafts when the app opens after 2 AM"
- "Show a 'long time no see' nudge the next time the user opens the app"
- Any trigger where missing the exact moment has zero cost — never alarm clocks, medication
  reminders, trading/financial triggers, or expiring time-locked content.

**Implementation:**
- **Android**: `AlarmManager.setExactAndAllowWhileIdle()` — real OS-level exact alarm, fires
  even if the app is fully closed.
- **iOS**: `UNUserNotificationCenter` local notification (`ExactAlarmIOSBehavior.SHOW_NOTIFICATION`,
  the default) shows a banner only — worker code runs only if the user taps it, or via the
  `applicationDidBecomeActive` catch-up path. `ATTEMPT_BACKGROUND_RUN` additionally requests an
  opportunistic `BGTaskScheduler` run with no timing guarantee.

**Permissions Required:**
- **Android**: `SCHEDULE_EXACT_ALARM` permission (Android 12+)
- **iOS**: Notification permission (required for `SHOW_NOTIFICATION` to have any effect at all)

**Important Notes:**
- **Android**: tasks run even in Doze mode, with precise execution (within seconds). Best for
  user-facing time-sensitive operations.
- **iOS**: DO NOT use for anything where missing the time window has irreversible cost. See
  `docs/iOS-EXACT-ALARM-GUIDE.md` for the full DO/DON'T list.

---

### Windowed

Execute a task within a time window (between start and end time).

```kotlin
data class Windowed(
    val earliest: Long,
    val latest: Long
) : TaskTrigger
```

**Parameters:**
- `earliest`: Window start time in epoch milliseconds
- `latest`: Window end time in epoch milliseconds. On iOS only `earliest` is used via `earliestBeginDate`; `latest` is logged but not enforced — the OS runs the task opportunistically.

**Platform Support:** ✅ Android, ⚠️ iOS (best-effort — `latest` not enforced)

**Example:**

```kotlin
val now = Clock.System.now().toEpochMilliseconds()

// Execute between 1 minute and 5 minutes from now
TaskTrigger.Windowed(
    earliest = now + 60_000,
    latest   = now + 5 * 60_000
)

// Execute between 2 PM and 4 PM today
val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
val start = LocalDateTime(today.year, today.monthNumber, today.dayOfMonth, 14, 0)
    .toInstant(TimeZone.currentSystemDefault())
    .toEpochMilliseconds()
val end = LocalDateTime(today.year, today.monthNumber, today.dayOfMonth, 16, 0)
    .toInstant(TimeZone.currentSystemDefault())
    .toEpochMilliseconds()

TaskTrigger.Windowed(earliest = start, latest = end)
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

### BatteryLow / BatteryOkay / StorageLow / DeviceIdle (Removed — compile error since v2.3.7)

These were removed as `TaskTrigger` subtypes and are now `DeprecationLevel.ERROR`. Any reference to them causes a **compile error**.

**Migration** — use `systemConstraints` in `Constraints` instead:

```kotlin
// Battery low condition
Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY))

// Require battery not low
Constraints(systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW))

// Storage low allowed
Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE))

// Device idle required
Constraints(systemConstraints = setOf(SystemConstraint.DEVICE_IDLE))
```

| Old trigger | New `SystemConstraint` | Platform |
|---|---|---|
| `BatteryLow` | `ALLOW_LOW_BATTERY` | Android + iOS |
| `BatteryOkay` | `REQUIRE_BATTERY_NOT_LOW` | Android + iOS |
| `StorageLow` | `ALLOW_LOW_STORAGE` | Android only |
| `DeviceIdle` | `DEVICE_IDLE` | Android only |

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

#### requiresUnmeteredNetwork

```kotlin
requiresUnmeteredNetwork: Boolean = false
```

Requires Wi-Fi (unmetered) rather than any network. There is no separate `NetworkType` enum
in the current contract — an earlier draft of this doc showed one (`NOT_REQUIRED`/`CONNECTED`/
`UNMETERED`/`NOT_ROAMING`/`METERED`/`TEMPORARILY_UNMETERED`); it doesn't exist.
`requiresNetwork`/`requiresUnmeteredNetwork` are the only two network-axis fields.

**Example:**

```kotlin
Constraints(requiresNetwork = true, requiresUnmeteredNetwork = true)
```

**Platform Support:** ✅ Android, ✅ iOS — enforced for standalone tasks and chain steps alike
(iOS: `StandaloneConstraintGuard`/`ChainExecutor`, checked at dispatch time since
`BGTaskScheduler` has no native Wi-Fi-only flag).

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

**Platform Support:** ✅ Android, ⚠️ iOS — honored unconditionally (OS-level flag) for a task
with a static Info.plist identifier, and for the dynamic queue when **every** currently-pending
dynamic task requires charging. For a queue mixing charging and non-charging tasks, enforced
only if the host app has separately opted in to `UIDevice.batteryMonitoringEnabled` — the
library never toggles that flag itself (would race the host's own UI thread). See
`docs/ROADMAP.md` for the tracked gap.

**Use Cases:**
- Video transcoding
- ML model training
- Large backups
- Database migrations
- Heavy processing

---

### System Constraints

`requiresBatteryNotLow`, `requiresStorageNotLow`, and `requiresDeviceIdle` as direct
`Constraints` boolean fields do **not exist** — an earlier draft of this doc showed them that
way. They were superseded by a single `systemConstraints: Set<SystemConstraint>` field:

```kotlin
enum class SystemConstraint {
    REQUIRE_BATTERY_NOT_LOW,
    ALLOW_LOW_BATTERY,
    ALLOW_LOW_STORAGE,
    DEVICE_IDLE
}
```

#### REQUIRE_BATTERY_NOT_LOW / ALLOW_LOW_BATTERY

Whether battery must be above the OS-defined "low" threshold. `ALLOW_LOW_BATTERY` overrides
`REQUIRE_BATTERY_NOT_LOW` for that specific task when both are present.

**Example:**

```kotlin
Constraints(systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW))
```

**Platform Support:** ✅ Android, ✅ iOS — via `NSProcessInfo.processInfo().lowPowerModeEnabled`
on iOS, for both chain steps and standalone tasks. Independent of the separate
`KmpWorkManagerRuntime.minBatteryLevelPercent` global runtime knob.

**Use Cases:**
- Background sync
- Image processing
- Non-critical uploads

---

#### ALLOW_LOW_STORAGE

Whether the task may run even with low device storage.

**Example:**

```kotlin
Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE))
```

**Platform Support:** ✅ Android, ❌ iOS — genuinely no storage-pressure constraint API on iOS,
not merely unwired.

**Use Cases:**
- File downloads
- Cache operations
- Database writes
- Media processing

---

#### DEVICE_IDLE

Whether the task requires the device to be idle (Android Doze / App Standby).

**Example:**

```kotlin
Constraints(systemConstraints = setOf(SystemConstraint.DEVICE_IDLE))
```

**Platform Support:** ✅ Android, ❌ iOS — no Doze-equivalent OS state on iOS to require.

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

#### Task priority (not a `Constraints` field)

`expedited: Boolean` does **not exist** on `Constraints` — an earlier draft of this doc showed
it that way. Expediting is driven by `TaskRequest.priority` instead, which lives on
`TaskRequest` (chain steps only — standalone `enqueue()` has no priority parameter):

```kotlin
enum class TaskPriority { CRITICAL, HIGH, NORMAL, LOW }
```

**Example:**

```kotlin
TaskRequest(workerClassName = "SyncWorker", priority = TaskPriority.CRITICAL)
```

**Platform Support:** `CRITICAL`/`HIGH` map to Android's `setExpedited()` (subject to
delay/heavy/charging/unmetered checks — `NORMAL`/`LOW`, the default, are never expedited). iOS
has no expedited-queue primitive; priority there only affects dynamic-queue sort order, not
actual scheduling urgency.

**Use Cases:**
- User-initiated sync
- Urgent uploads
- Critical updates
- Time-sensitive operations

**Requirements (Android `setExpedited()`):**
- Android 12+
- Task must complete within its expedited quota window
- Limited quota (system falls back to standard work if quota exceeded)

---

### Retry Policy

#### backoffPolicy

```kotlin
backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL
```

How to retry failed tasks.

**Options:**
- `BackoffPolicy.EXPONENTIAL` - 30s, 60s, 120s, 240s, ...
- `BackoffPolicy.LINEAR` - 30s, 60s, 90s, 120s, ...

**Example:**

```kotlin
Constraints(
    backoffPolicy = BackoffPolicy.EXPONENTIAL,
    backoffDelayMs = 30_000 // Start with 30 seconds — the default
)
```

**Platform Support:** ✅ Android, ✅ iOS — iOS honors this for standalone-task retry timing
**only when explicitly set**: the 30s/EXPONENTIAL default preserves the pre-3.4.0 "retry on
the next opportunistic BGTask wake" behavior for callers who never touch these fields, rather
than silently changing every existing caller's retry timing.

---

#### backoffDelayMs

```kotlin
backoffDelayMs: Long = 30_000
```

Initial retry delay in milliseconds (default: 30 seconds).

**Example:**

```kotlin
Constraints(
    backoffPolicy = BackoffPolicy.LINEAR,
    backoffDelayMs = 5_000 // Start with 5 seconds
)
```

**Platform Support:** ✅ Android, ✅ iOS (see the explicit-set caveat above)

---

#### maxRetries

```kotlin
maxRetries: Int = -1
```

Hard ceiling on retry attempts. `maxRetries = N` allows at most **N + 1** total runs
(1 initial run + N retries); the scheduler stops rescheduling once the run-attempt count
reaches `N`. This caps both a `WorkerResult.Failure(shouldRetry = true)` and a
`WorkerResult.Retry` that carries no explicit `attemptCap` (a per-result `attemptCap` always
wins over `maxRetries`).

**Applies to one-time and chained tasks only.** Periodic tasks ignore it — a periodic task
runs indefinitely by design, so a total-run ceiling is meaningless.

The default `-1` means **use the platform default** (not a shared value):
- **Android:** uncapped — WorkManager's own quota/backoff governs the upper bound (WorkManager
  has no native max-retry API, so `maxRetries` is enforced inside the worker).
- **iOS:** falls back to the built-in defaults (5 attempts for single tasks; a budget of 3
  whole-chain retries).

**Example:**

```kotlin
// At most 4 total runs (1 initial + 3 retries), then permanent failure.
Constraints(
    backoffPolicy = BackoffPolicy.EXPONENTIAL,
    backoffDelayMs = 10_000,
    maxRetries = 3
)

// Never retry — the initial run is the only attempt.
Constraints(maxRetries = 0)
```

**Platform Support:** ✅ Android, ✅ iOS *(one-time & chained tasks; ignored for periodic)*

---

### Existing Policy

#### policy

`ExistingPolicy` is **not** a `Constraints` field — it's a separate parameter to
`enqueue()`/`enqueueChain()`/`TaskChain.withId()`, alongside `constraints`:

```kotlin
enum class ExistingPolicy { KEEP, REPLACE, UPDATE }
```

What to do when a task or chain with the same ID already exists.

**Options:**
- `ExistingPolicy.KEEP` — keep existing, ignore the new request
- `ExistingPolicy.REPLACE` — cancel existing, schedule new
- `ExistingPolicy.UPDATE` (v3.4.0+) — update a periodic task's constraints/input **without**
  resetting its interval timer. Degrades to `REPLACE` for one-time tasks and chains, which
  have no timer anchor to preserve.

**Example:**

```kotlin
// Replace existing task
scheduler.enqueue(id = "sync", trigger = trigger, workerClassName = "SyncWorker",
    policy = ExistingPolicy.REPLACE)

// Keep existing, ignore new request
scheduler.enqueue(id = "sync", trigger = trigger, workerClassName = "SyncWorker",
    policy = ExistingPolicy.KEEP)

// Update a periodic task's constraints without resetting its schedule
scheduler.enqueue(id = "sync", trigger = TaskTrigger.Periodic(900_000), workerClassName = "SyncWorker",
    policy = ExistingPolicy.UPDATE)
```

**Platform Support:** ✅ Android, ✅ iOS — full parity, all three values.

> **Not supported: `APPEND`/`APPEND_OR_REPLACE`.** An earlier draft of this doc showed a
> fictional `ExistingWorkPolicy` enum with these two values and an `existingWorkPolicy`
> `Constraints` field — neither exists; `Constraints(existingWorkPolicy = ...)` does not
> compile. Appending steps to an already-queued-or-running chain is tracked as a real,
> unimplemented gap — see `docs/ROADMAP.md`'s `ExistingPolicy.APPEND` entry for why it's a
> deliberately deferred milestone rather than a quick addition (`ChainExecutor` reads a
> chain's definition once per execution, not per step, so appending to a running chain today
> would silently be dropped for that invocation).

---

### iOS Quality of Service

#### qos

```kotlin
qos: Qos = Qos.Background
```

The type is `Qos`, not `QualityOfService`, and the values/default shown in an earlier draft
of this doc (`HIGH`/`DEFAULT`/`LOW`) don't exist. **Currently a no-op on both platforms** —
`NativeTaskScheduler` (iOS) only logs it ("iOS manages priority automatically"); nothing reads
it to actually influence scheduling today. Accepted for forward API compatibility; don't rely
on it to affect real task priority yet — use `TaskRequest.priority`/`isHeavyTask` instead,
which do have real effect.

**Values:** `Qos.UserInteractive`, `Qos.UserInitiated`, `Qos.Background` (default),
`Qos.Utility`.

**Platform Support:** — (no-op on both; see above)

---

## Platform Support Matrix

### Triggers

| Trigger | Android | iOS | Notes |
|---------|---------|-----|-------|
| `OneTime` | ✅ | ✅ | Full support |
| `Periodic` | ✅ | ✅ | 15-min minimum; `runImmediately` + drift correction |
| `Exact` | ✅ | ⚠️ | Android: AlarmManager. iOS: best-effort via UNNotification |
| `Windowed` | ✅ | ⚠️ | iOS: `latest` not enforced — OS runs opportunistically |
| `ContentUri` | ✅ | ❌ | Android only |
| `BatteryLow` | ❌ | ❌ | **Removed** — use `SystemConstraint.ALLOW_LOW_BATTERY` |
| `BatteryOkay` | ❌ | ❌ | **Removed** — use `SystemConstraint.REQUIRE_BATTERY_NOT_LOW` |
| `StorageLow` | ❌ | ❌ | **Removed** — use `SystemConstraint.ALLOW_LOW_STORAGE` |
| `DeviceIdle` | ❌ | ❌ | **Removed** — use `SystemConstraint.DEVICE_IDLE` |

### Constraints

This table (and the per-field sections earlier in this document) reflect the actual
`Constraints` fields in `Contracts.kt:87-128`. An earlier revision of this document showed a
pre-refactor shape (`networkType`, `requiresBatteryNotLow`, `requiresStorageNotLow`,
`requiresDeviceIdle`, `expedited` as a `Constraints` field, `existingWorkPolicy`) — corrected
2026-09-02; none of those fields exist. They were superseded by `SystemConstraint`,
`TaskRequest.priority`, and the separate `ExistingPolicy` parameter respectively.

| Constraint | Android | iOS | Notes |
|------------|---------|-----|-------|
| requiresNetwork | ✅ | ✅ | Full support |
| requiresUnmeteredNetwork | ✅ | ✅ | iOS: enforced for chain steps and standalone tasks alike |
| requiresCharging | ✅ | ⚠️ | iOS: honored unconditionally (OS-level flag) for tasks with a static Info.plist identifier + `isHeavyTask=true`, and for dynamic-queue tasks when **every** task currently pending in the queue requires charging. For a queue mixing charging and non-charging tasks, enforced only if the host app has opted in to `UIDevice.batteryMonitoringEnabled` — the library never toggles that flag itself. Hosts that don't opt in see the mixed-queue case silently unenforced — tracked gap, see `docs/ROADMAP.md` |
| `systemConstraints`: `REQUIRE_BATTERY_NOT_LOW` / `ALLOW_LOW_BATTERY` | ✅ | ✅ | iOS: via `NSProcessInfo.processInfo().lowPowerModeEnabled`, for both chain steps and standalone tasks |
| `systemConstraints`: `ALLOW_LOW_STORAGE` | ✅ | ❌ | iOS has no storage-pressure constraint API — structurally not supported |
| `systemConstraints`: `DEVICE_IDLE` | ✅ | ❌ | iOS has no Doze-equivalent — structurally not supported |
| allowWhileIdle | ✅ | ❌ | Android only — no Doze-equivalent on iOS to bypass |
| isHeavyTask | ✅ | ✅ | Full support |
| backoffPolicy / backoffDelayMs | ✅ | ✅ | iOS: wired into standalone-task retry timing when explicitly set (default behavior unchanged — retry on the next opportunistic wake) |
| maxRetries | ✅ | ✅ | Full support |
| qos | — | ⚠️ | Logged only on iOS ("iOS manages priority automatically"); currently a no-op on both platforms |
| exactAlarmIOSBehavior | N/A | ✅ | iOS-only field, ignored on Android |

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

### SystemConstraint (replaces removed Battery/Storage/Idle triggers)
- `ALLOW_LOW_BATTERY` — power-saving mode, reduce sync frequency
- `REQUIRE_BATTERY_NOT_LOW` — resume heavy tasks when battery recovers
- `ALLOW_LOW_STORAGE` — cache cleanup, delete old files
- `DEVICE_IDLE` — database maintenance, index updates, background optimization

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
    trigger = TaskTrigger.OneTime(),
    workerClassName = "MLWorker",
    constraints = Constraints(
        isHeavyTask = true,
        requiresCharging = true,
        systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW)
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
        requiresUnmeteredNetwork = true
    )
)
```

**Bad - Contradictory constraints:**
```kotlin
// Don't do this! DEVICE_IDLE and TaskPriority.CRITICAL pull in opposite directions —
// a task waiting for device idle has no business also demanding to be expedited.
TaskRequest(
    workerClassName = "Worker",
    priority = TaskPriority.CRITICAL,
    constraints = Constraints(systemConstraints = setOf(SystemConstraint.DEVICE_IDLE))
)
```

---

### 3. Respect Platform Limitations

**iOS BGAppRefreshTask - 25 seconds max:**
```kotlin
// Keep workers fast
class QuickSyncWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        withTimeout(20_000) {
            // Complete within 20 seconds
        }
        return WorkerResult.Success()
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

Need help? [Open an issue](https://github.com/brewkits/kmpworkmanager/issues) or ask in [Discussions](https://github.com/brewkits/kmpworkmanager/discussions).
