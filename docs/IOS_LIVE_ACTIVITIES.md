# iOS Live Activities & Dynamic Island Integration

**Requires:** iOS 16.2+ (ActivityKit), KMP WorkManager 3.2.0+

KMP WorkManager provides `IosLiveActivityBridge` — a thin Kotlin/Native bridge that
relays background worker progress (`WorkerProgress`) to Swift so your host app can
update Live Activities (Lock Screen widgets and Dynamic Island indicators) in real time.

> **Architecture principle:** Kotlin owns the data model and the background execution;
> Swift owns the `ActivityKit` types and the UI. No `ActivityKit` symbols leak into Kotlin.

---

## 1. Define your Live Activity Attributes in Swift

```swift
import ActivityKit
import Foundation

struct UploadAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        var progress: Double    // 0.0 – 1.0
        var message: String
    }

    var taskId: String          // Store the worker task ID for correlation
}
```

---

## 2. Start the Live Activity when the worker is enqueued

```swift
import ActivityKit

// On iOS 16.2+
func startLiveActivity(taskId: String) {
    guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }

    let attributes = UploadAttributes(taskId: taskId)
    let initialState = UploadAttributes.ContentState(progress: 0.0, message: "Starting…")

    let activity = try? Activity.request(
        attributes: attributes,
        content: .init(state: initialState, staleDate: nil)
    )
    self.currentActivity = activity
}
```

---

## 3. Wire IosLiveActivityBridge in your AppDelegate / SwiftUI scene

```swift
import KMPWorkManager  // Your Kotlin/Native framework

@main
struct MyApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

class AppDelegate: UIResponder, UIApplicationDelegate {

    var currentActivity: Activity<UploadAttributes>?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {

        // Wire up progress bridge for a specific task:
        IosLiveActivityBridge.shared.startObserving(taskId: "my-upload-task") { [weak self] progress in
            Task { @MainActor in
                guard let activity = self?.currentActivity else { return }
                let newState = UploadAttributes.ContentState(
                    progress: Double(progress.percentage) / 100.0,
                    message: progress.message ?? ""
                )
                await activity.update(using: newState)
            }
        }

        // Or listen to ALL tasks (pass null for taskId):
        // IosLiveActivityBridge.shared.startObserving(taskId: nil) { progress in ... }

        return true
    }
}
```

---

## 4. Stop the bridge when the task completes

```swift
// Call this from a TaskEventBus observer or after the worker finishes:
IosLiveActivityBridge.shared.stopObserving(taskId: "my-upload-task")

// End the Live Activity itself:
await currentActivity?.end(nil, dismissalPolicy: .immediate)
```

---

## 5. Emit progress from your worker (Kotlin)

The bridge automatically receives events emitted via `WorkerEnvironment.progressListener`:

```kotlin
// commonMain — inside your Worker.doWork()
override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
    for (i in 1..100) {
        doStep(i)
        env.progressListener?.onProgressUpdate(
            WorkerProgress(percentage = i, message = "Uploading $i%")
        )
    }
    return WorkerResult.Success("Done")
}
```

`IosLiveActivityBridge` subscribes to `TaskProgressBus` which receives these events
automatically — no additional wiring required.

---

## Platform Notes

| | Android | iOS |
|---|---|---|
| `IosLiveActivityBridge.startObserving()` | No-op (stub) | Active observer |
| `IosLiveActivityBridge.stopObserving()` | No-op (stub) | Cancels coroutine |
| `ActivityKit` | Not applicable | iOS 16.2+ |
| `Dynamic Island` | Not applicable | iPhone 14 Pro+ |

---

## Troubleshooting

**Q: Progress updates are not reaching my Live Activity.**
- Verify `ActivityAuthorizationInfo().areActivitiesEnabled` is `true` at the call site.
- Ensure the task ID passed to `startObserving` matches exactly the ID used in `scheduler.enqueue()`.
- Live Activity updates are throttled by iOS — you may see a few-second delay on battery-saving devices.

**Q: Bridge fires on a background thread.**
- `IosLiveActivityBridge` dispatches all callbacks on `Dispatchers.Main` (main thread). `Activity.update()` requires `@MainActor`, which is satisfied. If you see a threading warning, wrap in `Task { @MainActor in … }` as shown above.
