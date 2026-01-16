package dev.brewkits.kmpworkmanager.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/**
 * Main Activity for KMP WorkManager v2.1.0 Demo.
 *
 * This activity is shared across all build variants (manual, koin, hilt).
 * The DI approach is determined by the Application class (flavor-specific).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                DemoScreen()
            }
        }
    }
}

@Preview
@Composable
fun DemoScreenPreview() {
    MaterialTheme {
        DemoScreen()
    }
}