package dev.brewkits.kmpworkmanager.sample.utils

import androidx.compose.runtime.Composable

@Composable
actual fun getPlatformContext(): Any {
    // There is no direct equivalent of Android's Context in iOS for this purpose.
    // We return a dummy Unit object as a placeholder.
    return Unit
}
