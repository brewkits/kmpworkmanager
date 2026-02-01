package dev.brewkits.kmpworkmanager.sample.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.brewkits.kmpworkmanager.sample.Platform
import dev.brewkits.kmpworkmanager.sample.getPlatform
import dev.brewkits.kmpworkmanager.sample.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.sample.stats.TaskExecution
import dev.brewkits.kmpworkmanager.sample.stats.TaskStatsManager
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun DashboardScreen(
    onNavigateToScenarios: () -> Unit = {},
    onNavigateToBuilder: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {}
) {
    val platform = getPlatform()
    val stats by TaskStatsManager.stats.collectAsState()
    val recentExecutions by TaskStatsManager.recentExecutions.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            "Dashboard",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

        // Stats Cards Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatsCard(
                title = "Total Tasks",
                value = stats.totalExecuted.toString(),
                icon = Icons.Default.Checklist,
                modifier = Modifier.weight(1f)
            )

            StatsCard(
                title = "Success Rate",
                value = "${stats.successRate.toInt()}%",
                icon = Icons.Default.CheckCircle,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatsCard(
                title = "Active",
                value = stats.activeCount.toString(),
                icon = Icons.Default.PlayArrow,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.weight(1f)
            )

            StatsCard(
                title = "Queue Size",
                value = stats.queueSize.toString(),
                icon = Icons.Default.Queue,
                modifier = Modifier.weight(1f)
            )
        }

        // Avg Duration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Column {
                    Text(
                        "Average Duration",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        formatDuration(stats.averageDuration),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        HorizontalDivider()

        // Recent Activity Section
        Text(
            "Recent Activity",
            style = MaterialTheme.typography.titleLarge
        )

        if (recentExecutions.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Inbox,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        "No tasks executed yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Execute tasks from the Demo Scenarios tab",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            recentExecutions.takeLast(10).reversed().forEach { execution ->
                RecentActivityItem(execution)
            }
        }

        HorizontalDivider()

        // Quick Actions Section
        Text(
            "Quick Actions",
            style = MaterialTheme.typography.titleLarge
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedCard(
                onClick = onNavigateToScenarios,
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null)
                    Text("Run Demo", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                }
            }

            OutlinedCard(
                onClick = onNavigateToBuilder,
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Build, contentDescription = null)
                    Text("Create Task", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                }
            }

            OutlinedCard(
                onClick = onNavigateToLogs,
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Article, contentDescription = null)
                    Text("View Logs", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                }
            }
        }

        HorizontalDivider()

        // Platform Info Section
        Text(
            "Platform Info",
            style = MaterialTheme.typography.titleLarge
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Platform", platform.name)
                // Add more platform info as needed
            }
        }
    }
}

@Composable
private fun StatsCard(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Text(
                title,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

@Composable
private fun RecentActivityItem(execution: TaskExecution) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (execution.success == true) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else if (execution.success == false) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                when (execution.success) {
                    true -> Icons.Default.CheckCircle
                    false -> Icons.Default.Error
                    null -> Icons.Default.HourglassEmpty
                },
                contentDescription = null,
                tint = when (execution.success) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    execution.taskName,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "ID: ${execution.taskId.take(12)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (execution.duration != null) {
                Text(
                    formatDuration(execution.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium
        )
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
