# Android Foreground Service Type Guide

Android 14 (API 34) makes the `foregroundServiceType` mandatory for every
foreground service. Android 15 (API 35) introduces a new type
`mediaProcessing` specifically for image/video transcoding workloads. If your
worker declares the wrong type, the OS throws
`ForegroundServiceStartNotAllowedException` at runtime — no build-time warning.

This guide pins down the right type per camera-app workload, plus the manifest
snippets you need to paste into the host app.

## Pick the right type

| Workload | Type constant | Min API | Manifest permission |
|---|---|---|---|
| HTTP upload / download / sync | `FGS_DATA_SYNC` (default) | 29 | `FOREGROUND_SERVICE_DATA_SYNC` (API 34+) |
| Image / video transcoding | `FGS_MEDIA_PROCESSING` | 35 | `FOREGROUND_SERVICE_MEDIA_PROCESSING` |
| Audio / video playback | `FGS_MEDIA_PLAYBACK` | 29 | `FOREGROUND_SERVICE_MEDIA_PLAYBACK` |
| Camera capture session | `FGS_CAMERA` | 29 | `FOREGROUND_SERVICE_CAMERA` |
| GPS / geofence | `FGS_LOCATION` | 29 | `FOREGROUND_SERVICE_LOCATION` |
| Bluetooth peripheral | `FGS_CONNECTED_DEVICE` | 29 | `FOREGROUND_SERVICE_CONNECTED_DEVICE` |

Wrong-type symptoms in production:

- Android 14+ device, `dataSync` declared but `mediaProjection` actually used →
  `SecurityException: Starting FGS with type mediaProjection ... requires permissions`.
- Android 15+ device, image compression with `dataSync` → still runs, but Play
  Store policy review flags the app for misusing the type. Use `mediaProcessing`.
- `dataSync` on a 6+ hour upload — Android 15 limits `dataSync` to 6 hours of
  cumulative runtime per 24h window. Heavy nightly sync workloads hit this.

## 1. Override `foregroundServiceType` in your subclass

`KmpHeavyWorker.foregroundServiceType` defaults to `FGS_DATA_SYNC`. Override it:

```kotlin
// Image / video compression worker
class TranscodeWorker(
    appContext: Context,
    params: WorkerParameters,
    factory: AndroidWorkerFactory,
) : KmpHeavyWorker(appContext, params, factory) {

    override val foregroundServiceType: Int
        get() = if (Build.VERSION.SDK_INT >= 35) {
            FGS_MEDIA_PROCESSING
        } else {
            FGS_DATA_SYNC  // pre-Android-15 fallback
        }
}
```

The guard against `Build.VERSION.SDK_INT >= 35` is required —
`FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` is API 35+; declaring it on older
devices is a no-op at best, a crash at worst.

## 2. Host app manifest snippets

### `mediaProcessing` (Android 15+ transcoding)

```xml
<manifest ...>
    <!-- Android 14+ -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <!-- Android 15+: required when FGS type is mediaProcessing -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />

    <application>
        <!-- WorkManager's foreground service. Type MUST match what your worker passes
             to ForegroundInfo. Manifest type can be multi-valued ("dataSync|mediaProcessing"). -->
        <service
            android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync|mediaProcessing"
            tools:node="merge" />
    </application>
</manifest>
```

### `camera` (capture session continuing in background)

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.CAMERA" />  <!-- runtime-requested -->

<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync|camera"
    tools:node="merge" />
```

### `location` (geofence trigger / GPS upload)

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync|location"
    tools:node="merge" />
```

## 3. Runtime permission check (recommended)

Some FGS types require a runtime permission grant on top of the manifest entry.
For example, `FGS_CAMERA` does nothing without `CAMERA`. Verify before scheduling:

```kotlin
if (Build.VERSION.SDK_INT >= 34 && ActivityCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) != PackageManager.PERMISSION_GRANTED) {
    // Don't schedule — system would kill the service immediately.
    return
}
scheduler.enqueueTask(/* ... */)
```

## 4. Android 15 `dataSync` 6-hour cap

Android 15 enforces a cumulative 6-hour-per-day cap on `dataSync` FGS runtime.
When the cap is hit:

- `setForeground()` throws `ForegroundServiceTypeException` on subsequent runs.
- WorkManager marks the work as `FAILED` (does NOT retry).

For long-running uploads (camera RAW backup), prefer chunked work + a constraint
on `requiresUnmeteredNetwork` so each worker instance is short-lived. The
6-hour clock resets at midnight device-local time.

## 5. Manifest type bitmask

`android:foregroundServiceType` accepts a `|`-separated list. Declare every type
that any of your workers might use:

```xml
android:foregroundServiceType="dataSync|mediaProcessing|camera|connectedDevice"
```

The OS picks the intersection of the manifest declaration AND the type passed
to `ForegroundInfo`. Declaring extras in the manifest is harmless; missing one
is a crash.

## 6. Testing

There is no Robolectric stub for the type check — it's a system-level guard.
Integration testing approach:

1. Connect a real Android 14+ device.
2. `adb shell cmd appops set <pkg> START_FOREGROUND deny` to simulate a denied FGS.
3. `adb shell dumpsys activity processes <pkg>` after scheduling — check the
   `foregroundServiceType` field on the running service.
4. For Android 15 6-hour cap testing: `adb shell device_config put activity_manager_native_boot fgs_data_sync_max_duration_ms 60000` (drops the cap to 1 minute for the next boot).

## See also

- [Android docs — Foreground service types](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Android 15 dataSync cap](https://developer.android.com/about/versions/15/changes/datasync-timeout)
- `KmpHeavyWorker.foregroundServiceType` KDoc — overrideable in subclasses
