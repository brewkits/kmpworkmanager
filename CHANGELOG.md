# Changelog

All notable changes to KMP WorkManager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.4.0] - 2026-09-03

A full library-wide QA pass (SSRF hardening, a broken exact-alarm execution path, and
20+ correctness/validation fixes across Android, iOS, common, and the KSP processor) on
top of the WorkManager parity pass below.

### Security

- **Fixed an SSRF bypass in `SecurityValidator.isPrivateIPv6`**: an IPv4-mapped
  (`::ffff:169.254.169.254`, `::ffff:a9fe:a9fe`) or IPv4-compatible (deprecated form)
  IPv6 literal encoding a private/loopback/link-local/cloud-metadata address (e.g. the
  `169.254.169.254` metadata endpoint) was not decoded before the private-range check,
  so it passed validation as "not private" even though the OS resolves it straight to
  the embedded IPv4 address. Any HTTP worker (`kmpworker-http`), `SecureRedirectFollowing`,
  or `TokenRefresh` call that validates a caller- or redirect-supplied host was affected.
  Fixed by extracting the embedded IPv4 address first and validating *that* whenever one
  is present.

### Fixed

- **iOS: fixed a TOCTOU race in `NativeTaskScheduler.enqueue()`** where N concurrent
  `enqueue()` calls for the same brand-new task id could all observe no existing metadata
  before any of them wrote, race into the write path concurrently, and corrupt the on-disk
  metadata. Fixed with a per-scheduler-instance `Mutex` serializing the check-then-act
  scheduling decision, mirroring `IosFileStorage.enqueueMutex`'s existing pattern.
  Fixes [#98](https://github.com/brewkits/kmpworkmanager/issues/98).
- **Android: `TaskTrigger.Exact` alarms silently never ran the worker.** The default
  `AlarmReceiver` registered by `KmpWorkManager.initialize()` only logged the fired alarm
  and finished the `PendingResult` — it never resolved or invoked the scheduled worker.
  Every exact-alarm task fired on time and did nothing. Fixed: the default receiver now
  resolves the worker via the registered `AndroidWorkerFactory` and runs it, emitting the
  same `TaskCompletionEvent`/`TelemetryHook` events as other execution paths.
- **Android: exact-alarm metadata was removed from `AlarmStore` before the work ran**,
  not after. A process kill between the alarm firing and the worker completing
  permanently lost the task with no trace and no way to recover it on reboot. Metadata
  removal now happens in `BaseAlarmReceiver`'s `finally` block, gated on `doAlarmWork`
  having reached a definitive (success, failure, or thrown-exception) outcome — a
  timed-out/hung run intentionally leaves the metadata in place for diagnostics. See
  **Changed** below for the impact on custom `AlarmReceiver` subclasses.
- **Android: `AndroidWorkerDiagnostics.getSchedulerStatus()` / `getTaskStatus()`** queried
  WorkManager with the wrong tag prefix (`"worker:"` instead of `"worker-"`) and used the
  blocking `Future.get()` on the calling coroutine instead of `.await()`. Status queries
  silently returned empty/stale results and could block a dispatcher thread.
- **Android: chain-step overflow files leaked when a chain was cancelled before a
  large-input step ever ran.** Chain-step overflow files are now registered under a
  stable `chainId#stepIndex#taskIndex` key (`OverflowFileRegistry.chainStepKey`) instead
  of a random UUID, so `NativeTaskScheduler.cancel(chainId)` can find and delete every
  step's overflow file for that chain via the new `consumeAndDeleteForChain`.
- **Android: `KmpHeavyWorker` treated a transient OS denial as permanent failure.** A
  `SecurityException` or `IllegalStateException` from `ForegroundServiceStartNotAllowedException`-adjacent
  OS policy (e.g. background-start restrictions) returned `Result.failure()`, discarding
  the task instead of `Result.retry()`, even though the condition (app in background,
  battery saver, OEM restriction) is often transient.
- **Android + iOS: `FileCompressionWorker` never validated `inputPath`**, only
  `outputPath`. Since `deleteOriginal` recursively deletes `inputPath`, and a chain step's
  merged input can let an earlier, less-trusted step's output overwrite same-named
  fields, an unvalidated `inputPath` was an open path-traversal/unsafe-delete vector.
- **Android: `shouldExpedite()` did not exclude `DEVICE_IDLE` / `REQUIRE_BATTERY_NOT_LOW`
  constraints**, so a `CRITICAL`-priority task requiring either could be requested as
  expedited work that WorkManager would then reject or silently downgrade.
- **Android: an oversized exact-alarm input JSON (>200 KB) could hit Android Binder's
  ~1 MB transaction limit** and crash the system server. `scheduleExactAlarm` now rejects
  (`REJECTED_OS_POLICY`) inputs over `MAX_ALARM_PERSISTED_INPUT_JSON_BYTES` before ever
  calling `AlarmStore.save()`.
- **Android: `schedulePeriodicWork()` did not catch `IllegalArgumentException`** from an
  invalid `PeriodicWorkRequest.Builder` configuration, crashing the caller instead of
  returning `REJECTED_OS_POLICY`.
- **iOS: `NativeTaskScheduler.enqueue()` could hang indefinitely** holding
  `schedulingMutex` if the underlying `BGTaskScheduler`/`isTaskPending()` call never
  completed, blocking every subsequent `enqueue()` call. Now bounded by a 10s
  `withTimeout` in production (skipped in test mode, matching the existing
  `migrationComplete.await()` pattern, since `runTest {}`'s virtual clock races real
  native async callbacks).
- **iOS: `IosFileStorage` overwrote existing task metadata, chain definitions, chain
  progress, and the transaction log with `NSString.writeToFile(atomically:)`**, an older
  API with known reliability gaps under some `NSFileProtection` classes and disk
  conditions — the same class of bug already fixed for `AppendOnlyQueue`'s compaction.
  Overwrites now go through a temp-file + `NSFileManager.replaceItemAtURL` swap.
  First-write-ever (no existing file) still uses the direct write, since there is nothing
  to atomically replace.
- **iOS: `IosBackgroundUrlSessionManager`'s completed-download handler used a
  delete-then-move pattern** (a window where a crash between the two steps loses the
  downloaded file) and discarded `NSError` details on failure. Now uses
  `replaceItemAtURL`/`moveItemAtURL` conditionally and logs the real error.
- **iOS: `ChainExecutor.activeChains` used wall-clock (`NSDate`) timestamps** to track
  chain age, which is vulnerable to NTP adjustments and clock changes mid-execution. Now
  uses `TimeSource.Monotonic`.
- **iOS: `TaskProgressBus.clearThrottle` was called with the wrong key** in
  `ChainExecutor.executeTask()`'s `finally` block (`workerClassName` instead of the
  `chainId ?: workerClassName` actually used when the throttle was set), so a chain
  task's throttle state never cleared. `SingleTaskExecutor.recordCompletion()` was
  missing the call entirely.
- **Common: `WorkerProgress.forStep()` divided by `totalSteps` without validating it**,
  throwing a raw `ArithmeticException` for `totalSteps <= 0` instead of a clear
  `IllegalArgumentException`.
- **Common: `TaskProgressBus`'s `MutableSharedFlow` had no `onBufferOverflow` policy**,
  defaulting to `SUSPEND` — a slow/absent collector could block progress emission
  indefinitely. Now `DROP_OLDEST`.
- **Common: `TaskChain.beginWith()` silently no-op'd on an empty initial task list**
  instead of failing, producing a chain with no first step. Now throws
  `IllegalArgumentException` immediately.
- **`kmpworker-ksp`: `WorkerProcessor`'s generated `requiredBgTaskIds` set used raw
  string interpolation instead of KotlinPoet's `%S` placeholder**, risking malformed
  generated code for a background-task id containing a quote or backslash.

### Changed

- **BREAKING (migration required for direct `AlarmReceiver` subclasses):**
  `AlarmReceiver.onReceive()` no longer removes `AlarmStore` metadata before dispatching
  to `handleAlarm()` — see the `AlarmStore` fix above. Apps extending `BaseAlarmReceiver`
  need no changes (it now handles removal itself, at the correct time). Apps extending
  `AlarmReceiver` **directly** (bypassing `BaseAlarmReceiver`) must now call
  `AlarmStore.remove(context, taskId)` themselves once their work is done, or entries
  will linger until the next `AlarmBootReceiver`/`cleanupStaleAlarms` sweep prunes them
  (not a permanent leak, but a behavior change from immediate removal).

### Docs

- `AlarmBootReceiver`'s manifest KDoc example was missing the
  `android.intent.action.MY_PACKAGE_REPLACED` action — a host app that copied only what
  was shown never registered for it, so the "app updated" alarm-restore path silently
  never ran.
- Clarified `BuiltinWorkerRegistry`'s KDoc scope (only `FileCompressionWorker`; HTTP
  workers live in `kmpworker-http`/`HttpWorkerRegistry`) and presented
  `CompositeWorkerFactory`'s null-return contract as preferred over exception-throwing
  (no runtime behavior change).

### Removed

- `TaskEventBus.resetForTest()` — a no-op with zero callers; `TaskEventManager.resetForTest()`
  is the functional one actually used by tests.

---

WorkManager parity pass (task tags, deadlines, InputMerger, `ExistingPolicy.UPDATE`),
a second Android/iOS constraint-parity pass (six more gaps closed), extensions to two
previously-shipped "Ultra" iOS features, two new `WorkQuery`-style batch APIs
(`observeTaskState`/`queryTasks`), and a documentation accuracy sweep.

### Added

- **`TaskRequest.tags` + `scheduler.cancelByTag(tag)` / `cancelByWorkerClass(name)`** —
  group cancellation. Tags are business-context labels independent of worker class, so a
  single call can cancel a mixed set of workers (`cancelByTag("user-123")`). Both APIs match
  standalone tasks *and* chain steps on both platforms; `enqueue()` gained a `tags` parameter
  so standalone tasks are reachable too. Tags are validated at construction (non-blank, no
  commas, ≤100 chars) because an unstorable tag would make cancellation silently no-op.
  **Not supported for `TaskTrigger.Exact` on Android** (AlarmManager is not tag-indexed) —
  a warning is logged rather than failing silently.
- **`TaskRequest.deadlineMs` + `enqueue(deadlineMs = …)`** — a task that has not started by
  its deadline is skipped instead of executed, so a delayed run cannot write stale data.
  Enforced at execution time on both platforms (Android `BaseKmpWorker`, iOS
  `DynamicTaskDispatcher` and `ChainExecutor`). A miss is recorded as
  `ExecutionStatus.SKIPPED` and never retried — retrying cannot un-miss a deadline. On iOS
  this finally gives `TaskTrigger.Windowed.latest` real teeth: it now defaults to the
  deadline, where previously BGTaskScheduler could only log that it was unenforceable.
  Deliberately ignored for periodic tasks (a deadline on a recurring task is a delayed
  cancel, not a deadline) — a warning is logged.
- **`TaskRequest.mergeOutputFromPreviousStep`** — the InputMerger. A chain step can opt in to
  receiving the previous step's `WorkerResult.Success.data` merged into its own `inputJson`,
  removing the need for an external store to pass data along a
  `download → validate → upload` pipeline. Overwriting-merge semantics (previous step wins on
  key collision), matching WorkManager's default `OverwritingInputMerger`, with one shared
  implementation (`ChainInputMerger`) used by both platforms so the behaviour cannot drift.
- **`ExistingPolicy.UPDATE`** — updates a periodic task's constraints/input **without**
  resetting its interval timer. Maps to `ExistingPeriodicWorkPolicy.UPDATE` on Android; on
  iOS the existing `anchoredStartMs` is preserved so drift correction keeps the original
  cadence. Degrades to `REPLACE` for one-time tasks and chains, which have no timer anchor.
- **iOS: `requiresUnmeteredNetwork` and `requiresCharging` now enforced for standalone tasks**
  — previously only chain steps (`ChainExecutor`) checked these; a plain `enqueue()` task
  ignored them entirely. Now checked at dispatch time in `DynamicTaskDispatcher` and in the
  static-Info.plist-identifier path (`IosBackgroundTaskHandler`), deferring with a retry
  instead of running on cellular / unplugged power. `requiresUnmeteredNetwork` is always
  enforced; `requiresCharging` — like the pre-existing `ChainExecutor` battery guard it
  mirrors — only fires once the host app has opted in to `UIDevice.batteryMonitoringEnabled`,
  since toggling that flag ourselves would race the host's own UI thread. Hosts that never
  touch that flag see no enforcement here (see `docs/ROADMAP.md` for the tracked follow-up).
- **iOS: `SystemConstraint.REQUIRE_BATTERY_NOT_LOW` / `ALLOW_LOW_BATTERY` implemented** — via
  `NSProcessInfo.isLowPowerModeEnabled`, for both chain steps and standalone tasks. Independent
  of the existing `KmpWorkManagerRuntime.minBatteryLevelPercent` global knob.
- **iOS: `Constraints.backoffPolicy` / `backoffDelayMs` now affect standalone-task retry
  timing** — only when explicitly set (the default keeps the pre-existing "retry on the next
  opportunistic wake" behavior, so no existing caller's timing silently changes). LINEAR scales
  the base delay by attempt number, EXPONENTIAL doubles it, both capped at 1 hour — mirroring
  WorkManager's own backoff math.
- **iOS: `DynamicQueueConstraintSummary.allRequireCharging`** — when every task pending in the
  dynamic queue requires charging, the shared Master Dispatcher's `BGProcessingTaskRequest` now
  sets `requiresExternalPower = true` at the OS level (mirroring the existing
  `allRequireNetwork`/`requiresNetworkConnectivity` pattern), in both places that submit that
  request (`NativeTaskScheduler`'s enqueue-time path and `DynamicTaskDispatcher`'s
  re-schedule-after-batch path). This is strictly better than relying solely on
  `StandaloneConstraintGuard`'s runtime check, which only fires if the host app has separately
  opted in to `UIDevice.batteryMonitoringEnabled` — the OS-level flag needs no such opt-in, and
  means the process isn't woken at all until the device is actually plugged in.

### Changed

- `BackgroundTaskScheduler.enqueue()` gained `tags` and `deadlineMs` parameters (both
  defaulted). **Source-compatible for callers**; classes that *override* `enqueue` must add
  the two parameters. `cancelByTag`/`cancelByWorkerClass` ship with no-op default
  implementations specifically so existing custom schedulers and test doubles keep compiling.
- `FakeBackgroundTaskScheduler` (test utility) now records `tags`, `deadlineMs`,
  `cancelledTags` and `cancelledWorkerClasses` so tests can assert on the new APIs.

- **`kmpworkmanager-http`: HMAC-SHA256 request signing + token refresh on 401.**
  `HttpRequestConfig` gains optional `hmacSigning: HmacSigningConfig` (canonical string
  `METHOD\nURL\nBODY\nTIMESTAMP`, via Okio's `ByteString.hmacSha256()`) and
  `tokenRefresh: TokenRefreshConfig` (dot-notation JSON path extraction from the refresh
  response, one-shot retry on 401, SSRF-validated at both the caller and inside
  `TokenRefresh.refreshToken()` itself so any future caller of `TokenRefreshConfig` stays
  protected). Fixes [#81](https://github.com/brewkits/kmpworkmanager/issues/81).
- **iOS: the master dispatcher now derives `requiresNetworkConnectivity` from the actual
  pending-task queue** instead of a static value, via a new
  `IosFileStorage.getDynamicQueueConstraintSummary()` O(N) scan. Only relaxes the
  constraint when *every* pending task is network-independent; the dispatcher itself stays
  `BGProcessingTaskRequest` always (an earlier draft that switched to
  `BGAppRefreshTaskRequest` for all-light queues was reverted before merge — that request
  type's hard ~30s ceiling has no safety margin against a full-length task). See
  [discussion #78](https://github.com/brewkits/kmpworkmanager/discussions/78) /
  [#79](https://github.com/brewkits/kmpworkmanager/issues/79).
- **Android: `setExpedited()` now gated on `TaskPriority`** — `CRITICAL`/`HIGH` chain steps are
  expedited as documented in `TaskPriority.kt`; `NORMAL`/`LOW` steps (the default) and every
  standalone `enqueue()` task (which has no priority parameter) are not. Previously every
  eligible task (delay=0, not heavy, no charging/unmetered requirement) was expedited
  unconditionally, regardless of priority — this narrows the fast-track lane to match the
  documented contract. If your app relied on the old blanket-expedited behavior for NORMAL/LOW
  work, it may now see slightly later scheduling under WorkManager quota pressure.

Also extends two features that shipped experimentally in earlier releases, and adds one new
storage seam:

- **`IosBackgroundUploadWorker`** — the upload counterpart to `IosBackgroundDownloadWorker`
  (v2.5.0), which was download-only. Same background-`NSURLSession` lifecycle, same
  `AppDelegate` hook, same async-completion-via-`TaskEventBus` contract. Source must be an
  on-disk file (`uploadTaskWithRequest(_:fromFile:)`) — background sessions don't support
  in-memory request bodies at all.
- **`sharedContainerIdentifier` on `IosBackgroundDownloadConfig`/`IosBackgroundUploadConfig`**
  — configures the background `NSURLSessionConfiguration` so a Share/Widget Extension in the
  same App Group can observe or initiate transfers on that session. Shares only the transport;
  see the note below on what it does not share.
- **`KmpWorkManager.initialize(appGroupIdentifier = ...)`** — roots task/chain/progress storage
  in a shared App Group container instead of the app's private Application Support directory,
  so an extension can construct its own read-only `IosFileStorage(baseDirectory = ...)` against
  the same container. Fails fast with `IllegalArgumentException` if the App Group entitlement
  is missing, rather than silently falling back to private storage. **Does not** support running
  the scheduler in more than one process against the same container — `ChainJobRegistry`, the
  progress-flush debounce buffer, and the dynamic queue's size counters are in-memory and
  process-local; exactly one process may run the scheduler, others must be read-only. See
  [`docs/IOS_APP_GROUP_STORAGE.md`](docs/IOS_APP_GROUP_STORAGE.md) for the full contract,
  including what's explicitly out of scope (live cross-process notifications).
- Also note: `BackgroundDownloadStateStore` (the completion-tracking store for background
  URLSession transfers) is **not** affected by `appGroupIdentifier` — it always writes to the
  main app's private Application Support directory, so `sharedContainerIdentifier` and
  `appGroupIdentifier` do not currently combine to give an extension visibility into pending
  transfer state.

Closes out Flutter parity Group 2 (`kmpworker-http`), the last unbuilt item from that group:

- **`maxBytesPerSecond` bandwidth throttling** on `HttpDownloadConfig`, `HttpUploadConfig`,
  `ParallelHttpDownloadConfig`, and `ParallelHttpUploadConfig` — a token-bucket
  (`BandwidthThrottle`) caps the average transfer rate while still allowing brief bursts,
  rather than chopping the stream into fixed time slices. The two parallel configs cap the
  **aggregate** rate across every concurrent chunk/file via one shared throttle instance, not
  a separate budget per chunk. `null` (default) is unlimited — fully backward compatible.
  Independent of `Constraints.requiresUnmeteredNetwork` (a Wi-Fi-only gate, not a rate limit)
  and of `maxBytes` (a size ceiling, not a rate one).

- **`BackgroundTaskScheduler.observeTaskState(id): Flow<TaskState>`** — a live-ish state
  stream for a task or chain, replacing poll-`getExecutionHistory()` for "is this thing
  running yet" questions. `TaskState` is `Enqueued`/`Running`/`Succeeded`/`Failed`/`Cancelled`/
  `Unknown`. Android wraps `WorkManager.getWorkInfosForUniqueWorkFlow` directly — precise and
  live. iOS has no OS-level "is this executing right now" API, so it's inferred from this
  library's own persisted task/chain metadata, execution history, and (for chains)
  `ChainJobRegistry`'s live active-job map, polled every 2s; `Running` there is a best-effort
  inference, not a guarantee — see the KDoc for the exact precedence rules and a known
  limitation (a cancelled task's state becomes `Unknown`, not `Cancelled`, once `cancel()`
  deletes its metadata). Has a no-op default (`Unknown`) for source compatibility with
  existing third-party `BackgroundTaskScheduler` implementations, same as `cancelByTag`.

- **`BackgroundTaskScheduler.queryTasks(tags, workerClassNames, states): List<QueriedTask>`**
  — a `WorkQuery`-style batch read, same AND-across-axes/OR-within-axis semantics as
  `androidx.work.WorkQuery`. Android does one `getWorkInfosByTag(TAG_KMP_TASK)` call and
  filters in-memory against each `WorkInfo`'s tags — including a new `worker-<name>` tag
  reuse that gives `workerClassNames` filtering even though real `WorkQuery` has no such axis
  natively, and a new `chain-<chainId>` tag added specifically so a chain's steps (each
  carrying only a random per-step id tag before this) can be grouped back under the chain's
  real id. iOS enumerates every known id (chain definitions, the dynamic queue, standalone
  task metadata, and execution history for anything already terminal) and reuses
  `observeTaskState`'s own `computeIosTaskState` for each one's state. **Known limitation**:
  once a task/chain reaches a terminal state, its tags/worker class name are no longer
  persisted anywhere (only `ExecutionRecord` survives, which carries neither) — a non-empty
  `tags`/`workerClassNames` filter therefore cannot match a terminal id, only a `states`-only
  filter can find it. Has a no-op default (empty list) for the same source-compatibility
  reason as `observeTaskState`.

### Fixed

- **Android: `KmpHeavyWorker` now diagnoses `foregroundServiceType` manifest mismatches**
  that previously failed silently. On API 29-33 (below the API 34 `ForegroundServiceTypeException`
  cliff) a worker/manifest type mismatch didn't throw — the FGS just silently started with
  whatever type the manifest declared. `doWork()` now reads the merged manifest's actual
  declaration via `PackageManager.getServiceInfo()` and logs a proactive warning on
  mismatch (bitwise-AND against the manifest's bitmask, not equality — `foregroundServiceType`
  is an OR-bitmask per `docs/ANDROID_FGS_GUIDE.md`'s own `dataSync|camera` example), plus
  enriches the existing `setForeground()` exception log on API 34+. Diagnostics-only: never
  throws on a failed manifest read, and skipped entirely below API 29. Supersedes the
  Gradle-plugin approach originally proposed in
  [#82](https://github.com/brewkits/kmpworkmanager/issues/82) — investigation found iOS
  already fails loudly via the KSP-generated `BgTaskIdProvider`, and a Gradle plugin can't
  reliably validate the Android side (`@Worker` is `SOURCE`-retention;
  `foregroundServiceType` is a computed `open val`, not a plugin-resolvable literal).
- **Android: chain-member tasks never reported `chainId`/`stepIndex` in telemetry, and
  `ExecutionRecord.chainId` held the wrong ID.** Found by manually running the demo app's
  Sequential chain and reading its logs: every `TaskStartedEvent` logged `chain=null
  step=null` even for genuine chain-member tasks. Root cause: WorkManager's native
  `then()`-chaining carries no chain metadata of its own — `enqueueChain()` never stamped
  it into a step's `inputData`, so `BaseKmpWorker` had nothing to read (iOS's
  `ChainExecutor` has always done this correctly). `ExecutionRecord.chainId` was also
  populated with `ListenableWorker.id` (WorkManager's per-request random UUID) instead of
  the ID passed to `TaskChain.withId()`. `enqueueChain()` now stamps namespaced
  `kmp_chain_id`/`kmp_step_index`/`kmp_total_steps` keys per step; `BaseKmpWorker` reads
  them back for `TaskStartedEvent`/`TaskCompletedEvent`/`TaskFailedEvent` and
  `ExecutionRecord`. Standalone tasks are unaffected. **Behavior change:**
  `ExecutionRecord.failedStep` on Android was previously always `0` on failure; it now
  reports the real 0-based step index that failed (still `0` for standalone tasks). See
  [#87](https://github.com/brewkits/kmpworkmanager/pull/87).
- **iOS: an unmet standalone-task constraint no longer burns retry budget.** The first cut of
  the `requiresUnmeteredNetwork`/`requiresCharging`/`REQUIRE_BATTERY_NOT_LOW` guard above routed
  a deferred task through the same path as a real worker failure, incrementing the attempt
  counter — so a Wi-Fi-only task stuck on cellular for `DEFAULT_ATTEMPT_CAP` (5) opportunistic
  wakes (e.g. a commute) would be silently deleted, having never run once. A constraint
  deferral now re-queues/re-submits without touching the attempt counter, matching the existing
  backoff-guard pattern and Android WorkManager's own contract (unmet constraints leave work
  enqueued, they don't consume retries).
- **iOS: the dynamic-task master dispatcher now honors the backoff floor when re-requesting
  itself.** Previously `rescheduleMasterDispatcher()` always requested `earliestBeginDate =
  now`, so a task in a multi-minute/hour backoff window caused the dispatcher to wake
  immediately, find nothing runnable, and re-request itself again — repeating for the whole
  backoff duration and burning BGTask quota. It now requests the earliest of all pending tasks'
  backoff floors when every pending task is still backed off (falling back to "now" the moment
  any task is actually ready).
- **iOS: a dedicated-Info.plist-identifier task's `Constraints.maxRetries` no longer resets to
  the platform default after its first retry.** `reconstructConstraintsFromMetadata` — used to
  rebuild `Constraints` for `scheduler.enqueue()` re-submission — omitted `maxRetries`, so the
  fresh metadata written on re-submit silently dropped the caller's custom cap in favor of
  `DEFAULT_ATTEMPT_CAP` (5) from the second attempt onward.
- **iOS: a dedicated-Info.plist-identifier one-time task's `windowLatest`/tags/deadline no
  longer vanish on the first retry or constraint deferral.** Re-submitting via
  `scheduler.enqueue(trigger = TaskTrigger.OneTime(...))` rebuilds metadata from scratch
  (`scheduleOneTimeTask`'s `buildMap`, which has no parameter for any of these three fields);
  `handleOneTimeTaskResult` now explicitly carries them forward from the pre-re-submit
  metadata. Previously a Windowed task lost its deadline enforcement permanently after its very
  first retry.
- **Docs: removed a false claim that App Group storage (`appGroupIdentifier`) makes execution
  history readable from an extension.** `IosEventStore`/`IosExecutionHistoryStore` are not
  wired to the shared `IosFileStorage` at all — only task/chain/progress metadata
  (`loadTaskMetadata`) is. `docs/IOS_APP_GROUP_STORAGE.md` and the `initialize()` KDoc now say
  so explicitly.
- **iOS: a one-time task's `requiresNetwork`/`requiresCharging`/`isHeavyTask` are now actually
  persisted to disk.** `NativeTaskScheduler.scheduleOneTimeTask` never wrote these three fields
  into a one-time task's metadata (only `schedulePeriodicTask` did) — a pre-existing bug, not
  introduced this pass, just surfaced while wiring the charging aggregate below. The most
  severe consequence: a dedicated-Info.plist-identifier **heavy** task's first retry silently
  read `isHeavyTask` back as `false` (the key was simply absent) and downgraded every
  subsequent re-submission from `BGProcessingTaskRequest` (minutes of budget) to
  `BGAppRefreshTaskRequest` (~30s hard ceiling) — regardless of what the caller originally
  requested.
- **`docs/ios-best-practices.md` had the same pre-refactor-`Constraints`-shape staleness as
  `constraints-triggers.md`** — its "Limited Constraints"/"Unsupported Constraints" sections
  and Pitfall #3 showed `requiresBatteryNotLow`/`requiresStorageNotLow` as direct `Constraints`
  fields (neither exists) and claimed `requiresCharging`/`REQUIRE_BATTERY_NOT_LOW` are
  unconditionally ignored on iOS (both are enforced now, the former with the
  `batteryMonitoringEnabled` opt-in caveat noted above). Also fixed a wrong `BGProcessingTask`
  time-limit ("~60 seconds" vs. the authoritative "several minutes" in
  `docs/IOS_BGTASK_LIMITS.md`).
- **Multiple docs actively described a pre-refactor `Constraints`/`ExistingPolicy` shape as
  current**, discovered while evaluating whether `ExistingPolicy.APPEND` (see below) was
  worth implementing: `docs/api-reference.md` and `docs/constraints-triggers.md` both showed
  a fictional `ExistingWorkPolicy` enum with `APPEND`/`APPEND_OR_REPLACE` and a nonexistent
  `Constraints.existingWorkPolicy` field — `Constraints(existingWorkPolicy = ...)` does not
  compile, and the real `ExistingPolicy` enum (`KEEP`/`REPLACE`/`UPDATE`) was missing
  entirely from both. The same pre-refactor shape (`networkType`/`NetworkType`,
  `requiresBatteryNotLow`, `requiresStorageNotLow`, `requiresDeviceIdle`, `expedited` as a
  `Constraints` field, `QualityOfService`) was scattered across `docs/api-reference.md`'s
  full `Constraints` reference section, `docs/BUILTIN_WORKERS_GUIDE.md`,
  `docs/platform-setup.md`, `docs/task-chains.md` (9 occurrences), `docs/ios-migration.md`,
  and `docs/quickstart.md` — all corrected to the real `SystemConstraint` enum,
  `TaskRequest.priority`, and `Qos` types. An earlier pass in this same release cycle had
  already partially fixed `docs/constraints-triggers.md` (its Exact-trigger section and
  summary Platform Support Matrix) and incorrectly marked the whole file's Constraints
  section done — the per-field prose sections above that table were still fully stale.
- **`ExistingPolicy.APPEND` evaluated and deliberately deferred, not implemented.** Real-world
  need is narrow (`KEEP`/`REPLACE`/`UPDATE` already cover the vast majority of use cases,
  including everything in this repo's own demo app) and `ChainExecutor` has proven fragile
  under scrutiny this release alone (two subtle bugs found from touching merely-adjacent
  code, both above) — implementing `APPEND` requires restructuring its core execution model
  (re-read-per-step instead of read-once) plus getting a finishing-chain race and
  progress-index renumbering right, exactly the shape of change likely to introduce a third
  subtle bug if rushed. See `docs/ROADMAP.md` for the full reasoning; tracked as its own
  future milestone.

### Security

- **`kmpworkmanager-http`: `TokenRefresh` now reads the refresh-endpoint response through a
  bounded channel instead of `bodyAsText()`**, so a misbehaving or compromised refresh
  endpoint can no longer buffer unbounded RAM before JSON parsing.
- **`HttpSyncWorker` now sanitizes the URL before persisting it into execution history**,
  matching `HttpRequestWorker` — the raw URL (including query string) was previously leaking
  into cleartext execution history.
- **`ParallelHttpDownloadWorker` now enforces the same response-size cap `HttpDownloadWorker`
  already has**, on both the chunked and sequential-fallback paths — neither previously had
  any ceiling on download size.

### Added

- **Public API stability gate**: `binary-compatibility-validator` on `kmpworker` and
  `kmpworker-http`, with a committed `.api` baseline. `apiCheck` runs as part of `check`
  (already covered by CI's `common-tests` job) and catches unintentional public API drift
  before merge.
- **Kover line-coverage floor** on both modules, set from measured coverage
  (`kmpworker` 65.0% → 60% floor, `kmpworker-http` 75.8% → 70% floor). `koverVerify` runs as
  part of `check`.
- **detekt static analysis** on all 4 publishable modules (`kmpworker`, `kmpworker-http`,
  `kmpworker-ksp`, `kmpworker-annotations`), with a per-module baseline for pre-existing
  findings and a shared `config/detekt/detekt.yml`. New CI job `static-analysis` runs it
  explicitly since `kmpworker-ksp`/`kmpworker-annotations` aren't otherwise covered by the
  `check` lifecycle in CI.
- **Android enqueue-latency benchmark** (`NativeTaskSchedulerBenchmarkTest`) as the
  Android-side counterpart to iOS's `QueuePerformanceBenchmark` — measures
  `NativeTaskScheduler.enqueue()` latency (not a 1:1 port: Android has no file-backed queue to
  benchmark, WorkManager owns that via its own Room database).
- **Governance docs**: root `SECURITY.md` stub (GitHub resolves the root file first for the
  repo's Security tab), `NOTICE` listing verified Apache-2.0 runtime dependencies of the
  published artifacts, and a public-API-stability section in `CONTRIBUTING.md` describing the
  new `apiDump`/`apiCheck` gate.

### Fixed

- **Vulnerability reporting now goes through GitHub Security Advisories everywhere**,
  replacing scattered/inconsistent contact emails that previously differed across
  `docs/SECURITY.md`, `CONTRIBUTING.md`, and three other docs.
- `docs/SECURITY.md` supported-versions table and version stamp updated to 3.4.0, recording
  the v3.4.0 security review (3 fixes above + 1 unverified finding).
- `docs/COVERAGE.md` / `docs/PERFORMANCE.md`: added disclaimers — these documents' figures
  predated the current version and referenced CI commands/tools that don't exist in this
  repo; they now point to the benchmarks/gates that do.

## [3.3.1] - 2026-08-09

A senior mobile QA/QC review pass across the whole library surfaced five further findings
on top of issue #71 — all fixed here. None are regressions from 3.3.0 shipping; all were
either narrow-trigger latent gaps or, in one case, code written for this very release that
hadn't reached Maven Central yet.

### Fixed

- **iOS: single (non-chained) tasks never persisted their completion event or execution
  history.** `SingleTaskExecutor` emitted fire-and-forget to `TaskEventBus` only — nothing
  was written to `EventStore` or `ExecutionHistoryStore`, so `getExecutionHistory()` only
  ever saw chain executions on iOS, and the emission itself could be lost entirely if
  `cleanup()` cancelled the executor's scope before the fire-and-forget coroutine ran.
  Found during 3.3.0 release verification and filed as
  [#71](https://github.com/brewkits/kmpworkmanager/issues/71); fixed here by routing
  through `TaskEventManager.emit()` (persists + forwards to the bus, same as
  `ChainExecutor`) and saving an `ExecutionRecord`, both awaited inside `executeTask`'s own
  coroutine under `NonCancellable` so a late cancellation can't cut the write off —
  mirroring `ChainExecutor` and Android's `BaseKmpWorker`. `executeTask` gained an optional
  `taskId` parameter (all three internal call sites now pass it) used as
  `ExecutionRecord.chainId`, the same convention `BaseKmpWorker` uses on Android.
- **iOS: `SingleTaskExecutor` used a wall-clock diff for `ExecutionRecord.durationMs`.**
  The exact class of bug `ChainExecutor.executeStep` already guards against for the
  identical reason (see 3.3.0's `withTimeoutOrNull` entry below): an NTP sync or manual
  clock change mid-task (up to 120s for `BGProcessingTask`) could silently corrupt the
  persisted duration. This was in code written for the `executeTask`/`taskId` fix above,
  caught during the same review before it ever reached a release. Now uses
  `TimeSource.Monotonic` for the duration, wall-clock only for the `startedAtMs`/`endedAtMs`
  timestamp fields — mirroring `ChainExecutor`'s existing split exactly.
- **KSP: two `@Worker` classes claiming the same name or alias silently overwrote one
  another** in the generated `providers` map — no compile error, no KSP warning. One
  worker became permanently unreachable at runtime. `WorkerProcessor` now fails the build
  (`logger.error()`) listing every colliding key and the classes claiming it. Covered by
  `WorkerProcessorDuplicateKeyTest`, which calls the validation directly rather than
  through `WorkerProcessorTest`'s compile-testing harness — that entire 21-test class is
  `@Ignore`d (kctfork 0.6.0 never invokes the processor for in-memory sources), so none of
  those tests actually run in CI today.
- **iOS: caller-supplied task/chain ids were used unsanitized as filenames** at 13 call
  sites in `IosFileStorage` (`saveTaskMetadata`, chain definition/progress/deleted-marker
  files) — `dir.safeAppend("$id.json")`, where `safeAppend` only guards against
  `URLByAppendingPathComponent` returning null, not path traversal. Ids containing `/` or
  equal to `.`/`..` are now percent-encoded via `String.encodeAsPathComponent()`.
  Deliberately narrow: only `/`, a bare `.`/`..`, and (for injectivity) a literal `%` are
  escaped, so ordinary ids (`"nightly-sync"`, `"com.example.sync"`, UUIDs) produce the
  exact same on-disk filename as before — tasks an app scheduled before upgrading past
  this fix keep resolving correctly.
- **`kmpworkmanager-http`: the HTTP clients' `User-Agent` header hardcoded
  `"KmpWorkManager/2.3.4"`**, un-synced with the real published version across every
  release since (13 releases stale at 3.3.0). `:kmpworker-http` now generates a
  `LIBRARY_VERSION` constant from `VERSION_NAME` on every build, so the header can't drift
  from the actual release again.
- **`kmpworkmanager-http`: the SSRF-aware manual redirect-following interceptor was
  duplicated verbatim** between `HttpClientProvider.android.kt` and
  `HttpClientProvider.ios.kt` (~25 identical lines re-validating each `Location` header via
  `SecurityValidator`, capping at 10 hops, stripping `Authorization`/`Cookie` on
  cross-origin hops). The logic itself was correct; being security-critical, two copies
  risked silently drifting apart on a future change landing in only one file. Extracted to
  a single `HttpClient.installSecureRedirectFollowing()` in commonMain.

## [3.3.0] - 2026-08-08

### Removed

- **BREAKING — Koin is no longer a dependency.** `koin-core` shipped at `runtime` scope in all
  five published artifacts, so consumers who never used Koin still carried it on the classpath
  and in the iOS klib link ([#66](https://github.com/brewkits/kmpworkmanager/discussions/66)).
  `kmpWorkerModule()` and `kmpWorkerCoreModule()` are removed; call `KmpWorkManager.initialize()`
  instead. The migration is roughly four lines in one file — see
  [`docs/MIGRATION_V3.3.0.md`](docs/MIGRATION_V3.3.0.md). Koin and Hilt users keep working by
  binding `KmpWorkManager.getInstance()` from their own module; the library deliberately does
  not ship a bridge artifact.
- `koin-android` dropped from the Android artifact ([#67](https://github.com/brewkits/kmpworkmanager/pull/67)).
  No production code imported it — it was only surviving because it transitively supplied
  `androidx.core` (`NotificationCompat`), which is now declared directly.

### Added

- **iOS: `KmpWorkManager.initialize()`** — a DI-agnostic entry point mirroring Android's,
  keeping the eager fail-fast checks (`IosWorkerFactory` type, Info.plist
  `BGTaskSchedulerPermittedIdentifiers`) at the same point in startup
  ([#68](https://github.com/brewkits/kmpworkmanager/pull/68)).
- `KmpWorkManagerInstance.workerFactory` on Android, for hosts that run workers outside
  WorkManager (a custom exact-alarm `BroadcastReceiver`, for example) and need to resolve a
  worker by class name themselves.
- Registry hardening suites — `V330RegistryHardeningTest` (iOS) and
  `V330AndroidRegistryHardeningTest` (Robolectric) — covering concurrent init election,
  concurrent readers against `by lazy`, init/shutdown leak loops, startup-path init cost,
  cached-resolution cost, hostile worker class names, and fail-fast before state is published.

### Fixed

- **iOS: execution history and task events were silently dropped.** `EventStore` and
  `ExecutionHistoryStore` were lazy `single { }` bindings whose global-registration side
  effects only ran if something resolved them — and nothing in the library or the sample ever
  did. Unless a host app resolved them itself, `KmpWorkManagerRuntime.executionHistoryStore`
  stayed `null`, workers dropped every record through `?.save(record)`, and
  `getExecutionHistory()` returned an empty list. Android was already correct via
  `createdAtStart = true`. Both stores are now created eagerly on both platforms.
- **`shutdown()` left stale global registrations.** `TaskEventManager.initialize()` is
  compare-and-set "first call wins", and neither platform's `shutdown()` released the claim, so
  `shutdown()` → `initialize()` left the global event store pointing at the dead registry's
  instance while the live registry held a different one. `shutdown()` now releases both the
  event store and the execution history store.
- **iOS: nested-timeout misattribution could theoretically survive under CPU starvation.**
  `ChainExecutor.executeChain`/`executeStep` disambiguated an inner (chain/task) timeout from
  an outer one via `elapsedNow() < timeout` inside `catch (TimeoutCancellationException)`.
  Correct under normal load, but a scheduling stall between the outer cancellation and the
  elapsed-time read could push `elapsed` past the inner budget and misattribute an outer
  cancellation as a genuine chain/task timeout. Both call sites now wrap their inner block in
  `withTimeoutOrNull` instead of `withTimeout` — kotlinx.coroutines identity-checks the
  exception internally, so there is no elapsed-time read on this path at all, and no window
  for the race. See `V330TimeoutIdentityDisambiguationTest`.
- **Android: `OverflowFileRegistry` could leak files in multi-process apps.** The
  `SharedPreferences`-backed registry caches in memory per `Context`, so a host with separate
  processes (`:background`, `:push`, …) could race a `register()` in one process against a
  `consumeAndDelete()` in another and leak the overflow file until the 24 h janitor sweep. Now
  backed by one file per entry under `cacheDir/overflow_registry/`, with no per-process cache to
  race. Task ids are caller-supplied, so filenames are derived via an injective, traversal-safe
  percent-encoding rather than a hash. Migrates any legacy `SharedPreferences` entries
  automatically on first use.

### Changed

- `KmpWorkManagerKoin` → `KmpWorkManagerAndroid`, backed by `AndroidServiceRegistry`; the iOS
  equivalent is `IosServiceRegistry`. Both are plain internal registries of `by lazy`
  singletons — the Koin module never used a container feature (no scopes, no qualifiers, no
  `parametersOf`, no lazy graph).
- Documentation swept for the removed API: README, quickstart, platform-setup, api-reference,
  examples, troubleshooting, the KSP README, and the `@Worker` / `BgTaskIdProvider` KDoc.

## [3.2.0] - 2026-08-05

### Fixed

- **Android:** Made Foreground Service permissions strictly opt-in. The library no longer automatically merges `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` into the consumer app's manifest, preventing unwarranted Play Store rejections for apps that don't use foreground features (#64). If you use `KmpHeavyWorker`, you must manually declare the required permissions and service type in your app's `AndroidManifest.xml` (see `ANDROID_FGS_GUIDE.md`).

## [3.1.0] - 2026-07-15

### Added

- Honor maxRetries retry ceiling on Android + iOS ([`65e9e6c`](https://github.com/brewkits/kmpworkmanager/commit/65e9e6c0aa1b219d5310c366dd80bf1ad269c9ce))

### Fixed

- Resolve CodeQL alerts (allowBackup, deprecated network API, ignored file status) ([`f0e5b1e`](https://github.com/brewkits/kmpworkmanager/commit/f0e5b1ea34d130449c1c89bdd626019b5771694c))
- GitHub Release body = changelog, not Maven Central steps (#63) ([`e4c301d`](https://github.com/brewkits/kmpworkmanager/commit/e4c301d7d703b8b3d16e36e912048c4272936423))

## [3.0.1] - 2026-06-20

### Fixed

- Document KmpWorker expedited crash fix on API <31 (v3.0.1) (#60) ([`94bd6fc`](https://github.com/brewkits/kmpworkmanager/commit/94bd6fc77508bf7001166a42bb4e1f498a2f98aa))
- Checksum verify should allow extra checksums (#61) ([`809e5c7`](https://github.com/brewkits/kmpworkmanager/commit/809e5c728e6cea2587bc5202838bd38ce6799ea9))

### Tests

- CI-level getForegroundInfo guard + bump install docs to 3.0.1 (#62) ([`a3bfe09`](https://github.com/brewkits/kmpworkmanager/commit/a3bfe09f0c3e867a58941b907044391a75eb23c2))

## [3.0.0] - 2026-06-14

### Added

- Ktor 3 support — release 3.0.0 (closes #33) (#34) ([`74a4009`](https://github.com/brewkits/kmpworkmanager/commit/74a40096f57940e41761041430ae1c0cf86a54f6))
- Real network reachability via NWPathMonitor (closes #40) (#41) ([`cb19179`](https://github.com/brewkits/kmpworkmanager/commit/cb191796efe3cdae70c35c47021ee1bdb2bce017))

### Changed

- Extract Ktor HTTP workers into kmpworkmanager-http (v3.0.0) (#44) ([`d80fa33`](https://github.com/brewkits/kmpworkmanager/commit/d80fa3384904f05cc3779015522ff1ba78022a8e))

### Documentation

- Fix iOS dispatcher setup — master + chain executor handlers (refs #32) ([`3a549fd`](https://github.com/brewkits/kmpworkmanager/commit/3a549fde68a30488c64c32b455d60ccd371fb1fa))
- Refresh logo palette to indigo→violet→pink + soft amber bolt ([`bffb1bb`](https://github.com/brewkits/kmpworkmanager/commit/bffb1bbb5a084d35d30f270c9217ccfca767f48d))

### Fixed

- Repair Common+KSP job and iOS 18 Xcode pin (#36) ([`f6e337d`](https://github.com/brewkits/kmpworkmanager/commit/f6e337da452c214818a0c903032230c09bafc281))
- Stop init job clobbering queue-size counter (data race) (#37) ([`afd5873`](https://github.com/brewkits/kmpworkmanager/commit/afd5873bc6855ca4ddfdd8c11a3162953a87f70c))
- Remove eager queue-counter init that double-counted (flaky tests) (#38) ([`2e067f0`](https://github.com/brewkits/kmpworkmanager/commit/2e067f0e8c3702b3e1c299496c5a601fa5598345))
- Don't auto-launch maintenance job in test env (flaky crash) (#43) ([`3b07c85`](https://github.com/brewkits/kmpworkmanager/commit/3b07c856341f222841c8010526a94448f789b117))

## [2.5.1] - 2026-05-20

### Documentation

- Fix Worker API examples and KSP annotation usage (fixes #32) ([`83bb97c`](https://github.com/brewkits/kmpworkmanager/commit/83bb97ce7d9772da084f762931e1026e4172feef))
- Fix remaining API inconsistencies in platform-setup and examples ([`7ba3c45`](https://github.com/brewkits/kmpworkmanager/commit/7ba3c45bdafcff9b1c3f47c54cc536f6affb8af5))

## [2.5.0] - 2026-05-18

### Fixed

- Throw on directory creation failure and skip flaky CI stress test ([`6105189`](https://github.com/brewkits/kmpworkmanager/commit/61051896ff6dc831750c5824b0cda2940a5c3cc2))
- Skip file protection on queue directories in test mode ([`5284c97`](https://github.com/brewkits/kmpworkmanager/commit/5284c9739ea3ebbd71c66b03c7fc2ab388a20694))
- Correct artifactId replacement and checksum generation timing ([`abb8486`](https://github.com/brewkits/kmpworkmanager/commit/abb848634e0b8fc96dc3c011666fd8f9fc0f5a98))
- Use non-atomic write in test mode to avoid CI failures ([`8f8ee52`](https://github.com/brewkits/kmpworkmanager/commit/8f8ee528df282d6ce82d451d999e53db6f8a14e9))
- 16 P0/P1/P2 bugs from 4 audit passes (v2.5.0 production hardening) ([`c3c6054`](https://github.com/brewkits/kmpworkmanager/commit/c3c6054bc0c91b39305fe91919788b31cd5d343b))

## [2.4.3] - 2026-05-01

### Documentation

- Update v2.4.3 release notes with actual bug fixes and infrastructure changes ([`72e21da`](https://github.com/brewkits/kmpworkmanager/commit/72e21dacd16429e0864d34637e688d3dcf760c34))

### Fixed

- Prevent hardcoded isTestMode and apply missing CI/CD maven config ([`999dd8a`](https://github.com/brewkits/kmpworkmanager/commit/999dd8a13a9707d0f0a53f0aabaf29ce880d418a))
- Apply 12 security and stability fixes, upgrade to Kotlin 2.1.21 ([`8323fbb`](https://github.com/brewkits/kmpworkmanager/commit/8323fbbe427dd1e8dc117d9159610d97797512e9))
- Eliminate shared-field race in concurrent test execution ([`b8f3cd3`](https://github.com/brewkits/kmpworkmanager/commit/b8f3cd3954e5a2deb38e806281ba3cc3e26216c6))
- Serialize K/N test workers to prevent Gradle XML writer race ([`341db77`](https://github.com/brewkits/kmpworkmanager/commit/341db77493ea12f9dfe41a98e15307a08b4574a9))
- Prevent coroutine leak in NativeTaskScheduler causing CI crashes on iOS ([`1f20e3c`](https://github.com/brewkits/kmpworkmanager/commit/1f20e3c9e33a43131e28c18014f4118062cb0318))

### Tests

- Add comprehensive test cases for v2.4.3 improvements ([`99cf5d7`](https://github.com/brewkits/kmpworkmanager/commit/99cf5d7840a88c068854498f5990afdf69271ff8))

## [2.4.2] - 2026-04-28

### Fixed

- Resolve duplicate AlarmReceiver class and improve iOS stress test robustness ([`eb286da`](https://github.com/brewkits/kmpworkmanager/commit/eb286da5d97bb2fce88530af568387e61e06a79c))
- Resolve D8 duplicate class error and stabilize iOS stress test ([`9d76599`](https://github.com/brewkits/kmpworkmanager/commit/9d765991dfe730021be112003d1fd1f7d1b4c95e))
- Finalize DemoAlarmReceiver package and ignore flaky iOS stress test ([`f76910a`](https://github.com/brewkits/kmpworkmanager/commit/f76910af61038f0c31dfea512b9bb6ee78f56ac5))
- Restore periodic task immediate execution and bump to v2.4.2 ([`ec68958`](https://github.com/brewkits/kmpworkmanager/commit/ec68958d50da777fc6c10adaed213df5648ee8a8))
- Resolve periodic task regression and related memory leaks ([`cdf5505`](https://github.com/brewkits/kmpworkmanager/commit/cdf5505a0454bf86c2b6fcde4786e40d2a7c5c23))

### Tests

- Fix flaky tests and CI hangs by isolating storage paths ([`9a1f04a`](https://github.com/brewkits/kmpworkmanager/commit/9a1f04a05669143f5d4f106898502af05f71a5c1))

## [2.4.1] - 2026-04-24

### Added

- Implement internal dispatcher queue for iOS dynamic tasks ([`ca9db14`](https://github.com/brewkits/kmpworkmanager/commit/ca9db1467fa1123507b4d14520b7eb4b3640b83b))
- Release v2.4.1 with iOS dynamic tasks and periodic task improvements ([`4c06482`](https://github.com/brewkits/kmpworkmanager/commit/4c0648295237b6a9f0c2af6779984526f28095f3))

### Documentation

- Add guide and workaround for iOS dynamic task scheduling limitations ([`571d8b7`](https://github.com/brewkits/kmpworkmanager/commit/571d8b7dc8f6aa9f5e5337b406ed3beaaa6b2ea3))

### merge

- Release v2.4.1 - iOS dynamic tasks and periodic task improvements ([`53c69c6`](https://github.com/brewkits/kmpworkmanager/commit/53c69c6bfd7d27b98ff8d42305833837955da860))

## [2.4.0] - 2026-04-16

### Added

- Implement native Kotlin background task handler and bump version to 2.4.0 ([`c49a392`](https://github.com/brewkits/kmpworkmanager/commit/c49a392828feac54dd8407aa257e9a706b176e64))

### Fixed

- Add foregroundServiceType to satisfy Android Lint and Android 14+ requirements ([`c8a1398`](https://github.com/brewkits/kmpworkmanager/commit/c8a13981e2da2ce8e3ebfcce7bca5939d4af06e7))
- Make BufferedIOTest more deterministic on CI using runTest and virtual time ([`420749d`](https://github.com/brewkits/kmpworkmanager/commit/420749dd4731ee262847054051f20314311914a7))
- Make BufferedIOTest and BugFixes_v239_IosTest deterministic on CI ([`a2fb7c3`](https://github.com/brewkits/kmpworkmanager/commit/a2fb7c3e99b03c53d84dc3ed82f2f01e17b343d5))
- Ensure all modules use root staging directory and include javadoc JARs in Maven Central ZIP ([`cb8020f`](https://github.com/brewkits/kmpworkmanager/commit/cb8020fc65ef504f60cfb6ea4478f39074670ea3))
- Fix Gradle sign/publish task ordering and javadoc jar registration ([`48c8d09`](https://github.com/brewkits/kmpworkmanager/commit/48c8d09485476aaf96abf01cff7ab01683c9eee7))
- Resolve Swift type ambiguity by unifying background engine and removing duplicates in sample app ([`2a4c560`](https://github.com/brewkits/kmpworkmanager/commit/2a4c56099549b4a1ddfadb9f1130c964012a33e3))

### Tests

- Add comprehensive security, performance, and stress tests for v2.4.0 release ([`f0a99c0`](https://github.com/brewkits/kmpworkmanager/commit/f0a99c0c7c68a75caf5fbc796e545dedeb148cb0))
- Fix CI stability by removing redundant tests and properly synchronizing coroutines ([`c427847`](https://github.com/brewkits/kmpworkmanager/commit/c4278473eebb19cf0761ef0db003dd6761e96b2f))

## [2.3.8] - 2026-04-08

### Added

- Comprehensive test suite and documentation improvements ([`b4435fa`](https://github.com/brewkits/kmpworkmanager/commit/b4435fa71bbac3fc1d38baa445d2a5f32eaa4711))
- Release v3.0.0 - Major performance and API improvements ([`677bd39`](https://github.com/brewkits/kmpworkmanager/commit/677bd399c77bc38e33a27b1ef111fe93a91d7464))
- Release v4.0.0 - Worker factory pattern and extensibility improvements ([`8afd8e5`](https://github.com/brewkits/kmpworkmanager/commit/8afd8e5d93a0025124a6f579785c0dc2133504dd))
- Add Event Persistence System design and interface ([`c867d10`](https://github.com/brewkits/kmpworkmanager/commit/c867d10069dc75668ab1d2425e2e066da7ce7a9c))
- Implement Event Persistence System with file-based storage ([`1b2fc7b`](https://github.com/brewkits/kmpworkmanager/commit/1b2fc7b0af3c58ddfbd6924308cd41aa1a2b3c82))
- Integrate Event Persistence with TaskEventBus and Koin ([`a42bcb7`](https://github.com/brewkits/kmpworkmanager/commit/a42bcb737124fa204053a9cef69343bbe98846c8))
- Implement iOS chain state restoration with progress tracking ([`51032b2`](https://github.com/brewkits/kmpworkmanager/commit/51032b2c89bfb0aabf4e954889fa279603cf2d4c))
- Add Windowed trigger support and worker progress tracking ([`b7b0efe`](https://github.com/brewkits/kmpworkmanager/commit/b7b0efe2dcccb4d0b0e40be46f68dd4e2a5ded0a))
- Add Maven Central publishing automation ([`bcfc4e4`](https://github.com/brewkits/kmpworkmanager/commit/bcfc4e4c0a53cc182c7915a4038bd421f993868d))
- Add iOS support for built-in workers ([`6111ee8`](https://github.com/brewkits/kmpworkmanager/commit/6111ee8e4ec2ff47337bd0386b79d174160835a7))
- Add WorkerResult API for data return from workers ([`9a19303`](https://github.com/brewkits/kmpworkmanager/commit/9a19303f93d3fcf75e987cd19f7f46f1a3d269a0))
- Complete built-in workers migration to WorkerResult ([`32018bc`](https://github.com/brewkits/kmpworkmanager/commit/32018bca0b5aa0d416ab62c060797e3ce3acdd80))
- TelemetryHook, TaskPriority, Battery Guard ([`1b365ea`](https://github.com/brewkits/kmpworkmanager/commit/1b365ea9f1888973e5213cb5a949de4b12211618))
- ExecutionHistory + KSP BGTask ID validation ([`ae540eb`](https://github.com/brewkits/kmpworkmanager/commit/ae540eb1b457b4226ce8906956d26c57df4ded6d))

### Changed

- Change package name from com.example.kmpworkmanagerv2 to io.kmp.taskmanager.sample ([`99cf9a4`](https://github.com/brewkits/kmpworkmanager/commit/99cf9a4c75b4aa5d15d3ac275a3b53ac510b636f))
- **BREAKING** Rename kmptaskmanager to kmpworker ([`3dbdc42`](https://github.com/brewkits/kmpworkmanager/commit/3dbdc424f3721081f1701fd33155a0b672fb8c0e))
- **BREAKING** Rename taskmanager to worker throughout codebase ([`21647ec`](https://github.com/brewkits/kmpworkmanager/commit/21647ecb3e771afa9ec14316963b1c7b1812cf00))
- Rename project from 'KMP Worker' to 'KMP WorkManager' and remove V2 suffix ([`57fdecc`](https://github.com/brewkits/kmpworkmanager/commit/57fdecca20b8776d42882d5cb3de551413817c94))
- **BREAKING** Change group ID from io.brewkits to dev.brewkits ([`2b3dd75`](https://github.com/brewkits/kmpworkmanager/commit/2b3dd756cb5294d85e46154d4dc427a70261cd4a))
- Update sample app to v2.3.0 WorkerResult API ([`9ba5d3c`](https://github.com/brewkits/kmpworkmanager/commit/9ba5d3c9bda9ea0607f6c8118cb39777b3764b73))

### Documentation

- Optimize README structure (543→420 lines) ([`e71850e`](https://github.com/brewkits/kmpworkmanager/commit/e71850ef1cbc7ff1b1a7d2aafbf0b059acdf780a))
- Refine README formatting and emoji usage ([`05a1776`](https://github.com/brewkits/kmpworkmanager/commit/05a177680debddaba398d93bdceb5ecd294190c8))
- Rewrite README to be more professional ([`ff0f5c2`](https://github.com/brewkits/kmpworkmanager/commit/ff0f5c2359febd582ebb7874f1aa1574a65e3349))
- Update old package name references ([`927c2e7`](https://github.com/brewkits/kmpworkmanager/commit/927c2e727add98aa53d83fd0c7cfa6fe838a0268))
- Update branding to Brewkits organization ([`15ddfa8`](https://github.com/brewkits/kmpworkmanager/commit/15ddfa83574ed10fc674e693a1ef5d850d84fdbd))
- Add comprehensive test plan for future improvements ([`a4f285c`](https://github.com/brewkits/kmpworkmanager/commit/a4f285ccf4b82e87576de382bbfcdfc6a1d13629))
- Complete final audit - Update all remaining "KMP Worker" references to "KMP WorkManager" ([`5635fcc`](https://github.com/brewkits/kmpworkmanager/commit/5635fcc5c95af7f80cd96694d341e153214d72a4))
- Add comprehensive research analysis and roadmap for v1.1.0 ([`fc4d2ab`](https://github.com/brewkits/kmpworkmanager/commit/fc4d2abccd1c2e69294a979beef357265274f83f))
- Add comprehensive iOS limitations documentation and Android-only API annotations ([`f60ab92`](https://github.com/brewkits/kmpworkmanager/commit/f60ab92d5a098768acbe6302d5c9ec01565dd099))
- Rebrand as 'KMP Worker - Enterprise-grade Background Manager' ([`12e9689`](https://github.com/brewkits/kmpworkmanager/commit/12e96897805efe0906c8ac38b0c7054dd4b527fc))
- Update DEMO_GUIDE with enterprise features and v1.1.0 capabilities ([`b485709`](https://github.com/brewkits/kmpworkmanager/commit/b485709d30343a06f193626f56fbabfa44ab2c9f))
- Add comprehensive publish summary and next steps ([`141344a`](https://github.com/brewkits/kmpworkmanager/commit/141344a71036fd1e9bd009f62bcb52914579ac46))
- Add comprehensive ROADMAP and DEPRECATED README ([`b26b99c`](https://github.com/brewkits/kmpworkmanager/commit/b26b99c8f407e513766379417a061bb74fc6d265))
- Update roadmap versions and improve gitignore ([`330eb0b`](https://github.com/brewkits/kmpworkmanager/commit/330eb0b1e06715335c5387270b3bbfba3fe32f39))
- Clarify task replacement behavior in demo UI ([`4c54152`](https://github.com/brewkits/kmpworkmanager/commit/4c5415222ec323855ebedf517db3b6f59d4e8d12))
- Clean up internal documentation files ([`6a1156c`](https://github.com/brewkits/kmpworkmanager/commit/6a1156c6b7d98791b3b6dd928a7044d8b85225cb))
- Update README with v2.1.2 release and correct roadmap versions ([`fe994f5`](https://github.com/brewkits/kmpworkmanager/commit/fe994f5e36ea8df86ff7d025e35dee03297be0c7))
- Add content-uri-task to Info.plist and document iOS Simulator limitations ([`900aea3`](https://github.com/brewkits/kmpworkmanager/commit/900aea3b91e9a47c74b8ff585678b79fb3c4f55e))
- Add comprehensive GPG signing setup guide for Maven Central ([`632d078`](https://github.com/brewkits/kmpworkmanager/commit/632d078bd5cee7631bdd02f446a5a524aa652a42))
- Update README to v2.1.2 and add dev.to article ([`1a1581e`](https://github.com/brewkits/kmpworkmanager/commit/1a1581ee66450922470e039a5b737103b2d0a0f4))
- Add hero cover image to README ([`5be7495`](https://github.com/brewkits/kmpworkmanager/commit/5be7495b804faae315cd5ed54d937979b2ca71ee))
- Bust GitHub image cache for banner ([`c3406d7`](https://github.com/brewkits/kmpworkmanager/commit/c3406d7feaac6c089f0b166bdd27f90eef479f6d))
- Add comprehensive KSP & Annotation guide ([`d8a640d`](https://github.com/brewkits/kmpworkmanager/commit/d8a640da482011bebef2abffd549458030020dbd))
- Convert KSP guide to English ([`1d464a4`](https://github.com/brewkits/kmpworkmanager/commit/1d464a4474d6596936c3f0cb5f9001d154e3bb32))
- Update README with realistic content and add release documentation ([`d467294`](https://github.com/brewkits/kmpworkmanager/commit/d4672943a95ac1ecd74372025ecaa0fe5a67659e))
- Update documents ([`6ed31c1`](https://github.com/brewkits/kmpworkmanager/commit/6ed31c1250c6da6f03830f72a6bac7ac8947271b))
- Modernize README with professional formatting and improved structure ([`c53f20d`](https://github.com/brewkits/kmpworkmanager/commit/c53f20d04a6b73acd0c5e5ed3d482563ea1432b4))
- Fix iOS initialization documentation inconsistencies (#15) ([`867a12e`](https://github.com/brewkits/kmpworkmanager/commit/867a12e64ec13201cf061b55e222b969d6e42da6))
- Rewrite README for developer audience ([`564c63c`](https://github.com/brewkits/kmpworkmanager/commit/564c63cf258c240dc3da99db452cdd3760591d6e))

### Fix

- Address compiler warnings and deprecations in ComposeApp ([`fa96e21`](https://github.com/brewkits/kmpworkmanager/commit/fa96e21602dd90de67d12b50c5053488e46bb19b))
- Resolve DEBUG macro conflict and update Swift calls to async/await ([`d9020f7`](https://github.com/brewkits/kmpworkmanager/commit/d9020f7f77bc4e3070733d352e22438ae634d6c9))
- Resolve TaskEventBusTest, update docs, correct worker return types, and document KSP/iOS demo build issues ([`a611fd7`](https://github.com/brewkits/kmpworkmanager/commit/a611fd7b4fbf5723317c010518a45c724fa2d7fe))

### Fixed

- Keep original contact email vietnguyentuan@gmail.com ([`6e169c2`](https://github.com/brewkits/kmpworkmanager/commit/6e169c29831faf997e1528d6960a3b33ab056491))
- Increase EventBus replay buffer from 0 to 5 events ([`81b95ac`](https://github.com/brewkits/kmpworkmanager/commit/81b95acbd0046e20c018c8dd19c60a9919dc2d40))
- Add exact-reminder to iOS Info.plist BGTaskSchedulerPermittedIdentifiers ([`d5c2428`](https://github.com/brewkits/kmpworkmanager/commit/d5c242865aaa5bef4e2ad417bfb39ed9770353df))
- Replace dynamic task ID with fixed ID for iOS compatibility ([`1f15e3f`](https://github.com/brewkits/kmpworkmanager/commit/1f15e3f0896f9e30582c364539fbbf4fdff37b77))
- Critical fixes for v2.1.0 production release ([`40ee80b`](https://github.com/brewkits/kmpworkmanager/commit/40ee80b42aa61cab84f5c463f924f28974ea6eaf))
- Critical iOS stability fixes for v2.1.2 ([`209205c`](https://github.com/brewkits/kmpworkmanager/commit/209205c55cb24b076e163c937727a1e0d1e6ef27))
- Add configurable foreground service type for Android 14+ ([`647be81`](https://github.com/brewkits/kmpworkmanager/commit/647be8193eb35e7e1b5d56fe97b79e1dc1e4564e))
- Update iOS app to use async/await API and correct KotlinInt properties ([`2d6567b`](https://github.com/brewkits/kmpworkmanager/commit/2d6567b98ee59d9355a490f568e96fb6b0366560))
- Use NSNumber conversion for KotlinInt in Swift ([`b469800`](https://github.com/brewkits/kmpworkmanager/commit/b469800727b597c1572e0ac70f0f1330ecfedf8f))
- Fix run GitHub action ([`e741bde`](https://github.com/brewkits/kmpworkmanager/commit/e741bdebb29ffcbc12fa852e2068ab567c22aa91))
- Fix bug for Github action ([`d4fb221`](https://github.com/brewkits/kmpworkmanager/commit/d4fb221d7a84b0523fae94c5ffa4acfba78001de))
- IOS simulator detection and chain executor improvements (#10) ([`8351c54`](https://github.com/brewkits/kmpworkmanager/commit/8351c54607787eefcff246d4943a9f0cd9143b95))
- IOS compilation issues for v2.2.2 Maven release ([`4e546cd`](https://github.com/brewkits/kmpworkmanager/commit/4e546cd607de77b9d451fb1b1b4a496f203dfb41))
- Resolve compilation errors ([`4bb4b51`](https://github.com/brewkits/kmpworkmanager/commit/4bb4b51b738b030dcb18930381e5c0eb36865408))
- Complete remaining implementation issues ([`317519d`](https://github.com/brewkits/kmpworkmanager/commit/317519d2cce94c2f4e8b93baa529f6540cf8b958))
- Update iOS demo app to use WorkerResult API ([`666201b`](https://github.com/brewkits/kmpworkmanager/commit/666201b8f770d87e8357a4ea06f55e72b3e36fdc))
- Update iOS app to use simplified WorkerResult type checking ([`231ff3e`](https://github.com/brewkits/kmpworkmanager/commit/231ff3ef0389f538b2629be5c2598d1027584afa))
- V2.3.3 — WorkManager 2.10.0+ compat, chain heavy routing, notification i18n (#14) ([`50fb143`](https://github.com/brewkits/kmpworkmanager/commit/50fb14351e61a636b13e4e635328e22ad666a5a9))
- V2.3.5 — bug fixes, test coverage, code cleanup ([`ea7010b`](https://github.com/brewkits/kmpworkmanager/commit/ea7010baed320bdfc7c14ccf21712d7d91ce9b51))
- V2.3.5 — bug fixes, test coverage, code cleanup ([`dc50d78`](https://github.com/brewkits/kmpworkmanager/commit/dc50d78c47d358dcdd287a482e1867c6c8ec80f5))
- V2.3.6 — 10 critical bug fixes across iOS and Android ([`ca43ecb`](https://github.com/brewkits/kmpworkmanager/commit/ca43ecb8ddafe83752b4c76950540123f14055ec))
- Address audit findings on iOS chain executor and storage (fix/audit-bugs) ([`0e7a6d0`](https://github.com/brewkits/kmpworkmanager/commit/0e7a6d018f1f849665b76622d92a36e58edb23f2))
- Comprehensive audit fixes — silent failures, diagnostics, security, API safety ([`3009e82`](https://github.com/brewkits/kmpworkmanager/commit/3009e823f02129d788362f50cad8ca1876f95685))
- V2.3.7 — close Android/iOS feature parity gaps ([`90859d5`](https://github.com/brewkits/kmpworkmanager/commit/90859d545d23fe332af83832748e9c36888b8533))
- V2.3.7 — stability hardening and crash prevention ([`6e2d8eb`](https://github.com/brewkits/kmpworkmanager/commit/6e2d8eb5ee2463119b52450f3779c6c1810901cf))
- V2.3.7 — code audit fixes across Android, iOS, and common ([`ab41dd0`](https://github.com/brewkits/kmpworkmanager/commit/ab41dd0ab01d8cc58cb3cd61626470999925a46b))
- V2.3.7 — address 4 architectural issues from code review ([`58f0b7e`](https://github.com/brewkits/kmpworkmanager/commit/58f0b7e7174df33b4d2d69e1b101ceefe14cc91b))
- V2.3.7 — clean up deprecated API, non-nullable factory, flush docs ([`f455d11`](https://github.com/brewkits/kmpworkmanager/commit/f455d118d539c8e901ce3a66ce8e1807e62ac67d))
- V2.3.7 — full audit fixes, streaming upload, QA test suite ([`d4811ee`](https://github.com/brewkits/kmpworkmanager/commit/d4811eeb58b41a0daf63817b821eb2415f441096))
- Wire built-in workers for upload/download demo on Android ([`ffcbd37`](https://github.com/brewkits/kmpworkmanager/commit/ffcbd37922a542257f17b9d982313af5467ceed3))
- Register all sample workers in DemoWorkerFactory ([`6e203bc`](https://github.com/brewkits/kmpworkmanager/commit/6e203bc1044c7a6dacc9b2183b9c41cfe2de8510))
- BuiltinWorkerRegistry returns null for unknown workers ([`e87fd22`](https://github.com/brewkits/kmpworkmanager/commit/e87fd222c435c0edccc54c30e441f665cd50b06e))
- SSRF UserInfo bypass, clearThrottle leak, dead dataClass field ([`08af382`](https://github.com/brewkits/kmpworkmanager/commit/08af38269104ffaf74977e67ec17c365bc85260f))
- Resolve VERSION_NAME NPE on CI ([`9b88d72`](https://github.com/brewkits/kmpworkmanager/commit/9b88d7223d1f74408ea2a8f86f99c2cfd5decd54))
- Inject all required gradle.properties for CI build ([`7ce0400`](https://github.com/brewkits/kmpworkmanager/commit/7ce04006f6136d7ab239263acd81d02d7ba6ff6a))
- Make testBufferConsistency deterministic on CI ([`86a7cd9`](https://github.com/brewkits/kmpworkmanager/commit/86a7cd9fd18cd59ee58851c7e14ae683e0f2e528))
- Add missing AndroidManifest.xml to kmpworker library ([`cbf457d`](https://github.com/brewkits/kmpworkmanager/commit/cbf457d8ea249c535ff16453c0bea1a936acfeed))
- V2.3.8 edge-case hardening — queue safety, stale locks, security ([`60d9f24`](https://github.com/brewkits/kmpworkmanager/commit/60d9f24058f5aaae8c4961973fabea3887993b56))
- Resolve iOS simulator demo chain freezing and zip compression fallback ([`1d8135b`](https://github.com/brewkits/kmpworkmanager/commit/1d8135b3aa0cb12e5007c34b7b6caa6627804931))

### Tests

- Add comprehensive iOS unit tests for stability ([`b1fff38`](https://github.com/brewkits/kmpworkmanager/commit/b1fff3808b01333ef4eb2386dc7e87abe8dfe743))
- Add comprehensive WorkerResult tests and built-in worker chain demos ([`5d9eb95`](https://github.com/brewkits/kmpworkmanager/commit/5d9eb955365ef18afb5552c8a96711e4d2a5acdb))

### add

- For build ios xframe ([`f27e055`](https://github.com/brewkits/kmpworkmanager/commit/f27e055c94338ac338ee27748f9039e1a5d12174))

### chore

- **BREAKING** Migrate to brewkits/kmp_worker organization ([`e9014a4`](https://github.com/brewkits/kmpworkmanager/commit/e9014a4934055d24be85e5bb76d7cd5e4fedd909))

### ref

- Update lib version ([`7c88b6a`](https://github.com/brewkits/kmpworkmanager/commit/7c88b6ac69436c041cd0e3bc2538273aa9ca5b58))
- V2.1.0 Phase 1 Day 1 Completion ([`e950328`](https://github.com/brewkits/kmpworkmanager/commit/e950328f25a02feabb6b9af563b6304015ec3ee3))

### release

- **BREAKING** Version 2.0.0 - Package namespace migration ([`54d3b67`](https://github.com/brewkits/kmpworkmanager/commit/54d3b67b373f7c204c8de2f21891fa3d69a246c6))


