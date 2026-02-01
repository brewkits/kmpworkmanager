package dev.brewkits.kmpworkmanager.sample.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.brewkits.kmpworkmanager.sample.background.data.WorkerTypes
import dev.brewkits.kmpworkmanager.sample.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.sample.background.domain.Constraints
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskTrigger
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun DemoScenariosScreen(scheduler: BackgroundTaskScheduler) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

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

            // Basic Tasks Section
            DemoSection(
                title = "Basic Tasks",
                icon = Icons.Default.PlayArrow
            ) {
                DemoCard(
                    title = "Quick Sync",
                    description = "OneTime task with no constraints",
                    icon = Icons.Default.Sync,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-quick-sync",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.SYNC_WORKER
                            )
                            snackbarHostState.showSnackbar("Quick Sync scheduled (2s delay)")
                        }
                    }
                )
                DemoCard(
                    title = "File Upload",
                    description = "OneTime with network required",
                    icon = Icons.Default.Upload,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-file-upload",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 5.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.UPLOAD_WORKER,
                                constraints = Constraints(requiresNetwork = true)
                            )
                            snackbarHostState.showSnackbar("File Upload scheduled (5s, network required)")
                        }
                    }
                )
                DemoCard(
                    title = "Database Operation",
                    description = "Batch inserts with progress",
                    icon = Icons.Default.Storage,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-database",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.DATABASE_WORKER
                            )
                            snackbarHostState.showSnackbar("Database Worker scheduled (3s delay)")
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
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-hourly-sync",
                                trigger = TaskTrigger.Periodic(intervalMs = 1.hours.inWholeMilliseconds),
                                workerClassName = WorkerTypes.SYNC_WORKER,
                                constraints = Constraints(requiresNetwork = true, requiresUnmeteredNetwork = true)
                            )
                            snackbarHostState.showSnackbar("Hourly Sync scheduled (1h interval)")
                        }
                    }
                )
                DemoCard(
                    title = "Daily Cleanup",
                    description = "Runs every 24 hours while charging",
                    icon = Icons.Default.CleaningServices,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-daily-cleanup",
                                trigger = TaskTrigger.Periodic(intervalMs = 24.hours.inWholeMilliseconds),
                                workerClassName = WorkerTypes.CLEANUP_WORKER,
                                constraints = Constraints(requiresCharging = true)
                            )
                            snackbarHostState.showSnackbar("Daily Cleanup scheduled (24h, charging)")
                        }
                    }
                )
                DemoCard(
                    title = "Location Sync",
                    description = "Periodic 15min location upload",
                    icon = Icons.Default.LocationOn,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-location-sync",
                                trigger = TaskTrigger.Periodic(intervalMs = 15.minutes.inWholeMilliseconds),
                                workerClassName = WorkerTypes.LOCATION_SYNC_WORKER
                            )
                            snackbarHostState.showSnackbar("Location Sync scheduled (15min)")
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
                    onClick = {
                        coroutineScope.launch {
                            scheduler.beginWith(TaskRequest(workerClassName = WorkerTypes.SYNC_WORKER))
                                .then(TaskRequest(workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER))
                                .then(TaskRequest(workerClassName = WorkerTypes.UPLOAD_WORKER))
                                .enqueue()
                            snackbarHostState.showSnackbar("Sequential chain started")
                        }
                    }
                )
                DemoCard(
                    title = "Parallel: Process 3 Images \u2192 Upload",
                    description = "Parallel processing then upload",
                    icon = Icons.Default.DynamicFeed,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.beginWith(
                                listOf(
                                    TaskRequest(workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER),
                                    TaskRequest(workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER)
                                )
                            )
                                .then(TaskRequest(workerClassName = WorkerTypes.BATCH_UPLOAD_WORKER))
                                .enqueue()
                            snackbarHostState.showSnackbar("Parallel chain started")
                        }
                    }
                )
                DemoCard(
                    title = "Mixed: Fetch \u2192 [Process \u2225 Analyze \u2225 Compress] \u2192 Upload",
                    description = "Sequential + parallel combination",
                    icon = Icons.Default.AccountTree,
                    onClick = {
                        coroutineScope.launch {
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
                            snackbarHostState.showSnackbar("Mixed chain started")
                        }
                    }
                )
                DemoCard(
                    title = "Long Chain: 5 Sequential Steps",
                    description = "Extended workflow demonstration",
                    icon = Icons.Default.LinearScale,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.beginWith(TaskRequest(workerClassName = WorkerTypes.SYNC_WORKER))
                                .then(TaskRequest(workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER))
                                .then(TaskRequest(workerClassName = WorkerTypes.DATABASE_WORKER))
                                .then(TaskRequest(workerClassName = WorkerTypes.ANALYTICS_WORKER))
                                .then(TaskRequest(workerClassName = WorkerTypes.UPLOAD_WORKER))
                                .enqueue()
                            snackbarHostState.showSnackbar("Long chain started (5 steps)")
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
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-network-required",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.SYNC_WORKER,
                                constraints = Constraints(requiresNetwork = true)
                            )
                            snackbarHostState.showSnackbar("Network-constrained task scheduled")
                        }
                    }
                )
                DemoCard(
                    title = "Unmetered Network (WiFi Only)",
                    description = "Only runs on WiFi/unmetered",
                    icon = Icons.Default.WifiTethering,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-unmetered",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.BATCH_UPLOAD_WORKER,
                                constraints = Constraints(requiresNetwork = true, requiresUnmeteredNetwork = true)
                            )
                            snackbarHostState.showSnackbar("WiFi-only task scheduled")
                        }
                    }
                )
                DemoCard(
                    title = "Charging Required",
                    description = "Runs only while device is charging",
                    icon = Icons.Default.BatteryChargingFull,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-charging",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.HEAVY_PROCESSING_WORKER,
                                constraints = Constraints(requiresCharging = true, isHeavyTask = true)
                            )
                            snackbarHostState.showSnackbar("Charging-constrained task scheduled")
                        }
                    }
                )
                DemoCard(
                    title = "Battery Not Low (Android)",
                    description = "Defers when battery is low",
                    icon = Icons.Default.BatteryFull,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-battery-ok",
                                trigger = TaskTrigger.BatteryOkay,
                                workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER
                            )
                            snackbarHostState.showSnackbar("Battery-OK task scheduled")
                        }
                    }
                )
                DemoCard(
                    title = "Storage Low Cleanup (Android)",
                    description = "Cleanup task for low storage scenarios",
                    icon = Icons.Default.SdCard,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-storage-low",
                                trigger = TaskTrigger.StorageLow,
                                workerClassName = WorkerTypes.CLEANUP_WORKER
                            )
                            snackbarHostState.showSnackbar("Storage-low task scheduled (Android only)")
                        }
                    }
                )
                DemoCard(
                    title = "Device Idle (Android)",
                    description = "Runs when device is idle/sleeping",
                    icon = Icons.Default.NightsStay,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-device-idle",
                                trigger = TaskTrigger.DeviceIdle,
                                workerClassName = WorkerTypes.HEAVY_PROCESSING_WORKER,
                                constraints = Constraints(isHeavyTask = true)
                            )
                            snackbarHostState.showSnackbar("Device-idle task scheduled (Android only)")
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
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-retry",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.NETWORK_RETRY_WORKER
                            )
                            snackbarHostState.showSnackbar("Retry demo started (watch logs)")
                        }
                    }
                )
                DemoCard(
                    title = "Random Database Failure",
                    description = "10% chance of transaction failure",
                    icon = Icons.Default.Error,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-db-fail",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.DATABASE_WORKER
                            )
                            snackbarHostState.showSnackbar("Database worker scheduled (may fail)")
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
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-heavy",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 3.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.HEAVY_PROCESSING_WORKER,
                                constraints = Constraints(isHeavyTask = true)
                            )
                            snackbarHostState.showSnackbar("Heavy task scheduled (ForegroundService/BGProcessingTask)")
                        }
                    }
                )
                DemoCard(
                    title = "Batch Upload (5 Files)",
                    description = "Multiple file uploads with progress",
                    icon = Icons.Default.CloudUpload,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-batch-upload",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.BATCH_UPLOAD_WORKER
                            )
                            snackbarHostState.showSnackbar("Batch upload started (5 files)")
                        }
                    }
                )
                DemoCard(
                    title = "Image Processing (5 Images x 3 Sizes)",
                    description = "CPU-intensive image resizing",
                    icon = Icons.Default.Image,
                    onClick = {
                        coroutineScope.launch {
                            scheduler.enqueue(
                                id = "demo-image-proc",
                                trigger = TaskTrigger.OneTime(initialDelayMs = 2.seconds.inWholeMilliseconds),
                                workerClassName = WorkerTypes.IMAGE_PROCESSING_WORKER
                            )
                            snackbarHostState.showSnackbar("Image processing started (15 operations)")
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
                        coroutineScope.launch {
                            scheduler.cancelAll()
                            snackbarHostState.showSnackbar("All tasks cancelled")
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
    onClick: () -> Unit
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
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
