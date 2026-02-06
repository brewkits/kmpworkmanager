package dev.brewkits.kmpworkmanager.sample.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun getPlatformContext(): Any {
    return LocalContext.current
}
