package dev.brewkits.kmpworkmanager.sample.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brewkits.kmpworkmanager.sample.logs.LogEntry
import dev.brewkits.kmpworkmanager.sample.logs.LogStore
import dev.brewkits.kmpworkmanager.sample.utils.Logger
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun LogViewerScreen() {
    val logs by LogStore.logs.collectAsState()
    var selectedTag by remember { mutableStateOf("All") }
    var selectedLevel by remember { mutableStateOf<Logger.Level?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // Filter logs based on selected tag, level, and search query
    val filteredLogs = remember(logs, selectedTag, selectedLevel, searchQuery) {
        logs.filter { entry ->
            val matchesTag = selectedTag == "All" || entry.tag == selectedTag
            val matchesLevel = selectedLevel == null || entry.level == selectedLevel
            val matchesSearch = searchQuery.isBlank() ||
                    entry.message.contains(searchQuery, ignoreCase = true) ||
                    entry.tag.contains(searchQuery, ignoreCase = true)
            matchesTag && matchesLevel && matchesSearch
        }
    }

    // Available tags from current logs
    val availableTags = remember(logs) {
        listOf("All") + logs.map { it.tag }.distinct().sorted()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                // Search bar
                if (isSearchExpanded) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        placeholder = { Text("Search logs...") },
                        leadingIcon = { Icon(Icons.Default.Search, "Search") },
                        trailingIcon = {
                            IconButton(onClick = {
                                searchQuery = ""
                                isSearchExpanded = false
                            }) {
                                Icon(Icons.Default.Clear, "Clear search")
                            }
                        },
                        singleLine = true
                    )
                }

                // Filter chips row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Search toggle button
                    if (!isSearchExpanded) {
                        IconButton(onClick = { isSearchExpanded = true }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                    }

                    // Tag filter dropdown
                    Box {
                        var tagMenuExpanded by remember { mutableStateOf(false) }
                        FilterChip(
                            selected = selectedTag != "All",
                            onClick = { tagMenuExpanded = true },
                            label = { Text("Tag: $selectedTag", style = MaterialTheme.typography.labelSmall) }
                        )
                        DropdownMenu(
                            expanded = tagMenuExpanded,
                            onDismissRequest = { tagMenuExpanded = false }
                        ) {
                            availableTags.forEach { tag ->
                                DropdownMenuItem(
                                    text = { Text(tag) },
                                    onClick = {
                                        selectedTag = tag
                                        tagMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Level filters
                    Logger.Level.entries.forEach { level ->
                        FilterChip(
                            selected = selectedLevel == level,
                            onClick = {
                                selectedLevel = if (selectedLevel == level) null else level
                            },
                            label = {
                                Text(
                                    text = level.displayName(),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = level.color().copy(alpha = 0.3f)
                            )
                        )
                    }
                }

                // Action buttons row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                LogStore.clear()
                                snackbarHostState.showSnackbar("Logs cleared")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear Logs", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Export: ${filteredLogs.size} logs")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = filteredLogs.isNotEmpty()
                    ) {
                        Text("Export (${filteredLogs.size})", style = MaterialTheme.typography.labelSmall)
                    }
                }

                HorizontalDivider()
            }
        }
    ) { paddingValues ->
        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No logs to display",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Logs will appear here as tasks execute",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(8.dp)
            ) {
                items(filteredLogs, key = { "${it.timestamp}-${it.tag}-${it.message.hashCode()}" }) { entry ->
                    LogEntryItem(entry)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun LogEntryItem(entry: LogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                entry.level.color().copy(alpha = 0.05f),
                shape = MaterialTheme.shapes.small
            )
            .padding(8.dp)
    ) {
        // Level indicator bar
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(48.dp)
                .background(entry.level.color(), shape = MaterialTheme.shapes.small)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Header: timestamp + level + tag
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.formatTimestamp(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
                Surface(
                    color = entry.level.color().copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = entry.level.displayName(),
                        style = MaterialTheme.typography.labelSmall,
                        color = entry.level.color(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = entry.tag,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Message
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun LogEntry.formatTimestamp(): String {
    val duration = (Clock.System.now().toEpochMilliseconds() - timestamp).milliseconds
    return when {
        duration.inWholeSeconds < 60 -> "${duration.inWholeSeconds}s ago"
        duration.inWholeMinutes < 60 -> "${duration.inWholeMinutes}m ago"
        else -> "${duration.inWholeHours}h ago"
    }
}

private fun Logger.Level.displayName(): String = when (this) {
    Logger.Level.DEBUG_LEVEL -> "DEBUG"
    Logger.Level.INFO -> "INFO"
    Logger.Level.WARN -> "WARN"
    Logger.Level.ERROR -> "ERROR"
}

private fun Logger.Level.color(): Color = when (this) {
    Logger.Level.DEBUG_LEVEL -> Color.Gray
    Logger.Level.INFO -> Color(0xFF2196F3)
    Logger.Level.WARN -> Color(0xFFFF9800)
    Logger.Level.ERROR -> Color(0xFFF44336)
}
