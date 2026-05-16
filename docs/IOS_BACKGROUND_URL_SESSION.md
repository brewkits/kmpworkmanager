# iOS Background URL Session integration (v2.5+)

This guide explains how to wire `IosBackgroundDownloadWorker` into an iOS host app so
downloads survive **full app termination** — not just suspension. The library cannot do
this by itself because iOS only delivers relaunch events to the app's `AppDelegate`.

## Why a background URL session

`BGTaskScheduler` (the default path used by every other worker in this library) gives
you ~30 s of wall-clock time per wake. That is fine for an API call, a small thumbnail,
or a metadata sync. It is **not** fine for a 500 MB raw photo or a multi-minute video
on cellular.

`URLSessionConfiguration.background(withIdentifier:)` is a separate iOS subsystem
managed by a daemon that survives:
- App suspension (locking the device, switching apps).
- App force-quit (user swipes the app away in the switcher).
- App crashes — the daemon is not in your process.
- OS reboots, in some cases — the OS resumes the task after the user logs back in.

When the download finishes, iOS relaunches the app long enough to deliver completion
events to a delegate. The library exposes
[`IosBackgroundUrlSessionManager`](../kmpworker/src/iosMain/kotlin/dev/brewkits/kmpworkmanager/workers/builtins/IosBackgroundUrlSessionManager.kt)
to handle that lifecycle.

## Setup

### 1. AppDelegate must forward background events to the library

Add the following method (or merge into your existing one):

```swift
// AppDelegate.swift
import KMPWorkManager // your generated framework name

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        // The library captures `completionHandler` and invokes it once iOS reports all
        // pending events for `identifier` have been delivered. Do NOT call it yourself.
        IosBackgroundUrlSessionManager.shared.handleBackgroundEvents(
            sessionIdentifier: identifier,
            completionHandler: completionHandler
        )
    }
}
```

If you forget this, iOS will not relaunch your app to deliver completion events and the
downloads will appear to "stall forever".

### 2. Enqueue with `IosBackgroundDownloadWorker`

From Kotlin (Compose-Multiplatform sample):

```kotlin
val config = IosBackgroundDownloadConfig(
    url = "https://cdn.example.com/raw/IMG_9999.dng",
    savePath = "$documents/IMG_9999.dng",
    sessionIdentifier = "com.example.cameraapp.bg.raw-import",
    isDiscretionary = false,        // start ASAP
    allowsCellularAccess = false,   // wait for Wi-Fi
    headers = mapOf("Authorization" to "Bearer $token")
)
scheduler.beginWith(
    TaskRequest(
        workerClassName = "IosBackgroundDownloadWorker",
        inputJson = json.encodeToString(config)
    )
).enqueue(id = "raw-${photo.id}")
```

`sessionIdentifier` should be a stable reverse-DNS string. Reusing the same identifier
across app launches lets iOS reconnect to the existing session and deliver any pending
events that fired while the app was dead.

### 3. Listen on `TaskEventBus` for completion

Unlike `HttpDownloadWorker`, the worker itself does **not** wait for the bytes to land.
It returns `Success` as soon as the OS accepts the request. To know when the file
actually appears on disk:

```kotlin
TaskEventBus.events
    .filter { it.taskName == "IosBackgroundDownloadWorker" }
    .collect { event ->
        if (event.success) {
            // event.message includes the resolved savePath
        } else {
            // Reschedule, surface to the user, etc.
        }
    }
```

## Limits and gotchas

| Behaviour | Notes |
|---|---|
| Single delegate queue per session | The library uses one `NSOperationQueue` per session identifier. Two `enqueueDownload` calls with the same `sessionIdentifier` share the queue. |
| Discretionary downloads | When `isDiscretionary = true`, iOS may delay the start by hours (waits for Wi-Fi + charging). Useful for non-urgent media backup. |
| Per-task timeout | The library forwards `timeoutMs` to `NSURLRequest.setTimeoutInterval`. The system daemon may extend it on its own retry path; treat it as a lower bound. |
| Foreground / background priority | When the app is foreground, the system runs the session at normal priority. When background, the daemon applies its own throttling. |
| File destination | Completed downloads land at the configured `savePath` via `NSFileManager.moveItemAtURL`. Any pre-existing file is removed first. |
| Authentication challenges | Currently the library does not implement `urlSession(_:task:didReceive:completionHandler:)` — clients that need Basic / Digest / TLS pinning must provide credentials in headers or extend `IosBackgroundUrlSessionManager`. |

## Operational checklist

- [ ] `application(_:handleEventsForBackgroundURLSession:completionHandler:)` is implemented.
- [ ] Each distinct background workload has its own `sessionIdentifier` (don't reuse one
      identifier for unrelated downloads — completion events get mingled).
- [ ] `Info.plist` has `UIBackgroundModes` containing `fetch` (or `processing`) so iOS
      grants the relaunch budget. (Many apps already have this for `BGTaskScheduler`.)
- [ ] Consumer subscribes to `TaskEventBus` early in the launch sequence — events fired
      during a relaunch arrive before the rest of the UI is mounted.
- [ ] Optionally call `IosBackgroundUrlSessionManager.sweepStaleStateOnLaunch()` from
      `didFinishLaunchingWithOptions` so abandoned state entries (downloads iOS gave up
      on after more than 7 days) are cleaned out. Without this, the state file grows
      slowly but unboundedly across years of installs.

## How cold-launch survival actually works (v2.5+)

A common assumption is that the URL session manager keeps an in-memory map of
`taskIdentifier → savePath`. **That assumption is wrong on iOS** — when the user
swipes the app away and iOS later relaunches it to deliver a completion event, the
in-memory map is empty. Before v2.5.0 this exact bug caused completed downloads
to be silently orphaned in `NSTemporaryDirectory`.

v2.5.0 backs the mapping with a JSON file at
`<AppSupport>/dev.brewkits.kmpworkmanager/bg_url_session_state.json`. The flow:

```
Time t=0    enqueueDownload() called
            ↓
            BackgroundDownloadStateStore.put(entry)   ← persisted FIRST
            ↓
            task.resume()                              ← daemon takes over

Time t=N    User swipes the app away
            ↓
            App process dies; in-memory maps wiped
            ↓
            Download keeps going inside nsurlsessiond (system daemon)

Time t=N+M  Download finishes
            ↓
            iOS cold-launches the app
            ↓
            AppDelegate forwards handleEventsForBackgroundURLSession
            ↓
            Session re-registered; delegate fires didFinishDownloadingToURL
            ↓
            getSync() reads the JSON file (cache was empty)
            ↓
            File moved synchronously to savePath
            ↓
            TaskCompletionEvent emitted
            ↓
            Entry removed from store
```

The synchronous disk read in step 4 is critical — iOS deletes the temporary
download file the moment the delegate method returns, so the move must finish
before the function exits. The store uses atomic writes (`writeToURL(atomically: true)`),
so a power loss mid-write keeps the previous version of the JSON file rather
than leaving a half-written one.

If the JSON file gets wiped between enqueue and completion (user reinstalls
the app, "Reset Settings", disk repair), the library logs a warning and the
file is intentionally orphaned — that's the price of self-healing rather than
crashing on missing state.

## Status

`IosBackgroundDownloadWorker` is experimental in v2.5. The single-direction download
path is covered; uploads via background URL session, authentication challenges, and
SSL pinning are tracked under v2.6 in [ROADMAP.md](./ROADMAP.md).
