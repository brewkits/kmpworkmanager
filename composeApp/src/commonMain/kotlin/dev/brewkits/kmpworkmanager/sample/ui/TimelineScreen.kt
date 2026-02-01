package dev.brewkits.kmpworkmanager.sample.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.brewkits.kmpworkmanager.sample.stats.TaskExecution
import dev.brewkits.kmpworkmanager.sample.stats.TaskStatsManager
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun TimelineScreen() {
    val executions by TaskStatsManager.recentExecutions.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(
            "Task Execution Timeline",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (executions.isEmpty()) {
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
                        Icons.Default.Timeline,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        "No execution history",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Task execution history will appear here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val groupedExecutions = executions.groupBy { it.taskName }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                groupedExecutions.forEach { (taskName, taskExecutions) ->
                    item {
                        TaskGroup(taskName, taskExecutions.reversed())
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskGroup(taskName: String, executions: List<TaskExecution>) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Work,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    taskName,
                    style = MaterialTheme.typography.titleMedium
                )
                Badge {
                    Text(executions.size.toString())
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            executions.forEach { execution ->
                TimelineItem(execution)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TimelineItem(execution: TaskExecution) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status indicator
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(40.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = when (execution.success) {
                            true -> Color(0xFF4CAF50)  // Green
                            false -> Color(0xFFF44336) // Red
                            null -> Color(0xFFFFEB3B)  // Yellow
                        },
                        shape = MaterialTheme.shapes.small
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when (execution.success) {
                        true -> Icons.Default.Check
                        false -> Icons.Default.Close
                        null -> Icons.Default.HourglassEmpty
                    },
                    tint = Color.White,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
            // Connector line
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }

        // Task details
        Surface(
            modifier = Modifier.weight(1f),
            color = when (execution.success) {
                true -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                false -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                null -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
            },
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when (execution.success) {
                            true -> "Success"
                            false -> "Failed"
                            null -> "Running"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = when (execution.success) {
                            true -> Color(0xFF4CAF50)
                            false -> Color(0xFFF44336)
                            null -> Color(0xFFFF9800)
                        }
                    )
                    if (execution.duration != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                formatDuration(execution.duration),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "ID: ${execution.taskId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (execution.endTime != null) {
                    Text(
                        formatTimestamp(execution.endTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val duration = durationMs.milliseconds
    return when {
        duration.inWholeSeconds < 1 -> "${durationMs}ms"
        duration.inWholeSeconds < 60 -> "${duration.inWholeSeconds}s"
        duration.inWholeMinutes < 60 -> "${duration.inWholeMinutes}m ${duration.inWholeSeconds % 60}s"
        else -> "${duration.inWholeHours}h ${duration.inWholeMinutes % 60}m"
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun formatTimestamp(timestamp: Long): String {
    val now = Clock.System.now().toEpochMilliseconds()
    val diff = (now - timestamp).milliseconds
    return when {
        diff.inWholeSeconds < 60 -> "${diff.inWholeSeconds}s ago"
        diff.inWholeMinutes < 60 -> "${diff.inWholeMinutes}m ago"
        diff.inWholeHours < 24 -> "${diff.inWholeHours}h ago"
        else -> "${diff.inWholeDays}d ago"
    }
}
