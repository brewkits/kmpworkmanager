package dev.brewkits.kmpworkmanager.sample.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.brewkits.kmpworkmanager.sample.background.WorkerTypes
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.brewkits.kmpworkmanager.sample.utils.*
import dev.brewkits.kmpworkmanager.workers.config.FileCompressionConfig
import dev.brewkits.kmpworkmanager.workers.config.HttpDownloadConfig
import dev.brewkits.kmpworkmanager.workers.config.HttpRequestConfig
import dev.brewkits.kmpworkmanager.workers.config.HttpSyncConfig
import dev.brewkits.kmpworkmanager.workers.config.HttpUploadConfig
import kotlin.time.TimeSource

@Composable
fun DemoScenariosScreen(scheduler: BackgroundTaskScheduler) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = getPlatformContext()

    // Track running tasks to disable other buttons
    var isAnyTaskRunning by remember { mutableStateOf(false) }
    var runningTaskName by remember { mutableStateOf("") }

    // Listen to task completion events
    LaunchedEffect(Unit) {
        dev.brewkits.kmpworkmanager.background.domain.TaskEventBus.events.collect { event ->
            // Reset running state when any task completes
            isAnyTaskRunning = false
            runningTaskName = ""
        }
    }

    // Helper function to run tasks with state tracking
    fun runTask(taskName: String, action: suspend () -> Unit) {
        if (isAnyTaskRunning) return
        isAnyTaskRunning = true
        runningTaskName = taskName
        coroutineScope.launch {
            try {
                action()
            } catch (e: Exception) {
                isAnyTaskRunning = false
                runningTaskName = ""
            }
        }
    }

    // Schedules a task and shows a truthful snackbar based on the ScheduleResult.
    // Use this for direct scheduler.enqueue() calls (not chain .enqueue() which returns Unit).
    suspend fun scheduleTask(successMessage: String, block: suspend () -> ScheduleResult) {
        val result = block()
        val message = if (result == ScheduleResult.ACCEPTED) successMessage else when (result) {
            ScheduleResult.REJECTED_OS_POLICY -> "Rejected by OS (Low Power Mode, missing Info.plist entry, or migration timeout)"
            ScheduleResult.DEADLINE_ALREADY_PASSED -> "Deadline already passed — task was not scheduled"
            ScheduleResult.THROTTLED -> "Throttled by OS — too many pending tasks"
            else -> "Failed to schedule"
        }
        snackbarHostState.showSnackbar(
            message = message,
            duration = SnackbarDuration.Short
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Demo Scenarios",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Comprehensive demonstrations of all library features",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Running Task Indicator
            if (isAnyTaskRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Task Running",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                runningTaskName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Button(
                            onClick = {
                                isAnyTaskRunning = false
                                runningTaskName = ""
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Stop")
                        }
                    }
                }
            }

            // Basic Tasks Section
            DemoSection(
                title = "Basic Tasks",
                icon = Icons.Default.PlayArrow
            ) {
                DemoCard(
                    title = "Quick Sync",
                    description = "OneTime task with no constraints",
                    icon = Icons.Default.Sync,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Quick Sync") {
                            scheduleTask("Quick Sync scheduled (2s delay)") {
                                scheduler.enqueue(
                                    id = "demo-quick-sync",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "File Upload",
                    description = "OneTime with network required",
                    icon = Icons.Default.Upload,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("File Upload") {
                            scheduleTask("File Upload scheduled (5s, network required)") {
                                scheduler.enqueue(
                                    id = "demo-file-upload",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 5.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.UPLOAD_WORKER,
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "Database Operation",
                    description = "Batch inserts with progress",
                    icon = Icons.Default.Storage,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Database Operation") {
                            scheduleTask("Database Worker scheduled (3s delay)") {
                                scheduler.enqueue(
                                    id = "demo-database",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.DATABASE_WORKER
                                )
                            }
                        }
                    }
                )
            }

            // Periodic Tasks Section
            DemoSection(
                title = "Periodic Tasks",
                icon = Icons.Default.Loop
            ) {
                DemoCard(
                    title = "Hourly Sync",
                    description = "Repeats every hour with network constraints",
                    icon = Icons.Default.Schedule,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Hourly Sync") {
                            scheduleTask("Hourly Sync scheduled (1h interval)") {
                                scheduler.enqueue(
                                    id = "demo-hourly-sync",
                                    trigger = TaskTrigger.Periodic(intervalMs = 1.hours.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER,
                                    constraints = Constraints(requiresNetwork = true, requiresUnmeteredNetwork = true)
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "Daily Cleanup",
                    description = "Runs every 24 hours while charging",
                    icon = Icons.Default.CleaningServices,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Daily Cleanup") {
                            scheduleTask("Daily Cleanup scheduled (24h, charging)") {
                                scheduler.enqueue(
                                    id = "demo-daily-cleanup",
                                    trigger = TaskTrigger.Periodic(intervalMs = 24.hours.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.CLEANUP_WORKER,
                                    constraints = Constraints(requiresCharging = true)
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "Location Sync",
                    description = "Periodic 15min location upload",
                    icon = Icons.Default.LocationOn,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Location Sync") {
                            scheduleTask("Location Sync scheduled (15min)") {
                                scheduler.enqueue(
                                    id = "demo-location-sync",
                                    trigger = TaskTrigger.Periodic(intervalMs = 15.minutes.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.LOCATION_SYNC_WORKER
                                )
                            }
                        }
                    }
                )
            }

            // Task Chains Section
            DemoSection(
                title = "Task Chains",
                icon = Icons.Default.Link
            ) {
                DemoCard(
                    title = "Sequential: Download \u2192 Process \u2192 Upload",
                    description = "Three tasks in sequence",
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Sequential: Download \u2192 Process \u2192 Upload") {
                            scheduler.beginWith(TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER))
                                .then(TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.IMAGE_PROCESSING_WORKER))
                                .then(TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.UPLOAD_WORKER))
                                .enqueue()
                            snackbarHostState.showSnackbar(message = "Sequential chain started", duration = SnackbarDuration.Short)
                        }
                    }
                )
                DemoCard(
                    title = "Parallel: Process 3 Images \u2192 Upload",
                    description = "Parallel processing then upload",
                    icon = Icons.Default.DynamicFeed,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Parallel: Process 3 Images \u2192 Upload") {
                            scheduler.beginWith(
                                listOf(
                                    TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.IMAGE_PROCESSING_WORKER),
                                    TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.IMAGE_PROCESSING_WORKER)
                                )
                            )
                                .then(TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.BATCH_UPLOAD_WORKER))
                                .enqueue()
                            snackbarHostState.showSnackbar(message = "Parallel chain started", duration = SnackbarDuration.Short)
                        }
                    }
                )
                DemoCard(
                    title = "Mixed: Fetch \u2192 [Process \u2225 Analyze \u2225 Compress] \u2192 Upload",
                    description = "Sequential + parallel combination",
                    icon = Icons.Default.AccountTree,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Mixed: Fetch \u2192 [Process \u2225 Analyze \u2225 Compress] \u2192 Upload") {
                            scheduler.beginWith(TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER))
                                .then(
                                    listOf(
                                        TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.IMAGE_PROCESSING_WORKER),
                                        TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.ANALYTICS_WORKER),
                                        TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.DATABASE_WORKER)
                                    )
                                )
                                .then(TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.UPLOAD_WORKER))
                                .enqueue()
                            snackbarHostState.showSnackbar(message = "Mixed chain started", duration = SnackbarDuration.Short)
                        }
                    }
                )
                DemoCard(
                    title = "Long Chain: 5 Sequential Steps",
                    description = "Extended workflow demonstration",
                    icon = Icons.Default.LinearScale,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Long Chain: 5 Sequential Steps") {
                            scheduler.beginWith(TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER))
                                .then(TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.IMAGE_PROCESSING_WORKER))
                                .then(TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.DATABASE_WORKER))
                                .then(TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.ANALYTICS_WORKER))
                                .then(TaskRequest(workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.UPLOAD_WORKER))
                                .enqueue()
                            snackbarHostState.showSnackbar(message = "Long chain started (5 steps)", duration = SnackbarDuration.Short)
                        }
                    }
                )
            }

            // Constraint Demos Section
            DemoSection(
                title = "Constraint Demos",
                icon = Icons.Default.Security
            ) {
                DemoCard(
                    title = "Network Required",
                    description = "Only runs when network available",
                    icon = Icons.Default.Wifi,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Network Required") {
                            scheduleTask("Network-constrained task scheduled") {
                                scheduler.enqueue(
                                    id = "demo-network-required",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER,
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "Unmetered Network (WiFi Only)",
                    description = "Only runs on WiFi/unmetered",
                    icon = Icons.Default.WifiTethering,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Unmetered Network (WiFi Only)") {
                            scheduleTask("WiFi-only task scheduled") {
                                scheduler.enqueue(
                                    id = "demo-unmetered",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.BATCH_UPLOAD_WORKER,
                                    constraints = Constraints(requiresNetwork = true, requiresUnmeteredNetwork = true)
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "Charging Required",
                    description = "Runs only while device is charging",
                    icon = Icons.Default.BatteryChargingFull,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Charging Required") {
                            scheduleTask("Charging-constrained task scheduled") {
                                scheduler.enqueue(
                                    id = "demo-charging",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HEAVY_PROCESSING_WORKER,
                                    constraints = Constraints(requiresCharging = true, isHeavyTask = true)
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "Battery Not Low (Android)",
                    description = "Defers when battery is low",
                    icon = Icons.Default.BatteryFull,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Battery Not Low (Android)") {
                            scheduleTask("Battery-OK task scheduled") {
                                scheduler.enqueue(
                                    id = "demo-battery-ok",
                                    trigger = TaskTrigger.OneTime(), constraints = Constraints(systemConstraints = setOf(dev.brewkits.kmpworkmanager.background.domain.SystemConstraint.REQUIRE_BATTERY_NOT_LOW)),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.IMAGE_PROCESSING_WORKER
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "Storage Low Cleanup (Android)",
                    description = "Cleanup task for low storage scenarios",
                    icon = Icons.Default.SdCard,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Storage Low Cleanup (Android)") {
                            scheduleTask("Storage-low task scheduled (Android only)") {
                                scheduler.enqueue(
                                    id = "demo-storage-low",
                                    trigger = TaskTrigger.OneTime(),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.CLEANUP_WORKER
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "Device Idle (Android)",
                    description = "Runs when device is idle/sleeping",
                    icon = Icons.Default.NightsStay,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Device Idle (Android)") {
                            scheduleTask("Device-idle task scheduled (Android only)") {
                                scheduler.enqueue(
                                    id = "demo-device-idle",
                                    trigger = TaskTrigger.OneTime(),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HEAVY_PROCESSING_WORKER,
                                    constraints = Constraints(
                                        isHeavyTask = true,
                                        systemConstraints = setOf(dev.brewkits.kmpworkmanager.background.domain.SystemConstraint.DEVICE_IDLE)
                                    )
                                )
                            }
                        }
                    }
                )
            }

            // Error Scenarios Section
            DemoSection(
                title = "Error Scenarios",
                icon = Icons.Default.Warning,
                containerColor = MaterialTheme.colorScheme.errorContainer
            ) {
                DemoCard(
                    title = "Network Retry with Backoff",
                    description = "Demonstrates exponential backoff (fails 2x, succeeds 3rd)",
                    icon = Icons.Default.Refresh,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Network Retry with Backoff") {
                            scheduleTask("Retry demo started (watch logs)") {
                                scheduler.enqueue(
                                    id = "demo-retry",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.NETWORK_RETRY_WORKER
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "Random Database Failure",
                    description = "10% chance of transaction failure",
                    icon = Icons.Default.Error,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Random Database Failure") {
                            scheduleTask("Database worker scheduled (may fail)") {
                                scheduler.enqueue(
                                    id = "demo-db-fail",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.DATABASE_WORKER
                                )
                            }
                        }
                    }
                )
            }

            // Heavy Tasks Section
            DemoSection(
                title = "Heavy/Long-Running Tasks",
                icon = Icons.Default.Bolt
            ) {
                DemoCard(
                    title = "Heavy Processing",
                    description = "Long-running CPU-intensive task (30s)",
                    icon = Icons.Default.Memory,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Heavy Processing") {
                            scheduleTask("Heavy task scheduled (ForegroundService/BGProcessingTask)") {
                                scheduler.enqueue(
                                    id = "demo-heavy",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HEAVY_PROCESSING_WORKER,
                                    constraints = Constraints(isHeavyTask = true)
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "Batch Upload (5 Files)",
                    description = "Multiple file uploads with progress",
                    icon = Icons.Default.CloudUpload,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Batch Upload (5 Files)") {
                            scheduleTask("Batch upload started (5 files)") {
                                scheduler.enqueue(
                                    id = "demo-batch-upload",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.BATCH_UPLOAD_WORKER
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "Image Processing (5 Images x 3 Sizes)",
                    description = "CPU-intensive image resizing",
                    icon = Icons.Default.Image,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Image Processing (5 Images x 3 Sizes)") {
                            scheduleTask("Image processing started (15 operations)") {
                                scheduler.enqueue(
                                    id = "demo-image-proc",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.IMAGE_PROCESSING_WORKER
                                )
                            }
                        }
                    }
                )
            }

            DemoSection(
                title = "Built-in Worker Chains",
                icon = Icons.Default.AccountTree,
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                DemoCard(
                    title = "Download \u2192 Compress \u2192 Upload Chain",
                    description = "Complete workflow: Download file, compress it, then upload with data passing between steps.",
                    icon = Icons.Default.CloudSync,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Download \u2192 Compress \u2192 Upload Chain") {
                            // Step 1: Download file
                            // Note: Using httpbin.org for demo (works reliably on iOS simulator)
                            val downloadConfig = HttpDownloadConfig(
                                url = "https://httpbin.org/bytes/10240",
                                savePath = getDummyDownloadPath(context)
                            )

                            // Step 2: Compress downloaded file
                            val compressionConfig = FileCompressionConfig(
                                inputPath = getDummyDownloadPath(context),
                                outputPath = getDummyCompressionOutputPath(context),
                                compressionLevel = "high"
                            )

                            // Step 3: Upload compressed file
                            val uploadConfig = HttpUploadConfig(
                                url = "https://httpbin.org/post",
                                filePath = getDummyCompressionOutputPath(context),
                                fileFieldName = "compressed_file",
                                fileName = "compressed_download.zip"
                            )

                            scheduler.beginWith(
                                TaskRequest(
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_DOWNLOAD_WORKER,
                                    inputJson = Json.encodeToString(HttpDownloadConfig.serializer(), downloadConfig),
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            )
                                .then(
                                    TaskRequest(
                                        workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.FILE_COMPRESSION_WORKER,
                                        inputJson = Json.encodeToString(FileCompressionConfig.serializer(), compressionConfig)
                                    )
                                )
                                .then(
                                    TaskRequest(
                                        workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_UPLOAD_WORKER,
                                        inputJson = Json.encodeToString(HttpUploadConfig.serializer(), uploadConfig),
                                        constraints = Constraints(requiresNetwork = true)
                                    )
                                )
                                .withId("demo-download-compress-upload-chain", policy = ExistingPolicy.KEEP)
                                .enqueue()

                            snackbarHostState.showSnackbar(message = "Download→Compress→Upload chain started! Check logs for data passing.", duration = SnackbarDuration.Short)
                        }
                    }
                )

                DemoCard(
                    title = "Parallel HTTP Sync \u2192 Compress Results",
                    description = "Fetch 3 APIs in parallel, then compress all results together",
                    icon = Icons.Default.DynamicFeed,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Parallel HTTP Sync \u2192 Compress Results") {
                            // Create dummy files first
                            val (uploadFilePath, compressInputPath) = createDummyFiles(context)

                            val syncConfigs = listOf(
                                HttpSyncConfig(
                                    url = "https://jsonplaceholder.typicode.com/posts/1",
                                    method = "GET"
                                ),
                                HttpSyncConfig(
                                    url = "https://jsonplaceholder.typicode.com/users/1",
                                    method = "GET"
                                ),
                                HttpSyncConfig(
                                    url = "https://jsonplaceholder.typicode.com/comments/1",
                                    method = "GET"
                                )
                            )

                            val compressionConfig = FileCompressionConfig(
                                inputPath = compressInputPath,
                                outputPath = getDummyCompressionOutputPath(context),
                                compressionLevel = "medium"
                            )

                            scheduler.beginWith(
                                syncConfigs.map { config ->
                                    TaskRequest(
                                        workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_SYNC_WORKER,
                                        inputJson = Json.encodeToString(HttpSyncConfig.serializer(), config),
                                        constraints = Constraints(requiresNetwork = true)
                                    )
                                }
                            )
                                .then(
                                    TaskRequest(
                                        workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.FILE_COMPRESSION_WORKER,
                                        inputJson = Json.encodeToString(FileCompressionConfig.serializer(), compressionConfig)
                                    )
                                )
                                .withId("demo-parallel-http-sync-compress", policy = ExistingPolicy.KEEP)
                                .enqueue()

                            snackbarHostState.showSnackbar(message = "Parallel HTTP→Compress chain started! Watch data flow in logs.", duration = SnackbarDuration.Short)
                        }
                    }
                )

                DemoCard(
                    title = "HTTP Request \u2192 Sync \u2192 Upload Pipeline",
                    description = "POST data, sync response, then upload result file",
                    icon = Icons.Default.SwapHoriz,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("HTTP Request \u2192 Sync \u2192 Upload Pipeline") {
                            // Create dummy files first
                            val (uploadFilePath, compressInputPath) = createDummyFiles(context)


                            val requestConfig = HttpRequestConfig(
                                url = "https://jsonplaceholder.typicode.com/posts",
                                method = "POST",
                                body = """{"title":"Chain Demo","body":"Built-in chain test","userId":1}""",
                                headers = mapOf("Content-Type" to "application/json")
                            )

                            val syncConfig = HttpSyncConfig(
                                url = "https://jsonplaceholder.typicode.com/posts/1",
                                method = "GET"
                            )

                            val uploadConfig = HttpUploadConfig(
                                url = "https://httpbin.org/post",
                                filePath = uploadFilePath,
                                fileFieldName = "result",
                                fileName = "sync_result.json"
                            )

                            scheduler.beginWith(
                                TaskRequest(
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_REQUEST_WORKER,
                                    inputJson = Json.encodeToString(HttpRequestConfig.serializer(), requestConfig),
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            )
                                .then(
                                    TaskRequest(
                                        workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_SYNC_WORKER,
                                        inputJson = Json.encodeToString(HttpSyncConfig.serializer(), syncConfig),
                                        constraints = Constraints(requiresNetwork = true)
                                    )
                                )
                                .then(
                                    TaskRequest(
                                        workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_UPLOAD_WORKER,
                                        inputJson = Json.encodeToString(HttpUploadConfig.serializer(), uploadConfig),
                                        constraints = Constraints(requiresNetwork = true)
                                    )
                                )
                                .withId("demo-request-sync-upload-pipeline", policy = ExistingPolicy.KEEP)
                                .enqueue()

                            snackbarHostState.showSnackbar(message = "Request→Sync→Upload pipeline started!", duration = SnackbarDuration.Short)
                        }
                    }
                )

                DemoCard(
                    title = "Long Chain: Download \u2192 Process \u2192 Compress \u2192 Sync \u2192 Upload",
                    description = "5-step workflow showcasing complete built-in worker integration",
                    icon = Icons.Default.LinearScale,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Long Chain: Download \u2192 Process \u2192 Compress \u2192 Sync \u2192 Upload") {
                            val downloadConfig = HttpDownloadConfig(
                                url = "https://httpbin.org/bytes/1024",
                                savePath = getDummyDownloadPath(context)
                            )

                            val compressionConfig = FileCompressionConfig(
                                inputPath = getDummyDownloadPath(context),
                                outputPath = getDummyCompressionOutputPath(context),
                                compressionLevel = "high"
                            )

                            val syncConfig = HttpSyncConfig(
                                url = "https://jsonplaceholder.typicode.com/posts/1",
                                method = "GET"
                            )

                            val uploadConfig = HttpUploadConfig(
                                url = "https://httpbin.org/post",
                                filePath = getDummyCompressionOutputPath(context),
                                fileFieldName = "final_result",
                                fileName = "final.zip"
                            )

                            scheduler.beginWith(
                                TaskRequest(
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_DOWNLOAD_WORKER,
                                    inputJson = Json.encodeToString(HttpDownloadConfig.serializer(), downloadConfig),
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            )
                                .then(
                                    TaskRequest(
                                        workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.IMAGE_PROCESSING_WORKER // Simulate processing
                                    )
                                )
                                .then(
                                    TaskRequest(
                                        workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.FILE_COMPRESSION_WORKER,
                                        inputJson = Json.encodeToString(FileCompressionConfig.serializer(), compressionConfig)
                                    )
                                )
                                .then(
                                    TaskRequest(
                                        workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_SYNC_WORKER,
                                        inputJson = Json.encodeToString(HttpSyncConfig.serializer(), syncConfig),
                                        constraints = Constraints(requiresNetwork = true)
                                    )
                                )
                                .then(
                                    TaskRequest(
                                        workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_UPLOAD_WORKER,
                                        inputJson = Json.encodeToString(HttpUploadConfig.serializer(), uploadConfig),
                                        constraints = Constraints(requiresNetwork = true)
                                    )
                                )
                                .withId("demo-long-5-step-chain", policy = ExistingPolicy.KEEP)
                                .enqueue()

                            snackbarHostState.showSnackbar(message = "5-step long chain started! This demonstrates complete workflow.", duration = SnackbarDuration.Short)
                        }
                    }
                )
            }

            // Built-in Workers Section
            DemoSection(
                title = "Built-in Workers",
                icon = Icons.Default.Build
            ) {
                DemoCard(
                    title = "HTTP Request Worker",
                    description = "Fire-and-forget HTTP POST request",
                    icon = Icons.Default.Http,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("HTTP Request Worker") {
                            val config = HttpRequestConfig(
                                url = "https://jsonplaceholder.typicode.com/posts",
                                method = "POST",
                                body = """{"title": "foo", "body": "bar", "userId": 1}""",
                                headers = mapOf("Content-Type" to "application/json")
                            )
                            scheduleTask("HttpRequestWorker scheduled") {
                                scheduler.enqueue(
                                    id = "demo-builtin-httprequest",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 1.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_REQUEST_WORKER,
                                    inputJson = Json.encodeToString(HttpRequestConfig.serializer(), config),
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "HTTP Sync Worker",
                    description = "JSON POST/GET with response logging",
                    icon = Icons.Default.SyncAlt,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("HTTP Sync Worker") {
                            val requestBody = buildJsonObject {
                                put("syncTime", TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds)
                                put("data", "sample")
                            }
                            val config = HttpSyncConfig(
                                url = "https://jsonplaceholder.typicode.com/posts",
                                method = "POST",
                                requestBody = requestBody,
                                headers = mapOf("Content-Type" to "application/json")
                            )
                            scheduleTask("HttpSyncWorker scheduled") {
                                scheduler.enqueue(
                                    id = "demo-builtin-httpsync",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 1.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_SYNC_WORKER,
                                    inputJson = Json.encodeToString(HttpSyncConfig.serializer(), config),
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "HTTP Download Worker",
                    description = "Download a file (dummy URL)",
                    icon = Icons.Default.Download,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("HTTP Download Worker") {
                            val config = HttpDownloadConfig(
                                url = "https://httpbin.org/bytes/10240", // 10KB test file (works on iOS simulator)
                                savePath = getDummyDownloadPath(context)
                            )
                            scheduleTask("HttpDownloadWorker scheduled") {
                                scheduler.enqueue(
                                    id = "demo-builtin-httpdownload",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 1.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_DOWNLOAD_WORKER,
                                    inputJson = Json.encodeToString(HttpDownloadConfig.serializer(), config),
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "HTTP Upload Worker",
                    description = "Upload a dummy file (POST) - ⚠️ Requires file creation first",
                    icon = Icons.Default.UploadFile,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("HTTP Upload Worker") {
                            // This requires a dummy file to exist for the demo to work
                            // For a real demo, you'd create this file first
                            val config = HttpUploadConfig(
                                url = "https://httpbin.org/post", // A public echo service
                                filePath = getDummyUploadPath(context),
                                fileFieldName = "file",
                                fileName = "upload_test.txt",
                                mimeType = "text/plain"
                            )
                            scheduleTask("HttpUploadWorker scheduled. (Requires dummy file)") {
                                scheduler.enqueue(
                                    id = "demo-builtin-httpupload",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 1.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HTTP_UPLOAD_WORKER,
                                    inputJson = Json.encodeToString(HttpUploadConfig.serializer(), config),
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "File Compression Worker",
                    description = "Compress a dummy folder into a zip - ⚠️ Requires folder/file creation first",
                    icon = Icons.Default.FolderZip,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("File Compression Worker") {
                            // This requires a dummy folder/files to exist
                            val config = FileCompressionConfig(
                                inputPath = getDummyCompressionInputPath(context),
                                outputPath = getDummyCompressionOutputPath(context),
                                compressionLevel = "medium"
                            )
                            scheduleTask("FileCompressionWorker scheduled. (Requires dummy folder)") {
                                scheduler.enqueue(
                                    id = "demo-builtin-filecompression",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 1.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.FILE_COMPRESSION_WORKER,
                                    inputJson = Json.encodeToString(FileCompressionConfig.serializer(), config)
                                )
                            }
                        }
                    }
                )
            }

            DemoSection(
                title = "v2.3.3 Bug Fixes",
                icon = Icons.Default.BugReport,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                // Fix #1: WorkManager 2.10.0+ compatibility
                DemoCard(
                    title = "Fix #1: WorkManager 2.10.0+ Compat",
                    description = "OneTime expedited task now works with WorkManager 2.10.0+. " +
                        "Previously crashed with IllegalStateException: Not implemented (getForegroundInfo).",
                    icon = Icons.Default.CheckCircle,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Fix1: Expedited task (WorkManager 2.10.0+ compat)") {
                            scheduleTask("✅ Fix #1: Expedited task scheduled — no crash on WorkManager 2.10.0+") {
                                scheduler.enqueue(
                                    id = "v233-fix1-expedited",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER,
                                    constraints = Constraints(isHeavyTask = false)
                                )
                            }
                        }
                    }
                )
                // Fix #2: Heavy task routing in chains
                DemoCard(
                    title = "Fix #2: Heavy Task in Chain",
                    description = "Chain with isHeavyTask=true now correctly uses KmpHeavyWorker (foreground service). " +
                        "Previously both branches silently used KmpWorker.",
                    icon = Icons.Default.AccountTree,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Fix2: Heavy task routing in chain") {
                            scheduler.beginWith(
                                dev.brewkits.kmpworkmanager.background.domain.TaskRequest(
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER,
                                    constraints = Constraints(isHeavyTask = false)
                                )
                            )
                            .then(
                                dev.brewkits.kmpworkmanager.background.domain.TaskRequest(
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HEAVY_PROCESSING_WORKER,
                                    constraints = Constraints(isHeavyTask = true)
                                )
                            )
                            .then(
                                dev.brewkits.kmpworkmanager.background.domain.TaskRequest(
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.UPLOAD_WORKER,
                                    constraints = Constraints(isHeavyTask = false)
                                )
                            )
                            .withId("v233-fix2-heavy-chain", policy = dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy.REPLACE)
                            .enqueue()
                            snackbarHostState.showSnackbar(
                                message = "✅ Fix #2: Chain with heavy task scheduled — KmpHeavyWorker used for step 2",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                )
                // Localization demo
                DemoCard(
                    title = "i18n: Notification String Resources",
                    description = "Notification strings (channel name, title) are now in res/values/strings.xml. " +
                        "Override kmp_worker_notification_title in your app's res/values-xx/strings.xml for localization.",
                    icon = Icons.Default.Language,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("i18n: Localized notification") {
                            // Schedule a regular task — if WorkManager promotes it to foreground,
                            // the notification title will be resolved from string resources (device locale)
                            scheduleTask("ℹ️ i18n: Override 'kmp_worker_notification_title' in res/values-xx/strings.xml for your language") {
                                scheduler.enqueue(
                                    id = "v233-i18n-notification",
                                    trigger = TaskTrigger.OneTime(initialDelayMs = 1.seconds.inWholeMilliseconds),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER,
                                    constraints = Constraints(isHeavyTask = false)
                                )
                            }
                        }
                    }
                )
            }

            DemoSection(
                title = "v2.3.6 Bug Fixes",
                icon = Icons.Default.BugReport,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                // AND-2: Periodic flex interval
                DemoCard(
                    title = "Fix AND-2: Periodic with Flex Interval",
                    description = "Periodic task with flexMs=15min in a 1h interval. " +
                        "Before fix: flexMs was silently ignored on Android. " +
                        "Now: task runs within the 15-minute flex window.",
                    icon = Icons.Default.Schedule,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Fix AND-2: Periodic with Flex Interval") {
                            scheduleTask("Fix AND-2: Periodic with 15min flex window scheduled (Android: flexMs now respected)") {
                                scheduler.enqueue(
                                    id = "v236-and2-periodic-flex",
                                    trigger = TaskTrigger.Periodic(
                                        intervalMs = 1.hours.inWholeMilliseconds,
                                        flexMs = 15.minutes.inWholeMilliseconds
                                    ),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER,
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            }
                        }
                    }
                )
                // AND-3: REQUIRE_BATTERY_NOT_LOW — non-expedited fallback
                DemoCard(
                    title = "Fix AND-3: Battery-Low Constraint (No Crash)",
                    description = "Task with BatteryOkay (→ REQUIRE_BATTERY_NOT_LOW) is now correctly " +
                        "excluded from expedited mode on Android. " +
                        "Before fix: WorkManager threw IllegalArgumentException when isHeavyTask=false.",
                    icon = Icons.Default.BatteryFull,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Fix AND-3: Battery-Low Constraint") {
                            // BatteryOkay is the legacy trigger that maps to REQUIRE_BATTERY_NOT_LOW.
                            // With Fix AND-3, this task correctly falls back to non-expedited mode
                            // instead of crashing with IllegalArgumentException.
                            @Suppress("DEPRECATION")
                            scheduleTask("Fix AND-3: BatteryOkay task scheduled (non-expedited fallback — no crash)") {
                                scheduler.enqueue(
                                    id = "v236-and3-battery-not-low",
                                    trigger = TaskTrigger.OneTime(), constraints = Constraints(systemConstraints = setOf(dev.brewkits.kmpworkmanager.background.domain.SystemConstraint.REQUIRE_BATTERY_NOT_LOW)),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.CLEANUP_WORKER
                                )
                            }
                        }
                    }
                )
                // IOS-1: Chain without network requirement
                DemoCard(
                    title = "Fix IOS-1: Offline Chain Execution",
                    description = "Chain executor BGTask no longer requires network. " +
                        "Before fix: a file-processing chain would never start on an offline device " +
                        "because the executor itself had requiresNetworkConnectivity=true.",
                    icon = Icons.Default.CloudOff,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Fix IOS-1: Offline Chain Execution") {
                            // Simulate a chain of local-only tasks (no network needed)
                            scheduler.beginWith(
                                dev.brewkits.kmpworkmanager.background.domain.TaskRequest(
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.DATABASE_WORKER,
                                    constraints = Constraints(requiresNetwork = false)
                                )
                            )
                            .then(
                                dev.brewkits.kmpworkmanager.background.domain.TaskRequest(
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.CLEANUP_WORKER,
                                    constraints = Constraints(requiresNetwork = false)
                                )
                            )
                            .withId("v236-ios1-offline-chain", policy = dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy.REPLACE)
                            .enqueue()
                            snackbarHostState.showSnackbar(
                                message = "Fix IOS-1: Offline chain started — executor no longer blocks on network (iOS)",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                )
                // CE-1/2/3: Chain correctness (withTimeout return + CancellationException + for+break)
                DemoCard(
                    title = "Fix CE-1/2/3: Chain Correctness",
                    description = "Chain success now correctly reflects step outcomes (CE-1). " +
                        "BGTask expiry properly cancels running chains (CE-2). " +
                        "Queue-empty check actually stops the batch loop (CE-3). iOS only.",
                    icon = Icons.Default.Link,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Fix CE-1/2/3: Chain Correctness") {
                            // A 3-step chain — on iOS, CE-1 ensures chainSucceeded tracks real outcome,
                            // CE-2 ensures BGTask expiry signal propagates, CE-3 ensures loop stops early.
                            scheduler.beginWith(
                                dev.brewkits.kmpworkmanager.background.domain.TaskRequest(
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER
                                )
                            )
                            .then(
                                dev.brewkits.kmpworkmanager.background.domain.TaskRequest(
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.DATABASE_WORKER
                                )
                            )
                            .then(
                                dev.brewkits.kmpworkmanager.background.domain.TaskRequest(
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.ANALYTICS_WORKER
                                )
                            )
                            .withId("v236-ce-chain-correctness", policy = dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy.REPLACE)
                            .enqueue()
                            snackbarHostState.showSnackbar(
                                message = "Fix CE-1/2/3: Chain scheduled — success flag, cancellation and loop break all fixed",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                )
            }

            DemoSection(
                title = "v2.4.2 Bug Fixes",
                icon = Icons.Default.BugReport,
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                DemoCard(
                    title = "Fix: Periodic runImmediately=true",
                    description = "Android: runImmediately=true now executes without delay, even with flexMs defaults.",
                    icon = Icons.Default.FastForward,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Fix: Periodic runImmediately=true") {
                            scheduleTask("Periodic runImmediately=true scheduled") {
                                scheduler.enqueue(
                                    id = "v242-periodic-immediate",
                                    trigger = TaskTrigger.Periodic(
                                        intervalMs = 15.minutes.inWholeMilliseconds,
                                        runImmediately = true
                                    ),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER
                                )
                            }
                        }
                    }
                )
                DemoCard(
                    title = "Fix: Periodic with REPLACE policy",
                    description = "iOS: REPLACE policy no longer incorrectly applies drift-correction delay.",
                    icon = Icons.Default.PublishedWithChanges,
                    enabled = !isAnyTaskRunning,
                    onClick = {
                        runTask("Fix: Periodic with REPLACE policy") {
                            scheduleTask("Periodic with REPLACE policy scheduled") {
                                scheduler.enqueue(
                                    id = "v242-periodic-replace",
                                    trigger = TaskTrigger.Periodic(
                                        intervalMs = 15.minutes.inWholeMilliseconds
                                    ),
                                    workerClassName = dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER,
                                    policy = dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy.REPLACE
                                )
                            }
                        }
                    }
                )
            }

            // Quick Actions
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        runTask("File Compression Worker") {
                            val (uploadedFilePath, compressedFolderPath) = createDummyFiles(context)
                            snackbarHostState.showSnackbar(message = "Dummy files created. Upload path: $uploadedFilePath, Compression folder: $compressedFolderPath", duration = SnackbarDuration.Short)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Setup Dummy Files", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = {
                        runTask("File Compression Worker") {
                            scheduler.cancelAll()
                            snackbarHostState.showSnackbar(message = "All tasks cancelled", duration = SnackbarDuration.Short)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cancel All", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun DemoSection(
    title: String,
    icon: ImageVector,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable ColumnScope.() -> Unit
) {
    var isExpanded by remember { mutableStateOf(true) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand"
                    )
                }
            }
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun DemoCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Run",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}