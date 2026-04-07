package dev.brewkits.kmpworkmanager.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.ExecutionStatus
import dev.brewkits.kmpworkmanager.sample.background.domain.BackgroundTaskScheduler
import kotlinx.coroutines.launch

/**
 * Demo screen showing execution history records from [BackgroundTaskScheduler.getExecutionHistory].
 */
@Composable
fun ExecutionHistoryScreen(scheduler: BackgroundTaskScheduler) {
    val coroutineScope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<ExecutionRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    fun load() {
        coroutineScope.launch {
            try {
                isLoading = true
                val result = scheduler.getExecutionHistory(limit = 200)
                records = result
                statusMessage = if (result.isEmpty()) "No history yet" else "${result.size} records"
            } catch (e: Exception) {
                statusMessage = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Execution History", style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(statusMessage, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedIconButton(onClick = { load() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
                OutlinedIconButton(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                scheduler.clearExecutionHistory()
                                records = emptyList()
                                statusMessage = "History cleared"
                            } catch (e: Exception) {
                                statusMessage = "Failed to clear: ${e.message}"
                            }
                        }
                    },
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear history")
                }
            }
        }

        // Legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LegendItem(Icons.Default.CheckCircle, Color(0xFF4CAF50), "Success")
            LegendItem(Icons.Default.Error,       Color(0xFFF44336), "Failure")
            LegendItem(Icons.Default.Clear,       Color(0xFFFF9800), "Abandoned")
            LegendItem(Icons.Default.SkipNext,    Color(0xFF9E9E9E), "Skipped")
            LegendItem(Icons.Default.HourglassBottom, Color(0xFF2196F3), "Timeout")
        }

        HorizontalDivider()

        if (isLoading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (records.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No execution records yet.", style = MaterialTheme.typography.bodyLarge)
                    Text("Run some tasks or chains to see history here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(records, key = { it.id + it.startedAtMs }) { record ->
                    ExecutionRecordCard(record)
                }
            }
        }
    }
}

@Composable
private fun ExecutionRecordCard(record: ExecutionRecord) {
    val icon: ImageVector = when (record.status) {
        ExecutionStatus.SUCCESS   -> Icons.Default.CheckCircle
        ExecutionStatus.FAILURE   -> Icons.Default.Error
        ExecutionStatus.ABANDONED -> Icons.Default.Clear
        ExecutionStatus.SKIPPED   -> Icons.Default.SkipNext
        ExecutionStatus.TIMEOUT   -> Icons.Default.HourglassBottom
    }

    val color: Color = when (record.status) {
        ExecutionStatus.SUCCESS   -> Color(0xFF4CAF50)
        ExecutionStatus.FAILURE   -> Color(0xFFF44336)
        ExecutionStatus.ABANDONED -> Color(0xFFFF9800)
        ExecutionStatus.SKIPPED   -> Color(0xFF9E9E9E)
        ExecutionStatus.TIMEOUT   -> Color(0xFF2196F3)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = record.status.name, tint = color,
                modifier = Modifier.padding(top = 2.dp))

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Chain ID + platform badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        record.chainId.take(36),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    PlatformBadge(record.platform)
                }

                // Status line
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.status.name, style = MaterialTheme.typography.bodySmall, color = color)
                    Text("·", style = MaterialTheme.typography.bodySmall)
                    Text(
                        formatDuration(record.durationMs),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (record.retryCount > 0) {
                        Text("·", style = MaterialTheme.typography.bodySmall)
                        Text("retry #${record.retryCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                }

                // Steps
                Text(
                    "${record.completedSteps}/${record.totalSteps} steps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Worker names (truncated)
                if (record.workerClassNames.isNotEmpty()) {
                    Text(
                        record.workerClassNames.joinToString(" → ") { it.substringAfterLast(".") },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                // Error message if present
                record.errorMessage?.let { errorMsg ->
                    Text(
                        errorMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendItem(icon: ImageVector, color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PlatformBadge(platform: String) {
    val bgColor = if (platform == "android") Color(0xFF3DDC84) else Color(0xFF007AFF)
    Box(
        modifier = Modifier
            .background(bgColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            platform.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = bgColor
        )
    }
}

private fun formatDuration(ms: Long): String {
    if (ms < 1_000) return "${ms}ms"
    if (ms < 60_000) {
        val seconds = ms / 1000
        val tenths = (ms % 1000) / 100
        return "$seconds.${tenths}s"
    }
    val minutes = ms / 60_000
    val seconds = (ms % 60_000) / 1000
    return "${minutes}m ${seconds}s"
}
