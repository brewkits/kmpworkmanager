# Troubleshooting Guide

Common issues and solutions for KMP WorkManager.

---

## Table of Contents

- [Installation Issues](#installation-issues)
- [Android Issues](#android-issues)
- [iOS Issues](#ios-issues)
- [Task Execution Issues](#task-execution-issues)
- [Performance Issues](#performance-issues)
- [Security Issues](#security-issues)
- [Build Issues](#build-issues)

---

## Installation Issues

### Dependency Resolution Failures

**Problem:** Gradle fails to resolve `dev.brewkits:kmpworkmanager`

```
Could not find dev.brewkits:kmpworkmanager:2.3.2
```

**Solutions:**
1. Verify Maven Central is in repositories:
   ```kotlin
   repositories {
       mavenCentral()
   }
   ```

2. Check version number is correct:
   ```kotlin
   implementation("dev.brewkits:kmpworkmanager:2.3.2") // Latest
   ```

3. Clear Gradle cache:
   ```bash
   ./gradlew clean
   ./gradlew --refresh-dependencies
   ```

### Koin Conflict

**Problem:** `KoinApplicationAlreadyStartedException`

**Solution:** KMP WorkManager uses isolated Koin scope. If conflict occurs:
```kotlin
// Don't initialize Koin manually for KmpWorkManager
// The library handles its own Koin instance

// Your app's Koin is separate:
startKoin {
    modules(myAppModule)
}
```

---

## Android Issues

### Tasks Not Executing

**Problem:** Tasks scheduled but never run

**Debugging Steps:**

1. **Check Constraints:**
   ```kotlin
   // Verify constraints are met
   val constraints = Constraints(
       requiresNetwork = true, // Is network available?
       requiresCharging = false // Don't require charging for testing
   )
   ```

2. **Check Logcat:**
   ```bash
   adb logcat | grep "KMP_"
   ```

3. **Verify WorkManager Status:**
   ```bash
   adb shell dumpsys jobscheduler | grep kmpwork
   ```

**Common Causes:**
- Device in Doze mode
- Constraints not met (no network, battery low)
- App force-stopped by user
- Battery optimization enabled

**Solutions:**
- Disable battery optimization during development
- Use `setExactAndAllowWhileIdle()` for exact alarms
- Check WorkManager status in Settings

### Exact Alarms Not Working

**Problem:** Alarms don't fire at exact time

**Required Permission (Android 12+):**
```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

**Request Permission:**
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    val alarmManager = getSystemService(AlarmManager::class.java)
    if (!alarmManager.canScheduleExactAlarms()) {
        // Request permission
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        startActivity(intent)
    }
}
```

### Heavy Tasks Failing

**Problem:** `ForegroundServiceStartNotAllowedException` on Android 12+

**Required Setup:**
```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge" />
```

**Usage:**
```kotlin
scheduler.enqueue(
    id = "heavy-task",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "HeavyWorker",
    constraints = Constraints(isHeavyTask = true) // Enable foreground service
)
```

---

## iOS Issues

### Tasks Never Execute

**Problem:** Background tasks scheduled but iOS never runs them

**Common Causes:**
1. **App Force-Quit:** iOS cancels all BGTasks when app is force-quit
2. **Low Power Mode:** Severely delays or prevents background execution
3. **Task Not Registered:** Missing `BGTaskSchedulerPermittedIdentifiers` in Info.plist
4. **Insufficient Time:** Task requires more time than iOS allows (30s refresh, 60s processing)

**Solutions:**

1. **Verify Info.plist Registration:**
   ```xml
   <key>BGTaskSchedulerPermittedIdentifiers</key>
   <array>
       <string>your-task-id</string>
   </array>
   ```

2. **Test in Simulator:**
   ```bash
   # Simulate task launch in Xcode debugger
   e -l objc -- (void)[[BGTaskScheduler sharedScheduler] \
     _simulateLaunchForTaskWithIdentifier:@"your-task-id"]
   ```

3. **Check System Conditions:**
   - Device is plugged in (helps)
   - Not in Low Power Mode
   - App not force-quit
   - Good cellular/Wi-Fi connection

4. **Use Longer Timeout:**
   ```kotlin
   // Use BGProcessingTask instead of BGAppRefreshTask
   val taskType = BGTaskType.PROCESSING // 5-10 min instead of 30s
   ```

### Chains Fail After 30 Seconds

**Problem:** Long task chains aborted mid-execution

**Solution:** Chains are batched. Use continuation callback (v2.3.1+):
```kotlin
val executor = ChainExecutor(
    workerFactory = factory,
    taskType = BGTaskType.PROCESSING, // Longer timeout
    onContinuationNeeded = {
        // Schedule next BGTask to continue chain
        val request = BGProcessingTaskRequest(identifier: "chain-continuation")
        BGTaskScheduler.shared.submit(request)
    }
)
```

### iOS Simulator Not Working

**Problem:** Tasks don't run in iOS Simulator

**Note:** BGTaskScheduler behavior differs in simulator:
- Tasks must be manually simulated
- Timing is not realistic
- Use device for realistic testing

**Simulator Testing:**
```bash
# In Xcode LLDB console during debug:
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] \
  _simulateLaunchForTaskWithIdentifier:@"task-id"]
```

---

## Task Execution Issues

### Worker Not Found

**Problem:** `Worker factory returned null for: MyWorker`

**Cause:** Worker not registered in factory

**Android Solution:**
```kotlin
class MyWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "MyWorker" -> MyWorker() // Add this line
            else -> null
        }
    }
}
```

**iOS Solution:**
```kotlin
class MyWorkerFactory : IosWorkerFactory {
    override fun createWorker(workerClassName: String): IosWorker? {
        return when (workerClassName) {
            "MyWorker" -> MyWorker() // Add this line
            else -> null
        }
    }
}
```

### Task Chains Not Completing

**Problem:** Chain stops at certain step

**Debugging:**
```kotlin
// Enable verbose logging
Logger.setMinLevel(LogLevel.DEBUG)

// Check chain progress (iOS)
val progress = fileStorage.loadChainProgress(chainId)
Logger.d("Chain progress: ${progress?.currentStep}/${progress?.totalSteps}")
```

**Common Causes:**
- Worker returns `WorkerResult.Failure`
- Exception thrown in worker
- Timeout exceeded

**Solution:**
```kotlin
class MyWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        return try {
            // Your work here
            WorkerResult.Success()
        } catch (e: Exception) {
            Logger.e("MyWorker failed", e)
            WorkerResult.Failure(e.message ?: "Unknown error")
        }
    }
}
```

### URL Validation Failures

**Problem:** `Invalid or unsafe URL` error

**Cause:** SSRF protection blocking URL

**Check URL:**
```kotlin
if (!SecurityValidator.validateURL(url)) {
    // URL is blocked
    // Reason: localhost, private IP, or invalid scheme
}
```

**Allowed:**
- `https://api.example.com`
- `http://public-domain.com`
- Public IPs (8.8.8.8, etc.)

**Blocked:**
- `http://localhost`
- `http://127.0.0.1`
- `http://10.0.0.1` (private IP)
- `http://192.168.x.x` (private network)
- `file:///path` (invalid scheme)

**Solution:** Use public URLs only, or whitelist specific internal URLs in your code

---

## Performance Issues

### Slow Task Execution

**Problem:** Tasks take longer than expected

**Android Debugging:**
```bash
# Check WorkManager workers
adb shell dumpsys activity service WorkManagerService

# Monitor task execution
adb logcat | grep "KmpWorker"
```

**iOS Debugging:**
```kotlin
// Check execution metrics
val metrics = executor.getExecutionMetrics()
Logger.i("Duration: ${metrics.duration}ms")
Logger.i("Time usage: ${metrics.timeUsagePercentage}%")
```

**Common Causes:**
- Network latency
- Large file operations
- Inefficient worker logic
- CPU-intensive operations

**Solutions:**
1. Profile worker code
2. Use chunked operations for large data
3. Add progress reporting
4. Optimize algorithms

### High Memory Usage

**Problem:** App crashes with OOM

**Check:**
```kotlin
// iOS queue size
val queueSize = fileStorage.getQueueSize()
if (queueSize > 1000) {
    Logger.w("Large queue: $queueSize chains")
}
```

**Solutions:**
1. Limit queue size (v2.3.1+ uses O(1) memory)
2. Reduce file sizes (100MB upload limit)
3. Clear completed chains
4. Use pagination for large operations

---

## Security Issues

### SSRF Attack Blocked

**Problem:** Legitimate internal API blocked

**Workaround:**
```kotlin
// Option 1: Use public domain/IP
url = "https://public-api.yourcompany.com"

// Option 2: Extend SecurityValidator (advanced)
object MySecurityValidator {
    fun validateURL(url: String): Boolean {
        // Add your custom whitelist
        if (url.startsWith("https://internal.mycompany.com")) {
            return true
        }
        return SecurityValidator.validateURL(url)
    }
}
```

**Note:** Carefully consider security implications before whitelisting internal URLs

### Path Traversal Blocked

**Problem:** Valid file path rejected

**Check:**
```kotlin
val path = "/valid/path/file.txt"
if (!SecurityValidator.validateFilePath(path)) {
    // Contains ".." or invalid characters
}
```

**Solution:** Use absolute paths without ".." components

---

## Build Issues

### Compilation Errors

**Problem:** Kotlin compilation fails

**Common Causes:**
1. Kotlin version mismatch
2. Missing expect/actual implementations
3. Conflicting dependencies

**Solutions:**
```kotlin
// Verify Kotlin version (2.1.21+)
kotlin {
    jvmToolchain(17)
}

// Clean and rebuild
./gradlew clean build
```

### Missing Platform Dependencies

**Problem:** `Cannot find symbol` on platform-specific code

**Solution:** Ensure proper source set configuration:
```kotlin
kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.work.runtime.ktx)
        }
        iosMain.dependencies {
            // iOS platform dependencies
        }
    }
}
```

---

## Getting Help

If your issue isn't covered here:

1. **Check Documentation:**
   - [README.md](../README.md)
   - [API Reference](api-reference.md)
   - [Architecture](ARCHITECTURE.md)

2. **Search Issues:**
   - [GitHub Issues](https://github.com/brewkits/kmpworkmanager/issues)

3. **Ask Community:**
   - [GitHub Discussions](https://github.com/brewkits/kmpworkmanager/discussions)

4. **Report Bug:**
   - Include: OS, version, logs, reproducible example
   - Use issue template

5. **Security Issues:**
   - Email: datacenter111@gmail.com
   - **Do not** post publicly

---

## Debug Checklist

Before asking for help, try:

- [ ] Enable verbose logging
- [ ] Check platform logs (Logcat/Console)
- [ ] Verify dependencies are up to date
- [ ] Test on physical device (not just simulator)
- [ ] Check permissions in manifest/Info.plist
- [ ] Review worker factory registration
- [ ] Verify constraints are met
- [ ] Clear app data and test fresh install
- [ ] Check for known issues in GitHub

---

**Last Updated:** February 16, 2026
**Version:** 2.3.1
