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
import dev.brewkits.kmpworkmanager.sample.background.data.WorkerTypes
import dev.brewkits.kmpworkmanager.sample.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.sample.background.domain.Constraints
import dev.brewkits.kmpworkmanager.sample.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskTrigger
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
        dev.brewkits.kmpworkmanager.sample.background.domain.TaskEventBus.events.collect { event ->
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
                            scheduler.enqueue(
                                id = "demo-quick-sync",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.SYNC_WORKER
                            )
                            snackbarHostState.showSnackbar(message = "Quick Sync scheduled (2s delay)", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-file-upload",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 5.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.UPLOAD_WORKER,
                                constraints = Constraints(requiresNetwork = true)
                            )
                            snackbarHostState.showSnackbar(message = "File Upload scheduled (5s, network required)", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-database",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.DATABASE_WORKER
                            )
                            snackbarHostState.showSnackbar(message = "Database Worker scheduled (3s delay)", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-hourly-sync",
                                trigger = TaskTrigger.Periodic(intervalMs = 1.hours.inWholeMilliseconds),
                                workerClassName = WorkerTypes.SYNC_WORKER,
                                constraints = Constraints(requiresNetwork = true, requiresUnmeteredNetwork = true)
                            )
                            snackbarHostState.showSnackbar(message = "Hourly Sync scheduled (1h interval)", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-daily-cleanup",
                                trigger = TaskTrigger.Periodic(intervalMs = 24.hours.inWholeMilliseconds),
                                workerClassName = WorkerTypes.CLEANUP_WORKER,
                                constraints = Constraints(requiresCharging = true)
                            )
                            snackbarHostState.showSnackbar(message = "Daily Cleanup scheduled (24h, charging)", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-location-sync",
                                trigger = TaskTrigger.Periodic(intervalMs = 15.minutes.inWholeMilliseconds),
                                workerClassName = WorkerTypes.LOCATION_SYNC_WORKER
                            )
                            snackbarHostState.showSnackbar(message = "Location Sync scheduled (15min)", duration = SnackbarDuration.Short)
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
                            scheduler.beginWith(TaskRequest(workerClassName = WorkerTypes.SYNC_WORKER))
                                .then(TaskRequest(workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER))
                                .then(TaskRequest(workerClassName = WorkerTypes.UPLOAD_WORKER))
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
                                    TaskRequest(workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER),
                                    TaskRequest(workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER)
                                )
                            )
                                .then(TaskRequest(workerClassName = WorkerTypes.BATCH_UPLOAD_WORKER))
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
                            scheduler.beginWith(TaskRequest(workerClassName = WorkerTypes.SYNC_WORKER))
                                .then(
                                    listOf(
                                        TaskRequest(workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER),
                                        TaskRequest(workerClassName = WorkerTypes.ANALYTICS_WORKER),
                                        TaskRequest(workerClassName = WorkerTypes.DATABASE_WORKER)
                                    )
                                )
                                .then(TaskRequest(workerClassName = WorkerTypes.UPLOAD_WORKER))
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
                            scheduler.beginWith(TaskRequest(workerClassName = WorkerTypes.SYNC_WORKER))
                                .then(TaskRequest(workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER))
                                .then(TaskRequest(workerClassName = WorkerTypes.DATABASE_WORKER))
                                .then(TaskRequest(workerClassName = WorkerTypes.ANALYTICS_WORKER))
                                .then(TaskRequest(workerClassName = WorkerTypes.UPLOAD_WORKER))
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
                            scheduler.enqueue(
                                id = "demo-network-required",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.SYNC_WORKER,
                                constraints = Constraints(requiresNetwork = true)
                            )
                            snackbarHostState.showSnackbar(message = "Network-constrained task scheduled", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-unmetered",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.BATCH_UPLOAD_WORKER,
                                constraints = Constraints(requiresNetwork = true, requiresUnmeteredNetwork = true)
                            )
                            snackbarHostState.showSnackbar(message = "WiFi-only task scheduled", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-charging",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.HEAVY_PROCESSING_WORKER,
                                constraints = Constraints(requiresCharging = true, isHeavyTask = true)
                            )
                            snackbarHostState.showSnackbar(message = "Charging-constrained task scheduled", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-battery-ok",
                                trigger = TaskTrigger.BatteryOkay,
                                workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER
                            )
                            snackbarHostState.showSnackbar(message = "Battery-OK task scheduled", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-storage-low",
                                trigger = TaskTrigger.StorageLow,
                                workerClassName = WorkerTypes.CLEANUP_WORKER
                            )
                            snackbarHostState.showSnackbar(message = "Storage-low task scheduled (Android only)", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-device-idle",
                                trigger = TaskTrigger.DeviceIdle,
                                workerClassName = WorkerTypes.HEAVY_PROCESSING_WORKER,
                                constraints = Constraints(isHeavyTask = true)
                            )
                            snackbarHostState.showSnackbar(message = "Device-idle task scheduled (Android only)", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-retry",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.NETWORK_RETRY_WORKER
                            )
                            snackbarHostState.showSnackbar(message = "Retry demo started (watch logs)", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-db-fail",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.DATABASE_WORKER
                            )
                            snackbarHostState.showSnackbar(message = "Database worker scheduled (may fail)", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-heavy",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.HEAVY_PROCESSING_WORKER,
                                constraints = Constraints(isHeavyTask = true)
                            )
                            snackbarHostState.showSnackbar(message = "Heavy task scheduled (ForegroundService/BGProcessingTask)", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-batch-upload",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.BATCH_UPLOAD_WORKER
                            )
                            snackbarHostState.showSnackbar(message = "Batch upload started (5 files)", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-image-proc",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER
                            )
                            snackbarHostState.showSnackbar(message = "Image processing started (15 operations)", duration = SnackbarDuration.Short)
                        }
                    }
                )
            }

            // Built-in Worker Chains Section (v2.3.0 Feature)
            DemoSection(
                title = "Built-in Worker Chains (v2.3.0)",
                icon = Icons.Default.AccountTree,
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                DemoCard(
                    title = "Download \u2192 Compress \u2192 Upload Chain",
                    description = "Complete workflow: Download file, compress it, then upload (v2.3.0 data passing)",
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
                                    workerClassName = WorkerTypes.HTTP_DOWNLOAD_WORKER,
                                    inputJson = Json.encodeToString(HttpDownloadConfig.serializer(), downloadConfig),
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            )
                                .then(
                                    TaskRequest(
                                        workerClassName = WorkerTypes.FILE_COMPRESSION_WORKER,
                                        inputJson = Json.encodeToString(FileCompressionConfig.serializer(), compressionConfig)
                                    )
                                )
                                .then(
                                    TaskRequest(
                                        workerClassName = WorkerTypes.HTTP_UPLOAD_WORKER,
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
                                        workerClassName = WorkerTypes.HTTP_SYNC_WORKER,
                                        inputJson = Json.encodeToString(HttpSyncConfig.serializer(), config),
                                        constraints = Constraints(requiresNetwork = true)
                                    )
                                }
                            )
                                .then(
                                    TaskRequest(
                                        workerClassName = WorkerTypes.FILE_COMPRESSION_WORKER,
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
                                body = """{"title":"Chain Demo","body":"v2.3.0 test","userId":1}""",
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
                                    workerClassName = WorkerTypes.HTTP_REQUEST_WORKER,
                                    inputJson = Json.encodeToString(HttpRequestConfig.serializer(), requestConfig),
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            )
                                .then(
                                    TaskRequest(
                                        workerClassName = WorkerTypes.HTTP_SYNC_WORKER,
                                        inputJson = Json.encodeToString(HttpSyncConfig.serializer(), syncConfig),
                                        constraints = Constraints(requiresNetwork = true)
                                    )
                                )
                                .then(
                                    TaskRequest(
                                        workerClassName = WorkerTypes.HTTP_UPLOAD_WORKER,
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
                                    workerClassName = WorkerTypes.HTTP_DOWNLOAD_WORKER,
                                    inputJson = Json.encodeToString(HttpDownloadConfig.serializer(), downloadConfig),
                                    constraints = Constraints(requiresNetwork = true)
                                )
                            )
                                .then(
                                    TaskRequest(
                                        workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER // Simulate processing
                                    )
                                )
                                .then(
                                    TaskRequest(
                                        workerClassName = WorkerTypes.FILE_COMPRESSION_WORKER,
                                        inputJson = Json.encodeToString(FileCompressionConfig.serializer(), compressionConfig)
                                    )
                                )
                                .then(
                                    TaskRequest(
                                        workerClassName = WorkerTypes.HTTP_SYNC_WORKER,
                                        inputJson = Json.encodeToString(HttpSyncConfig.serializer(), syncConfig),
                                        constraints = Constraints(requiresNetwork = true)
                                    )
                                )
                                .then(
                                    TaskRequest(
                                        workerClassName = WorkerTypes.HTTP_UPLOAD_WORKER,
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
                            scheduler.enqueue(
                                id = "demo-builtin-httprequest",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 1.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.HTTP_REQUEST_WORKER,
                                inputJson = Json.encodeToString(HttpRequestConfig.serializer(), config),
                                constraints = Constraints(requiresNetwork = true)
                            )
                            snackbarHostState.showSnackbar(message = "HttpRequestWorker scheduled", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-builtin-httpsync",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 1.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.HTTP_SYNC_WORKER,
                                inputJson = Json.encodeToString(HttpSyncConfig.serializer(), config),
                                constraints = Constraints(requiresNetwork = true)
                            )
                            snackbarHostState.showSnackbar(message = "HttpSyncWorker scheduled", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-builtin-httpdownload",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 1.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.HTTP_DOWNLOAD_WORKER,
                                inputJson = Json.encodeToString(HttpDownloadConfig.serializer(), config),
                                constraints = Constraints(requiresNetwork = true)
                            )
                            snackbarHostState.showSnackbar(message = "HttpDownloadWorker scheduled", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-builtin-httpupload",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 1.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.HTTP_UPLOAD_WORKER,
                                inputJson = Json.encodeToString(HttpUploadConfig.serializer(), config),
                                constraints = Constraints(requiresNetwork = true)
                            )
                            snackbarHostState.showSnackbar(message = "HttpUploadWorker scheduled. (Requires dummy file)", duration = SnackbarDuration.Short)
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
                            scheduler.enqueue(
                                id = "demo-builtin-filecompression",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 1.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.FILE_COMPRESSION_WORKER,
                                inputJson = Json.encodeToString(FileCompressionConfig.serializer(), config)
                            )
                            snackbarHostState.showSnackbar(message = "FileCompressionWorker scheduled. (Requires dummy folder)", duration = SnackbarDuration.Short)
                        }
                    }
                )
            }

            // v2.3.3 Bug Fixes Demo
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
                            scheduler.enqueue(
                                id = "v233-fix1-expedited",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.SYNC_WORKER,
                                constraints = Constraints(isHeavyTask = false)
                            )
                            snackbarHostState.showSnackbar(
                                message = "✅ Fix #1: Expedited task scheduled — no crash on WorkManager 2.10.0+",
                                duration = SnackbarDuration.Short
                            )
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
                                dev.brewkits.kmpworkmanager.sample.background.domain.TaskRequest(
                                    workerClassName = WorkerTypes.SYNC_WORKER,
                                    constraints = Constraints(isHeavyTask = false)
                                )
                            )
                            .then(
                                dev.brewkits.kmpworkmanager.sample.background.domain.TaskRequest(
                                    workerClassName = WorkerTypes.HEAVY_PROCESSING_WORKER,
                                    constraints = Constraints(isHeavyTask = true)
                                )
                            )
                            .then(
                                dev.brewkits.kmpworkmanager.sample.background.domain.TaskRequest(
                                    workerClassName = WorkerTypes.UPLOAD_WORKER,
                                    constraints = Constraints(isHeavyTask = false)
                                )
                            )
                            .withId("v233-fix2-heavy-chain", policy = dev.brewkits.kmpworkmanager.sample.background.domain.ExistingPolicy.REPLACE)
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
                            scheduler.enqueue(
                                id = "v233-i18n-notification",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 1.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.SYNC_WORKER,
                                constraints = Constraints(isHeavyTask = false)
                            )
                            snackbarHostState.showSnackbar(
                                message = "ℹ️ i18n: Override 'kmp_worker_notification_title' in res/values-xx/strings.xml for your language",
                                duration = SnackbarDuration.Long
                            )
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