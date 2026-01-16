package dev.brewkits.kmpworkmanager.sample

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Demo screen for v2.1.0 - shows current DI approach and provides
 * simple task scheduling buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen() {
    var statusText by remember { mutableStateOf("Ready") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KMP WorkManager v2.1.0 Demo") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Current approach card
            ApproachCard()

            Divider()

            // Status text
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Status: $statusText",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            // Action buttons
            Text(
                "Demo Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Button(
                onClick = {
                    statusText = "Scheduling demo task..."
                    // TODO: Schedule task via WorkerManagerInitializer.getScheduler()
                    statusText = "Task scheduled!"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Schedule Demo Task")
            }

            Button(
                onClick = {
                    statusText = "Scheduling heavy task..."
                    // TODO: Schedule heavy task
                    statusText = "Heavy task scheduled!"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Schedule Heavy Task")
            }

            OutlinedButton(
                onClick = {
                    statusText = "Demo initialized successfully"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test Status Update")
            }
        }
    }
}

@Composable
private fun ApproachCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Current Approach",
                style = MaterialTheme.typography.labelMedium
            )

            Text(
                DemoConfig.getApproachName(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                DemoConfig.getApproachDescription(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "💡 Switch build variants in Android Studio to test different approaches",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
        }
    }
}
