# Real Device Testing Guide - KMP WorkManager
**Version:** 2.3.4
**Date:** February 26, 2026
**Purpose:** Ensure 100% reliability on production devices

---

## Table of Contents
1. [Overview](#overview)
2. [Test Device Matrix](#test-device-matrix)
3. [Android Real Device Tests](#android-real-device-tests)
4. [iOS Real Device Tests](#ios-real-device-tests)
5. [Performance Benchmarks](#performance-benchmarks)
6. [Battery Impact Tests](#battery-impact-tests)
7. [Network Condition Tests](#network-condition-tests)
8. [Doze Mode & App Standby Tests](#doze-mode-tests)
9. [Chinese ROM Tests](#chinese-rom-tests)
10. [Automated Test Execution](#automated-test-execution)

---

## Overview

### Why Real Device Testing?

**Emulators vs Real Devices:**
```
Aspect                | Emulator      | Real Device
----------------------|---------------|------------------
Background execution  | ✅ Reliable   | ⚠️ Varies by OEM
Battery optimization  | ❌ Simulated  | ✅ Real behavior
Doze mode            | ❌ Partial    | ✅ Aggressive
Network conditions   | ❌ Perfect    | ✅ Real-world
OEM modifications    | ❌ None       | ✅ MIUI, EMUI, etc.
System resources     | ❌ Unlimited  | ✅ Constrained
```

**Critical Issues Only Found on Real Devices:**
- Chinese ROM aggressive task killers (MIUI, EMUI, ColorOS)
- iOS background time budget exhaustion
- Network flakiness causing task failures
- Battery optimization breaking periodic tasks
- System-level doze mode interference

---

## Test Device Matrix

### Recommended Test Devices

**Android Devices (Minimum 5 devices):**

| Device | OS Version | ROM Type | Priority | Reason |
|--------|-----------|----------|----------|---------|
| Pixel 8 | Android 14 | Stock | ✅ Critical | Pure Android baseline |
| Samsung S23 | Android 14 | OneUI 6 | ✅ Critical | Most popular globally |
| Xiaomi 13 | Android 13 | MIUI 14 | ✅ Critical | Aggressive background restrictions |
| OnePlus 11 | Android 14 | OxygenOS | ⚠️ High | Popular, moderate restrictions |
| Huawei P50 | Android 11 | EMUI 12 | ⚠️ Medium | Extremely restrictive (if available) |
| Oppo Find X6 | Android 13 | ColorOS | ⚠️ Medium | Chinese ROM testing |

**iOS Devices (Minimum 3 devices):**

| Device | OS Version | Priority | Reason |
|--------|-----------|----------|---------|
| iPhone 15 Pro | iOS 17.x | ✅ Critical | Latest hardware/software |
| iPhone 13 | iOS 16.x | ✅ Critical | Common in market |
| iPhone 11 | iOS 15.x | ⚠️ Medium | Older device, battery constraints |

**Budget Alternative:**
- Rent devices from AWS Device Farm, Firebase Test Lab, or BrowserStack

---

## Android Real Device Tests

### Setup Instructions

**1. Enable Developer Options & USB Debugging:**
```bash
# On device:
Settings → About Phone → Tap "Build Number" 7 times
Settings → Developer Options → USB Debugging → Enable
```

**2. Install Test App:**
```bash
# Connect device via USB
adb devices  # Verify device is connected

# Install debug APK
./gradlew :composeApp:installDebug

# Or use Android Studio:
Run → Select Device → Run 'app'
```

**3. Grant Required Permissions:**
```bash
# Grant exact alarm permission (Android 12+)
adb shell appops set dev.brewkits.kmpworkmanager.sample SCHEDULE_EXACT_ALARM allow

# Grant notification permission
adb shell pm grant dev.brewkits.kmpworkmanager.sample android.permission.POST_NOTIFICATIONS

# Grant battery optimization exemption (for testing)
adb shell dumpsys deviceidle whitelist +dev.brewkits.kmpworkmanager.sample
```

### Test Suite 1: Background Execution Reliability

**Test Case 1.1: One-Time Task Execution**
```kotlin
// Manual Test Steps:
1. Open app on real device
2. Schedule task with 10-second delay:
   scheduler.enqueue(
       id = "test-one-time",
       trigger = TaskTrigger.OneTime(initialDelayMs = 10_000),
       workerClassName = "LogWorker"
   )
3. Lock screen and wait 15 seconds
4. Unlock device
5. Check logs: adb logcat | grep "LogWorker"

✅ PASS: Task executed within 10-15 seconds
❌ FAIL: Task didn't execute or delayed >30 seconds
```

**Test Case 1.2: Periodic Task Execution**
```kotlin
// Schedule periodic task (15 min interval - WorkManager minimum)
scheduler.enqueue(
    id = "test-periodic",
    trigger = TaskTrigger.Periodic(intervalMs = 15 * 60 * 1000),
    workerClassName = "PeriodicLogWorker"
)

// Test Steps:
1. Schedule task
2. Leave device unlocked for 20 minutes
3. Monitor logs every 5 minutes:
   adb logcat -c  # Clear logs
   # Wait 15 minutes
   adb logcat | grep "PeriodicLogWorker"

✅ PASS: Task runs every ~15 minutes (±5 min tolerance)
❌ FAIL: Task doesn't run or interval >30 minutes
```

**Test Case 1.3: Task Chain Execution**
```kotlin
// Test multi-step chain
scheduler.beginWith(
    TaskRequest(workerClassName = "Step1Worker")
).then(
    TaskRequest(workerClassName = "Step2Worker")
).then(
    TaskRequest(workerClassName = "Step3Worker")
).enqueue()

// Test Steps:
1. Schedule chain
2. Monitor logs for sequential execution:
   adb logcat | grep -E "Step1Worker|Step2Worker|Step3Worker"

✅ PASS: All steps execute in order without gaps
❌ FAIL: Steps skip or execute out of order
```

### Test Suite 2: Constraint Handling

**Test Case 2.1: Network Constraint**
```kotlin
scheduler.enqueue(
    id = "test-network",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "NetworkWorker",
    constraints = Constraints(requiresNetwork = true)
)

// Test Steps:
1. Turn OFF WiFi and mobile data
2. Schedule task
3. Verify task is BLOCKED (not running)
4. Turn ON WiFi
5. Verify task starts within 30 seconds

✅ PASS: Task waits for network and runs when available
❌ FAIL: Task runs without network or never runs
```

**Test Case 2.2: Charging Constraint**
```kotlin
scheduler.enqueue(
    id = "test-charging",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "ChargingWorker",
    constraints = Constraints(requiresCharging = true)
)

// Test Steps:
1. Unplug device (ensure not charging)
2. Schedule task
3. Verify task is BLOCKED
4. Plug in charger
5. Verify task starts within 30 seconds

✅ PASS: Task waits for charging and runs when plugged in
❌ FAIL: Task runs while unplugged or never runs
```

**Test Case 2.3: Battery Not Low Constraint**
```kotlin
scheduler.enqueue(
    id = "test-battery",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "BatteryWorker",
    constraints = Constraints(
        systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW)
    )
)

// Test Steps:
1. Drain battery below 15% (or use adb to simulate)
2. Schedule task
3. Verify task is BLOCKED
4. Charge battery above 15%
5. Verify task runs

✅ PASS: Task respects battery level
❌ FAIL: Task runs with low battery or never runs
```

### Test Suite 3: Doze Mode & App Standby

**Test Case 3.1: Light Doze Mode**
```bash
# Enable Light Doze Mode
adb shell dumpsys deviceidle force-idle light

# Schedule task
adb shell am broadcast -a dev.brewkits.kmpworkmanager.TEST_SCHEDULE

# Wait 5 minutes, check if task ran
adb logcat | grep "TestWorker"

# Exit Doze Mode
adb shell dumpsys deviceidle unforce

✅ PASS: Task deferred during Doze, runs when exited
❌ FAIL: Task cancelled or never runs
```

**Test Case 3.2: Deep Doze Mode**
```bash
# Enable Deep Doze Mode
adb shell dumpsys deviceidle force-idle deep

# Check device is in Doze
adb shell dumpsys deviceidle get deep
# Should output: IDLE

# Schedule task with OneTime trigger (should defer)
# Schedule task with Exact trigger (should break through Doze)

# Wait for maintenance window or exit Doze:
adb shell dumpsys deviceidle step deep

✅ PASS: OneTime tasks defer, Exact tasks wake device
❌ FAIL: All tasks cancelled or Exact tasks also defer
```

**Test Case 3.3: App Standby Buckets**
```bash
# Check current bucket
adb shell am get-standby-bucket dev.brewkits.kmpworkmanager.sample
# Possible: active (5), working_set (10), frequent (20), rare (30), restricted (40)

# Force app to RARE bucket (most restrictive)
adb shell am set-standby-bucket dev.brewkits.kmpworkmanager.sample rare

# Schedule periodic task (15 min interval)
# Observe actual execution interval

✅ PASS: Tasks still execute (may be deferred)
❌ FAIL: Tasks stop executing entirely
```

### Test Suite 4: Stress Tests

**Test Case 4.1: 100 Concurrent Tasks**
```kotlin
// Schedule 100 one-time tasks simultaneously
repeat(100) { index ->
    scheduler.enqueue(
        id = "stress-test-$index",
        trigger = TaskTrigger.OneTime(initialDelayMs = 1000),
        workerClassName = "StressTestWorker"
    )
}

// Monitor execution:
adb logcat | grep "StressTestWorker" | wc -l
# Should reach 100 within 5 minutes

✅ PASS: All 100 tasks execute successfully
❌ FAIL: Tasks drop or timeout
```

**Test Case 4.2: Long-Running Task (30 minutes)**
```kotlin
scheduler.enqueue(
    id = "long-running",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "LongRunningWorker",  // Runs for 30 min
    constraints = Constraints(requiresCharging = true)
)

// Test Steps:
1. Plug in device (to avoid battery optimization)
2. Schedule task
3. Monitor for 35 minutes
4. Check logs for completion

✅ PASS: Task completes after 30 minutes
❌ FAIL: Task killed before completion
```

**Test Case 4.3: Rapid Reschedule (1000 times)**
```kotlin
// Rapidly cancel and reschedule same task
repeat(1000) {
    scheduler.cancel("rapid-test")
    scheduler.enqueue(
        id = "rapid-test",
        trigger = TaskTrigger.OneTime(initialDelayMs = 60_000),
        workerClassName = "RapidWorker",
        policy = ExistingPolicy.REPLACE
    )
}

// Verify no crashes and final task is scheduled
✅ PASS: No crashes, task scheduled
❌ FAIL: Crash or task not scheduled
```

---

## iOS Real Device Tests

### Setup Instructions

**1. Install Test App:**
```bash
# Open Xcode project
open iosApp/iosApp.xcodeproj

# Select your device in Xcode
# Product → Destination → Your iPhone

# Build and Run
# Xcode will prompt for Developer Certificate - follow instructions
```

**2. Enable Background Modes:**
```
Xcode → Target → Signing & Capabilities → + Capability → Background Modes
✅ Background fetch
✅ Background processing
```

**3. Configure Info.plist:**
```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>dev.brewkits.kmpworkmanager.refresh</string>
    <string>dev.brewkits.kmpworkmanager.processing</string>
</array>
```

### Test Suite 1: BGTask Execution

**Test Case 1.1: BGAppRefreshTask (30-second window)**
```swift
// Schedule refresh task
let scheduler = KmpWorkManager.shared.backgroundTaskScheduler
scheduler.enqueue(
    id: "dev.brewkits.kmpworkmanager.refresh",
    trigger: TaskTrigger.OneTime(initialDelayMs: 0),
    workerClassName: "RefreshWorker",
    constraints: Constraints(),
    inputJson: nil,
    policy: ExistingPolicy.REPLACE
)

// Test Steps:
1. Schedule task in app
2. Close app (swipe up in app switcher)
3. Wait 15-30 minutes (iOS decides when to run)
4. Check Xcode console logs or device logs:
   # Connect device to Mac
   # Console.app → Select Device → Filter "KmpWorkManager"

⚠️ IMPORTANT: iOS execution is OPPORTUNISTIC - not guaranteed to run immediately

✅ PASS: Task executes within 1 hour (iOS schedules opportunistically)
❌ FAIL: Task never executes after 2 hours
```

**Test Case 1.2: BGProcessingTask (60-second window)**
```swift
scheduler.enqueue(
    id: "dev.brewkits.kmpworkmanager.processing",
    trigger: TaskTrigger.OneTime(initialDelayMs: 0),
    workerClassName: "ProcessingWorker",
    constraints: Constraints(requiresCharging: true),
    inputJson: nil,
    policy: ExistingPolicy.REPLACE
)

// Test Steps:
1. Schedule task
2. Plug in device to charger
3. Close app
4. Wait up to 1 hour
5. Check logs

✅ PASS: Task executes when charging (within 1-2 hours)
❌ FAIL: Task never executes
```

**Test Case 1.3: Force BGTask Execution (Xcode Simulator)**
```bash
# In Xcode debugger:
# Run app, set breakpoint, then execute:
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"dev.brewkits.kmpworkmanager.refresh"]

# This FORCES task to run immediately (testing only)

✅ PASS: Task executes immediately when forced
❌ FAIL: Task doesn't execute even when forced
```

### Test Suite 2: iOS Chain Execution

**Test Case 2.1: Chain with 5 Steps (Total <30s)**
```swift
scheduler.beginWith(
    task: TaskRequest(workerClassName: "Step1Worker")
).then(
    task: TaskRequest(workerClassName: "Step2Worker")
).then(
    task: TaskRequest(workerClassName: "Step3Worker")
).then(
    task: TaskRequest(workerClassName: "Step4Worker")
).then(
    task: TaskRequest(workerClassName: "Step5Worker")
).enqueue()

// Each step takes ~5 seconds = 25 seconds total

// Test Steps:
1. Schedule chain
2. Close app
3. Wait for BGTask to trigger (15-60 min)
4. Check logs - all 5 steps should complete

✅ PASS: All 5 steps complete in sequence
❌ FAIL: Chain stops mid-execution or times out
```

**Test Case 2.2: Chain Timeout Handling**
```swift
// Schedule chain that takes >30 seconds (should fail gracefully)
scheduler.beginWith(
    task: TaskRequest(workerClassName: "SlowWorker1")  // Takes 20s
).then(
    task: TaskRequest(workerClassName: "SlowWorker2")  // Takes 15s
).enqueue()

// Total: 35 seconds (exceeds BGAppRefreshTask 30s limit)

// Test Steps:
1. Schedule chain
2. Wait for execution
3. Check logs

✅ PASS: First step completes, second step times out gracefully (not crash)
❌ FAIL: App crashes or system kills app
```

### Test Suite 3: iOS Low Power Mode

**Test Case 3.1: Tasks in Low Power Mode**
```
// Test Steps:
1. Enable Low Power Mode:
   Settings → Battery → Low Power Mode → ON
2. Schedule BGAppRefreshTask
3. Close app
4. Wait 2 hours

✅ PASS: Task deferred until Low Power Mode disabled
❌ FAIL: Task executes in Low Power Mode (shouldn't)
```

**Test Case 3.2: Task Execution After Disabling Low Power Mode**
```
1. Schedule task in Low Power Mode
2. Wait 30 minutes (task blocked)
3. Disable Low Power Mode
4. Wait 15 minutes
5. Check if task executes

✅ PASS: Task executes shortly after disabling Low Power Mode
❌ FAIL: Task never executes
```

### Test Suite 4: iOS Background Time Budget

**Test Case 4.1: Monitor Time Budget Exhaustion**
```swift
// Check background time remaining during task execution
// In worker code:
import UIKit

class TimeBudgetWorker: IosWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        val remaining = UIApplication.shared.backgroundTimeRemaining
        print("⏰ Background time remaining: \(remaining) seconds")

        if remaining < 5.0 {
            // Less than 5 seconds - stop gracefully
            return WorkerResult.Failure(
                message = "Insufficient background time",
                shouldRetry = true
            )
        }

        // Do work...
        return WorkerResult.Success(message = "Completed")
    }
}

✅ PASS: Worker detects low time and exits gracefully
❌ FAIL: Worker killed by system without handling
```

---

## Performance Benchmarks

### Android Performance Tests

**Test Case P.1: Task Scheduling Latency**
```kotlin
// Measure time from enqueue() call to WorkManager acceptance
val startTime = System.nanoTime()
scheduler.enqueue(
    id = "perf-test",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "PerfWorker"
)
val endTime = System.nanoTime()
val latencyMs = (endTime - startTime) / 1_000_000

println("📊 Scheduling latency: ${latencyMs}ms")

✅ PASS: Latency <50ms (good performance)
⚠️ WARNING: Latency 50-100ms (acceptable)
❌ FAIL: Latency >100ms (performance issue)
```

**Test Case P.2: Task Execution Start Time**
```kotlin
// Schedule task with 1-second delay
val scheduleTime = System.currentTimeMillis()
scheduler.enqueue(
    id = "execution-perf",
    trigger = TaskTrigger.OneTime(initialDelayMs = 1000),
    workerClassName: "MeasureWorker"
)

// In MeasureWorker.doWork():
val executionTime = System.currentTimeMillis()
val actualDelay = executionTime - scheduleTime
println("📊 Actual delay: ${actualDelay}ms (expected: 1000ms)")

✅ PASS: Actual delay 1000-1200ms (±20% tolerance)
⚠️ WARNING: Actual delay 1200-2000ms
❌ FAIL: Actual delay >2000ms
```

**Test Case P.3: HTTP Task Performance**
```kotlin
// Measure HttpRequestWorker performance
val startTime = System.currentTimeMillis()
scheduler.enqueue(
    id = "http-perf",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "HttpRequestWorker",
    inputJson = """{"url": "https://api.example.com/fast"}"""
)

// In worker completion event:
val endTime = System.currentTimeMillis()
val duration = endTime - startTime
println("📊 HTTP task duration: ${duration}ms")

✅ PASS: Duration <500ms (fast network)
⚠️ WARNING: Duration 500-2000ms (acceptable)
❌ FAIL: Duration >5000ms (timeout or performance issue)
```

### iOS Performance Tests

**Test Case P.4: Chain Execution Time**
```swift
// Measure total chain execution time
let startTime = Date()
scheduler.beginWith(
    task: TaskRequest(workerClassName: "ChainStep1")
).then(
    task: TaskRequest(workerClassName: "ChainStep2")
).then(
    task: TaskRequest(workerClassName: "ChainStep3")
).enqueue()

// Log in last worker:
let endTime = Date()
let duration = endTime.timeIntervalSince(startTime)
print("📊 Chain execution: \(duration) seconds")

✅ PASS: Duration <15s for 3 steps
⚠️ WARNING: Duration 15-25s
❌ FAIL: Duration >25s (approaching 30s BGTask limit)
```

---

## Battery Impact Tests

### Android Battery Tests

**Test Case B.1: Overnight Periodic Task Impact**
```
Setup:
1. Fully charge device to 100%
2. Schedule periodic task (15 min interval)
3. Enable airplane mode (to isolate background task impact)
4. Leave device idle overnight (8 hours)
5. Check battery level in morning

Expected Results:
✅ PASS: Battery drain 0-5% (excellent)
⚠️ WARNING: Battery drain 5-10% (acceptable)
❌ FAIL: Battery drain >10% (excessive)
```

**Test Case B.2: Wakelock Analysis**
```bash
# Check wakelocks from WorkManager
adb shell dumpsys batterystats | grep -A 20 "Wake lock"

# Look for excessive wakelocks from your app package
# Should see minimal wakelock time (<1% total)

✅ PASS: App wakelock time <1% of total
❌ FAIL: App wakelock time >5% of total
```

### iOS Battery Tests

**Test Case B.3: Background Energy Impact**
```
Setup:
1. Settings → Battery → Enable "Battery %" display
2. Settings → Battery → Show battery usage per app
3. Schedule periodic BGAppRefreshTask
4. Use device normally for 24 hours
5. Check battery usage: Settings → Battery → Last 10 Days

Expected:
✅ PASS: App shows <5% battery usage
⚠️ WARNING: App shows 5-10% battery usage
❌ FAIL: App shows >10% battery usage

Apple's Guidelines:
- Background fetch should use <5% battery
- App should not appear in "High Battery Usage" list
```

---

## Network Condition Tests

### Test Case N.1: Slow Network (3G Speed)

**Android Setup:**
```bash
# Simulate 3G network using adb
adb shell settings put global network_speed_limit 384  # 384 kbps (3G speed)

# Or use Network Link Conditioner (on Mac):
brew install --cask network-link-conditioner
```

**Test:**
```kotlin
scheduler.enqueue(
    id = "http-slow-network",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "HttpDownloadWorker",
    inputJson = """{"url": "https://example.com/10mb.zip"}""",
    constraints = Constraints(requiresNetwork = true)
)

// Monitor execution time
✅ PASS: Task completes (may take longer)
⚠️ WARNING: Task timeout and retries
❌ FAIL: Task fails immediately without retry
```

### Test Case N.2: Network Loss Mid-Execution

**Test:**
```
1. Schedule HTTP download task
2. During execution, turn OFF WiFi and mobile data
3. Monitor task behavior

✅ PASS: Task detects network loss and retries gracefully
❌ FAIL: Task crashes or hangs
```

### Test Case N.3: Network Switching (WiFi → Mobile Data)

**Test:**
```
1. Connect to WiFi
2. Schedule task with requiresNetwork = true
3. During execution, turn OFF WiFi (device switches to mobile data)
4. Monitor task

✅ PASS: Task continues on mobile data without interruption
⚠️ WARNING: Task pauses briefly and resumes
❌ FAIL: Task fails when network switches
```

---

## Chinese ROM Tests

### Xiaomi MIUI Tests

**Test Case X.1: Autostart Permission**
```
Setup:
1. Settings → Apps → Manage Apps → [Your App]
2. Autostart → Enable
3. Battery Saver → No restrictions
4. Schedule periodic task (15 min)
5. Close app (swipe from recent apps)
6. Wait 30 minutes

✅ PASS: Task executes in background
❌ FAIL: Task doesn't execute (autostart blocked)
```

**Test Case X.2: Battery Optimization (MIUI Aggressive Mode)**
```
MIUI Settings:
Settings → Battery & performance → Choose apps
→ [Your App] → Battery saver → No restrictions

Without this:
❌ FAIL: App killed after 5 minutes in background

With this:
✅ PASS: Tasks continue to run
```

### Huawei EMUI Tests

**Test Case H.1: Protected Apps**
```
Setup:
1. Phone Manager → Protected Apps → [Your App] → Enable
2. Without this, app is killed in background within 2-3 minutes

✅ PASS: With "Protected Apps", tasks survive background
❌ FAIL: Without "Protected Apps", app killed immediately
```

---

## Automated Test Execution

### Android Automated Tests

**Run All Instrumented Tests:**
```bash
# Run all Android instrumented tests on connected device
./gradlew :kmpworker:connectedDebugAndroidTest

# Filter specific test class
./gradlew :kmpworker:connectedDebugAndroidTest \
  --tests "dev.brewkits.kmpworkmanager.AndroidExactAlarmTest"

# Generate HTML report
# Results: kmpworker/build/reports/androidTests/connected/index.html
```

**Run Tests on Firebase Test Lab (Multiple Devices):**
```bash
# Prerequisites:
# 1. Create Firebase project
# 2. Install gcloud CLI: https://cloud.google.com/sdk/docs/install

# Build test APK
./gradlew :kmpworker:assembleDebugAndroidTest

# Run on Firebase Test Lab (20+ real devices)
gcloud firebase test android run \
  --type instrumentation \
  --app composeApp/build/outputs/apk/debug/composeApp-debug.apk \
  --test kmpworker/build/outputs/apk/androidTest/debug/kmpworker-debug-androidTest.apk \
  --device model=Pixel6,version=33,locale=en,orientation=portrait \
  --device model=SM-G981B,version=30,locale=en,orientation=portrait \
  --device model=Mi9T,version=29,locale=en,orientation=portrait \
  --timeout 30m

# View results:
# https://console.firebase.google.com/project/YOUR_PROJECT/testlab/histories
```

### iOS Automated Tests

**Run XCTest on Real Device:**
```bash
# Prerequisites:
# 1. Connect iPhone via USB
# 2. Trust computer on device

# List available devices
xcodebuild -showdestinations \
  -scheme iosApp \
  -project iosApp/iosApp.xcodeproj

# Run tests on connected device
xcodebuild test \
  -scheme iosApp \
  -project iosApp/iosApp.xcodeproj \
  -destination 'platform=iOS,name=Your iPhone Name' \
  -resultBundlePath TestResults.xcresult

# View results
open TestResults.xcresult
```

**Run Tests on AWS Device Farm:**
```bash
# Prerequisites:
# 1. AWS Account with Device Farm access
# 2. Install AWS CLI: pip install awscli

# Create test package
xcodebuild \
  -scheme iosApp \
  -project iosApp/iosApp.xcodeproj \
  -destination generic/platform=iOS \
  -derivedDataPath build \
  -sdk iphoneos

# Upload to AWS Device Farm
aws devicefarm create-upload \
  --project-arn YOUR_PROJECT_ARN \
  --name iosApp.ipa \
  --type IOS_APP

# Schedule test run on 10+ real iOS devices
# See: https://docs.aws.amazon.com/devicefarm/latest/developerguide/
```

---

## Continuous Integration Setup

### GitHub Actions - Real Device Testing

**`.github/workflows/real-device-tests.yml`:**
```yaml
name: Real Device Tests

on:
  pull_request:
    branches: [main]
  schedule:
    - cron: '0 2 * * *'  # Run daily at 2 AM

jobs:
  android-real-devices:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'

      - name: Build APKs
        run: ./gradlew assembleDebug assembleDebugAndroidTest

      - name: Run on Firebase Test Lab
        uses: asadmansr/Firebase-Test-Lab-Action@v1.0
        with:
          arg-spec: 'firebase-test-lab-config.yml'
        env:
          SERVICE_ACCOUNT: ${{ secrets.FIREBASE_SERVICE_ACCOUNT }}

  ios-real-devices:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v3

      - name: Build iOS
        run: |
          cd iosApp
          xcodebuild -scheme iosApp -destination 'generic/platform=iOS' build

      - name: Run on AWS Device Farm
        uses: aws-actions/configure-aws-credentials@v1
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: us-west-2

      # Upload and run tests (see AWS Device Farm docs)
```

---

## Test Results Tracking

### Test Execution Checklist

**Before Each Release:**
- [ ] Run all Android instrumented tests on 5+ real devices
- [ ] Run all iOS XCTests on 3+ real iPhones
- [ ] Execute 24-hour battery impact test
- [ ] Test on Chinese ROMs (MIUI, EMUI, ColorOS)
- [ ] Verify Doze mode handling
- [ ] Test network condition scenarios
- [ ] Run stress tests (100+ concurrent tasks)
- [ ] Performance benchmarks meet targets

### Success Criteria

**Android:**
- ✅ 95%+ test pass rate on stock Android
- ✅ 70%+ test pass rate on Chinese ROMs (with user config)
- ✅ <50ms task scheduling latency
- ✅ <5% battery drain overnight (periodic tasks)
- ✅ Tasks survive Doze mode
- ✅ All constraints honored

**iOS:**
- ✅ 90%+ test pass rate (accounting for iOS opportunistic scheduling)
- ✅ Chains complete within 30s (BGAppRefreshTask)
- ✅ <5% battery usage per day
- ✅ Graceful handling of Low Power Mode
- ✅ No crashes on task timeout

---

## Troubleshooting Common Issues

### Android Issues

**Issue 1: Tasks don't run on Chinese ROM**
```
Solution:
1. Enable autostart permission
2. Disable battery optimization
3. Add to protected/locked apps list
4. See: docs/chinese-roms-guide.md
```

**Issue 2: Tasks killed in Doze mode**
```
Solution:
1. Use ExactAlarmManager for critical tasks
2. Request battery optimization exemption (use sparingly)
3. Educate users about Doze mode limitations
```

**Issue 3: Tasks delayed >30 minutes**
```
Diagnosis:
- Check app standby bucket:
  adb shell am get-standby-bucket [package]

Solution:
- If in RARE bucket, app needs more user engagement
- Android throttles background execution for rarely-used apps
```

### iOS Issues

**Issue 1: BGTasks never execute**
```
Diagnosis:
1. Check Info.plist has task identifiers
2. Verify Background Modes are enabled
3. Check device is plugged in (for BGProcessingTask)

Common Mistakes:
- Mismatched task ID (Info.plist vs code)
- App force-closed by user (cancels all BGTasks)
- Low Power Mode enabled
```

**Issue 2: Tasks timeout after 30 seconds**
```
Solution:
- Redesign tasks to complete <30s
- Use BGProcessingTask (60s) for longer work
- Break into multiple tasks if needed
- See: docs/ios-best-practices.md
```

---

## Summary

### Real Device Testing Workflow

```
1. Setup (1 hour)
   └── Install on 5+ Android devices
   └── Install on 3+ iOS devices
   └── Grant permissions

2. Execute Tests (4 hours)
   └── Run automated test suites
   └── Manual testing (network, battery, Doze)
   └── Chinese ROM testing

3. Monitor Results (24 hours)
   └── Background execution reliability
   └── Battery impact overnight
   └── Periodic task intervals

4. Analyze & Fix (2 hours)
   └── Review test failures
   └── Fix platform-specific issues
   └── Update documentation

Total Time: 8 hours + 24h monitoring = 32 hours per release
```

**Recommendation:** Run comprehensive real device tests before every major release (x.y.0) and spot-check on minor releases (x.y.z).

---

**Document Version:** 1.0
**Last Updated:** February 26, 2026
**Status:** Production Ready
