package dev.brewkits.kmpworkmanager.sample.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.brewkits.kmpworkmanager.sample.background.data.WorkerTypes
import dev.brewkits.kmpworkmanager.sample.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.sample.background.domain.Constraints
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskTrigger
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
fun TaskBuilderScreen(scheduler: BackgroundTaskScheduler) {
    var selectedWorker by remember { mutableStateOf(WorkerTypes.SYNC_WORKER) }
    var taskId by remember { mutableStateOf("custom-task-${Clock.System.now().toEpochMilliseconds()}") }
    var triggerType by remember { mutableStateOf(TriggerType.ONE_TIME) }
    var delayAmount by remember { mutableStateOf("5") }
    var delayUnit by remember { mutableStateOf(TimeUnit.SECONDS) }

    // Constraints
    var requiresNetwork by remember { mutableStateOf(false) }
    var requiresUnmetered by remember { mutableStateOf(false) }
    var requiresCharging by remember { mutableStateOf(false) }
    var isHeavyTask by remember { mutableStateOf(false) }

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
                "Task Builder",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Create custom background tasks with specific configurations",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Worker Selection
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Worker Type", style = MaterialTheme.typography.titleMedium)
                    var expanded by remember { mutableStateOf(false) }
                    val workers = listOf(
                        WorkerTypes.SYNC_WORKER to "Sync Worker",
                        WorkerTypes.UPLOAD_WORKER to "Upload Worker",
                        WorkerTypes.HEAVY_PROCESSING_WORKER to "Heavy Processing",
                        WorkerTypes.DATABASE_WORKER to "Database Worker",
                        WorkerTypes.NETWORK_RETRY_WORKER to "Network Retry",
                        WorkerTypes.IMAGE_PROCESSING_WORKER to "Image Processing",
                        WorkerTypes.LOCATION_SYNC_WORKER to "Location Sync",
                        WorkerTypes.CLEANUP_WORKER to "Cleanup Worker",
                        WorkerTypes.BATCH_UPLOAD_WORKER to "Batch Upload",
                        WorkerTypes.ANALYTICS_WORKER to "Analytics Worker"
                    )
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = workers.find { it.first == selectedWorker }?.second ?: "Unknown",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            workers.forEach { (workerType, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        selectedWorker = workerType
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Task ID
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Task ID", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = taskId,
                        onValueChange = { taskId = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter unique task ID") }
                    )
                }
            }

            // Trigger Configuration
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Trigger Type", style = MaterialTheme.typography.titleMedium)
                    TriggerType.entries.forEach { type ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = triggerType == type,
                                onClick = { triggerType = type }
                            )
                            Text(
                                type.label,
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    if (triggerType == TriggerType.ONE_TIME || triggerType == TriggerType.PERIODIC) {
                        HorizontalDivider()
                        Text("Delay/Interval", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = delayAmount,
                                onValueChange = { if (it.all { c -> c.isDigit() }) delayAmount = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Amount") }
                            )
                            var unitExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = unitExpanded,
                                onExpandedChange = { unitExpanded = !unitExpanded },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = delayUnit.label,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    label = { Text("Unit") }
                                )
                                ExposedDropdownMenu(
                                    expanded = unitExpanded,
                                    onDismissRequest = { unitExpanded = false }
                                ) {
                                    TimeUnit.entries.forEach { unit ->
                                        DropdownMenuItem(
                                            text = { Text(unit.label) },
                                            onClick = {
                                                delayUnit = unit
                                                unitExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Constraints
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Constraints", style = MaterialTheme.typography.titleMedium)
                    ConstraintSwitch("Network Required", requiresNetwork) { requiresNetwork = it }
                    ConstraintSwitch("Unmetered Network Only", requiresUnmetered) { requiresUnmetered = it }
                    ConstraintSwitch("Charging Required", requiresCharging) { requiresCharging = it }
                    ConstraintSwitch("Heavy Task", isHeavyTask) { isHeavyTask = it }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        taskId = "custom-task-${Clock.System.now().toEpochMilliseconds()}"
                        triggerType = TriggerType.ONE_TIME
                        delayAmount = "5"
                        delayUnit = TimeUnit.SECONDS
                        requiresNetwork = false
                        requiresUnmetered = false
                        requiresCharging = false
                        isHeavyTask = false
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset")
                }

                Button(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                val delay = delayAmount.toLongOrNull() ?: 5L
                                val delayMs = when (delayUnit) {
                                    TimeUnit.SECONDS -> delay.seconds
                                    TimeUnit.MINUTES -> delay.minutes
                                    TimeUnit.HOURS -> delay.hours
                                }.inWholeMilliseconds
                                val trigger = when (triggerType) {
                                    TriggerType.ONE_TIME -> TaskTrigger.OneTime(initialDelayMs = delayMs)
                                    TriggerType.PERIODIC -> TaskTrigger.Periodic(intervalMs = delayMs)
                                    TriggerType.BATTERY_OK -> TaskTrigger.BatteryOkay
                                    TriggerType.DEVICE_IDLE -> TaskTrigger.DeviceIdle
                                }
                                val constraints = Constraints(
                                    requiresNetwork = requiresNetwork,
                                    requiresUnmeteredNetwork = requiresUnmetered,
                                    requiresCharging = requiresCharging,
                                    isHeavyTask = isHeavyTask
                                )
                                scheduler.enqueue(
                                    id = taskId,
                                    trigger = trigger,
                                    workerClassName = selectedWorker,
                                    constraints = constraints
                                )
                                snackbarHostState.showSnackbar("Task '$taskId' scheduled successfully!")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Error: ${e.message}")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Schedule Task")
                }
            }
        }
    }
}

@Composable
private fun ConstraintSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private enum class TriggerType(val label: String) {
    ONE_TIME("One Time"),
    PERIODIC("Periodic"),
    BATTERY_OK("Battery Okay (Android)"),
    DEVICE_IDLE("Device Idle (Android)")
}

private enum class TimeUnit(val label: String) {
    SECONDS("Seconds"),
    MINUTES("Minutes"),
    HOURS("Hours")
}
