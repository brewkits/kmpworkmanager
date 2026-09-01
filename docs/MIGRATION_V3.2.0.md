# Migration to v3.2.0

Version `3.2.0` introduces a strict **"Opt-In Permissions"** architecture for Android to resolve unwarranted Google Play Store rejections (Issue #64).

## What changed?

Prior to `3.2.0`, the library automatically injected the `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` permissions, and the `SystemForegroundService` component into your app's `AndroidManifest.xml` via Gradle's Manifest Merger.

This caused apps that *never* actually used foreground services to get flagged and rejected by Google Play Store policies because the permissions were present in the final APK.

In `3.2.0`, **these permissions have been completely removed from the library's internal manifest.** 

## How to migrate

If you are using standard `KmpWorker` or `NativeTaskScheduler` for normal background jobs, **you don't need to do anything.** The Play Store will no longer block your app!

### If you use `KmpHeavyWorker` (Foreground Services)

If your app intentionally uses `KmpHeavyWorker` to run long-running tasks as a Foreground Service, your app will now crash with a `SecurityException` on Android 14+ because the permissions are missing. 

To fix this, you must explicitly declare the required permissions and the service tag in **your app's** `AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- 1. Explicitly request Foreground Service permissions -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <application>
        <!-- 2. Declare the WorkManager foreground service component -->
        <service
            android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync"
            tools:node="merge" />
            
        ...
    </application>
</manifest>
```

For advanced use cases like Camera or Media Processing, please see the full [Android FGS Type Guide](ANDROID_FGS_GUIDE.md).
