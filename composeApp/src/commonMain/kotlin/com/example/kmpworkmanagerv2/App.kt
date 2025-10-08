package com.example.kmpworkmanagerv2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
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
import com.example.kmpworkmanagerv2.showNotification
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.mp.KoinPlatform.getKoin
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * The main entry point of the application.
 * This composable function is responsible for rendering the entire UI.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun App(
    scheduler: BackgroundTaskScheduler = getKoin().get(),
    pushHandler: PushNotificationHandler = getKoin().get()
) {
    // State for holding the status text to be displayed on the UI.
    var statusText by remember { mutableStateOf("Requesting permissions...") }

    // Coroutine scope for launching asynchronous operations from UI events.
    val coroutineScope = rememberCoroutineScope()

    // State for managing notification permission.
    val notificationPermissionState = rememberNotificationPermissionState { isGranted ->
        statusText =
            if (isGranted) "Notification permission granted." else "Notification permission denied."
    }

    // State for managing exact alarm permission on Android.
    val exactAlarmPermissionState = rememberExactAlarmPermissionState()

    MaterialTheme {
        // UPDATED: Re-added verticalScroll to the main Column to prevent overflow.
        Column(
            Modifier.fillMaxSize()
                .padding(WindowInsets.systemBars.asPaddingValues())
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("KMP Background Task Demo", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = statusText, style = MaterialTheme.typography.subtitle1)
            Spacer(modifier = Modifier.height(16.dp))
            Divider()

            // --- Permissions --- //
            Text("Permissions", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))
            if (notificationPermissionState.shouldShowRequest) {
                Button(onClick = notificationPermissionState.requestPermission) {
                    Text("Grant Notification Permission")
                }
                Text(
                    "Notification permission is required to show notifications.",
                    style = MaterialTheme.typography.caption,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (exactAlarmPermissionState.shouldShowRequest) {
                Button(onClick = exactAlarmPermissionState.requestPermission) {
                    Text("Grant Exact Alarm Permission")
                }
                Text(
                    "Exact reminders require a special permission on Android 12+.",
                    style = MaterialTheme.typography.caption,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Divider()

            // --- Android WorkManager / iOS BGTaskScheduler --- //
            Text("WorkManager / BGTaskScheduler", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))

            // --- One-Time Tasks --- //
            Text("Run a task once", style = MaterialTheme.typography.subtitle1)
            Text(
                "Schedule a task to run once in the future.",
                style = MaterialTheme.typography.caption,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                coroutineScope.launch {
                    val result = scheduler.enqueue(
                        id = TaskIds.ONE_TIME_UPLOAD,
                        trigger = TaskTrigger.OneTime(initialDelayMs = 10.seconds.inWholeMilliseconds),
                        workerClassName = WorkerTypes.UPLOAD_WORKER
                    )
                    statusText = "One-Time Task Schedule Result: $result"
                }
            }) {
                Text("Run BG Task in 10s")
            }
            Text(
                "⚙️ Run a one-time background task after 10 seconds.",
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = {
                coroutineScope.launch {
                    val result = scheduler.enqueue(
                        id = TaskIds.HEAVY_TASK_1,
                        trigger = TaskTrigger.OneTime(initialDelayMs = 5.seconds.inWholeMilliseconds),
                        workerClassName = WorkerTypes.HEAVY_PROCESSING_WORKER,
                        constraints = Constraints(isHeavyTask = true) // Mark as a heavy task
                    )
                    statusText = "Heavy Task Schedule Result: $result"
                }
            }) {
                Text("Schedule Heavy Task (30s)")
            }
            Text(
                "⚡ Run a heavy background task (Foreground Service / BGProcessingTask).",
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center
            )
            if (getPlatform().name.contains("iOS")) {
                Text(
                    "Note: Heavy tasks on iOS have a time limit (usually around 30 minutes) and require the device to be charging and connected to a network.",
                    style = MaterialTheme.typography.caption,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = {
                coroutineScope.launch {
                    val result = scheduler.enqueue(
                        id = "network-task",
                        trigger = TaskTrigger.OneTime(initialDelayMs = 5.seconds.inWholeMilliseconds),
                        workerClassName = WorkerTypes.UPLOAD_WORKER,
                        constraints = Constraints(requiresNetwork = true)
                    )
                    statusText = "Network Task Schedule Result: $result"
                }
            }) {
                Text("Schedule Task with Network Constraint")
            }
            Text(
                "🌐 Schedule a task that requires a network connection.",
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center
            )
            if (getPlatform().name.contains("Android")) {
                Text(
                    "Note: On Android, this uses WorkManager's network constraints. The task will only run when the device has a network connection.",
                    style = MaterialTheme.typography.caption,
                    textAlign = TextAlign.Center
                )
            } else if (getPlatform().name.contains("iOS")) {
                Text(
                    "Note: On iOS, network constraints are only supported for heavy tasks (BGProcessingTask). Regular tasks (BGAppRefreshTask) do not support this constraint.",
                    style = MaterialTheme.typography.caption,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // --- Periodic Tasks --- //
            Text("Run a task repeatedly", style = MaterialTheme.typography.subtitle1)
            Text(
                "Schedule a task to run periodically in the background.",
                style = MaterialTheme.typography.caption,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                coroutineScope.launch {
                    val result = scheduler.enqueue(
                        id = TaskIds.PERIODIC_SYNC_TASK,
                        trigger = TaskTrigger.Periodic(intervalMs = 15.minutes.inWholeMilliseconds),
                        workerClassName = WorkerTypes.SYNC_WORKER
                    )
                    statusText = "Periodic Sync Schedule Result: $result"
                }
            }) {
                Text("Schedule Periodic Sync (15 min)")
            }
            Text(
                "🔄 Schedule a recurring task using BGTaskScheduler/WorkManager.",
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center
            )
            if (getPlatform().name.contains("Android")) {
                Text(
                    "Note: Periodic tasks on Android are not exact and may be delayed by Doze mode. The minimum interval is 15 minutes.",
                    style = MaterialTheme.typography.caption,
                    textAlign = TextAlign.Center
                )
            } else if (getPlatform().name.contains("iOS")) {
                Text(
                    "Note: Periodic tasks on iOS are not guaranteed to run. The system decides when to run them based on app usage, battery, and network conditions. The minimum interval is not guaranteed.",
                    style = MaterialTheme.typography.caption,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Divider()

            // --- Android AlarmManager / iOS UserNotifications --- //
            Text("AlarmManager / UserNotifications", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                coroutineScope.launch {
                    val reminderTime = Clock.System.now().plus(10.seconds).toEpochMilliseconds()
                    val result = scheduler.enqueue(
                        id = TaskIds.EXACT_REMINDER,
                        trigger = TaskTrigger.Exact(atEpochMillis = reminderTime),
                        workerClassName = "Reminder"
                    )
                    statusText = "Exact Reminder Schedule Result: $result"
                }
            }, enabled = exactAlarmPermissionState.hasPermission) {
                Text("Schedule Reminder in 10s")
            }
            Text(
                "⏰ Set an exact alarm/notification.",
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center
            )
            if (getPlatform().name.contains("Android")) {
                Text(
                    "Note: On Android 14+, the USE_EXACT_ALARM permission is required. If not granted, the reminder will not be exact.",
                    style = MaterialTheme.typography.caption,
                    textAlign = TextAlign.Center
                )
                if (getPlatform().name.contains("iOS")) {
                    Text(
                        "Note: Exact alarms on iOS are scheduled as local notifications. The app is not guaranteed to be woken up to run code at the exact time.",
                        style = MaterialTheme.typography.caption,
                        textAlign = TextAlign.Center
                    )
                }

                // --- Inexact Alarms --- //
                Spacer(modifier = Modifier.height(16.dp))
                Text("Inexact Alarms", style = MaterialTheme.typography.subtitle1)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    coroutineScope.launch {
                        val result = scheduler.enqueue(
                            id = "inexact-alarm",
                            trigger = TaskTrigger.OneTime(initialDelayMs = 10.seconds.inWholeMilliseconds),
                            workerClassName = "Inexact-Alarm"
                        )
                        statusText = "Inexact Alarm Schedule Result: $result"
                    }
                }) {
                    Text("Schedule Inexact Alarm in 10s")
                }
                Text(
                    "⏰ Schedule an inexact alarm that will run around the specified time.",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center
                )
                if (getPlatform().name.contains("Android")) {
                    Text(
                        "Note: On Android, this uses WorkManager and is subject to Doze mode and other battery optimizations.",
                        style = MaterialTheme.typography.caption,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Divider()

                // --- Push Notifications --- //
                Text("Push Notifications", style = MaterialTheme.typography.h6)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "When a silent push is received, the app will schedule a background task to run after 5 seconds. " +
                            "When the task is completed, it will show a local notification.",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (getPlatform().name.contains("Android")) {
                    Text(
                        "To test on Android, send a push notification to the app while it's in the background. " +
                                "You can use the Firebase console or a tool like `adb`.",
                        style = MaterialTheme.typography.caption,
                        textAlign = TextAlign.Center
                    )
                } else if (getPlatform().name.contains("iOS")) {
                    Text(
                        "To test on iOS, send a silent push notification to the simulator using the `xcrun simctl push` command. " +
                                "Make sure the `push.apns` file contains `\"content-available\": 1`.",
                        style = MaterialTheme.typography.caption,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Divider()

                // --- Cancel Tasks --- //
                Text("Cancel Tasks", style = MaterialTheme.typography.h6)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    scheduler.cancelAll()
                    statusText = "All tasks cancelled."
                }) {
                    Text("Cancel All Tasks")
                }
                Text(
                    "🛑 Cancel all tasks and alarms.",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @Preview
    @Composable
    fun AppPreview() {
        App(scheduler = FakeBackgroundTaskScheduler(), pushHandler = FakePushNotificationHandler())
    }
}