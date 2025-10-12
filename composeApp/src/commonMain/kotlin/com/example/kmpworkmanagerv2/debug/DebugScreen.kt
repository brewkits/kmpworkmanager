package com.example.kmpworkmanagerv2.debug

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
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen() {
    val viewModel = remember { DebugViewModel() }
    val tasks by viewModel.tasks.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Background Task Debugger") },
                actions = {
                    Button(onClick = { viewModel.refresh() }) { Text("Refresh") }
                }
            )
        }
    ) {
        paddingValues ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (tasks.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No tasks found.")
                    }
                }
            }
            items(tasks) { task ->
                TaskInfoItem(task)
                Divider()
            }
        }
    }
}

@Composable
private fun TaskInfoItem(task: DebugTaskInfo) {
    val statusColor = when (task.status) {
        "SUCCEEDED" -> Color.Green.copy(alpha = 0.2f)
        "FAILED", "CANCELLED" -> Color.Red.copy(alpha = 0.2f)
        "RUNNING" -> Color.Yellow.copy(alpha = 0.2f)
        "ENQUEUED", "QUEUED" -> Color.Blue.copy(alpha = 0.2f)
        else -> Color.Gray.copy(alpha = 0.2f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(statusColor)
            .padding(16.dp)
    ) {
        Text(text = task.id, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = task.workerClassName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(text = task.status, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Chip(task.type)
            if (task.isPeriodic) Chip("Periodic")
            if (task.isChain) Chip("Chain")
        }
    }
}

@Composable
private fun Chip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
