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
        statusText = if (isGranted) "Notification permission granted." else "Notification permission denied."
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

            // Show the notification permission request button if needed.
            if (notificationPermissionState.shouldShowRequest) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = notificationPermissionState.requestPermission) {
                    Text("Grant Notification Permission")
                }
                Text("Notification permission is required to show notifications.", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
            }

            // Show the exact alarm permission request button if needed.
            if (exactAlarmPermissionState.shouldShowRequest) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = exactAlarmPermissionState.requestPermission) {
                    Text("Grant Exact Alarm Permission")
                }
                Text("Exact reminders require a special permission on Android 12+.", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
            }

            // --- UPDATED: Re-added Push Notification Simulation Section ---
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                val fakePayload = mapOf(
                    "screen" to "details",
                    "item_id" to "12345",
                    "source" to "simulated_push"
                )
                pushHandler.handlePushPayload(fakePayload)
                statusText = "Simulated Push Payload Handled. Check Logs!"
            }) {
                Text("Simulate Push Notification")
            }
            Text("📱 Simulate receiving a data payload from a push notification.", style = MaterialTheme.typography.body2, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Divider()

            // --- Periodic Task --- //
            Spacer(modifier = Modifier.height(16.dp))
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
            Text("🔄 Schedule a recurring task using BGTaskScheduler/WorkManager.", style = MaterialTheme.typography.body2, textAlign = TextAlign.Center)
            if (getPlatform().name.contains("Android")) {
                Text("Note: Periodic tasks on Android are not exact and may be delayed by Doze mode. The minimum interval is 15 minutes.", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)
            } else if (getPlatform().name.contains("iOS")) {
                Text("Note: Periodic tasks on iOS are not guaranteed to run. The system decides when to run them based on app usage, battery, and network conditions. The minimum interval is not guaranteed.", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)
            }

            // --- One-Time Task --- //
            Spacer(modifier = Modifier.height(16.dp))
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
            Text("⚙️ Run a one-time background task after 10 seconds.", style = MaterialTheme.typography.body2, textAlign = TextAlign.Center)

            // --- Heavy Task --- //
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
            Text("⚡ Run a heavy background task (Foreground Service / BGProcessingTask).", style = MaterialTheme.typography.body2, textAlign = TextAlign.Center)
            if (getPlatform().name.contains("iOS")) {
                Text("Note: Heavy tasks on iOS have a time limit (usually around 30 minutes) and require the device to be charging and connected to a network.", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)
            }

            // --- Exact Reminder --- //
            Spacer(modifier = Modifier.height(16.dp))
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
            Text("⏰ Set an exact alarm/notification.", style = MaterialTheme.typography.body2, textAlign = TextAlign.Center)
            if (getPlatform().name.contains("Android")) {
                Text("Note: On Android 14+, the USE_EXACT_ALARM permission is required. If not granted, the reminder will not be exact.", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)
            } else if (getPlatform().name.contains("iOS")) {
                Text("Note: Exact alarms on iOS are scheduled as local notifications. The app is not guaranteed to be woken up to run code at the exact time.", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.weight(1f))
            Divider()

            // --- Cancel All Tasks --- //
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                scheduler.cancelAll()
                statusText = "All tasks cancelled."
            }) {
                Text("Cancel All Tasks")
            }
            Text("🛑 Cancel all tasks and alarms.", style = MaterialTheme.typography.body2, textAlign = TextAlign.Center)
        }
    }
}

@Preview
@Composable
fun AppPreview() {
    App(scheduler = FakeBackgroundTaskScheduler(), pushHandler = FakePushNotificationHandler())
}