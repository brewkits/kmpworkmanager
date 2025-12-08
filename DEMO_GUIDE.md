# 🚀 KMP Task Manager Demo App Guide

This guide explains how to test and use the comprehensive demo app included in this project.

## 📱 Demo App Overview

The demo app showcases all features of KMP Task Manager across **6 interactive tabs**:

1. **Test & Demo** - Quick tests that work instantly in foreground
2. **Tasks** - Schedule background tasks with various triggers
3. **Chains** - Create sequential and parallel task workflows
4. **Alarms** - Exact alarms and push notifications
5. **Permissions** - Manage notification and alarm permissions
6. **Debug** - View all scheduled tasks and their status

## 🎯 Tab 1: Test & Demo (Quick Testing)

**Best for**: Testing features that work immediately without background execution

### Features:

#### 1. EventBus & Toast System
- **What it tests**: Event bus communication between workers and UI
- **How to use**: Click "Test EventBus → Toast"
- **Expected result**: Toast appears immediately with success message

#### 2. Simulated Worker Execution
- **What it tests**: Worker lifecycle (start → execute → complete)
- **How to use**:
  - Click "Simulate Upload Worker (2s)" or "Simulate Sync Worker (1.5s)"
- **Expected result**:
  - Progress toast appears
  - After delay, completion toast shows

#### 3. Task Scheduling
- **What it tests**: Native scheduler integration (WorkManager/BGTaskScheduler)
- **How to use**: Click "Schedule Task (Check Debug Tab)"
- **Expected result**:
  - Success toast appears
  - Go to Debug tab to verify task was scheduled

#### 4. Task Chain Simulation
- **What it tests**: Multi-step workflow execution
- **How to use**: Click "Simulate Task Chain (3.5s)"
- **Expected result**:
  - See 3 sequential step toasts
  - Final completion toast

#### 5. Failure Scenarios
- **What it tests**: Error handling and failure reporting
- **How to use**: Click "Simulate Failed Worker"
- **Expected result**: Error toast with failure message

## ⚙️ Tab 2: Tasks (Background Scheduling)

**Best for**: Testing actual background task execution

### One-Time Tasks

#### Run BG Task in 10s
- **Trigger**: OneTime (10 seconds delay)
- **Worker**: UPLOAD_WORKER
- **Test on Android**:
  1. Click button
  2. Wait 10 seconds
  3. Toast appears automatically
- **Test on iOS**:
  1. Click button
  2. Press Home button (app to background)
  3. Wait for iOS to execute
  4. Open app → See toast

#### Schedule Heavy Task (30s)
- **Trigger**: OneTime (5 seconds delay)
- **Worker**: HEAVY_PROCESSING_WORKER
- **Constraints**: isHeavyTask = true
- **Android**: Uses ForegroundService
- **iOS**: Uses BGProcessingTask (longer execution time)

#### Schedule Task with Network Constraint
- **Trigger**: OneTime (5 seconds delay)
- **Worker**: UPLOAD_WORKER
- **Constraints**: requiresNetwork = true
- **Android**: Only runs when connected to network
- **iOS**: Only supported for heavy tasks

### Periodic Tasks

#### Schedule Periodic Sync (15 min)
- **Trigger**: Periodic (15 minutes interval)
- **Worker**: SYNC_WORKER
- **Android**: Minimum 15 minutes enforced
- **iOS**: No guaranteed interval, system decides

### Advanced Triggers (Android Only)

#### Monitor Image Content Changes
- **Trigger**: ContentUri (MediaStore images)
- **Worker**: SYNC_WORKER
- **When it runs**: When new photos are added/changed
- **iOS**: Returns REJECTED_OS_POLICY

#### Run When Battery Is Okay
- **Trigger**: BatteryOkay
- **Worker**: SYNC_WORKER
- **When it runs**: Only when battery is not low
- **iOS**: Returns REJECTED_OS_POLICY

#### Run When Device Is Idle
- **Trigger**: DeviceIdle
- **Worker**: HEAVY_PROCESSING_WORKER
- **When it runs**: Screen off, not moving
- **iOS**: Returns REJECTED_OS_POLICY

### Task Management

- **Cancel Upload Task**: Cancel ONE_TIME_UPLOAD task by ID
- **Cancel Periodic**: Cancel PERIODIC_SYNC task
- **Cancel All Tasks**: Clear all pending work

## 🔗 Tab 3: Chains (Sequential & Parallel Workflows)

**Best for**: Testing complex task dependencies

### Run Sequential Chain
- **Flow**: Sync → Upload → Sync
- **Use case**: Tasks that must run in order
- **Example**: Download → Process → Upload

### Run Mixed Chain
- **Flow**: Sync → (Upload ∥ Heavy Processing) → Sync
- **Use case**: Parallel processing after initial setup
- **Android**: Uses WorkManager continuation API
- **iOS**: Custom chain executor with coroutines

### Run Parallel Start Chain
- **Flow**: (Sync ∥ Upload) → Sync
- **Use case**: Multiple independent tasks → Final aggregation
- **Example**: (Fetch API 1 ∥ Fetch API 2) → Merge results

## ⏰ Tab 4: Alarms (Exact Timing)

**Best for**: Testing time-critical notifications

### Schedule Reminder in 10s
- **Trigger**: Exact (10 seconds from now)
- **Worker**: "Reminder"
- **Android**:
  - Uses AlarmManager.setExactAndAllowWhileIdle()
  - Requires SCHEDULE_EXACT_ALARM permission on Android 12+
  - Can wake device from doze mode
- **iOS**:
  - Uses UNUserNotificationCenter
  - Shows local notification at exact time
  - Does not execute code in background

### Push Notifications
- **Android**: Send silent push → schedules task after 5s → shows notification
- **iOS**: Silent push with `content-available: 1` → triggers background task

#### Test on Android:
```bash
# Using adb
adb shell am broadcast -a com.google.android.c2dm.intent.RECEIVE \
  -n com.example.kmpworkmanagerv2/.push.PushReceiver \
  --es notification-type silent
```

#### Test on iOS:
```bash
# 1. Create push.apns file:
{
  "Simulator Target Bundle": "com.example.kmpworkmanagerv2",
  "aps": {
    "content-available": 1,
    "alert": {
      "title": "Background Task",
      "body": "Triggered"
    }
  }
}

# 2. Send push to simulator:
xcrun simctl push booted com.example.kmpworkmanagerv2 push.apns
```

## 🔐 Tab 5: Permissions

### Notification Permission
- **Required for**: Showing notifications from background tasks
- **Android**: Requested automatically on Android 13+
- **iOS**: Requested via UNUserNotificationCenter

### Exact Alarm Permission
- **Required for**: Exact timing alarms on Android 12+ (API 31+)
- **Android**: Opens system settings for SCHEDULE_EXACT_ALARM
- **iOS**: Not required (always granted)

## 🐛 Tab 6: Debug (Task Inspector)

**Best for**: Verifying tasks are scheduled correctly

### Features:
- **Task List**: All scheduled tasks with their status
- **Task ID**: Unique identifier for each task
- **Worker Class**: Which worker will execute
- **Status**: ENQUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED
- **Type**: Task trigger type
- **Flags**: Periodic, Chain, etc.

### How to use:
1. Schedule tasks in other tabs
2. Navigate to Debug tab
3. Click "Refresh" to update list
4. Verify your tasks appear with correct status

## 🧪 Testing Scenarios

### Scenario 1: Quick Functionality Test (1 minute)
1. Go to **Test & Demo** tab
2. Test EventBus → Toast
3. Simulate Upload Worker
4. Verify toasts appear correctly

### Scenario 2: Android Background Task (30 seconds)
1. Go to **Tasks** tab
2. Click "Run BG Task in 10s"
3. Wait 10 seconds
4. Toast appears automatically

### Scenario 3: iOS Background Task (Requires background mode)
1. Go to **Tasks** tab
2. Click "Run BG Task in 10s"
3. Press Home button (app to background)
4. Wait 15-60 seconds for iOS to execute
5. Open app → See completion toast

#### Or use Xcode LLDB:
```bash
# In Xcode console:
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"one-time-upload"]
```

### Scenario 4: Task Chain (Android & iOS)
1. Go to **Chains** tab
2. Click "Run Sequential Chain"
3. Go to **Debug** tab
4. Verify multiple tasks scheduled
5. Wait for execution
6. Check completion toasts

### Scenario 5: Exact Alarm
1. Go to **Permissions** tab
2. Grant Exact Alarm permission (Android only)
3. Go to **Alarms** tab
4. Click "Schedule Reminder in 10s"
5. Wait 10 seconds
6. Notification appears

### Scenario 6: Periodic Sync (Long-term test)
1. Go to **Tasks** tab
2. Click "Schedule Periodic Sync (15 min)"
3. Go to **Debug** tab → Verify task scheduled
4. Wait 15+ minutes
5. Check for completion toasts
6. Task automatically reschedules itself

## 📊 Workers Included

### Android Workers (WorkManager)
- **KmpWorker**: Generic worker handling SYNC_WORKER and UPLOAD_WORKER
- **KmpHeavyWorker**: Heavy processing with ForegroundService
- **AlarmReceiver**: Exact alarm receiver

### iOS Workers (BGTaskScheduler)
- **SyncWorker**: Data synchronization (BGAppRefreshTask)
- **HeavyProcessingWorker**: CPU-intensive work (BGProcessingTask)
- **UploadWorker**: File upload simulation (BGAppRefreshTask)

## 🎯 Expected Behaviors

### Android
- ✅ Tasks run reliably even in foreground
- ✅ Tasks survive app restart and device reboot
- ✅ Constraints (network, charging, battery) are enforced
- ✅ Periodic tasks repeat automatically
- ✅ Heavy tasks show persistent notification

### iOS
- ⚠️ Tasks only run in background (not when app is active)
- ⚠️ iOS decides when to run (not guaranteed)
- ⚠️ Force-quit by user may prevent execution
- ⚠️ Low Power Mode significantly delays tasks
- ⚠️ Periodic tasks must be manually rescheduled
- ✅ BGProcessingTask gets more execution time
- ✅ Network/charging constraints work for processing tasks

## 🔧 Troubleshooting

### Issue: No toast appears after scheduling (Android)
- **Solution**: Check Android Logcat for errors
- **Verify**: Go to Debug tab → Refresh → Check task status

### Issue: Task never runs (iOS)
- **Cause 1**: App still in foreground
  - **Solution**: Press Home button to background app
- **Cause 2**: Task ID not in Info.plist
  - **Solution**: Verify BGTaskSchedulerPermittedIdentifiers
- **Cause 3**: Low Power Mode enabled
  - **Solution**: Disable Low Power Mode or wait longer
- **Cause 4**: App force-quit by user
  - **Solution**: Don't force-quit, just background it

### Issue: Exact alarm not working (Android)
- **Cause**: Missing SCHEDULE_EXACT_ALARM permission
- **Solution**: Go to Permissions tab → Grant permission

### Issue: Network constraint not working (iOS)
- **Cause**: Only BGProcessingTask supports network constraint
- **Solution**: Use heavy task (isHeavyTask = true)

## 📝 Testing Checklist

Use this checklist to verify all features:

- [ ] EventBus & Toast system works
- [ ] Simulated workers execute correctly
- [ ] One-time tasks schedule successfully
- [ ] Heavy tasks run with correct constraints
- [ ] Network constraint is enforced
- [ ] Periodic tasks schedule and repeat
- [ ] Advanced Android triggers work (ContentUri, Battery, DeviceIdle)
- [ ] Task cancellation works (single & all)
- [ ] Sequential chains execute in order
- [ ] Mixed chains handle parallel tasks
- [ ] Parallel start chains work correctly
- [ ] Exact alarms trigger on time
- [ ] Notification permission can be granted
- [ ] Exact alarm permission can be granted (Android)
- [ ] Debug tab shows all scheduled tasks
- [ ] Refresh updates task list correctly
- [ ] Task status changes are reflected (ENQUEUED → RUNNING → SUCCEEDED)

## 🎓 Learning Outcomes

After testing this demo, you should understand:

1. **Differences between Android and iOS background execution**
2. **How to schedule tasks with various triggers**
3. **Task chains for complex workflows**
4. **Constraint-based execution**
5. **Permission handling for notifications and alarms**
6. **EventBus pattern for worker-UI communication**
7. **Platform-specific limitations and workarounds**

## 🚀 Next Steps

1. **Integrate into your app**: Copy workers and schedulers
2. **Customize workers**: Implement your business logic
3. **Add more constraints**: Battery, network, storage
4. **Create complex chains**: Multi-step workflows
5. **Monitor with Debug tab**: Track task execution
6. **Handle failures**: Implement retry logic with backoff

---

**Happy Testing!** 🎉

For more documentation, see [README.md](README.md)
