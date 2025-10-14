package com.example.kmpworkmanagerv2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.kmpworkmanagerv2.background.data.TaskIds
import com.example.kmpworkmanagerv2.background.data.WorkerTypes
import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.Constraints
import com.example.kmpworkmanagerv2.background.domain.TaskRequest
import com.example.kmpworkmanagerv2.background.domain.TaskTrigger
import com.example.kmpworkmanagerv2.background.domain.TaskEventBus
import com.example.kmpworkmanagerv2.background.domain.TaskCompletionEvent
import com.example.kmpworkmanagerv2.background.domain.ScheduleResult
import com.example.kmpworkmanagerv2.debug.DebugScreen
import com.example.kmpworkmanagerv2.push.FakePushNotificationHandler
import com.example.kmpworkmanagerv2.push.PushNotificationHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.mp.KoinPlatform.getKoin
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * The main entry point of the application.
 * This composable function is responsible for rendering the entire UI, handling dependencies
 * (via parameters or Koin), and managing core states like permissions.
 *
 * @param scheduler The platform-specific background task scheduler instance.
 * @param pushHandler The platform-specific push notification handler instance.
 */
@OptIn(ExperimentalTime::class, ExperimentalFoundationApi::class)
@Composable
fun App(
    // Dependencies are injected, defaulting to Koin lookup if not provided (for production code)
    scheduler: BackgroundTaskScheduler = getKoin().get(),
    pushHandler: PushNotificationHandler = getKoin().get()
) {
    // State for holding the status text to be displayed on the UI.
    var statusText by remember { mutableStateOf("Requesting permissions...") }

    // Coroutine scope for launching asynchronous operations from UI events (e.g., button clicks).
    val coroutineScope = rememberCoroutineScope()

    // State for managing notification permission using the platform-specific implementation.
    val notificationPermissionState = rememberNotificationPermissionState { isGranted ->
        statusText =
            if (isGranted) "Notification permission granted." else "Notification permission denied."
    }

    // State for managing exact alarm permission on Android (iOS implementation is a no-op/always true).
    val exactAlarmPermissionState = rememberExactAlarmPermissionState()

    // State for managing the horizontal pager (tab view).
    val pagerState = rememberPagerState(pageCount = { 6 })

    // Snackbar host state for showing toast messages
    val snackbarHostState = remember { SnackbarHostState() }

    // Listen for task completion events and show snackbar
    LaunchedEffect(Unit) {
        TaskEventBus.events.collect { event ->
            snackbarHostState.showSnackbar(
                message = event.message,
                duration = SnackbarDuration.Long
            )
        }
    }

    MaterialTheme {
        Scaffold(
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(
                        action = {
                            TextButton(onClick = { data.dismiss() }) {
                                Text("Close")
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text(data.visuals.message)
                    }
                }
            }
        ) { paddingValues ->
        Column(
            Modifier.fillMaxSize()
                // Apply padding for system bars (e.g., status bar, navigation bar)
                .padding(WindowInsets.systemBars.asPaddingValues())
        ) {
            // Tab bar for navigation
            TabRow(selectedTabIndex = pagerState.currentPage) {
                Tab(selected = pagerState.currentPage == 0, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } }) { Text("Test & Demo", modifier = Modifier.padding(16.dp)) }
                Tab(selected = pagerState.currentPage == 1, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } }) { Text("Tasks", modifier = Modifier.padding(16.dp)) }
                Tab(selected = pagerState.currentPage == 2, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } }) { Text("Chains", modifier = Modifier.padding(16.dp)) }
                Tab(selected = pagerState.currentPage == 3, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(3) } }) { Text("Alarms", modifier = Modifier.padding(16.dp)) }
                Tab(selected = pagerState.currentPage == 4, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(4) } }) { Text("Permissions", modifier = Modifier.padding(16.dp)) }
                Tab(selected = pagerState.currentPage == 5, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(5) } }) { Text("Debug", modifier = Modifier.padding(16.dp)) }
            }

            // Horizontal pager to host the different tab screens
            HorizontalPager(state = pagerState) {
                when (it) {
                    0 -> TestDemoTab(scheduler, coroutineScope, snackbarHostState)
                    1 -> TasksTab(scheduler, coroutineScope, statusText, snackbarHostState)
                    2 -> TaskChainsTab(scheduler, coroutineScope, snackbarHostState)
                    3 -> AlarmsAndPushTab(scheduler, coroutineScope, statusText, exactAlarmPermissionState, snackbarHostState)
                    4 -> PermissionsAndInfoTab(notificationPermissionState, exactAlarmPermissionState)
                    5 -> DebugScreen()
                }
            }
        }
        }
    }
}

/**
 * Test & Demo tab - Easy-to-test features that work in foreground
 */
@OptIn(ExperimentalTime::class)
@Composable
fun TestDemoTab(scheduler: BackgroundTaskScheduler, coroutineScope: CoroutineScope, snackbarHostState: SnackbarHostState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Quick Test & Demo", style = MaterialTheme.typography.headlineSmall)
        Text("All features here work instantly in foreground!", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("1. EventBus & Toast System", style = MaterialTheme.typography.titleLarge)
                InfoBox("Test the event bus system that workers use to communicate with UI.")
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            TaskEventBus.emit(
                                TaskCompletionEvent(
                                    taskName = "EventBus Test",
                                    success = true,
                                    message = "✅ EventBus is working! Toast displayed successfully."
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test EventBus → Toast")
                }
                Text("✓ Instantly shows toast message", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("2. Simulated Worker Execution", style = MaterialTheme.typography.titleLarge)
                InfoBox("Simulate a worker running and completing (like what happens in background).")
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                message = "⚙️ Worker started...",
                                duration = SnackbarDuration.Short
                            )

                            kotlinx.coroutines.delay(2000)

                            TaskEventBus.emit(
                                TaskCompletionEvent(
                                    taskName = "Upload Worker",
                                    success = true,
                                    message = "📤 Simulated: Uploaded 100MB successfully!"
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Simulate Upload Worker (2s)")
                }
                Text("✓ Shows progress → completion toast", style = MaterialTheme.typography.bodySmall)

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                message = "🔄 Syncing data...",
                                duration = SnackbarDuration.Short
                            )

                            kotlinx.coroutines.delay(1500)

                            TaskEventBus.emit(
                                TaskCompletionEvent(
                                    taskName = "Sync Worker",
                                    success = true,
                                    message = "🔄 Simulated: Data synced successfully!"
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Simulate Sync Worker (1.5s)")
                }
                Text("✓ Shows sync → success toast", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("3. Task Scheduling (Both Platforms)", style = MaterialTheme.typography.titleLarge)
                InfoBox("Schedule tasks on native schedulers. Check Debug tab to see scheduled tasks.")
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            val timestamp = Clock.System.now().toEpochMilliseconds()
                            scheduler.enqueue(
                                id = "demo-task-$timestamp",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 5000),
                                workerClassName = WorkerTypes.SYNC_WORKER
                            )
                            snackbarHostState.showSnackbar(
                                message = "✅ Task scheduled! Check Debug tab to verify.",
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Schedule Task (Check Debug Tab)")
                }
                Text("✓ Android: WorkManager | iOS: BGTaskScheduler", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("4. Task Chain Simulation", style = MaterialTheme.typography.titleLarge)
                InfoBox("Simulate a chain of workers executing sequentially.")
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            // Step 1
                            snackbarHostState.showSnackbar(
                                message = "🔗 Step 1/3: Syncing...",
                                duration = SnackbarDuration.Short
                            )
                            kotlinx.coroutines.delay(1000)

                            // Step 2
                            snackbarHostState.showSnackbar(
                                message = "🔗 Step 2/3: Uploading...",
                                duration = SnackbarDuration.Short
                            )
                            kotlinx.coroutines.delay(1500)

                            // Step 3
                            snackbarHostState.showSnackbar(
                                message = "🔗 Step 3/3: Final sync...",
                                duration = SnackbarDuration.Short
                            )
                            kotlinx.coroutines.delay(1000)

                            // Complete
                            TaskEventBus.emit(
                                TaskCompletionEvent(
                                    taskName = "Task Chain",
                                    success = true,
                                    message = "✅ Simulated: Chain completed! (Sync → Upload → Sync)"
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Simulate Task Chain (3.5s)")
                }
                Text("✓ Shows all 3 steps + completion", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("5. Failure Scenarios", style = MaterialTheme.typography.titleLarge)
                InfoBox("Test how the app handles failures and errors.")
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                message = "⚠️ Worker started...",
                                duration = SnackbarDuration.Short
                            )

                            kotlinx.coroutines.delay(1500)

                            TaskEventBus.emit(
                                TaskCompletionEvent(
                                    taskName = "Upload Worker",
                                    success = false,
                                    message = "❌ Simulated: Upload failed! Network error."
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Simulate Failed Worker")
                }
                Text("✓ Shows failure toast", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("💡 Testing Background Tasks (iOS)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "iOS BGTaskScheduler tasks only run in background:\n\n" +
                    "1. Schedule task in 'Tasks' tab\n" +
                    "2. Press Home button (app to background)\n" +
                    "3. Wait for iOS to execute\n" +
                    "4. Open app → See completion toast\n\n" +
                    "Or use Xcode LLDB:\n" +
                    "e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@\"one-time-upload\"]",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("💡 Testing Background Tasks (Android)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Android WorkManager runs even in foreground:\n\n" +
                    "1. Schedule task in 'Tasks' tab\n" +
                    "2. Wait for delay time\n" +
                    "3. Toast appears automatically\n\n" +
                    "Tasks run reliably with WorkManager!",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Composable for scheduling and managing background tasks (WorkManager/BGTaskScheduler).
 */
@Composable
fun TasksTab(scheduler: BackgroundTaskScheduler, coroutineScope: CoroutineScope, statusText: String, snackbarHostState: SnackbarHostState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("WorkManager / BGTaskScheduler", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Run a task once", style = MaterialTheme.typography.titleLarge)
                InfoBox("Schedule a task to run once in the future.")
                Spacer(modifier = Modifier.height(16.dp))
                // Button to schedule a regular one-time task
                Button(onClick = {
                    coroutineScope.launch {
                        val result = scheduler.enqueue(
                            id = TaskIds.ONE_TIME_UPLOAD,
                            trigger = TaskTrigger.OneTime(initialDelayMs = 10.seconds.inWholeMilliseconds),
                            workerClassName = WorkerTypes.UPLOAD_WORKER
                        )
                        snackbarHostState.showSnackbar(
                            message = "✅ Background task scheduled! Will run in 10s",
                            duration = SnackbarDuration.Short
                        )
                    }
                }) {
                    Text("Run BG Task in 10s")
                }
                Text("⚙️ Run a one-time background task after 10 seconds.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))

                // Button to schedule a heavy/processing task
                Button(onClick = {
                    coroutineScope.launch {
                        val result = scheduler.enqueue(
                            id = TaskIds.HEAVY_TASK_1,
                            trigger = TaskTrigger.OneTime(initialDelayMs = 5.seconds.inWholeMilliseconds),
                            workerClassName = WorkerTypes.HEAVY_PROCESSING_WORKER,
                            constraints = Constraints(isHeavyTask = true) // Mark as a heavy task for platform
                        )
                        val message = when (result) {
                            ScheduleResult.ACCEPTED -> "⚡ Heavy task scheduled! Will run in 5s"
                            ScheduleResult.REJECTED_OS_POLICY -> "❌ Task rejected by OS policy"
                            ScheduleResult.THROTTLED -> "⏳ Task throttled, will retry later"
                        }
                        snackbarHostState.showSnackbar(
                            message = message,
                            duration = SnackbarDuration.Short
                        )
                    }
                }) {
                    Text("Schedule Heavy Task (30s)")
                }
                Text("⚡ Run a heavy background task (Foreground Service / BGProcessingTask).", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                InfoBox("Note: Heavy tasks on iOS have a time limit (usually around 30 minutes) and require the device to be charging and connected to a network.")
                Spacer(modifier = Modifier.height(16.dp))

                // Button to schedule a task with network constraints
                Button(onClick = {
                    coroutineScope.launch {
                        val result = scheduler.enqueue(
                            id = "network-task",
                            trigger = TaskTrigger.OneTime(initialDelayMs = 5.seconds.inWholeMilliseconds),
                            workerClassName = WorkerTypes.UPLOAD_WORKER,
                            constraints = Constraints(requiresNetwork = true)
                        )
                        snackbarHostState.showSnackbar(
                            message = "🌐 Network task scheduled! Will run when connected",
                            duration = SnackbarDuration.Short
                        )
                    }
                }) {
                    Text("Schedule Task with Network Constraint")
                }
                Text("🌐 Schedule a task that requires a network connection.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                InfoBox("Note: On Android, this uses WorkManager's network constraints. The task will only run when the device has a network connection.\n\nNote: On iOS, network constraints are only supported for heavy tasks (BGProcessingTask). Regular tasks (BGAppRefreshTask) do not support this constraint.")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Run a task repeatedly", style = MaterialTheme.typography.titleLarge)
                InfoBox("Schedule a task to run periodically in the background.")
                Spacer(modifier = Modifier.height(16.dp))
                // Button to schedule a periodic task
                Button(onClick = {
                    coroutineScope.launch {
                        val result = scheduler.enqueue(
                            id = TaskIds.PERIODIC_SYNC_TASK,
                            trigger = TaskTrigger.Periodic(intervalMs = 15.minutes.inWholeMilliseconds),
                            workerClassName = WorkerTypes.SYNC_WORKER
                        )
                        snackbarHostState.showSnackbar(
                            message = "🔄 Periodic sync scheduled! Will run every 15 min",
                            duration = SnackbarDuration.Short
                        )
                    }
                }) {
                    Text("Schedule Periodic Sync (15 min)")
                }
                Text("🔄 Schedule a recurring task using BGTaskScheduler/WorkManager.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                InfoBox("Note: Periodic tasks on Android are not exact and may be delayed by Doze mode. The minimum interval is 15 minutes.\n\nNote: Periodic tasks on iOS are not guaranteed to run. The system decides when to run them based on app usage, battery, and network conditions. The minimum interval is not guaranteed.")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Advanced Triggers (Android Only)", style = MaterialTheme.typography.titleLarge)
                InfoBox("These triggers use Android-specific features and will be rejected on iOS.")
                Spacer(modifier = Modifier.height(16.dp))

                // ContentUri trigger
                Button(onClick = {
                    coroutineScope.launch {
                        val result = scheduler.enqueue(
                            id = "content-uri-task",
                            trigger = TaskTrigger.ContentUri(
                                uriString = "content://media/external/images/media",
                                triggerForDescendants = true
                            ),
                            workerClassName = WorkerTypes.SYNC_WORKER
                        )
                        val message = when (result) {
                            ScheduleResult.ACCEPTED -> "📸 ContentUri trigger scheduled! Will run when images change"
                            ScheduleResult.REJECTED_OS_POLICY -> "❌ ContentUri not supported on this platform (Android only)"
                            ScheduleResult.THROTTLED -> "⏳ Task throttled, will retry later"
                        }
                        snackbarHostState.showSnackbar(
                            message = message,
                            duration = SnackbarDuration.Short
                        )
                    }
                }) {
                    Text("Monitor Image Content Changes")
                }
                Text("📸 Triggers when MediaStore images change.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(8.dp))

                // BatteryOkay trigger
                Button(onClick = {
                    coroutineScope.launch {
                        val result = scheduler.enqueue(
                            id = "battery-okay-task",
                            trigger = TaskTrigger.BatteryOkay,
                            workerClassName = WorkerTypes.SYNC_WORKER
                        )
                        snackbarHostState.showSnackbar(
                            message = "🔋 BatteryOkay trigger scheduled! Will run when battery is not low",
                            duration = SnackbarDuration.Short
                        )
                    }
                }) {
                    Text("Run When Battery Is Okay")
                }
                Text("🔋 Only runs when battery is not low.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(8.dp))

                // DeviceIdle trigger
                Button(onClick = {
                    coroutineScope.launch {
                        val result = scheduler.enqueue(
                            id = "device-idle-task",
                            trigger = TaskTrigger.DeviceIdle,
                            workerClassName = WorkerTypes.HEAVY_PROCESSING_WORKER,
                            constraints = Constraints(isHeavyTask = true)
                        )
                        snackbarHostState.showSnackbar(
                            message = "💤 DeviceIdle trigger scheduled! Will run when device is idle",
                            duration = SnackbarDuration.Short
                        )
                    }
                }) {
                    Text("Run When Device Is Idle")
                }
                Text("💤 Only runs when device is idle (screen off, not moving).", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(8.dp))

                InfoBox("Note: These triggers are Android-only and use WorkManager's advanced constraints. ContentUri triggers fire when content changes, while BatteryOkay and DeviceIdle are constraint-based triggers.\n\nOn iOS, these triggers will be rejected with REJECTED_OS_POLICY status.")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Task Management", style = MaterialTheme.typography.titleLarge)
                InfoBox("Cancel scheduled tasks or clear all pending work.")
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Cancel specific task
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                scheduler.cancel(TaskIds.ONE_TIME_UPLOAD)
                                snackbarHostState.showSnackbar(
                                    message = "🚫 Cancelled ONE_TIME_UPLOAD task",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel Upload Task", style = MaterialTheme.typography.bodySmall)
                    }

                    // Cancel periodic task
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                scheduler.cancel(TaskIds.PERIODIC_SYNC_TASK)
                                snackbarHostState.showSnackbar(
                                    message = "🚫 Cancelled PERIODIC_SYNC task",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel Periodic", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Cancel all tasks
                Button(
                    onClick = {
                        coroutineScope.launch {
                            scheduler.cancelAll()
                            snackbarHostState.showSnackbar(
                                message = "🚫 All tasks cancelled!",
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Cancel All Tasks")
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("⚠️ Cancel specific tasks by ID or clear all pending work.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Composable for demonstrating task chaining functionality.
 */
@Composable
fun TaskChainsTab(scheduler: BackgroundTaskScheduler, coroutineScope: CoroutineScope, snackbarHostState: SnackbarHostState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Task Chains", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Sequential & Parallel Task Execution", style = MaterialTheme.typography.titleLarge)
                InfoBox("Task chains allow you to execute multiple tasks in sequence or in parallel. This is useful for complex workflows that require multiple steps.")
                Spacer(modifier = Modifier.height(16.dp))

                // Example 1: Simple Sequential Chain
                Button(onClick = {
                    coroutineScope.launch {
                        scheduler.beginWith(TaskRequest(workerClassName = WorkerTypes.SYNC_WORKER))
                            .then(TaskRequest(workerClassName = WorkerTypes.UPLOAD_WORKER))
                            .then(TaskRequest(workerClassName = WorkerTypes.SYNC_WORKER, inputJson = "{\"status\":\"complete\"}"))
                            .enqueue()
                        snackbarHostState.showSnackbar(
                            message = "🔗 Sequential chain started!",
                            duration = SnackbarDuration.Short
                        )
                    }
                }) {
                    Text("Run Sequential Chain")
                }
                Text("🔗 Execute: Sync → Upload → Sync", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))

                // Example 2: Sequential + Parallel Chain
                Button(onClick = {
                    coroutineScope.launch {
                        scheduler.beginWith(TaskRequest(workerClassName = WorkerTypes.SYNC_WORKER))
                            .then(
                                listOf(
                                    TaskRequest(workerClassName = WorkerTypes.UPLOAD_WORKER),
                                    TaskRequest(
                                        workerClassName = WorkerTypes.HEAVY_PROCESSING_WORKER,
                                        constraints = Constraints(isHeavyTask = true)
                                    )
                                )
                            )
                            .then(TaskRequest(workerClassName = WorkerTypes.SYNC_WORKER, inputJson = "{\"status\":\"complete\"}"))
                            .enqueue()
                        snackbarHostState.showSnackbar(
                            message = "🔀 Mixed chain started! Running parallel tasks...",
                            duration = SnackbarDuration.Short
                        )
                    }
                }) {
                    Text("Run Mixed Chain")
                }
                Text("🔀 Execute: Sync → (Upload ∥ Heavy Processing) → Sync", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                InfoBox("This chain starts with a sync, then runs upload and heavy processing in parallel, and finishes with another sync.")
                Spacer(modifier = Modifier.height(16.dp))

                // Example 3: Parallel Start Chain
                Button(onClick = {
                    coroutineScope.launch {
                        scheduler.beginWith(
                            listOf(
                                TaskRequest(workerClassName = WorkerTypes.SYNC_WORKER),
                                TaskRequest(workerClassName = WorkerTypes.UPLOAD_WORKER)
                            )
                        )
                            .then(TaskRequest(workerClassName = WorkerTypes.SYNC_WORKER, inputJson = "{\"status\":\"done\"}"))
                            .enqueue()
                        snackbarHostState.showSnackbar(
                            message = "⚡ Parallel start chain launched!",
                            duration = SnackbarDuration.Short
                        )
                    }
                }) {
                    Text("Run Parallel Start Chain")
                }
                Text("⚡ Execute: (Sync ∥ Upload) → Sync", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))

                InfoBox("Note on Android: Task chains use WorkManager's continuation API. Tasks in parallel groups run concurrently.\n\nNote on iOS: Task chains are serialized and stored in UserDefaults. A special chain executor task processes them step by step. Parallel tasks within a step are executed using coroutines.")
            }
        }
    }
}

/**
 * Composable for scheduling exact alarms/reminders (AlarmManager/UserNotifications) and Push Notifications info.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun AlarmsAndPushTab(scheduler: BackgroundTaskScheduler, coroutineScope: CoroutineScope, statusText: String, exactAlarmPermissionState: ExactAlarmPermissionState, snackbarHostState: SnackbarHostState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("AlarmManager / UserNotifications", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                // Button to schedule an exact alarm (enabled only if permission is granted)
                Button(onClick = {
                    coroutineScope.launch {
                        // Calculate time 10 seconds from now
                        val reminderTime = Clock.System.now().plus(10.seconds).toEpochMilliseconds()
                        val result = scheduler.enqueue(
                            id = TaskIds.EXACT_REMINDER,
                            trigger = TaskTrigger.Exact(atEpochMillis = reminderTime),
                            workerClassName = "Reminder"
                        )
                        snackbarHostState.showSnackbar(
                            message = "⏰ Reminder set! Will notify in 10s",
                            duration = SnackbarDuration.Short
                        )
                    }
                }, enabled = exactAlarmPermissionState.hasPermission) {
                    Text("Schedule Reminder in 10s")
                }
                Text("⏰ Set an exact alarm/notification.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                InfoBox("Note: On Android 14+, the USE_EXACT_ALARM permission is required. If not granted, the reminder will not be exact.\n\nNote: Exact alarms on iOS are scheduled as local notifications. The app is not guaranteed to be woken up to run code at the exact time.")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Push Notifications", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "When a silent push is received, the app will schedule a background task to run after 5 seconds. " +
                            "When the task is completed, it will show a local notification.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                InfoBox("To test on Android, send a push notification to the app while it's in the background. You can use the Firebase console or a tool like `adb`.\n\nTo test on iOS, send a silent push notification to the simulator using the `xcrun simctl push` command. Make sure the `push.apns` file contains `\"content-available\": 1`.")
            }
        }
    }
}

/**
 * Composable for displaying current permission states and providing grant buttons.
 */
@Composable
fun PermissionsAndInfoTab(notificationPermissionState: NotificationPermissionState, exactAlarmPermissionState: ExactAlarmPermissionState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Permissions", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                // Notification Permission UI
                if (notificationPermissionState.shouldShowRequest) {
                    Button(onClick = notificationPermissionState.requestPermission) {
                        Text("Grant Notification Permission")
                    }
                    Text("Notification permission is required to show notifications.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                    Text("Notification permission has been granted.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                // Exact Alarm Permission UI
                if (exactAlarmPermissionState.shouldShowRequest) {
                    Button(onClick = exactAlarmPermissionState.requestPermission) {
                        Text("Grant Exact Alarm Permission")
                    }
                    Text("Exact reminders require a special permission on Android 12+.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                } else {
                    Text("Exact alarm permission has been granted.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * A utility composable to display informational notes with a distinct styling.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun InfoBox(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Preview composable for the App, using fake schedulers for safe rendering.
 */
@Preview
@Composable
fun AppPreview() {
    App(scheduler = FakeBackgroundTaskScheduler(), pushHandler = FakePushNotificationHandler())
}
