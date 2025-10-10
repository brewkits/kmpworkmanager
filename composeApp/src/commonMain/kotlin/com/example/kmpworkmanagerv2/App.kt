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
import com.example.kmpworkmanagerv2.background.domain.TaskTrigger
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
    val pagerState = rememberPagerState(pageCount = { 3 })

    MaterialTheme {
        Column(
            Modifier.fillMaxSize()
                // Apply padding for system bars (e.g., status bar, navigation bar)
                .padding(WindowInsets.systemBars.asPaddingValues())
        ) {
            // Tab bar for navigation
            TabRow(selectedTabIndex = pagerState.currentPage) {
                Tab(selected = pagerState.currentPage == 0, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } }) { Text("Tasks", modifier = Modifier.padding(16.dp)) }
                Tab(selected = pagerState.currentPage == 1, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } }) { Text("Alarms & Push", modifier = Modifier.padding(16.dp)) }
                Tab(selected = pagerState.currentPage == 2, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } }) { Text("Permissions & Info", modifier = Modifier.padding(16.dp)) }
            }

            // Horizontal pager to host the different tab screens
            HorizontalPager(state = pagerState) {
                when (it) {
                    0 -> TasksTab(scheduler, coroutineScope, statusText)
                    1 -> AlarmsAndPushTab(scheduler, coroutineScope, statusText, exactAlarmPermissionState)
                    2 -> PermissionsAndInfoTab(notificationPermissionState, exactAlarmPermissionState)
                }
            }
        }
    }
}

/**
 * Composable for scheduling and managing background tasks (WorkManager/BGTaskScheduler).
 */
@Composable
fun TasksTab(scheduler: BackgroundTaskScheduler, coroutineScope: CoroutineScope, statusText: String) {
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
                        //                statusText = "One-Time Task Schedule Result: $result" // Status update commented out
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
                        //                statusText = "Heavy Task Schedule Result: $result" // Status update commented out
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
                        //                statusText = "Network Task Schedule Result: $result" // Status update commented out
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
                        //                statusText = "Periodic Sync Schedule Result: $result" // Status update commented out
                    }
                }) {
                    Text("Schedule Periodic Sync (15 min)")
                }
                Text("🔄 Schedule a recurring task using BGTaskScheduler/WorkManager.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                InfoBox("Note: Periodic tasks on Android are not exact and may be delayed by Doze mode. The minimum interval is 15 minutes.\n\nNote: Periodic tasks on iOS are not guaranteed to run. The system decides when to run them based on app usage, battery, and network conditions. The minimum interval is not guaranteed.")
            }
        }
    }
}

/**
 * Composable for scheduling exact alarms/reminders (AlarmManager/UserNotifications) and Push Notifications info.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun AlarmsAndPushTab(scheduler: BackgroundTaskScheduler, coroutineScope: CoroutineScope, statusText: String, exactAlarmPermissionState: ExactAlarmPermissionState) {
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
                        //                statusText = "Exact Reminder Schedule Result: $result" // Status update commented out
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