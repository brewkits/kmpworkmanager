package com.example.kmpworkmanagerv2

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.kmpworkmanagerv2.background.data.WorkerTypes
import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.Constraints
import com.example.kmpworkmanagerv2.background.domain.TaskTrigger
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.mp.KoinPlatform.getKoin
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
fun App() {
    val scheduler: BackgroundTaskScheduler = getKoin().get()
    var statusText by remember { mutableStateOf("Requesting permissions...") }
    val coroutineScope = rememberCoroutineScope()

    // Sử dụng các hàm expect/actual đã định nghĩa
    val exactAlarmPermissionState = rememberExactAlarmPermissionState()
    RequestNotificationPermission { isGranted ->
        statusText = if (isGranted) "Notification permission granted." else "Notification permission denied."
    }

    MaterialTheme {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("KMP Background Task Demo", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = statusText, style = MaterialTheme.typography.subtitle1)
            Spacer(modifier = Modifier.height(16.dp))
            Divider()

            if (exactAlarmPermissionState.shouldShowRequest) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = exactAlarmPermissionState.requestPermission) {
                    Text("Grant Exact Alarm Permission")
                }
                Text("Exact reminders require a special permission on Android 12+.", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
            }

            // ... (Phần còn lại của UI không thay đổi)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                coroutineScope.launch {
                    val result = scheduler.enqueue(
                        id = "periodic-sync-task",
                        trigger = TaskTrigger.Periodic(intervalMs = 15.minutes.inWholeMilliseconds),
                        workerClassName = WorkerTypes.SYNC_WORKER
                    )
                    statusText = "Periodic Sync Schedule Result: $result"
                }
            }) {
                Text("Schedule Periodic Sync (15 min)")
            }
            Text("🔄 Lên lịch công việc lặp lại bằng BGTaskScheduler/WorkManager.", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                coroutineScope.launch {
                    val result = scheduler.enqueue(
                        id = "one-time-upload",
                        trigger = TaskTrigger.OneTime(initialDelayMs = 10.seconds.inWholeMilliseconds),
                        workerClassName = WorkerTypes.UPLOAD_WORKER
                    )
                    statusText = "One-Time Task Schedule Result: $result"
                }
            }) {
                Text("Run BG Task in 10s")
            }
            Text("⚙️ Chạy tác vụ nền 1 lần sau 10 giây.", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                coroutineScope.launch {
                    val result = scheduler.enqueue(
                        id = "heavy-task-1",
                        trigger = TaskTrigger.OneTime(initialDelayMs = 5.seconds.inWholeMilliseconds),
                        workerClassName = WorkerTypes.HEAVY_PROCESSING_WORKER,
                        constraints = Constraints(isHeavyTask = true) // <-- ĐÁNH DẤU LÀ TÁC VỤ NẶNG
                    )
                    statusText = "Heavy Task Schedule Result: $result"
                }
            }) {
                Text("Schedule Heavy Task (30s)")
            }
            Text("⚡ Chạy tác vụ nền nặng (Foreground Service / BGProcessingTask).", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                coroutineScope.launch {
                    val reminderTime = kotlin.time.Clock.System.now().plus(10.seconds).toEpochMilliseconds()
                    val result = scheduler.enqueue(
                        id = "exact-reminder",
                        trigger = TaskTrigger.Exact(atEpochMillis = reminderTime),
                        workerClassName = "Reminder"
                    )
                    statusText = "Exact Reminder Schedule Result: $result"
                }
            }, enabled = exactAlarmPermissionState.hasPermission) {
                Text("Schedule Reminder in 10s")
            }
            Text("⏰ Đặt báo thức/thông báo chính xác.", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)

            Spacer(modifier = Modifier.weight(1f))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                scheduler.cancelAll()
                statusText = "All tasks cancelled."
            }) {
                Text("Cancel All Tasks")
            }
            Text("🛑 Hủy tất cả các tác vụ và báo thức.", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)
        }
    }
}

@Preview
@Composable
fun AppPreview() {
    App()
}