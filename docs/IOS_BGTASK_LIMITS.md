# iOS Background Task — Hard Limits

> **TL;DR.** iOS `BGTaskScheduler` is **opportunistic**, **time-bounded**, and
> **headless**. No library — including this one — can break these limits. They
> are enforced by `kernel_task` and `dasd` (Duet Activity Scheduler Daemon),
> not by Swift code. Plan your feature around them or it will fail in
> production.

This doc captures the four hard limits we keep seeing teams hit. Each section
states the limit, what it actually feels like in production, and how to design
around it.

---

## 1. Scheduling is opportunistic ("the BGTask black box")

### What's actually happening

When you call `BGTaskScheduler.submit(BGAppRefreshTaskRequest)`, iOS does **not**
schedule a wakeup at `earliestBeginDate`. It enqueues a *hint* — the actual
decision of whether to wake the app is made by **`dasd`** (Duet Activity
Scheduler Daemon), an on-device system service whose inputs are:

- App usage patterns (Siri prediction model) — how often the user opens this app
  at this hour of day, on this Wi-Fi network, with the phone in this orientation.
- Battery level + charging state. Below ~20 % battery, BGTask requests are
  silently dropped.
- Low Power Mode — drops **all** BGTask requests, no fallback.
- Thermal state — `ProcessInfo.processInfo.thermalState >= .serious` drops
  BGTasks.
- Cellular vs Wi-Fi for `BGProcessingTaskRequest.requiresNetworkConnectivity`.
- Whether the user has Background App Refresh enabled (Settings → General →
  Background App Refresh) — globally and per-app.

`earliestBeginDate` is a **hint**, not a guarantee. Apple documents this as
"the system runs tasks at times that minimise the impact on the user", which in
practice means **the task may never run**.

### What this feels like in production

- Apps that prompt the user once a week run reliably.
- Apps that prompt the user every 2-3 days run "usually".
- Apps the user installed and forgot about — task is **never** scheduled.
  The lower the engagement, the lower `dasd`'s priority weight.
- Power-user phones with 200+ apps installed: `dasd` rotates BGTask slots
  across all eligible apps. You compete with every other backgrounded app
  for a fixed daily budget.

### Do NOT use BGTask for

- 🚫 **Alarms / reminders** — `BGAppRefreshTaskRequest` will not fire on time,
  ever. Use `UNUserNotificationCenter.add(_:)` with a `UNTimeIntervalTrigger`
  or `UNCalendarNotificationTrigger`.
- 🚫 **Real-time sync** ("messages should arrive within 10 seconds") — use
  silent pushes (`content-available: 1`) with `application(_:didReceiveRemoteNotification:fetchCompletionHandler:)`,
  not BGTask.
- 🚫 **Anything the user expects to "just happen" by a specific time** — the
  contract iOS gives you is "maybe, eventually, when convenient for the OS".

### DO use BGTask for

- ✅ Camera-roll backup that the user is OK happening "overnight or whenever"
  the phone is charging on Wi-Fi.
- ✅ Pre-fetching content (article cache, image thumbnails) for the next time
  the user opens the app.
- ✅ Cleanup tasks (log rotation, expired-cache eviction) that have no SLA.
- ✅ Best-effort upload of analytics / telemetry batches.

### Library guarantees

`KmpWorkManager` on iOS:

- ✅ Persists scheduled tasks to disk so they survive process death and reboot.
- ✅ Re-submits the BGTask request after the task completes, after a reboot,
  and after the first cold-launch following app install.
- ❌ Cannot make iOS wake the app on a deterministic schedule. **Nothing can.**

---

## 2. Time budget exhaustion (`0x8badf00d` SIGKILL)

### What's actually happening

Each BGTask wake gives the app a wall-clock execution window:

| Task type | Budget | Notes |
|---|---|---|
| `BGAppRefreshTask` | ~30 seconds | Hard ceiling, no extension API |
| `BGProcessingTask` | Several minutes (typically 1–10 min) | iOS adjusts dynamically based on battery + thermal state |
| Silent push (`content-available`) | ~30 seconds | Same as App Refresh |

When the budget runs out:

1. iOS calls your `task.expirationHandler` (if set). You have **a few hundred
   milliseconds** to clean up.
2. If the expiration handler doesn't return in time, the OS sends `SIGKILL`
   with exception code `0x8badf00d` ("ate bad food"). Your process is gone —
   `finally` blocks may or may not run, file writes in progress are corrupted.
3. iOS records the kill against your app's reliability score, which reduces
   `dasd`'s willingness to wake you next time. **Repeated 0x8badf00d kills
   shadowban your app from background execution.**

### What this feels like in production

- A video transcoding task that takes 45 s on the latest iPhone Pro runs
  cleanly. The same task on an iPhone XR with a hot battery gets killed at 20 s
  → file partial, never finalised.
- An upload chain with 50 photos averaging 800 ms each = 40 s. Works on Wi-Fi;
  fails on cellular (slower per-photo) because 50 × 1.2 s = 60 s exceeds the
  `BGAppRefreshTask` ceiling.

### Design rules

1. **Each step in a chain MUST finish in ≤ 5 seconds.**
   Long-running work should be split into many granular steps; the
   `ChainExecutor` checkpoints after every step, so a kill mid-chain resumes
   from the last completed step.
2. **Never `runBlocking`** on a coroutine that calls network I/O. Always
   `withTimeout(5_000)` around individual operations.
3. **Use `BGProcessingTaskRequest` (not `BGAppRefreshTaskRequest`)** for any
   workload over 10 s. The processing-task budget is much larger and the
   API accepts `requiresExternalPower = true` to prefer charging time.
4. **Implement `expirationHandler` for every task you submit** — even if it
   just calls `chainExecutor.cancelAndPersist()`. Without it the OS gives you
   a hard SIGKILL with no cleanup.

### Library guarantees

`KmpWorkManager` on iOS:

- ✅ Checkpoints `ChainProgress` after every step. Re-launch resumes from the
  last completed step, not from scratch.
- ✅ Wires `task.expirationHandler` to cancel the chain coroutine + flush
  progress to disk (best-effort within the ~hundreds-of-ms window).
- ✅ Emits `withTimeout` around `doAlarmWork` on Android with a default 8 s
  budget; on iOS each step in `ChainExecutor` is bounded.
- ❌ Cannot prevent `0x8badf00d` if your step itself blocks > 5 s. Split the
  step.

### Diagnosing `0x8badf00d`

Xcode → Window → Devices → View Device Logs → filter `<process>: <0x8badf00d>`.
Each entry includes the stack at kill time. Common patterns:

- Stack in `CFNetwork` → upload hung past the budget; add per-request timeout.
- Stack in `dispatch_semaphore_wait` → coroutine blocked on a `runBlocking`;
  refactor to suspend.
- Stack in your `expirationHandler` → handler itself is too slow; pre-build
  the cleanup work, don't allocate inside the handler.

---

## 3. Headless DI cold-start

### What's actually happening

When iOS wakes your app for a BGTask:

1. Process starts with `UIApplication.shared.applicationState == .background`.
2. `AppDelegate.application(_:didFinishLaunchingWithOptions:)` runs **with
   `launchOptions[.bluetoothCentrals] == nil`** — the OS doesn't tell you the
   wake reason. You may not even know you're in a BGTask context until later.
3. **No UI is created.** `UIWindow`, `UIScene`, view controllers — none of
   it. The app is "headless".
4. iOS expects `didFinishLaunching` to return quickly (~10 s). After that
   point, BGTask budget starts ticking against you.
5. If your DI graph (Koin module loading, Realm/SQLite init, Firebase config
   fetch) takes 8 s to initialise, you only have ~30 - 8 = 22 s of actual
   work budget left for the `BGAppRefreshTask`.

### What this feels like in production

- Production app with 40 Koin modules, all eager — cold-start to first useful
  work = 6.5 s on iPhone 11. On a `BGAppRefreshTask` that's already 22 % of
  your budget gone before the worker even starts.
- Firebase Remote Config blocking init for 4 s on cellular — task budget
  drains before any I/O happens.
- A `RealmConfiguration.defaultConfiguration` that triggers a migration —
  multi-second pause before the first DB query.

### Design rules

1. **Lazy-init everything not needed for BGTask.** UI theme manager,
   analytics SDK, third-party crash reporter — these have no business
   loading during a `BGAppRefreshTask`. Initialise them in
   `applicationDidBecomeActive` instead of `didFinishLaunching`.
2. **Detect BGTask context early.** A typical pattern:

   ```swift
   func application(
       _ application: UIApplication,
       didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
   ) -> Bool {
       let isHeadless = application.applicationState == .background
       AppContainer.bootstrap(headless: isHeadless)  // skip UI / analytics deps
       BGTaskScheduler.shared.register(forTaskWithIdentifier: "com.app.refresh", using: nil) { task in
           Task { await handleRefresh(task) }
       }
       return true
   }
   ```
3. **Measure cold-start.** Add a `os_signpost` around your DI bootstrap and
   audit it on a real device, not the simulator. Simulator cold-start is
   ~5× faster than a 4-year-old iPhone in Low Power Mode.
4. **Never trigger network I/O during DI init.** Firebase Remote Config,
   feature-flag fetches, etc. should be background tasks of their own, not
   blocking dependencies of the DI graph.

### Library guarantees

`KmpWorkManager` on iOS:

- ✅ Koin module registration is small (one module, ~5 ms on iPhone 11). The
  library itself does not bloat your cold-start.
- ✅ `KmpWorkManager.initialize()` is idempotent and safe to call inside the
  BGTask handler if you want to defer it past `didFinishLaunching`.
- ❌ Cannot speed up your host app's DI graph. Audit your own bootstrap path.

---

## 4. ZIP codec absence (Kotlin/Native limitation)

### What's actually happening

Kotlin/Native does not ship a ZIP codec. The JVM has `java.util.zip`,
Kotlin/Native does not. `FileCompressionWorker.ios.kt` historically worked
around this by **silently copying the file uncompressed** — the worker
returned `Success`, the host app shipped an 80 MB "compressed" RAW upload to
the server, no one noticed until the bill arrived.

### Current behaviour (v2.5)

`FileCompressionConfig.allowIosUncompressedFallback`:

- `false` (default) — `FileCompressionWorker.ios` returns `WorkerResult.Failure`
  with an actionable message. The app must handle the failure explicitly.
- `true` — opt-in copy fallback (the pre-v2.5 behaviour, but now explicit).

This is a correctness-vs-convenience trade-off; the default favours
correctness.

### Future direction (v2.6+)

The Apple SDK exposes ZIP via:

- `Compression` framework (`compression_encode_buffer` / `compression_decode_buffer`)
  — fast, in-memory, but block-based; not a streaming ZIP format. Good for
  per-file LZMA/Brotli compression inside a tarball, not a ZIP archive.
- `Foundation`'s undocumented `_NSZipArchive` — private API, App Store
  rejection risk.
- Third-party Swift packages (ZIPFoundation, SSZipArchive) — usable but
  require adding a Swift dependency to the iOS host and exposing it via
  Kotlin/Native cinterop.

The plan documented in `ROADMAP.md` v2.6 is **cinterop to `libz` (zlib)**
which is part of the iOS SDK at `/usr/lib/libz.dylib`, plus a small Kotlin
wrapper that emits ZIP file format (local file headers + central directory +
end-of-central-directory record). zlib's `deflate` handles the compressed
payload; the ZIP container is ~150 lines of byte-pushing code.

Until this lands, **camera apps that need real compression on iOS** should:

1. Set `allowIosUncompressedFallback = false` (the default).
2. Handle the failure by either:
   - Implementing compression in Swift host code (using ZIPFoundation in
     your app), and invoking the KMP chain only for the upload step, OR
   - Skipping compression on iOS and uploading uncompressed (often
     acceptable for already-compressed media like H.264 video / JPEG).

### Library guarantees

`KmpWorkManager` on iOS:

- ✅ `FileCompressionWorker.ios` fails fast by default — no silent corruption.
- ⏳ Real ZIP via zlib cinterop tracked in v2.6 roadmap.
- ❌ Cannot use `java.util.zip` on iOS. K/N has no JVM stdlib.

---

## Summary table

| Limit | Hard ceiling | Library can mitigate? | Workaround |
|---|---|---|---|
| Opportunistic scheduling | OS decides, not us | No | Don't use BGTask for alarms / realtime; use `UNUserNotification` / silent push |
| Time budget | ~30 s App Refresh, few min Processing | Partial (checkpointing, expirationHandler) | Granular steps ≤ 5 s; prefer `BGProcessingTaskRequest` |
| Headless DI cold-start | ~10 s before budget starts ticking | No | Lazy-init non-BGTask deps; measure cold-start on real devices |
| No ZIP codec | K/N stdlib gap | Yes (fail-fast default) | Use Swift host for compression, or wait for v2.6 zlib cinterop |

---

## When in doubt

> "If your feature breaks when iOS decides not to wake the app, your feature
> should not have been a BGTask in the first place."

For deterministic schedules — alarms, exam reminders, prayer-time
notifications, scheduled-message delivery — use `UNUserNotificationCenter`.
The notification fires at the scheduled time without your app running at all;
iOS handles it. When the user taps the notification, your app opens and you
can do real work then.

Use BGTask for *opportunistic, best-effort* work where "eventually" is good
enough.
