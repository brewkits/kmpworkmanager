package dev.brewkits.kmpworkmanager.sample.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brewkits.kmpworkmanager.sample.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.sample.debug.DebugSource
import dev.brewkits.kmpworkmanager.sample.debug.DebugTaskInfo
import dev.brewkits.kmpworkmanager.sample.stats.TaskStatsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun LiveMonitorScreen(
    debugSource: DebugSource,
    scheduler: BackgroundTaskScheduler
) {
    var tasks by remember { mutableStateOf<List<DebugTaskInfo>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val stats by TaskStatsManager.stats.collectAsState()

    // Auto-refresh every 2 seconds
    LaunchedEffect(Unit) {
        while (isActive) {
            isRefreshing = true
            tasks = try {
                debugSource.getTasks()
            } catch (e: Exception) {
                emptyList()
            }
            isRefreshing = false
            delay(2000)
        }
    }

    val activeTasks = tasks.filter { it.status == "RUNNING" }
    val queuedTasks = tasks.filter { it.status == "ENQUEUED" }
    val completedTasks = tasks.filter { it.status == "SUCCEEDED" || it.status == "FAILED" }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with refresh indicator
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Live Monitor",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Text(
                            "Auto-refresh: 2s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Summary cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard(
                        title = "Active",
                        count = activeTasks.size,
                        icon = Icons.Default.PlayArrow,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        title = "Queued",
                        count = queuedTasks.size,
                        icon = Icons.Default.Queue,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        title = "Total",
                        count = tasks.size,
                        icon = Icons.Default.Checklist,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Active Tasks Section
            if (activeTasks.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Active Tasks",
                        count = activeTasks.size,
                        icon = Icons.Default.PlayArrow
                    )
                }
                items(activeTasks, key = { it.id }) { task ->
                    ActiveTaskCard(task, scheduler, snackbarHostState, coroutineScope)
                }
            }

            // Queued Tasks Section
            if (queuedTasks.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Queued Tasks",
                        count = queuedTasks.size,
                        icon = Icons.Default.Queue
                    )
                }
                items(queuedTasks, key = { it.id }) { task ->
                    QueuedTaskCard(task, scheduler, snackbarHostState, coroutineScope)
                }
            }

            // Completed Tasks Section (Collapsible)
            if (completedTasks.isNotEmpty()) {
                item {
                    var isExpanded by remember { mutableStateOf(false) }
                    Column {
                        OutlinedCard(
                            onClick = { isExpanded = !isExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                                    Text(
                                        "Completed Tasks",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Badge {
                                        Text(completedTasks.size.toString())
                                    }
                                }
                                Icon(
                                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (isExpanded) "Collapse" else "Expand"
                                )
                            }
                        }
                        if (isExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            completedTasks.take(20).forEach { task ->
                                CompletedTaskCard(task)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }

            // Empty state
            if (tasks.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(48.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Inbox,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                "No tasks scheduled",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Schedule tasks from the Tasks or Demo Scenarios tabs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    count: Int,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(count.toString(), style = MaterialTheme.typography.titleLarge)
            Text(title, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    icon: ImageVector
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Badge {
            Text(count.toString())
        }
    }
}

@Composable
private fun ActiveTaskCard(
    task: DebugTaskInfo,
    scheduler: BackgroundTaskScheduler,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            task.workerClassName.split(".").lastOrNull() ?: task.workerClassName,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "ID: ${task.id.take(16)}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Type: ${task.type}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            scheduler.cancel(task.id)
                            snackbarHostState.showSnackbar("Task ${task.id.take(8)} cancelled")
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun QueuedTaskCard(
    task: DebugTaskInfo,
    scheduler: BackgroundTaskScheduler,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.Queue,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        task.workerClassName.split(".").lastOrNull() ?: task.workerClassName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "ID: ${task.id.take(16)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        scheduler.cancel(task.id)
                        snackbarHostState.showSnackbar("Task cancelled")
                    }
                }
            ) {
                Text("Cancel", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun CompletedTaskCard(task: DebugTaskInfo) {
    val isSuccess = task.status == "SUCCEEDED"
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isSuccess) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.workerClassName.split(".").lastOrNull() ?: task.workerClassName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "ID: ${task.id.take(12)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                color = if (isSuccess) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    task.status,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
