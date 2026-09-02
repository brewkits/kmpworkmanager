# iOS App Group storage (v3.6.0+)

`KmpWorkManager.initialize(appGroupIdentifier = ...)` roots the library's task/chain/progress
storage in a shared App Group container instead of the app's private Application Support
directory, so a Widget or Share Extension in the same App Group can read what the main app has
scheduled.

## What this covers

Passing `appGroupIdentifier` threads a single shared `IosFileStorage` (rooted at the App
Group's container URL) through `NativeTaskScheduler`, `ChainExecutor`, and
`DynamicTaskDispatcher` — the three places that otherwise each construct their own
`IosFileStorage()` against the default private path. All three already accepted an injectable
`fileStorage` parameter for testing; this is the same seam, now exposed through the public
initializer.

```kotlin
KmpWorkManager.initialize(
    workerFactory = MyWorkerFactory(),
    appGroupIdentifier = "group.com.example.myapp"
)
```

You must also add the App Group capability in Xcode (Signing & Capabilities → **+ Capability**
→ **App Groups**) with a matching identifier, for both the main app target and any extension
target that needs to read the shared data. If the entitlement is missing or the identifier
doesn't match, `initialize()` throws `IllegalArgumentException` with the exact identifier that
failed to resolve — it does **not** silently fall back to private storage, because writing your
task data to the wrong place and only noticing later would be worse than a loud failure at
startup.

## What this does NOT cover

**Running the scheduler in more than one process at once is not supported.** Sharing the
storage *location* does not make it safe for two processes to run `NativeTaskScheduler`/
`ChainExecutor`/`DynamicTaskDispatcher` against it simultaneously. Several pieces of state
above the file-coordination layer are in-memory and process-local:

- `ChainJobRegistry` — a `Job` map used to cancel an in-flight chain on `ExistingPolicy.REPLACE`.
  A `Job` has no meaning outside the process that created it.
- `IosFileStorage`'s progress-flush debounce buffer (`FLUSH_DEBOUNCE_MS`) — writes are batched
  in RAM before the coordinated disk flush, so a second process reading during that window
  sees stale or missing progress.
- The dynamic queue's size counters (`AtomicInt`) — enforce `MAX_QUEUE_SIZE` only within one
  process; two processes each independently checking their own counter can jointly exceed it.
- `BackgroundDownloadStateStore`'s in-memory cache (used by
  [`IosBackgroundUrlSessionManager`](./IOS_BACKGROUND_URL_SESSION.md)) — separately, this store
  is **not** wired to `appGroupIdentifier` at all; it always writes to the main app's private
  Application Support directory regardless of this setting.
- `IosEventStore`/`IosExecutionHistoryStore` — backing `TaskEventBus` replay and
  `getExecutionHistory()` — are **also not** wired to `appGroupIdentifier`. Both always resolve
  their own `NSApplicationSupportDirectory` path, independent of the shared `IosFileStorage`
  described above. An extension cannot read execution history through this setting.

**The supported shape is: exactly one process runs the scheduler (normally the main app);
every other process sharing the App Group container is read-only.** An extension that wants to
observe what's scheduled should construct its own `IosFileStorage(baseDirectory = <same
container URL>)` directly and call the read-only `loadTaskMetadata` method — never `enqueue`,
`cancel`, or anything that mutates the queue. Execution history is not readable this way (see
above).

```kotlin
// Inside a Widget/Share Extension target — NOT KmpWorkManager.initialize()
val containerURL = NSFileManager.defaultManager
    .containerURLForSecurityApplicationGroupIdentifier("group.com.example.myapp")
val readOnlyStorage = IosFileStorage(baseDirectory = containerURL)
val metadata = readOnlyStorage.loadTaskMetadata("some-task-id", periodic = false)
```

## Live cross-process sharing (Darwin notifications) — not yet built

A future milestone could let an extension observe *changes* (not just point-in-time reads) via
`CFNotificationCenterGetDarwinNotifyCenter` — the mechanism iOS provides for one process to
signal another without a shared memory model. This isn't built yet: `IosFileCoordinator`
(already used for `AppendOnlyQueue` and `IosEventStore`) provides mutual exclusion for file
*access*, but no change notification, and none of `CFNotificationCenter`/`NSFilePresenter` are
used anywhere in the codebase today. Building this out would also require redesigning the
in-memory state listed above (registry, debounce buffer, queue counters) to invalidate on an
external-write signal instead of assuming they're the only writer — that's its own project, not
a small addition on top of `appGroupIdentifier`.

## See also

- [`docs/IOS_BACKGROUND_URL_SESSION.md`](./IOS_BACKGROUND_URL_SESSION.md) — the separate
  `sharedContainerIdentifier` option on `IosBackgroundDownloadConfig`/`IosBackgroundUploadConfig`,
  which shares the *transport* for background transfers but not this storage.
- [`docs/ROADMAP.md`](./ROADMAP.md) — tracks the live cross-process sharing gap.
