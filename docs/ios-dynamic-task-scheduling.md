# KMP WorkManager - iOS Dynamic Task Scheduling Limitation & Solutions

## 1. The Core Problem (Root Cause)

This issue stems from the fundamental platform disparity between how Android and iOS handle background tasks.

### iOS `BGTaskScheduler` Design
* Apple designed `BGTaskScheduler` around the concept of a **"Category of Work"** rather than discrete, individual task items.
* Every Task Identifier must be statically declared before compilation in the `Info.plist` file under the `BGTaskSchedulerPermittedIdentifiers` array.
* If an application (or library) attempts to submit a `submitTaskRequest` with a dynamically generated ID at runtime (e.g., `photo-upload-123`), the iOS operating system will **reject it immediately** because the ID is not listed in `Info.plist`.
* A common workaround is to declare a single static ID (e.g., `photo-upload`) and pass dynamic data/parameters. However, iOS treats these as the **same task**. Consequently, if you schedule 3 photos in a row using the same static ID, iOS will overwrite the previous tasks, leading to data loss for the first two tasks.

### Android `WorkManager` Comparison
* Android provides maximum flexibility. Developers can use dynamically generated IDs (like UUIDs) and enqueue thousands of tasks. WorkManager automatically handles the internal queue, batching, and communication with the OS's `JobScheduler` or `AlarmManager` via its own internal SQLite/Room database.

---

## 2. Current KMP WorkManager Implementation

Currently, the `NativeTaskScheduler` in the library's `iosMain` source set directly maps a developer's Task ID 1:1 to an iOS `BGTaskRequest`.
* When a developer calls `schedule(id = "photo-123")`, the library attempts to submit `photo-123` directly to iOS. This shifts the burden of static ID management and the risk of task rejection entirely onto the developer.
* The library currently offers `ExistingPolicy` options like `REPLACE` or `KEEP`, but these rely on the iOS API to cancel and recreate tasks. This exacerbates the data loss issue when developers use the static ID workaround, as the latest task will simply cancel and replace the previous ones.

---

## 3. Solutions & Workarounds

To thoroughly resolve this issue and abstract away the platform limitations, a **Queue / Dispatcher Pattern** must be implemented.

### Option 1: Handle at the Application Layer (Consumer App) - Current Workaround
* **Mechanism:** Instead of scheduling individual files (e.g., `photo-123`), the application saves the list of files to be uploaded into a local database (Room, SQLDelight, or File Storage).
* The app then calls `KMPWorkManager.schedule()` using a **single static ID** (e.g., `dispatcher_task`) that has been properly declared in `Info.plist`.
* When the Worker executes, the application's `doWork` logic queries the local database and uploads the pending files sequentially or concurrently.
* *Pros:* Works right now without library changes.
* *Cons:* Poor Developer Experience (DX). It forces developers to reinvent a queuing system for every project on iOS.

### Option 2: Handle at the Library Layer (KMP WorkManager) - 🔥 Recommended Future Architecture 🔥
For the library to be truly "Production-ready," it must hide this complexity and provide an API that works identically across both platforms.
* **Leverage Existing Storage:** The KMP WorkManager library already contains `IosFileStorage` and queuing mechanisms (seen in `ChainExecutor`).
* **Internal Queue:** When a developer calls `schedule(dynamic_id)` on iOS, the library **DOES NOT** immediately submit it to iOS. Instead, the library saves this task request into its own internal File System or Database.
* **The Master Dispatcher:** The library defines a default, static identifier (e.g., `dev.brewkits.kmpworkmanager.master_dispatcher`) and instructs developers to add this single key to their `Info.plist`.
* **Execution:** Whenever a new task is added to the internal queue, the library submits the `master_dispatcher` task to iOS (if not already pending). When iOS wakes up the app, the library automatically reads its internal queue, launches the appropriate Workers for all pending `dynamic_id`s, and removes them from the queue upon completion.

---

## 4. Implementation in v2.4.3: The Master Dispatcher

As of version **2.4.3**, KMP WorkManager has implemented **Option 2** (the Library-layer Queue/Dispatcher pattern).

### How it works now:
1.  **Transparency**: Developers can call `scheduler.enqueue(id = "any-dynamic-id", ...)` just like on Android.
2.  **Automatic Routing**: The library checks if the `id` is listed in `Info.plist`. If it is NOT, the library automatically routes the task through an internal `AppendOnlyQueue` and schedules a single static task: `kmp_master_dispatcher_task`.
3.  **The Single Plist Entry**: Developers only need to add one entry to their `Info.plist` to support unlimited dynamic task IDs:
    ```xml
    <key>BGTaskSchedulerPermittedIdentifiers</key>
    <array>
        <string>kmp_master_dispatcher_task</string>
    </array>
    ```
4.  **Batch Execution**: When iOS fires the master dispatcher, the library's `DynamicTaskDispatcher` drains the internal queue, executing all pending tasks within the available background budget.

This architecture delivers a seamless, unified Developer Experience (DX) across Android and iOS, perfectly aligning with the "Write once, run anywhere" philosophy of Kotlin Multiplatform.

---

## 5. Known Trade-off: Master Dispatcher Ignores Per-Task Constraints

The master dispatcher always submits `kmp_master_dispatcher_task` as a **`BGProcessingTaskRequest`**. As of the fix below, `requiresNetworkConnectivity` is derived from the pending queue; the request *type* itself is always `BGProcessingTaskRequest`, never `BGAppRefreshTaskRequest` — see "Why not `BGAppRefreshTaskRequest`?" below for why that's deliberate, not an oversight.

* **Why unconstrained by default:** a dynamic-task queue is typically mixed (some tasks need network, some don't; some are light, some are heavy). Submitting an unconstrained `BGProcessingTaskRequest` lets non-network tasks run opportunistically even while offline — each worker still checks its own `Constraints.requiresNetwork` at execution time and fails/retries if unmet. Constraining the dispatcher itself would block the *whole* batch on the strictest pending task.
* **What's fixed:** `NativeTaskScheduler.submitTaskRequest` and `DynamicTaskDispatcher.rescheduleMasterDispatcher` now read `IosFileStorage.getDynamicQueueConstraintSummary()` and set `requiresNetworkConnectivity = true` only when **every** pending dynamic task requires network — so a queue that's entirely network-dependent no longer wakes opportunistically while offline just to fail every task and re-queue them.
* **This only affects dynamic task IDs.** A task scheduled with a **static ID declared in `Info.plist`** goes through `createBackgroundTaskRequest()` directly and correctly becomes a `BGAppRefreshTaskRequest` when `Constraints.isHeavyTask = false`.

### Why not `BGAppRefreshTaskRequest` when the queue is all-light?

This was the original ask in [discussion #78](https://github.com/brewkits/kmpworkmanager/discussions/78) — use the cheaper, better-scheduled `BGAppRefreshTaskRequest` when every pending task is light. It turns out **not to be safe** with the current batch executor, so it's deliberately not implemented:

* `BGAppRefreshTask` has a hard ceiling of **~30 seconds with no extension API** (see [IOS_BGTASK_LIMITS.md § 1](./IOS_BGTASK_LIMITS.md)).
* `DynamicTaskDispatcher.executePendingTasks` dequeues and runs tasks in a loop; a single dequeued task may run up to `SingleTaskExecutor.DEFAULT_TIMEOUT_MS` (**25 seconds**) before its own per-task timeout fires.
* 25s of worker execution plus BGTask wake latency and completion/cleanup overhead leaves no safe margin under a 30s hard ceiling — not even for a *single* queued task, let alone a batch. Unlike a static light task (one task, one `BGAppRefreshTask` window, by construction), the master dispatcher is a **batch** processor sized for `BGProcessingTask`'s multi-minute budget; "every pending task is light" does not mean "the batch fits in 30 seconds."
* Making this safe would require the dispatcher to know it's running under an App Refresh budget and cap itself to (at most) one task with a much smaller per-task timeout — plus verification on a physical device, since the Simulator doesn't model real `BGTaskScheduler` timing (see the iOS Simulator Fallback warning in `NativeTaskScheduler.kt`).

Tracked as follow-up scope in [brewkits/kmpworkmanager#79](https://github.com/brewkits/kmpworkmanager/issues/79) if it's worth pursuing later.

**Workaround today:** for lightweight, network-bound, latency-sensitive work, declare a dedicated static ID in `BGTaskSchedulerPermittedIdentifiers` instead of relying on the dynamic-task/master-dispatcher path — that's still the only way to get an actual `BGAppRefreshTaskRequest` for iOS background work in this library.

